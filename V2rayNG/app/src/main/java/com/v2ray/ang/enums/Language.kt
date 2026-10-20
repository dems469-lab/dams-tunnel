package com.v2ray.ang.enums

import androidx.core.os.LocaleListCompat
import java.util.Locale

enum class Language(val code: String, val languageTag: String) {
    AUTO("auto", ""),
    ENGLISH("en", "en"),
    FRENCH("fr", "fr");

    val locale: Locale?
        get() = languageTag.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)

    fun toLocaleList(): LocaleListCompat =
        if (languageTag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }

    companion object {
        fun fromCode(code: String): Language {
            return entries.find { it.code == code } ?: AUTO
        }

        fun fromLanguageTag(languageTag: String?): Language {
            if (languageTag.isNullOrBlank()) return AUTO

            val locale = Locale.forLanguageTag(languageTag)
            return when (locale.language) {
                "en" -> ENGLISH
                "fr" -> FRENCH
                else -> AUTO
            }
        }
    }
}
