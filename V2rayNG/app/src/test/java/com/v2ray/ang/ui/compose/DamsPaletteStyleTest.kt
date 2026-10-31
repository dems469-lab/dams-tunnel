package com.v2ray.ang.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamsPaletteStyleTest {
    @Test
    fun everyConfiguredPaletteHasItsExpectedAnimationStyle() {
        assertEquals("neon", damsPaletteStyleForId("0"))
        assertEquals("gold", damsPaletteStyleForId("1"))
        assertEquals("fluid", damsPaletteStyleForId("2"))
        assertEquals("organic", damsPaletteStyleForId("3"))
        assertEquals("crystal", damsPaletteStyleForId("4"))
    }

    @Test
    fun unknownPaletteFallsBackToDamsCyanStyle() {
        assertEquals("neon", damsPaletteStyleForId("unknown"))
    }

    @Test
    fun paletteTokensExposePerThemeMotionAndPowerSavingSettings() {
        val expected = listOf(
            "0" to ("neon" to 3000),
            "1" to ("gold" to 4000),
            "2" to ("fluid" to 3600),
            "3" to ("organic" to 3800),
            "4" to ("crystal" to 2200),
        )
        expected.forEach { (id, profile) ->
            val tokens = resolveDamsThemeTokens(id, dark = true)
            assertEquals(profile.first, tokens.style)
            assertEquals(profile.second, tokens.respirationMillis)
            assertTrue(tokens.lueur in 0f..1f)
        }
        assertTrue(!resolveDamsThemeTokens("4", dark = true).particules)
        assertTrue(resolveDamsThemeTokens("4", dark = false).particules)
    }

    @Test
    fun customPalettesRespectTheSelectedLightModeAndSecondaryTextContrast() {
        listOf("1", "2", "3", "4").forEach { palette ->
            val dark = resolveDamsThemeTokens(palette, dark = true)
            val light = resolveDamsThemeTokens(palette, dark = false)
            assertTrue(dark.fond != light.fond)
            assertTrue(contrastRatio(light.texteDoux, light.fond) >= 4.5)
        }
    }

    @Test
    fun actionTextMaintainsAccessibleContrastOnEveryThemeAccent() {
        listOf("0", "1", "2", "3", "4").forEach { palette ->
            val tokens = resolveDamsThemeTokens(palette, dark = true)
            assertTrue(contrastRatio(accessibleTextColor(tokens.accent), tokens.accent) >= 4.5)
            assertTrue(contrastRatio(accessibleTextColor(tokens.accentClair), tokens.accentClair) >= 4.5)
            assertTrue(contrastRatio(accessibleTextColor(tokens.succes), tokens.succes) >= 4.5)
        }
    }

    private fun contrastRatio(foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): Double {
        fun luminance(color: androidx.compose.ui.graphics.Color): Double {
            fun channel(value: Float): Double {
                val normalized = value.toDouble()
                return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
