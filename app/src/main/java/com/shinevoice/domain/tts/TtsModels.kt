package com.shinevoice.domain.tts

import com.shinevoice.core.model.AudioFormat

enum class TtsErrorCode {
    ProviderNotInitialized,
    ModelNotInstalled,
    ModelCorrupted,
    InvalidReferenceAudio,
    InvalidReferenceText,
    UnsupportedFormat,
    UnsupportedLanguage,
    NetworkUnavailable,
    ApiUnauthorized,
    ApiRateLimited,
    ApiServerError,
    GenerationTimeout,
    NativeRuntimeError,
    SystemTtsError,
    StorageError,
    EmptyText,
    ProviderNotFound,
    Cancelled,
    Unknown,
}

data class TtsError(
    val code: TtsErrorCode,
    val userMessage: String,
    val causeMessage: String? = null,
)

data class ProviderResult(
    val success: Boolean,
    val message: String = "",
    val error: TtsError? = null,
) {
    companion object {
        fun ok(message: String = "") = ProviderResult(success = true, message = message)

        fun failure(error: TtsError) = ProviderResult(
            success = false,
            message = error.userMessage,
            error = error,
        )
    }
}

data class TtsVoice(
    val id: String,
    val displayName: String,
    val language: String? = null,
)

/** A provider-neutral BCP-47 language exposed to users and generation tasks. */
data class TtsLanguage(
    val id: String,
    val displayName: String,
    val nativeName: String,
)

/**
 * The app's single language catalog. Providers expose a subset of these
 * values; provider-specific names such as MiniMax's `English` stay in the
 * provider mapping layer.
 */
object TtsLanguageCatalog {
    val AUTO = TtsLanguage("auto", "自动识别", "Auto")
    val ZH_CN = TtsLanguage("zh-CN", "中文", "中文")
    val ZH_HK = TtsLanguage("zh-HK", "粤语", "廣東話")
    val EN_US = TtsLanguage("en-US", "English", "English")
    val EN_GB = TtsLanguage("en-GB", "English (UK)", "English")
    val JA_JP = TtsLanguage("ja-JP", "日本語", "日本語")
    val KO_KR = TtsLanguage("ko-KR", "한국어", "한국어")
    val FR_FR = TtsLanguage("fr-FR", "Français", "Français")
    val DE_DE = TtsLanguage("de-DE", "Deutsch", "Deutsch")
    val ES_ES = TtsLanguage("es-ES", "Español", "Español")
    val PT_PT = TtsLanguage("pt-PT", "Português", "Português")
    val RU_RU = TtsLanguage("ru-RU", "Русский", "Русский")
    val AR_SA = TtsLanguage("ar-SA", "العربية", "العربية")
    val TH_TH = TtsLanguage("th-TH", "ไทย", "ไทย")
    val VI_VN = TtsLanguage("vi-VN", "Tiếng Việt", "Tiếng Việt")
    val ID_ID = TtsLanguage("id-ID", "Bahasa Indonesia", "Bahasa Indonesia")
    val IT_IT = TtsLanguage("it-IT", "Italiano", "Italiano")
    val TR_TR = TtsLanguage("tr-TR", "Türkçe", "Türkçe")
    val NL_NL = TtsLanguage("nl-NL", "Nederlands", "Nederlands")
    val UK_UA = TtsLanguage("uk-UA", "Українська", "Українська")
    val PL_PL = TtsLanguage("pl-PL", "Polski", "Polski")
    val RO_RO = TtsLanguage("ro-RO", "Română", "Română")
    val EL_GR = TtsLanguage("el-GR", "Ελληνικά", "Ελληνικά")
    val CS_CZ = TtsLanguage("cs-CZ", "Čeština", "Čeština")
    val FI_FI = TtsLanguage("fi-FI", "Suomi", "Suomi")
    val HI_IN = TtsLanguage("hi-IN", "हिन्दी", "हिन्दी")
    val BG_BG = TtsLanguage("bg-BG", "Български", "Български")
    val DA_DK = TtsLanguage("da-DK", "Dansk", "Dansk")
    val HE_IL = TtsLanguage("he-IL", "עברית", "עברית")
    val MS_MY = TtsLanguage("ms-MY", "Bahasa Melayu", "Bahasa Melayu")
    val FA_IR = TtsLanguage("fa-IR", "فارسی", "فارسی")
    val SK_SK = TtsLanguage("sk-SK", "Slovenčina", "Slovenčina")
    val SV_SE = TtsLanguage("sv-SE", "Svenska", "Svenska")
    val HR_HR = TtsLanguage("hr-HR", "Hrvatski", "Hrvatski")
    val FIL_PH = TtsLanguage("fil-PH", "Filipino", "Filipino")
    val HU_HU = TtsLanguage("hu-HU", "Magyar", "Magyar")
    val NB_NO = TtsLanguage("nb-NO", "Norsk", "Norsk")
    val SL_SI = TtsLanguage("sl-SI", "Slovenščina", "Slovenščina")
    val CA_ES = TtsLanguage("ca-ES", "Català", "Català")
    val NN_NO = TtsLanguage("nn-NO", "Nynorsk", "Nynorsk")
    val TA_IN = TtsLanguage("ta-IN", "தமிழ்", "தமிழ்")
    val AF_ZA = TtsLanguage("af-ZA", "Afrikaans", "Afrikaans")

