package com.v2ray.ang.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DamsWarningTokenTest {
    @Test
    fun warningTokenHasReadableContrastInAllFivePalettesAndModes() {
        listOf("0", "1", "2", "3", "4").forEach { palette ->
            listOf(true, false).forEach { dark ->
                val tokens = resolveDamsThemeTokens(palette, dark)
                assertTrue(
                    contrastRatio(tokens.avertissement, tokens.fond) >= 4.5,
                    "Warning contrast too low for palette=$palette, dark=$dark",
                )
            }
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun channel(value: Float): Double {
                val normalized = value.toDouble()
                return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
        val first = luminance(foreground)
        val second = luminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
