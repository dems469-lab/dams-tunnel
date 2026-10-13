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
}