    /** Stable display order: common shortcuts first, then the full catalog. */
    val all: List<TtsLanguage> = listOf(
        AUTO, ZH_CN, ZH_HK, EN_US, EN_GB, JA_JP, KO_KR, FR_FR, DE_DE, ES_ES,
        PT_PT, RU_RU, AR_SA, TH_TH, VI_VN, ID_ID, IT_IT, TR_TR, NL_NL, UK_UA,
        PL_PL, RO_RO, EL_GR, CS_CZ, FI_FI, HI_IN, BG_BG, DA_DK, HE_IL, MS_MY,
        FA_IR, SK_SK, SV_SE, HR_HR, FIL_PH, HU_HU, NB_NO, SL_SI, CA_ES, NN_NO,
        TA_IN, AF_ZA,
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String?): TtsLanguage? = byId[id]

    fun displayName(id: String?): String = find(id)?.displayName ?: id.orEmpty()

    fun normalize(id: String?): String = find(id)?.id ?: AUTO.id

    /** Treat regional English variants as the same language family for fallback. */
    fun sameLanguageFamily(left: String?, right: String?): Boolean {
        val leftLanguage = left?.substringBefore('-')?.lowercase() ?: return false
        val rightLanguage = right?.substringBefore('-')?.lowercase() ?: return false
        return leftLanguage == rightLanguage
    }
}

data class TtsCapabilities(
    val supportsVoiceClone: Boolean,
    val supportsOffline: Boolean,
    val supportsStreaming: Boolean,
    val supportsSpeed: Boolean,
    val supportsPitch: Boolean,
    val supportsEmotion: Boolean,
    val supportsFileOutput: Boolean,
    val supportedFormats: Set<AudioFormat>,
    val supportedLanguages: Set<String> = emptySet(),
    val supportsAutoLanguage: Boolean = false,
    val minSpeed: Float = 0.5f,
    val maxSpeed: Float = 2.0f,
    val defaultSpeed: Float = 1.0f,
)

data class TtsRequest(
    val taskId: String,
    val text: String,
    val providerId: String,
    val voiceProfileId: String? = null,
    val voiceId: String? = null,
    /** BCP-47 id from [TtsLanguageCatalog], owned by this generation only. */
    val language: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val emotion: String? = null,
    val outputFormat: AudioFormat = AudioFormat.WAV_PCM_16,
    val extra: Map<String, String> = emptyMap(),
)

data class TtsResult(
    val taskId: String,
    val providerId: String,
    val success: Boolean,
    val audioFile: String? = null,
    val durationMs: Long? = null,
    val sampleRate: Int? = null,
    val model: String? = null,
    val voiceId: String? = null,
    val elapsedMs: Long,
    val error: TtsError? = null,
) {
    val rtf: Double?
        get() = if (durationMs == null || durationMs == 0L) null else elapsedMs.toDouble() / durationMs

    companion object {
        fun failure(
            taskId: String,
            providerId: String,
            elapsedMs: Long,
            error: TtsError,
        ) = TtsResult(
            taskId = taskId,
            providerId = providerId,
            success = false,
            elapsedMs = elapsedMs,
            error = error,
        )
    }
}

data class VoiceCloneRequest(
    val voiceProfileId: String,
    val referenceAudioPath: String,
    val referenceText: String,
)

data class VoiceCloneResult(
    val success: Boolean,
    val voiceId: String? = null,
    val error: TtsError? = null,
)

