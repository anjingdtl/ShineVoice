package com.shinevoice.domain.model

import android.content.Context
import com.shinevoice.core.storage.ModelDirectoryResolver
import com.shinevoice.data.settings.SettingsStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A deployable local model. ZipVoice-Distill INT8 is currently the only real
 * implementation, but the registry abstraction (and the settings UI built on
 * it) never assumes there will always be exactly one model.
 */
data class ModelProfile(
    val id: String,
    val displayName: String,
    /** Runtime engine identifier, e.g. "sherpa-onnx+zipvoice". */
    val engine: String,
    val version: String,
    val path: String,
    /** Capability tags: "zh", "offline", "voice-clone", "speed". */
    val capabilities: Set<String>,
    val installed: Boolean,
    val active: Boolean,
    /** On-disk status summary (missing files / checksum result). */
    val statusSummary: String,
)

/** Where a known model lives on disk and how it is verified. */
private data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val engine: String,
    val version: String,
    val rootRelativePath: String,
    val capabilities: Set<String>,
)

/**
 * Enumerates locally deployable models and tracks which one is active.
 * Adding a future engine means adding a descriptor here — callers (UI,
 * ViewModel) stay model-agnostic.
 */
class LocalModelRegistry(
    context: Context,
    private val resolver: ModelDirectoryResolver,
    private val settingsStore: SettingsStore,
) {
    private val externalRoot = context.getExternalFilesDir(null) ?: context.filesDir

    /** Known model catalog; engine-agnostic structure, ZipVoice is the only real entry today. */
    private val descriptors: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = ModelDirectoryResolver.ZIPVOICE_MODEL_ID,
            displayName = "ZipVoice 中文声音克隆（INT8）",
            engine = "sherpa-onnx+zipvoice",
            version = "v1",
            rootRelativePath = "models/zipvoice",
            capabilities = setOf("zh", "en", "offline", "voice-clone", "speed"),
        ),
    )

    val activeModelId: Flow<String?> = settingsStore.activeLocalModel

    /** All known models with live install state; active flag from settings. */
    suspend fun availableModels(): List<ModelProfile> {
        val active = settingsStore.activeLocalModel.first() ?: descriptors.first().id
        val zipVoiceStatus = resolver.inspect()
        return descriptors.map { descriptor ->
            val root = File(externalRoot, descriptor.rootRelativePath)
            ModelProfile(
                id = descriptor.id,
                displayName = descriptor.displayName,
                engine = descriptor.engine,
                version = descriptor.version,
                path = root.absolutePath,
                capabilities = descriptor.capabilities,
                installed = zipVoiceStatus.ready,
                active = descriptor.id == active,
                statusSummary = zipVoiceStatus.summary,
            )
        }
    }

    /** Selects the active model; only installed models may be activated. */
    suspend fun selectModel(modelId: String): Boolean {
        val target = availableModels().firstOrNull { it.id == modelId } ?: return false
        if (!target.installed) return false
        settingsStore.setActiveLocalModel(modelId)
        return true
    }

    /** Re-runs the on-disk inspection (checksums included). */
    fun reinspect() {
        resolver.inspect(forceIntegrityCheck = true)
    }
}
