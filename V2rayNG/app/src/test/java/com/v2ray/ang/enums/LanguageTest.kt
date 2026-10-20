package com.v2ray.ang.enums

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanguageTest {
    @Test
    fun frenchCanBeSelectedAndRestoredFromTheSystemLanguageTag() {
        assertEquals(Language.FRENCH, Language.fromCode("fr"))
        assertEquals(Language.FRENCH, Language.fromLanguageTag("fr-FR"))
        assertEquals("fr", Language.FRENCH.toLocaleList().toLanguageTags())
    }

    @Test
    fun onlyEnglishAndFrenchAreExplicitlySupported() {
        assertEquals(Language.ENGLISH, Language.fromCode("en"))
        assertEquals(Language.ENGLISH, Language.fromLanguageTag("en-US"))
        assertEquals(Language.AUTO, Language.fromCode("zh-rCN"))
        assertEquals(Language.AUTO, Language.fromLanguageTag("zh-CN"))
        assertEquals(Language.AUTO, Language.fromLanguageTag("ar"))
    }
}
