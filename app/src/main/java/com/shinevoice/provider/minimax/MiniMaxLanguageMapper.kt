package com.shinevoice.provider.minimax

import com.shinevoice.domain.tts.TtsLanguageCatalog

/** Maps the app's stable BCP-47 ids to MiniMax's language_boost enum values. */
object MiniMaxLanguageMapper {
    val supportedLanguageIds: Set<String> = setOf(
        TtsLanguageCatalog.AUTO.id,
        TtsLanguageCatalog.ZH_CN.id,
        TtsLanguageCatalog.ZH_HK.id,
        TtsLanguageCatalog.EN_US.id,
        TtsLanguageCatalog.EN_GB.id,
        TtsLanguageCatalog.AR_SA.id,
        TtsLanguageCatalog.RU_RU.id,
        TtsLanguageCatalog.ES_ES.id,
        TtsLanguageCatalog.FR_FR.id,
        TtsLanguageCatalog.PT_PT.id,
        TtsLanguageCatalog.DE_DE.id,
        TtsLanguageCatalog.TR_TR.id,
        TtsLanguageCatalog.NL_NL.id,
        TtsLanguageCatalog.UK_UA.id,
        TtsLanguageCatalog.VI_VN.id,
        TtsLanguageCatalog.ID_ID.id,
        TtsLanguageCatalog.JA_JP.id,
        TtsLanguageCatalog.IT_IT.id,
        TtsLanguageCatalog.KO_KR.id,
        TtsLanguageCatalog.TH_TH.id,
        TtsLanguageCatalog.PL_PL.id,
        TtsLanguageCatalog.RO_RO.id,
        TtsLanguageCatalog.EL_GR.id,
        TtsLanguageCatalog.CS_CZ.id,
        TtsLanguageCatalog.FI_FI.id,
        TtsLanguageCatalog.HI_IN.id,
        TtsLanguageCatalog.BG_BG.id,
        TtsLanguageCatalog.DA_DK.id,
        TtsLanguageCatalog.HE_IL.id,
        TtsLanguageCatalog.MS_MY.id,
        TtsLanguageCatalog.FA_IR.id,
        TtsLanguageCatalog.SK_SK.id,
        TtsLanguageCatalog.SV_SE.id,
        TtsLanguageCatalog.HR_HR.id,
        TtsLanguageCatalog.FIL_PH.id,
        TtsLanguageCatalog.HU_HU.id,
        TtsLanguageCatalog.NB_NO.id,
        TtsLanguageCatalog.SL_SI.id,
        TtsLanguageCatalog.CA_ES.id,
        TtsLanguageCatalog.NN_NO.id,
        TtsLanguageCatalog.TA_IN.id,
        TtsLanguageCatalog.AF_ZA.id,
    )

    fun toLanguageBoost(languageId: String?): String? = when (languageId) {
        null -> null
        TtsLanguageCatalog.AUTO.id -> "auto"
        TtsLanguageCatalog.ZH_CN.id -> "Chinese"
        TtsLanguageCatalog.ZH_HK.id -> "Chinese,Yue"
        TtsLanguageCatalog.EN_US.id, TtsLanguageCatalog.EN_GB.id -> "English"
        TtsLanguageCatalog.AR_SA.id -> "Arabic"
        TtsLanguageCatalog.RU_RU.id -> "Russian"
        TtsLanguageCatalog.ES_ES.id -> "Spanish"
        TtsLanguageCatalog.FR_FR.id -> "French"
        TtsLanguageCatalog.PT_PT.id -> "Portuguese"
        TtsLanguageCatalog.DE_DE.id -> "German"
        TtsLanguageCatalog.TR_TR.id -> "Turkish"
        TtsLanguageCatalog.NL_NL.id -> "Dutch"
        TtsLanguageCatalog.UK_UA.id -> "Ukrainian"
        TtsLanguageCatalog.VI_VN.id -> "Vietnamese"
        TtsLanguageCatalog.ID_ID.id -> "Indonesian"
        TtsLanguageCatalog.JA_JP.id -> "Japanese"
        TtsLanguageCatalog.IT_IT.id -> "Italian"
        TtsLanguageCatalog.KO_KR.id -> "Korean"
        TtsLanguageCatalog.TH_TH.id -> "Thai"
        TtsLanguageCatalog.PL_PL.id -> "Polish"
        TtsLanguageCatalog.RO_RO.id -> "Romanian"
        TtsLanguageCatalog.EL_GR.id -> "Greek"
        TtsLanguageCatalog.CS_CZ.id -> "Czech"
        TtsLanguageCatalog.FI_FI.id -> "Finnish"
        TtsLanguageCatalog.HI_IN.id -> "Hindi"
        TtsLanguageCatalog.BG_BG.id -> "Bulgarian"
        TtsLanguageCatalog.DA_DK.id -> "Danish"
        TtsLanguageCatalog.HE_IL.id -> "Hebrew"
        TtsLanguageCatalog.MS_MY.id -> "Malay"
        TtsLanguageCatalog.FA_IR.id -> "Persian"
        TtsLanguageCatalog.SK_SK.id -> "Slovak"
        TtsLanguageCatalog.SV_SE.id -> "Swedish"
        TtsLanguageCatalog.HR_HR.id -> "Croatian"
        TtsLanguageCatalog.FIL_PH.id -> "Filipino"
        TtsLanguageCatalog.HU_HU.id -> "Hungarian"
        TtsLanguageCatalog.NB_NO.id -> "Norwegian"
        TtsLanguageCatalog.SL_SI.id -> "Slovenian"
        TtsLanguageCatalog.CA_ES.id -> "Catalan"
        TtsLanguageCatalog.NN_NO.id -> "Nynorsk"
        TtsLanguageCatalog.TA_IN.id -> "Tamil"
        TtsLanguageCatalog.AF_ZA.id -> "Afrikaans"
        else -> null
    }
}
