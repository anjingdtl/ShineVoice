package com.shinevoice.ui

import android.os.Debug
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shinevoice.ShineVoiceApplication
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.core.storage.ReferenceAudioStatus
import com.shinevoice.core.storage.ZipVoiceModelStatus
import com.shinevoice.data.db.GenerationHistoryEntity
import com.shinevoice.data.db.VoiceProfileEntity
import com.shinevoice.data.settings.ThemeMode
import com.shinevoice.domain.tts.TtsRequest
import com.shinevoice.domain.tts.TtsResult
import com.shinevoice.provider.androidtts.AndroidSystemTtsProvider
import com.shinevoice.provider.minimax.MiniMaxProvider
import com.shinevoice.provider.sherpa.SherpaZipVoiceProvider
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

data class MainUiState(
    val targetText: String = "世恒哥，这是 ShineVoice 的本地中文声音克隆测试。",
    val speed: Float = 1.0f,
    val modelStatus: ZipVoiceModelStatus? = null,
    val referenceStatus: ReferenceAudioStatus? = null,
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
    val minimaxStatus: String = "未配置",
    val minimaxClonedVoices: List<com.shinevoice.domain.tts.TtsVoice> = emptyList(),
    val cloudCloning: Boolean = false,
    val selectedProviderId: String = SherpaZipVoiceProvider.PROVIDER_ID,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoSave: Boolean = false,
    val storageStats: StorageStats? = null,
    val message: String? = null,
)

