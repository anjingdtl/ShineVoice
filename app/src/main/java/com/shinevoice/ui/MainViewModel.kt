package com.shinevoice.ui

import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.audio.PlaybackRoute
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.ZipVoiceModelStatus
import com.shinevoice.data.db.GenerationHistoryEntity
import com.shinevoice.data.db.VoiceProfileEntity
import com.shinevoice.data.settings.ThemeMode
import com.shinevoice.domain.voice.VoiceReferenceStatus
import com.shinevoice.domain.tts.TtsLanguage
import com.shinevoice.domain.tts.TtsLanguageCatalog
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
import com.shinevoice.update.UpdateUiState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StabilitySummary(
    val attempts: Int,
    val successes: Int,
    val averageElapsedMs: Long?,
    val maxElapsedMs: Long?,
    val averageRtf: Double?,
    val maxRtf: Double?,
    val totalElapsedMs: Long,
    val failedTasks: List<String>,
    val memoryBeforePssKb: Int? = null,
    val memoryAfterPssKb: Int? = null,
) {
    val successRate: String
        get() = "${successes}/${attempts} (${successes * 100 / attempts}%)"

    val memoryDeltaPssKb: Int?
        get() = if (memoryBeforePssKb != null && memoryAfterPssKb != null) {
            memoryAfterPssKb - memoryBeforePssKb
        } else {
            null
        }
}

/** Storage footprint summary for the 存储 settings section. */
data class StorageStats(
    val generatedCount: Int,
    val generatedBytes: Long,
    val modelsBytes: Long,
    val voicesBytes: Long,
) {
    val summary: String
        get() = buildString {
            append("历史音频 $generatedCount 个，")
            append("占 ${formatBytes(generatedBytes)}")
        }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}

/** Settings-page cloud setup card: connection phase separated from detail text. */
enum class CloudSetupPhase { UNCONFIGURED, SAVED_UNTESTED, TESTING, REACHABLE, FAILED }

data class CloudSetupState(
    val phase: CloudSetupPhase = CloudSetupPhase.UNCONFIGURED,
    /** Full safe diagnostic line shown below the title; never contains the key. */
    val detail: String? = null,
    val officialVoices: List<com.shinevoice.domain.tts.TtsVoice> = emptyList(),
    val clonedVoices: List<com.shinevoice.domain.tts.TtsVoice> = emptyList(),
    /** Non-fatal reason why the cloned list could not be read (clone perms etc). */
    val clonedUnavailableReason: String? = null,
    /** Official voice chosen for generation when the profile has no clone binding. */
    val selectedOfficialVoiceId: String? = null,
    val selectedOfficialVoiceName: String? = null,
    val testingSynthesis: Boolean = false,
    val lastSynthesisCheckOk: Boolean? = null,
    val lastSynthesisCheckMessage: String? = null,
    /** True when the stored key exists but cannot be decrypted on this device. */
    val keyUndecryptable: Boolean = false,
) {
    val configured: Boolean get() = phase != CloudSetupPhase.UNCONFIGURED

    /** Cloud generation is possible with either a clone binding or an official voice. */
    val canGenerate: Boolean
        get() = phase == CloudSetupPhase.REACHABLE && selectedOfficialVoiceId != null
}

data class MainUiState(
    val targetText: String = "世恒哥，这是 ShineVoice 的语音生成测试。",
    val speed: Float = 1.0f,
    val selectedLanguage: String = TtsLanguageCatalog.ZH_CN.id,
    val availableLanguages: List<TtsLanguage> = listOf(
        TtsLanguageCatalog.ZH_CN,
        TtsLanguageCatalog.EN_US,
    ),
    val isLanguagePickerOpen: Boolean = false,
    val languageHint: String? = null,
    val modelStatus: ZipVoiceModelStatus? = null,
    val referenceStatus: VoiceReferenceStatus? = null,
    val currentVoice: VoiceProfileEntity? = null,
    val voices: List<VoiceProfileEntity> = emptyList(),
    val providerInitialized: Boolean = false,
    val isGenerating: Boolean = false,
    val stabilityRunning: Boolean = false,
    val stabilityCompleted: Int = 0,
    val lastResult: TtsResult? = null,
    val stabilitySummary: StabilitySummary? = null,
    val history: List<GenerationHistoryEntity> = emptyList(),
    val historySelection: Set<String> = emptySet(),
    val nowPlayingTaskId: String? = null,
    val nowPlayingTitle: String? = null,
    val minimaxGroupId: String = "",
    val minimaxApiKey: String = "",
    val minimaxRegion: com.shinevoice.data.settings.MiniMaxRegion =
        com.shinevoice.data.settings.MiniMaxRegion.CN,
    /** True while the UI holds an unsaved region/key draft (persist on 保存). */
    val minimaxDraftDirty: Boolean = false,
    val cloudSetup: CloudSetupState = CloudSetupState(),
    val cloudCloning: Boolean = false,
    val systemEngines: List<com.shinevoice.provider.androidtts.SystemEngineInfo> = emptyList(),
    val systemSelectedEngine: String? = null,
    val systemVoices: List<com.shinevoice.domain.tts.TtsVoice> = emptyList(),
    val systemSelectedVoice: String? = null,
    val systemStatus: String = "",
    val localModels: List<com.shinevoice.domain.model.ModelProfile> = emptyList(),
    val recentLogs: List<String> = emptyList(),
    val selectedProviderId: String = SherpaZipVoiceProvider.PROVIDER_ID,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val autoSave: Boolean = false,
    val playbackRoute: PlaybackRoute = PlaybackRoute.SPEAKER,
    val storageStats: StorageStats? = null,
    val message: String? = null,
    val updateState: UpdateUiState = UpdateUiState.Idle,
)

