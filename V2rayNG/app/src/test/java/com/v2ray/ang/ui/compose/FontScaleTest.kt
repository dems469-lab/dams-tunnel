package com.v2ray.ang.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FontScaleTest {
    @Test
    fun `known levels are applied as multipliers`() {
        assertEquals(0.85f, resolveFontScaleMultiplier("0.85"))
        assertEquals(1f, resolveFontScaleMultiplier("1"))
        assertEquals(1.15f, resolveFontScaleMultiplier("1.15"))
        assertEquals(1.3f, resolveFontScaleMultiplier("1.3"))
        assertEquals(1.5f, resolveFontScaleMultiplier("1.5"))
    }

    @Test
    fun `blank and malformed values fall back to neutral`() {
        assertEquals(1f, resolveFontScaleMultiplier(""))
        assertEquals(1f, resolveFontScaleMultiplier("large"))
    }

    @Test
    fun `out of range values fall back to neutral`() {
        assertEquals(1f, resolveFontScaleMultiplier("0"))
        assertEquals(1f, resolveFontScaleMultiplier("-2"))
        assertEquals(1f, resolveFontScaleMultiplier("4"))
    }
}