class MainViewModel(
    private val application: ShineVoiceApplication,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

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
            application.voiceProfileManager.observeProfiles().collectLatest { voices ->
                _uiState.update { it.copy(voices = voices) }
            }
        }
        viewModelScope.launch {
            application.voiceProfileManager.observeCurrent().collectLatest { voice ->
                _uiState.update {
                    it.copy(
                        currentVoice = voice,
                        referenceStatus = application.modelResolver.referenceAudioStatus(
                            voice?.referenceText ?: ModelDirectoryResolver.DEFAULT_REFERENCE_TEXT,
                        ),
                    )
                }
            }
        }
        refreshModelAndInitialize()
    }

    fun onTargetTextChanged(value: String) {
        _uiState.update { it.copy(targetText = value) }
    }

    fun onSpeedChanged(value: Float) {
        _uiState.update { it.copy(speed = value.coerceIn(0.5f, 2.0f)) }
    }

    /** Edits the current voice's referenceText (bound to the create page). */
    fun onCurrentReferenceTextChanged(value: String) {
        val current = _uiState.value.currentVoice ?: return
        viewModelScope.launch {
            application.voiceProfileManager.updateReference(current.id, referenceText = value)
        }
    }

    fun refreshModelAndInitialize() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { application.modelResolver.inspect(forceIntegrityCheck = true) }
            _uiState.update { it.copy(modelStatus = status, message = status.summary) }
            if (status.ready) initializeProvider(showMessage = false)
        }
    }

    fun createVoice(displayName: String, onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val id = application.voiceProfileManager.create(
                displayName = displayName,
                referenceText = null,
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
    ) {
        viewModelScope.launch {
            application.voiceProfileManager.updateReference(
                id = profileId,
                referenceAudioPath = referenceAudioPath,
                referenceText = referenceText?.takeIf { it.isNotBlank() },
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
            _uiState.update { it.copy(message = "请输入需要生成的中文文本。") }
            return
        }
        val voice = snapshot.currentVoice
        when (snapshot.selectedProviderId) {
            MiniMaxProvider.PROVIDER_ID -> {
                if (snapshot.minimaxStatus == "未配置") {
                    _uiState.update { it.copy(message = "请先在设置中配置云端服务（API Key）。") }
                    return
                }
                val voiceId = voice?.minimaxVoiceId
                    ?: run {
                        _uiState.update { it.copy(message = "请先在音色库中为当前音色克隆云端音色。") }
                        return
                    }
            }
            SherpaZipVoiceProvider.PROVIDER_ID -> {
                val referenceCheck = sherpaProvider()?.validateReference(
                    voice?.referenceAudioPath ?: application.modelResolver.referenceAudio.absolutePath,
                    (voice?.referenceText ?: "").trim(),
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
                    ensureInitialized()
                }
                application.ttsManager.synthesize(newRequest(snapshot))
            }
            voice?.let { application.voiceProfileManager.touch(it.id) }
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    lastResult = result,
                    message = if (result.success) {
                        "生成成功：${result.elapsedMs} ms"
                    } else {
                        result.error?.userMessage ?: "生成失败"
                    },
                )
            }
        }
    }

    fun onSelectProvider(providerId: String) {
        _uiState.update { it.copy(selectedProviderId = providerId) }
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
                (voice?.referenceText ?: "").trim(),
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
                    ensureInitialized()
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

    private suspend fun ensureInitialized() {
        if (!_uiState.value.providerInitialized) initializeProvider(showMessage = false)
    }

    private suspend fun initializeProvider(showMessage: Boolean) {
        val result = withContext(Dispatchers.Default) {
            application.ttsManager.initialize(SherpaZipVoiceProvider.PROVIDER_ID)
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
                voiceId = voice?.minimaxVoiceId,
                speed = state.speed,
            )
            AndroidSystemTtsProvider.PROVIDER_ID -> TtsRequest(
                taskId = UUID.randomUUID().toString(),
                text = state.targetText.trim(),
                providerId = AndroidSystemTtsProvider.PROVIDER_ID,
                voiceProfileId = voice?.id,
                voiceId = voice?.androidTtsVoice,
                speed = state.speed,
                pitch = 1.0f,
            )
            else -> TtsRequest(
                taskId = UUID.randomUUID().toString(),
                text = state.targetText.trim(),
                providerId = SherpaZipVoiceProvider.PROVIDER_ID,
                voiceProfileId = voice?.id ?: DEFAULT_VOICE_PROFILE_ID,
                voiceId = SherpaZipVoiceProvider.DEFAULT_VOICE_ID,
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

    fun onMinimaxGroupIdChanged(value: String) {
        _uiState.update { it.copy(minimaxGroupId = value) }
    }

    fun onMinimaxApiKeyChanged(value: String) {
        _uiState.update { it.copy(minimaxApiKey = value) }
    }

    fun loadMinimaxConfig() {
        viewModelScope.launch {
            val groupId = application.minimaxConfig.groupId.first()
            val apiKey = application.minimaxConfig.apiKey()
            _uiState.update {
                it.copy(
                    minimaxGroupId = groupId ?: "",
                    minimaxApiKey = apiKey ?: "",
                    minimaxStatus = if (apiKey.isNullOrBlank()) "未配置" else "已配置",
                )
            }
        }
    }

    fun saveMinimaxConfig(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        val snapshot = _uiState.value
        if (snapshot.minimaxGroupId.isBlank() || snapshot.minimaxApiKey.isBlank()) {
            onDone(false, "请填写 Group ID 与 API Key。")
            return
        }
        viewModelScope.launch {
            application.minimaxConfig.save(snapshot.minimaxGroupId, snapshot.minimaxApiKey)
            val provider = minimaxProvider()
            val result = provider?.validateConfig() ?: run {
                _uiState.update { it.copy(minimaxStatus = "Provider 未注册") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    minimaxStatus = if (result.success) "连接正常" else result.message,
                    minimaxClonedVoices = emptyList(),
                )
            }
            onDone(result.success, result.message)
        }
    }

    fun testMinimaxConnection() {
        loadMinimaxConfig()
        viewModelScope.launch {
            _uiState.update { it.copy(minimaxStatus = "连接中…") }
            val provider = minimaxProvider()
            val result = provider?.validateConfig()
            _uiState.update {
                it.copy(
                    minimaxStatus = result?.takeIf { r -> r.success }?.message ?: "连接失败：${result?.message ?: "Provider 未注册"}",
                )
            }
            if (result?.success == true) {
                val voices = provider?.getVoices() ?: emptyList()
                _uiState.update { it.copy(minimaxClonedVoices = voices) }
            }
        }
    }

    fun refreshMinimaxVoices() {
        viewModelScope.launch {
            val voices = minimaxProvider()?.getVoices() ?: emptyList()
            _uiState.update { it.copy(minimaxClonedVoices = voices) }
        }
    }

    fun clearMinimaxConfig() {
        viewModelScope.launch {
            application.minimaxConfig.clear()
            _uiState.update {
                it.copy(
                    minimaxGroupId = "",
                    minimaxApiKey = "",
                    minimaxStatus = "未配置",
                    minimaxClonedVoices = emptyList(),
                )
            }
        }
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