class MainViewModel(
    private val application: ShineVoiceApplication,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Only one cloud connection test runs at a time; newer revisions win. */
    private var cloudTestJob: kotlinx.coroutines.Job? = null
    private val cloudTestRevision = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        viewModelScope.launch {
            application.historyRepository.observeAll().collectLatest { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
        viewModelScope.launch {
            application.settingsStore.themeMode.collectLatest { mode ->
                _uiState.update { it.copy(themeMode = mode) }
            }
        }
        viewModelScope.launch {
            application.settingsStore.autoSave.collectLatest { enabled ->
                _uiState.update { it.copy(autoSave = enabled) }
            }
        }
        viewModelScope.launch {
            application.settingsStore.playbackRoute.collectLatest { route ->
                _uiState.update { it.copy(playbackRoute = route) }
            }
        }
        viewModelScope.launch {
            application.voiceProfileManager.observeProfiles().collectLatest { voices ->
                _uiState.update { it.copy(voices = voices) }
            }
        }
        viewModelScope.launch {
            application.voiceProfileManager.observeCurrent().collectLatest { voice ->
                _uiState.update {
                    it.copy(
                        currentVoice = voice,
                        referenceStatus = application.voiceProfileManager.referenceStatus(voice),
                    )
                }
            }
        }
        viewModelScope.launch {
            application.updateManager.state.collectLatest { updateState ->
                _uiState.update { it.copy(updateState = updateState) }
            }
        }
        // Cloud config must be restored on startup: the create page gates the
        // 云端高清 button on minimaxStatus, which previously only refreshed
        // when the user opened 设置 (bug: restart + direct cloud use failed).
        loadMinimaxConfig()
        loadSystemTtsState()
        loadGenerationPreferences()
        refreshAvailableLanguages()
        refreshModelAndInitialize()
    }

    fun onTargetTextChanged(value: String) {
        _uiState.update { it.copy(targetText = value) }
    }

    fun onSpeedChanged(value: Float) {
        val speed = value.coerceIn(0.5f, 2.0f)
        _uiState.update { it.copy(speed = speed) }
        viewModelScope.launch { application.settingsStore.setSpeechRate(speed) }
    }

    fun onLanguageChanged(languageId: String) {
        val selected = _uiState.value.availableLanguages.firstOrNull { it.id == languageId } ?: return
        _uiState.update { it.copy(selectedLanguage = selected.id, languageHint = null) }
        viewModelScope.launch { application.settingsStore.setGenerationLanguage(selected.id) }
    }

    fun setLanguagePickerOpen(open: Boolean) {
        _uiState.update { it.copy(isLanguagePickerOpen = open) }
    }

    fun checkForUpdate(force: Boolean = true) {
        viewModelScope.launch { application.updateManager.checkForUpdate(force = force) }
    }

    fun downloadAndInstallUpdate() {
        viewModelScope.launch { application.updateManager.downloadAndInstall() }
    }

    fun resumePendingUpdateInstall() {
        viewModelScope.launch { application.updateManager.resumePendingInstall() }
    }

    fun dismissUpdate() {
        application.updateManager.dismiss()
    }

    /** Provider capabilities drive the language selector and safe fallbacks. */
    fun refreshAvailableLanguages(providerId: String = _uiState.value.selectedProviderId) {
        viewModelScope.launch {
            val capabilities = application.providerRegistry.capabilities(providerId)
            val supportedIds = capabilities?.supportedLanguages.orEmpty()
            val languages = TtsLanguageCatalog.all.filter { it.id in supportedIds }
            val current = _uiState.value.selectedLanguage
            val next = chooseLanguageFallback(current, languages)
            _uiState.update {
                it.copy(
                    availableLanguages = languages,
                    selectedLanguage = next,
                    languageHint = if (current != next && languages.isNotEmpty()) {
                        "当前生成方式不支持${TtsLanguageCatalog.displayName(current)}，已切换为${TtsLanguageCatalog.displayName(next)}。"
                    } else {
                        it.languageHint
                    },
                )
            }
            if (next != current) application.settingsStore.setGenerationLanguage(next)
        }
    }

    private fun chooseLanguageFallback(current: String, available: List<TtsLanguage>): String {
        if (available.any { it.id == current }) return current
        return available.firstOrNull { it.id == TtsLanguageCatalog.ZH_CN.id }?.id
            ?: available.firstOrNull { it.id == TtsLanguageCatalog.AUTO.id }?.id
            ?: available.firstOrNull()?.id
            ?: TtsLanguageCatalog.ZH_CN.id
    }

    private fun loadGenerationPreferences() {
        viewModelScope.launch {
            val language = application.settingsStore.generationLanguage.first()
            val speed = application.settingsStore.speechRate.first()
            _uiState.update { it.copy(selectedLanguage = language, speed = speed) }
            refreshAvailableLanguages()
        }
    }

    fun refreshModelAndInitialize() {
        viewModelScope.launch {
            application.awaitStartupPreparation()
            val status = withContext(Dispatchers.IO) { application.modelResolver.inspect(forceIntegrityCheck = true) }
            val userMessage = when {
                status.ready -> "本地模型已就绪，可离线生成。"
                status.missingFiles.isNotEmpty() -> "本地模型未安装完整，本地生成暂不可用。"
                else -> "本地模型校验未通过，请到「设置 → 高级 → 开发与诊断」查看。"
            }
            val currentVoice = application.voiceProfileManager.observeCurrent().first()
            _uiState.update {
                it.copy(
                    modelStatus = status,
                    currentVoice = currentVoice,
                    referenceStatus = application.voiceProfileManager.referenceStatus(currentVoice),
                    message = userMessage,
                )
            }
            if (status.ready) {
                initializeProvider(
                    providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                    showMessage = false,
                )
            }
        }
    }

    /** Creates a voice profile with its optional reference text (音色页创建入口). */
    fun createVoice(displayName: String, referenceText: String?, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = application.voiceProfileManager.create(
                displayName = displayName,
                referenceText = referenceText,
                referenceAudioPath = null,
            ).id
            application.voiceProfileManager.setCurrent(id)
            onCreated(id)
            _uiState.update { it.copy(message = "音色「$displayName」已创建，请录音或导入参考音频。") }
        }
    }

    /** Records/imports the normalized reference WAV into the profile. */
    fun attachVoiceAudio(
        profileId: String,
        referenceAudioPath: String,
        referenceText: String?,
        sourceAudioPath: String? = null,
    ) {
        viewModelScope.launch {
            application.voiceProfileManager.updateReference(
                id = profileId,
                referenceAudioPath = referenceAudioPath,
                referenceText = referenceText?.takeIf { it.isNotBlank() },
                sourceAudioPath = sourceAudioPath,
            )
            application.voiceProfileManager.touch(profileId)
            _uiState.update { it.copy(message = "参考音频已保存。") }
        }
    }

    fun renameVoice(profileId: String, displayName: String) {
        viewModelScope.launch {
            application.voiceProfileManager.rename(profileId, displayName)
        }
    }

    /** Edits a profile's referenceText from the voice library screen. */
    fun updateVoiceReferenceText(profileId: String, referenceText: String) {
        viewModelScope.launch {
            application.voiceProfileManager.updateReference(profileId, referenceText = referenceText)
        }
    }

    fun setCurrentVoice(profileId: String) {
        viewModelScope.launch {
            application.voiceProfileManager.setCurrent(profileId)
        }
    }

    fun deleteVoice(profileId: String) {
        viewModelScope.launch {
            val wasCurrent = _uiState.value.currentVoice?.id == profileId
            application.voiceProfileManager.delete(profileId)
            if (wasCurrent) {
                val defaultId = application.voiceProfileManager.getById(
                    com.shinevoice.domain.voice.VoiceProfileManager.DEFAULT_PROFILE_ID,
                )
                if (defaultId != null) application.voiceProfileManager.setCurrent(defaultId.id)
            }
            _uiState.update { it.copy(message = "音色已删除。") }
        }
    }

    fun generate() {
        val snapshot = _uiState.value
        if (snapshot.isGenerating || snapshot.stabilityRunning) return
        if (snapshot.targetText.isBlank()) {
            _uiState.update { it.copy(message = "请输入需要生成的文字。") }
            return
        }
        val voice = snapshot.currentVoice
        when (snapshot.selectedProviderId) {
            MiniMaxProvider.PROVIDER_ID -> {
                if (!snapshot.cloudSetup.configured) {
                    _uiState.update { it.copy(message = "请先在设置中配置云端服务（API Key）。") }
                    return
                }
                // Generation needs a clone binding on the current profile OR a
                // selected official voice — cloning is not a prerequisite.
                val hasCloudVoice = voice?.minimaxVoiceId != null ||
                    snapshot.cloudSetup.selectedOfficialVoiceId != null
                if (!hasCloudVoice) {
                    _uiState.update { it.copy(message = "请先在设置中选择一个官方音色，或在音色库中克隆云端音色。") }
                    return
                }
            }
            SherpaZipVoiceProvider.PROVIDER_ID -> {
                // Validate exactly the inputs newRequest() will send: the
                // current profile's own reference audio + text.
                val referenceCheck = sherpaProvider()?.validateReference(
                    voice?.referenceAudioPath ?: application.modelResolver.referenceAudio.absolutePath,
                    (voice?.referenceText ?: ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT).trim(),
                )
                if (referenceCheck != null && !referenceCheck.success) {
                    _uiState.update { it.copy(message = referenceCheck.message) }
                    return
                }
            }
            AndroidSystemTtsProvider.PROVIDER_ID -> Unit
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, message = "正在生成…") }
            val result = withContext(Dispatchers.Default) {
                if (_uiState.value.selectedProviderId != AndroidSystemTtsProvider.PROVIDER_ID) {
                    ensureInitialized(snapshot.selectedProviderId)
                }
                application.ttsManager.synthesize(newRequest(snapshot))
            }
            voice?.let { application.voiceProfileManager.touch(it.id) }
            if (!result.success) {
                application.logger.w(
                    "generation failed provider=${result.providerId} code=${result.error?.code} " +
                        "user=${result.error?.userMessage} cause=${result.error?.causeMessage}",
                )
            }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    lastResult = result,
                    // Normal users see no engineering metrics; elapsed ms stays
                    // in diagnostics (设置 → 高级 → 开发与诊断).
                    message = if (result.success) {
                        "生成成功"
                    } else {
                        result.error?.userMessage ?: "生成失败"
                    },
                )
            }
        }
    }

    fun onSelectProvider(providerId: String) {
        if (application.providerRegistry.get(providerId) == null) return
        _uiState.update { it.copy(selectedProviderId = providerId, providerInitialized = false) }
        refreshAvailableLanguages(providerId)
    }

    fun onThemeModeChanged(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            application.settingsStore.setThemeMode(mode)
        }
    }

    fun onAutoSaveChanged(enabled: Boolean) {
        _uiState.update { it.copy(autoSave = enabled) }
        viewModelScope.launch {
            application.settingsStore.setAutoSave(enabled)
        }
    }

    fun onPlaybackRouteChanged(route: PlaybackRoute) {
        _uiState.update { it.copy(playbackRoute = route) }
        viewModelScope.launch {
            application.settingsStore.setPlaybackRoute(route)
        }
    }

    fun refreshStorageStats() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) {
                val generatedDir = java.io.File(application.filesDir, "generated")
                val generatedFiles = generatedDir.listFiles()?.toList() ?: emptyList()
                val modelsDir = application.modelResolver.zipVoiceRoot
                val voicesDir = application.voiceProfileManager.voicesRoot
                StorageStats(
                    generatedCount = generatedFiles.size,
                    generatedBytes = generatedFiles.sumOf { it.length() },
                    modelsBytes = dirSize(modelsDir),
                    voicesBytes = dirSize(voicesDir),
                )
            }
            _uiState.update { it.copy(storageStats = stats) }
        }
    }

    /** Loads the local model catalog with live install/active state. */
    fun refreshLocalModels() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { application.localModelRegistry.availableModels() }
            _uiState.update { it.copy(localModels = models) }
        }
    }

    fun selectLocalModel(modelId: String) {
        viewModelScope.launch {
            val ok = application.localModelRegistry.selectModel(modelId)
            _uiState.update {
                it.copy(
                    message = if (ok) "已切换本地模型。" else "该模型未安装或不可用。",
                )
            }
            refreshLocalModels()
        }
    }

    /** Full re-detect: model checksums, storage stats, diagnostics logs. */
    fun redetectEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { application.localModelRegistry.reinspect() }
            refreshModelAndInitialize()
            refreshLocalModels()
            refreshStorageStats()
            _uiState.update { it.copy(recentLogs = application.logger.recentLogs()) }
        }
    }

    fun refreshRecentLogs() {
        _uiState.update { it.copy(recentLogs = application.logger.recentLogs()) }
    }

    private fun dirSize(dir: java.io.File): Long {
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Friendly label for the current generation mode (本地生成/云端高清/系统语音). */
    fun providerLabel(providerId: String): String = when (providerId) {
        MiniMaxProvider.PROVIDER_ID -> "云端高清"
        AndroidSystemTtsProvider.PROVIDER_ID -> "系统语音"
        else -> "本地生成"
    }

    fun runTwentyGenerationStabilityTest() {
        val snapshot = _uiState.value
        if (snapshot.isGenerating || snapshot.stabilityRunning) return
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { application.modelResolver.inspect(forceIntegrityCheck = true) }
            _uiState.update {
                it.copy(
                    modelStatus = status,
                    stabilityRunning = true,
                    stabilityCompleted = 0,
                    stabilitySummary = null,
                    message = "开始连续 20 次真实 Native 生成…",
                )
            }
            if (!status.ready) {
                _uiState.update {
                    it.copy(stabilityRunning = false, message = status.summary)
                }
                return@launch
            }
            val voice = _uiState.value.currentVoice
            val referenceCheck = sherpaProvider()?.validateReference(
                voice?.referenceAudioPath ?: application.modelResolver.referenceAudio.absolutePath,
                (voice?.referenceText ?: ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT).trim(),
            )
            if (referenceCheck != null && !referenceCheck.success) {
                _uiState.update {
                    it.copy(stabilityRunning = false, message = referenceCheck.message)
                }
                return@launch
            }

            val memoryBeforePssKb = readMemoryPssKb()
            val results = mutableListOf<TtsResult>()
            repeat(STABILITY_ATTEMPTS) { index ->
                val result = withContext(Dispatchers.Default) {
                    ensureInitialized(SherpaZipVoiceProvider.PROVIDER_ID)
                    application.ttsManager.synthesize(
                        newRequest(
                            _uiState.value.copy(selectedProviderId = SherpaZipVoiceProvider.PROVIDER_ID),
                        ).copy(taskId = "stability-${index + 1}-${UUID.randomUUID()}"),
                    )
                }
                results += result
                _uiState.update {
                    it.copy(
                        stabilityCompleted = index + 1,
                        lastResult = result,
                        message = "连续测试 ${index + 1}/$STABILITY_ATTEMPTS：" +
                            if (result.success) "成功" else "失败",
                    )
                }
            }

            val successful = results.filter { it.success }
            val memoryAfterPssKb = readMemoryPssKb()
            val summary = StabilitySummary(
                attempts = results.size,
                successes = successful.size,
                averageElapsedMs = successful.map { it.elapsedMs }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                maxElapsedMs = successful.maxOfOrNull { it.elapsedMs },
                averageRtf = successful.mapNotNull { it.rtf }.takeIf { it.isNotEmpty() }?.average(),
                maxRtf = successful.mapNotNull { it.rtf }.maxOrNull(),
                totalElapsedMs = results.sumOf { it.elapsedMs },
                failedTasks = results.filterNot { it.success }.map { it.taskId },
                memoryBeforePssKb = memoryBeforePssKb,
                memoryAfterPssKb = memoryAfterPssKb,
            )
            application.logger.i(
                "STABILITY_20 attempts=${summary.attempts} successes=${summary.successes} " +
                    "averageMs=${summary.averageElapsedMs} maxMs=${summary.maxElapsedMs} " +
                    "averageRtf=${summary.averageRtf} maxRtf=${summary.maxRtf} failed=${summary.failedTasks.size} memoryBeforePssKb=${summary.memoryBeforePssKb} " +
                    "memoryAfterPssKb=${summary.memoryAfterPssKb} memoryDeltaPssKb=${summary.memoryDeltaPssKb}",
            )
            _uiState.update {
                it.copy(
                    stabilityRunning = false,
                    stabilitySummary = summary,
                    message = "20 次连续测试完成：${summary.successRate}",
                )
            }
        }
    }

    private suspend fun ensureInitialized(providerId: String) {
        if (!_uiState.value.providerInitialized) initializeProvider(providerId = providerId, showMessage = false)
    }

    private suspend fun initializeProvider(
        providerId: String = _uiState.value.selectedProviderId,
        showMessage: Boolean,
    ) {
        val result = withContext(Dispatchers.Default) {
            application.ttsManager.initialize(providerId)
        }
        _uiState.update {
            it.copy(
                providerInitialized = result.success,
                message = if (showMessage || !result.success) result.message else it.message,
            )
        }
    }

    private fun newRequest(state: MainUiState): TtsRequest {
        val voice = state.currentVoice
        val providerId = state.selectedProviderId
        return when (providerId) {
            MiniMaxProvider.PROVIDER_ID -> TtsRequest(
                taskId = UUID.randomUUID().toString(),
                text = state.targetText.trim(),
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceProfileId = voice?.id,
                voiceId = voice?.minimaxVoiceId ?: state.cloudSetup.selectedOfficialVoiceId,
                language = state.selectedLanguage,
                speed = state.speed,
            )
            AndroidSystemTtsProvider.PROVIDER_ID -> TtsRequest(
                taskId = UUID.randomUUID().toString(),
                text = state.targetText.trim(),
                providerId = AndroidSystemTtsProvider.PROVIDER_ID,
                voiceProfileId = voice?.id,
                voiceId = voice?.androidTtsVoice ?: state.systemSelectedVoice,
                language = state.selectedLanguage,
                speed = state.speed,
                pitch = 1.0f,
                extra = voice?.androidTtsEngine?.takeIf { it.isNotBlank() }
                    ?.let { mapOf(AndroidSystemTtsProvider.EXTRA_ENGINE to it) }
                    ?: emptyMap(),
            )
            else -> TtsRequest(
                taskId = UUID.randomUUID().toString(),
                text = state.targetText.trim(),
                providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                voiceProfileId = voice?.id ?: DEFAULT_VOICE_PROFILE_ID,
                voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
                language = state.selectedLanguage,
                speed = state.speed,
                extra = mapOf(
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_AUDIO to
                        (voice?.referenceAudioPath ?: application.modelResolver.referenceAudio.absolutePath),
                    SherpaZipVoiceProvider.EXTRA_REFERENCE_TEXT to
                        (voice?.referenceText ?: ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT).trim(),
                    SherpaZipVoiceProvider.EXTRA_NUM_STEPS to "4",
                ),
            )
        }
    }

    private fun sherpaProvider(): SherpaZipVoiceProvider? =
        application.providerRegistry.get(SherpaZipVoiceProvider.PROVIDER_ID) as? SherpaZipVoiceProvider

    private fun minimaxProvider(): MiniMaxProvider? =
        application.providerRegistry.get(MiniMaxProvider.PROVIDER_ID) as? MiniMaxProvider

    private fun systemTtsProvider(): com.shinevoice.provider.androidtts.AndroidSystemTtsProvider? =
        application.providerRegistry.get(AndroidSystemTtsProvider.PROVIDER_ID)
            as? com.shinevoice.provider.androidtts.AndroidSystemTtsProvider

    /** Loads installed TTS engines, the persisted choice, and real engine voices. */
    fun loadSystemTtsState() {
        viewModelScope.launch {
            val engines = systemTtsProvider()?.availableEngines() ?: emptyList()
            val selected = application.settingsStore.systemTtsEngine.first()
            _uiState.update {
                it.copy(
                    systemEngines = engines,
                    systemSelectedEngine = selected,
                    systemStatus = engines.summarizeEngines(selected),
                )
            }
            refreshSystemVoices(selected)
        }
    }

    /** Switches the system engine, persists it, refreshes its voice list. */
    fun selectSystemEngine(enginePackage: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(systemStatus = "正在切换系统语音引擎…") }
            val result = systemTtsProvider()?.switchEngine(enginePackage)
                ?: return@launch _uiState.update { it.copy(systemStatus = "系统语音不可用") }
            _uiState.update {
                it.copy(
                    systemSelectedEngine = enginePackage,
                    systemStatus = result.message,
                    systemVoices = emptyList(),
                    systemSelectedVoice = null,
                )
            }
            application.settingsStore.setSystemTtsVoice(null)
            refreshSystemVoices(enginePackage)
        }
    }

    fun selectSystemVoice(voiceName: String) {
        viewModelScope.launch {
            application.settingsStore.setSystemTtsVoice(voiceName)
            _uiState.update { it.copy(systemSelectedVoice = voiceName) }
        }
    }

    private suspend fun refreshSystemVoices(enginePackage: String?) {
        val voices = systemTtsProvider()?.getVoices() ?: emptyList()
        val persisted = application.settingsStore.systemTtsVoice.first()
        _uiState.update {
            it.copy(
                systemVoices = voices,
                systemSelectedVoice = persisted?.takeIf { v -> voices.any { it.id == v } },
                systemStatus = it.systemStatus.ifBlank {
                    "已就绪（${voices.size} 个系统语音）"
                },
            )
        }
        // enginePackage is intentionally the source of this refresh; kept for logs.
        application.logger.i("SystemTTS voices loaded engine=${enginePackage ?: "default"} count=${voices.size}")
        if (_uiState.value.selectedProviderId == AndroidSystemTtsProvider.PROVIDER_ID) {
            refreshAvailableLanguages(AndroidSystemTtsProvider.PROVIDER_ID)
        }
    }

    /** Binds a system engine + voice to a voice profile (系统 Binding). */
    fun bindProfileSystemVoice(profileId: String, engine: String?, voiceName: String?) {
        viewModelScope.launch {
            application.voiceProfileManager.updateSystemBinding(profileId, engine, voiceName)
            application.voiceProfileManager.touch(profileId)
            _uiState.update { it.copy(message = "系统语音绑定已保存。") }
        }
    }

    fun clearProfileSystemBinding(profileId: String) {
        viewModelScope.launch {
            application.voiceProfileManager.clearSystemBinding(profileId)
            _uiState.update { it.copy(message = "系统语音绑定已解除。") }
        }
    }

    private fun List<com.shinevoice.provider.androidtts.SystemEngineInfo>.summarizeEngines(
        selected: String?,
    ): String = when {
        isEmpty() -> "未找到系统语音引擎"
        else -> {
            val name = firstOrNull { it.packageName == selected }?.label ?: "系统默认引擎"
            "已选择：$name"
        }
    }

    fun toggleHistorySelect(taskId: String) {
        _uiState.update {
            val selection = it.historySelection.toMutableSet()
            if (!selection.add(taskId)) selection.remove(taskId)
            it.copy(historySelection = selection)
        }
    }

    fun setHistorySelectAll(select: Boolean) {
        _uiState.update {
            it.copy(historySelection = if (select) it.history.map { h -> h.taskId }.toSet() else emptySet())
        }
    }

    fun exitHistorySelection() {
        _uiState.update { it.copy(historySelection = emptySet()) }
    }

    /** Deletes selected history rows together with their on-disk WAV files. */
    fun deleteSelectedHistory() {
        val ids = _uiState.value.historySelection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            application.historyRepository.getByIds(ids)
                .mapNotNull { it.audioPath }
                .forEach { path -> runCatching { java.io.File(path).delete() } }
            application.historyRepository.deleteByIds(ids)
            _uiState.update { it.copy(historySelection = emptySet()) }
        }
    }

    fun setNowPlaying(taskId: String?, title: String?) {
        _uiState.update { it.copy(nowPlayingTaskId = taskId, nowPlayingTitle = title) }
    }

    init {
        // Mirror the shared PlaybackService state so every page renders the
        // same now-playing bar; the service owns the single player.
        viewModelScope.launch {
            com.shinevoice.core.playback.PlaybackService.Hub.state.collectLatest { playback ->
                _uiState.update {
                    it.copy(
                        nowPlayingTaskId = playback.itemId.takeIf { playback.isPlaying },
                        nowPlayingTitle = playback.title.takeIf { playback.isPlaying },
                    )
                }
            }
        }
    }

    fun onMinimaxGroupIdChanged(value: String) {
        _uiState.update { it.copy(minimaxGroupId = value, minimaxDraftDirty = true) }
    }

    fun onMinimaxApiKeyChanged(value: String) {
        _uiState.update { it.copy(minimaxApiKey = value, minimaxDraftDirty = true) }
    }

    fun loadMinimaxConfig() {
        viewModelScope.launch {
            val snapshot = application.minimaxConfig.snapshot()
            val keyState = application.minimaxConfig.keyState()
            _uiState.update {
                it.copy(
                    minimaxGroupId = snapshot.groupId ?: "",
                    // Never prefill a real key into the editable field; the UI
                    // works with hasStoredKey + a blank "keep" draft instead.
                    minimaxApiKey = "",
                    minimaxRegion = snapshot.region,
                    minimaxDraftDirty = false,
                    cloudSetup = it.cloudSetup.copy(
                        phase = when {
                            keyState is com.shinevoice.data.settings.MiniMaxKeyState.Undecryptable ->
                                CloudSetupPhase.FAILED
                            snapshot.isComplete -> CloudSetupPhase.SAVED_UNTESTED
                            else -> CloudSetupPhase.UNCONFIGURED
                        },
                        detail = when (keyState) {
                            is com.shinevoice.data.settings.MiniMaxKeyState.Undecryptable ->
                                "本机已保存的密钥无法读取（可能是系统备份迁移），请重新填写 API Key。"
                            else -> null
                        },
                        keyUndecryptable = keyState is com.shinevoice.data.settings.MiniMaxKeyState.Undecryptable,
                        selectedOfficialVoiceId = application.minimaxConfig.defaultVoiceId.first(),
                    ),
                )
            }
        }
    }

    /** Region choice is a draft until 保存并测试 persists it. */
    fun onMinimaxRegionChanged(region: com.shinevoice.data.settings.MiniMaxRegion) {
        _uiState.update { it.copy(minimaxRegion = region, minimaxDraftDirty = true) }
    }

    private fun draftConnection(): com.shinevoice.provider.minimax.MiniMaxConnection? {
        val state = _uiState.value
        val key = state.minimaxApiKey.trim().ifBlank { null } ?: return null
        return com.shinevoice.provider.minimax.MiniMaxConnection(
            apiKey = key,
            baseUrl = state.minimaxRegion.baseUrl,
            groupId = state.minimaxGroupId.trim().ifBlank { null },
        )
    }

    /** Persists the draft (or keeps the stored key when the field is blank), then tests. */
    fun saveMinimaxConfig(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val state = _uiState.value
        // A blank key field means "keep the stored key"; resolved inside the
        // coroutine so the main thread never blocks on DataStore.
        val typedKey = state.minimaxApiKey.trim()
        cancelCloudTest()
        val revision = cloudTestRevision.incrementAndGet()
        cloudTestJob = viewModelScope.launch {
            val storedKey = application.minimaxConfig.snapshot().apiKey
            val effectiveKey = typedKey.ifBlank { storedKey ?: "" }
            val sanitized = com.shinevoice.data.settings.MiniMaxApiKeySanitizer.sanitize(effectiveKey)
            if (sanitized is com.shinevoice.data.settings.MiniMaxApiKeySanitizer.Result.Invalid) {
                val reason = sanitized.reason
                _uiState.update {
                    it.copy(
                        cloudSetup = it.cloudSetup.copy(phase = CloudSetupPhase.FAILED, detail = reason),
                    )
                }
                onDone(false, reason)
                return@launch
            }
            val key = (sanitized as com.shinevoice.data.settings.MiniMaxApiKeySanitizer.Result.Valid).key
            application.minimaxConfig.save(state.minimaxGroupId, key, state.minimaxRegion)
            _uiState.update {
                it.copy(
                    minimaxApiKey = "",
                    minimaxDraftDirty = false,
                    minimaxRegion = state.minimaxRegion,
                    cloudSetup = it.cloudSetup.copy(
                        phase = CloudSetupPhase.TESTING,
                        detail = "已保存配置，正在连接 ${state.minimaxRegion.displayName}服务…",
                        keyUndecryptable = false,
                    ),
                )
            }
            val outcome = runCloudCatalogTest(revision)
            if (outcome != null) onDone(outcome.first, outcome.second)
        }
    }

    /** Tests the *current* configuration without persisting any draft. */
    fun testMinimaxConnection() {
        val state = _uiState.value
        val conn = draftConnection()
        cancelCloudTest()
        val revision = cloudTestRevision.incrementAndGet()
        cloudTestJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cloudSetup = it.cloudSetup.copy(
                        phase = CloudSetupPhase.TESTING,
                        detail = if (conn != null) {
                            "正在连接 ${state.minimaxRegion.displayName}服务（测试当前输入，未保存）…"
                        } else {
                            "正在测试已保存的配置…"
                        },
                    ),
                )
            }
            runCloudCatalogTest(revision, overrideConnection = conn)
        }
    }

    /**
     * Shared staged connection check: system catalog (connectivity + auth) then
     * cloned catalog (independent capability). Returns null when superseded.
     */
    private suspend fun runCloudCatalogTest(
        revision: Long,
        overrideConnection: com.shinevoice.provider.minimax.MiniMaxConnection? = null,
    ): Pair<Boolean, String>? {
        val provider = minimaxProvider() ?: run {
            _uiState.update {
                it.copy(cloudSetup = it.cloudSetup.copy(phase = CloudSetupPhase.FAILED, detail = "Provider 未注册"))
            }
            return false to "Provider 未注册"
        }
        val result = try {
            if (overrideConnection != null) {
                val system = provider.let {
                    application.minimaxApiClient.listVoices(overrideConnection, com.shinevoice.provider.minimax.MiniMaxApiClient.VOICE_TYPE_SYSTEM)
                }
                val cloned = application.minimaxApiClient.listVoices(overrideConnection, com.shinevoice.provider.minimax.MiniMaxApiClient.VOICE_TYPE_CLONED)
                if (system.isFailure) {
                    Result.failure(system.exceptionOrNull() ?: IllegalStateException("unknown"))
                } else {
                    Result.success(
                        com.shinevoice.provider.minimax.MiniMaxVoiceCatalog(
                            systemVoices = system.getOrThrow().systemVoices,
                            clonedVoices = cloned.getOrNull()?.clonedVoices ?: emptyList(),
                            clonedError = cloned.exceptionOrNull()?.let { e -> (e as? com.shinevoice.provider.minimax.MiniMaxException)?.error },
                        ),
                    )
                }
            } else {
                provider.voiceCatalog()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
        if (revision != cloudTestRevision.get()) return null
        result.fold(
            onSuccess = { catalog ->
                val officialVoices = catalog.systemVoices.map {
                    com.shinevoice.domain.tts.TtsVoice(it.voiceId, it.displayName, null)
                }
                val clonedVoices = catalog.clonedVoices.map {
                    com.shinevoice.domain.tts.TtsVoice(it.voiceId, it.displayName, null)
                }
                val keepSelection = _uiState.value.cloudSetup.selectedOfficialVoiceId
                val selected = keepSelection?.takeIf { id -> officialVoices.any { it.id == id } || clonedVoices.any { it.id == id } }
                _uiState.update {
                    it.copy(
                        cloudSetup = it.cloudSetup.copy(
                            phase = CloudSetupPhase.REACHABLE,
                            detail = buildString {
                                append("官方音色 ${officialVoices.size} 个")
                                append(" · 克隆音色 ${clonedVoices.size} 个")
                                catalog.clonedError?.let { err -> append("；${err.userMessage}") }
                            },
                            officialVoices = officialVoices,
                            clonedVoices = clonedVoices,
                            clonedUnavailableReason = catalog.clonedError?.userMessage,
                            selectedOfficialVoiceId = selected,
                            selectedOfficialVoiceName = (
                                officialVoices.firstOrNull { v -> v.id == selected }
                                    ?: clonedVoices.firstOrNull { v -> v.id == selected }
                                )?.displayName,
                        ),
                    )
                }
                return true to "云端连接正常（官方音色 ${officialVoices.size} 个）"
            },
            onFailure = { error ->
                val ttsError = (error as? com.shinevoice.provider.minimax.MiniMaxException)?.error
                _uiState.update {
                    it.copy(
                        cloudSetup = it.cloudSetup.copy(
                            phase = CloudSetupPhase.FAILED,
                            detail = ttsError?.userMessage ?: "云端连接测试失败。",
                        ),
                    )
                }
                return false to (ttsError?.userMessage ?: "云端连接测试失败。")
            },
        )
    }

    /** Selects which cloud voice (official or cloned) generation should use. */
    fun selectCloudVoice(voiceId: String) {
        val cloud = _uiState.value.cloudSetup
        val voice = cloud.officialVoices.firstOrNull { it.id == voiceId }
            ?: cloud.clonedVoices.firstOrNull { it.id == voiceId }
            ?: return
        _uiState.update {
            it.copy(
                cloudSetup = it.cloudSetup.copy(
                    selectedOfficialVoiceId = voice.id,
                    selectedOfficialVoiceName = voice.displayName,
                    lastSynthesisCheckOk = null,
                    lastSynthesisCheckMessage = null,
                ),
            )
        }
        viewModelScope.launch { application.minimaxConfig.saveDefaultVoiceId(voice.id) }
    }

    /** User-triggered billable synthesis check with the selected cloud voice. */
    fun runCloudSynthesisCheck() {
        val state = _uiState.value
        if (state.cloudSetup.testingSynthesis || state.isGenerating) return
        val voiceId = state.currentVoice?.minimaxVoiceId
            ?: state.cloudSetup.selectedOfficialVoiceId
            ?: run {
                _uiState.update {
                    it.copy(
                        cloudSetup = it.cloudSetup.copy(
                            lastSynthesisCheckOk = false,
                            lastSynthesisCheckMessage = "请先选择一个云端音色。",
                        ),
                    )
                }
                return
            }
        viewModelScope.launch {
            _uiState.update {
                it.copy(cloudSetup = it.cloudSetup.copy(testingSynthesis = true, lastSynthesisCheckMessage = "正在生成测试语音（调用计费接口）…"))
            }
            val request = TtsRequest(
                taskId = "cloud-check-${UUID.randomUUID()}",
                text = "你好，这是云端音色的生成测试。",
                providerId = MiniMaxProvider.PROVIDER_ID,
                voiceId = voiceId,
                language = TtsLanguageCatalog.ZH_CN.id,
                speed = 1.0f,
            )
            val result = withContext(Dispatchers.Default) {
                ensureInitialized(MiniMaxProvider.PROVIDER_ID)
                application.ttsManager.synthesize(request)
            }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    lastResult = if (result.success) result else it.lastResult,
                    cloudSetup = it.cloudSetup.copy(
                        testingSynthesis = false,
                        lastSynthesisCheckOk = result.success,
                        lastSynthesisCheckMessage = if (result.success) {
                            "生成测试语音成功（${result.durationMs ?: 0} ms），可播放试听。"
                        } else {
                            result.error?.userMessage ?: "生成测试语音失败。"
                        },
                    ),
                )
            }
        }
    }

    fun refreshMinimaxVoices() {
        cancelCloudTest()
        val revision = cloudTestRevision.incrementAndGet()
        cloudTestJob = viewModelScope.launch { runCloudCatalogTest(revision) }
    }

    fun clearMinimaxConfig() {
        cancelCloudTest()
        viewModelScope.launch {
            application.minimaxConfig.clear()
            _uiState.update {
                it.copy(
                    minimaxGroupId = "",
                    minimaxApiKey = "",
                    minimaxDraftDirty = false,
                    cloudSetup = CloudSetupState(),
                )
            }
        }
    }

    private fun cancelCloudTest() {
        cloudTestJob?.cancel()
        cloudTestJob = null
        cloudTestRevision.incrementAndGet()
    }

    /** Clones the given profile's reference audio to MiniMax and saves voice_id. */
    fun cloneVoiceToCloud(profileId: String, onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val profile = application.voiceProfileManager.getById(profileId) ?: run {
                onDone(false, "音色不存在。")
                return@launch
            }
            val referencePath = profile.referenceAudioPath
            if (referencePath == null) {
                onDone(false, "该音色还没有参考音频，请先录音或导入。")
                return@launch
            }
            _uiState.update { it.copy(cloudCloning = true) }
            val provider = minimaxProvider()
            val result = provider?.cloneVoice(
                com.shinevoice.domain.tts.VoiceCloneRequest(
                    voiceProfileId = profileId,
                    referenceAudioPath = referencePath,
                    referenceText = profile.referenceText ?: "",
                ),
            )
            _uiState.update { it.copy(cloudCloning = false) }
            if (result?.success == true && result.voiceId != null) {
                application.voiceProfileManager.updateCloudBinding(profileId, result.voiceId!!)
                application.voiceProfileManager.touch(profileId)
                onDone(true, "云端音色已创建。")
            } else {
                onDone(false, result?.error?.userMessage ?: "云端克隆失败。")
            }
        }
    }

    private fun readMemoryPssKb(): Int? = runCatching {
        Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
    }.getOrNull()

    class Factory(
        private val application: ShineVoiceApplication,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            return MainViewModel(application) as T
        }
    }

    companion object {
        const val STABILITY_ATTEMPTS = 20
        const val DEFAULT_VOICE_PROFILE_ID = "default-local-reference"
    }
}
