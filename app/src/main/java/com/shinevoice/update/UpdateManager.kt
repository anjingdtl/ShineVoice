package com.shinevoice.update

import android.content.Context
import com.shinevoice.BuildConfig
import com.shinevoice.core.log.AppLogger
import com.shinevoice.data.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Latest(val localVersionCode: Long) : UpdateUiState
    data class Available(val candidate: UpdateCandidate) : UpdateUiState
    data class Downloading(
        val candidate: UpdateCandidate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState
    data class AwaitingInstall(
        val candidate: UpdateCandidate,
        val apk: File,
        val permissionRequired: Boolean,
    ) : UpdateUiState
    data object Installing : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

/**
 * Small injectable policy for the automatic update-check throttle. The
 * timestamp is written only by [recordSuccessfulCheck], after a valid release
 * response has been received. This keeps transient failures retryable.
 */
class UpdateCheckThrottle(
    private val readLastCheckAt: suspend () -> Long,
    private val writeLastCheckAt: suspend (Long) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val intervalMs: Long = UpdateManager.AUTO_CHECK_INTERVAL_MS,
) {
    suspend fun shouldSkip(force: Boolean): Boolean {
        if (force) return false
        val lastCheckAt = readLastCheckAt()
        if (lastCheckAt <= 0L) return false
        return nowMs() - lastCheckAt < intervalMs
    }

    suspend fun recordSuccessfulCheck() {
        writeLastCheckAt(nowMs())
    }
}

/** Coordinates check -> download -> verify -> user-confirmed Android install. */
class UpdateManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val logger: AppLogger,
    private val releaseClient: GitHubReleaseClient = GitHubReleaseClient(),
    private val downloader: UpdateDownloader = UpdateDownloader(),
    private val checkThrottle: UpdateCheckThrottle = UpdateCheckThrottle(
        readLastCheckAt = { settingsStore.updateLastCheckAt.first() },
        writeLastCheckAt = settingsStore::setUpdateLastCheckAt,
    ),
) {
    private val appContext = context.applicationContext
    private val apkVerifier = ApkVerifier(appContext)
    private val apkInstaller = ApkInstaller(appContext)
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val localVersionName: String = BuildConfig.VERSION_NAME
    val localVersionCode: Long = BuildConfig.VERSION_CODE.toLong()

    suspend fun checkForUpdate(force: Boolean = false) {
        operationMutex.withLock {
            if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
            if (checkThrottle.shouldSkip(force)) return
            _state.value = UpdateUiState.Checking
            val result = releaseClient.fetchLatest()
            result.fold(
                onSuccess = { candidate ->
                    runCatching { checkThrottle.recordSuccessfulCheck() }
                        .onFailure { logger.w("Could not persist update-check timestamp", it) }
                    if (UpdateProtocol.decide(localVersionCode, candidate) == UpdateDecision.UpdateAvailable) {
                        _state.value = UpdateUiState.Available(candidate)
                        logger.i("Update available remoteVersionCode=${candidate.metadata.versionCode} tag=${candidate.tagName}")
                    } else {
                        _state.value = UpdateUiState.Latest(localVersionCode)
                        logger.i("Update check latest localVersionCode=$localVersionCode")
                    }
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "更新检查失败，请稍后重试。"
                    _state.value = UpdateUiState.Failed(message)
                    logger.w("Update check failed: ${error::class.simpleName}")
                },
            )
        }
    }

    suspend fun downloadAndInstall(candidate: UpdateCandidate? = null) {
        operationMutex.withLock {
            val target = candidate ?: (_state.value as? UpdateUiState.Available)?.candidate
                ?: return
            _state.value = UpdateUiState.Downloading(target, 0L, target.metadata.apkSizeBytes)
            val download = downloader.download(target, appContext.cacheDir) { downloaded, total ->
                _state.value = UpdateUiState.Downloading(target, downloaded, total)
            }
            val apk = download.getOrElse { error ->
                _state.value = UpdateUiState.Failed(error.message ?: "更新包下载失败，请稍后重试。")
                return
            }
            val verification = apkVerifier.verify(
                apk = apk,
                expectedVersionCode = target.metadata.versionCode,
                expectedSha256 = target.metadata.sha256,
            )
            verification.fold(
                onSuccess = { identity ->
                    logger.i(
                        "Update APK verified size=${apk.length()} sha256=${identity.apkSha256.take(12)} " +
                            "package=${identity.packageName} versionCode=${identity.versionCode} signerMatch=true",
                    )
                    launchInstallerLocked(target, apk)
                },
                onFailure = { error ->
                    runCatching { apk.delete() }
                    _state.value = UpdateUiState.Failed(error.message ?: "安装包校验失败，已拒绝安装。")
                    logger.w("Update APK verification failed")
                },
            )
        }
    }

    /** Called after returning from Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES. */
    suspend fun resumePendingInstall() {
        operationMutex.withLock {
            val pending = _state.value as? UpdateUiState.AwaitingInstall ?: return
            if (!pending.apk.isFile) {
                _state.value = UpdateUiState.Failed("更新包已不存在，请重新检查更新。")
                return
            }
            launchInstallerLocked(pending.candidate, pending.apk)
        }
    }

    fun dismiss() {
        val candidate = when (val state = _state.value) {
            is UpdateUiState.Available -> state.candidate
            is UpdateUiState.Downloading -> state.candidate
            is UpdateUiState.AwaitingInstall -> state.candidate
            else -> null
        }
        if (candidate != null && UpdateProtocol.requiresImmediateUpdate(localVersionCode, candidate)) return
        if (_state.value is UpdateUiState.Downloading || _state.value is UpdateUiState.Installing) return
        _state.value = UpdateUiState.Idle
    }

    private fun launchInstallerLocked(candidate: UpdateCandidate, apk: File) {
        val launch = apkInstaller.openInstaller(apk)
        launch.fold(
            onSuccess = { result ->
                _state.value = if (result.started) {
                    UpdateUiState.Installing
                } else {
                    UpdateUiState.AwaitingInstall(candidate, apk, result.permissionRequired)
                }
            },
            onFailure = { error ->
                _state.value = UpdateUiState.Failed(error.message ?: "无法打开系统安装器。")
            },
        )
    }

    companion object {
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
