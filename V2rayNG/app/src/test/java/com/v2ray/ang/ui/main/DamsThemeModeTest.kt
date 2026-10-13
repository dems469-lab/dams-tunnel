package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DamsThemeModeTest {
    @Test
    fun persistsSystemLightAndDarkModesButIgnoresInvalidValues() {
        val persistedModes = mutableListOf<String>()
        val persist: (String) -> Unit = { persistedModes.add(it) }

        listOf("0", "1", "2", "", "3", "dark").forEach {
            persistDamsThemeModeIfSupported(it, persist)
        }

        assertEquals(listOf("0", "1", "2"), persistedModes)
    }
}
