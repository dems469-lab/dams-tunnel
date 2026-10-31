package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF087CB0),
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFDCEBFF), // Soft blue
    onPrimaryContainer = Color(0xFF062E61), // Deep blue
    secondary = Color(0xFF008EAA),
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFC5F3FF), // Pale cyan
    onSecondaryContainer = Color(0xFF003544), // Deep cyan
    tertiary = Color(0xFF16A974), // Green
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFA0F2D0), // Light Green
    onTertiaryContainer = Color(0xFF00201A), // Dark Teal
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF1C1B1F), // Near Black
    surface = Color(0xFFFBFDFF), // Cool white
    onSurface = Color(0xFF1C1B1F), // Near Black
    surfaceVariant = Color(0xFFE8EFFA),
    onSurfaceVariant = Color(0xFF49454F), // Dark Gray
    outline = Color(0xFF8295B6),
    outlineVariant = Color(0xFFCCDAF3),
    inverseSurface = Color(0xFF313033), // Dark Gray
    inverseOnSurface = Color(0xFFF4EFF4), // Very Light Gray
    inversePrimary = Color(0xFF9CC7FF), // Light blue
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF000000), // Black
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFEDF3FC),
    surfaceContainer = Color(0xFFE6EEF9),
    surfaceContainerHigh = Color(0xFFDCE7F6),
    surfaceContainerHighest = Color(0xFFD2E0F2),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00263A),
    primaryContainer = Color(0xFF0B3A6B),
    onPrimaryContainer = Color(0xFFE8F0FF),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF00343B),
    secondaryContainer = Color(0xFF0A4355),
    onSecondaryContainer = Color(0xFFD5F8FF),
    tertiary = Color(0xFF5EE5AE),
    onTertiary = Color(0xFF003827),
    tertiaryContainer = Color(0xFF064B39),
    onTertiaryContainer = Color(0xFFD1FBE8),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF601D2A),
    onError = Color(0xFFFFFFFF),
    onErrorContainer = Color(0xFFFFD9DC),
    background = Color(0xFF050B1A),
    onBackground = Color(0xFFE8F0FF),
    surface = Color(0xFF0A1530),
    onSurface = Color(0xFFE8F0FF),
    surfaceVariant = Color(0xFF111F3D),
    onSurfaceVariant = Color(0xFF8FA4CF),
    outline = Color(0xFF516B9C),
    outlineVariant = Color(0xFF1B2C55),
    inverseSurface = Color(0xFFE8F0FF),
    inverseOnSurface = Color(0xFF050B1A),
    inversePrimary = Color(0xFF087CB0),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF38BDF8),
    surfaceContainerLowest = Color(0xFF030816),
    surfaceContainerLow = Color(0xFF08112A),
    surfaceContainer = Color(0xFF0A1530),
    surfaceContainerHigh = Color(0xFF101E3A),
    surfaceContainerHighest = Color(0xFF142546),
)

private val DamsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Shared visual tokens for every Dams screen; palette changes are presentation-only. */
data class DamsThemeTokens(
    val fond: Color,
    val fondRadial: Color,
    val surface: Color,
    val bordure: Color,
    val accent: Color,
    val accentClair: Color,
    val succes: Color,
    val erreur: Color,
    val avertissement: Color = Color(0xFFFFC857),
    val texteFort: Color,
    val texteDoux: Color,
    val lueur: Float,
    val particules: Boolean,
    val style: String,
    val respirationMillis: Int,
    val ondeMillis: Int,
    val animationsEnabled: Boolean = true,
    val ringAlphaMin: Float = 0.04f,
    val ringAlphaMid: Float = 0.14f,
    val ringAlphaMax: Float = 0.32f,
    val ringScaleMin: Float = 1f,
    val ringScaleMax: Float = 1.12f,
    val idlePowerScaleMax: Float = 1.025f,
    val statusPulseMinAlpha: Float = 0.72f,
    val particleAlphaMin: Float = 0.035f,
    val particleAlphaMax: Float = 0.08f,
    val particleRadiusDp: Float = 1.35f,
    val backgroundRadialAlpha: Float = 0.46f,
    val cardAlpha: Float = 0.76f,
    val statusSurfaceAlpha: Float = 0.86f,
    val navigationSurfaceAlpha: Float = 0.95f,
    val headerSurfaceAlpha: Float = 0.94f,
    val shimmerAlpha: Float = 0.14f,
    val shimmerMillis: Int = 6000,
    val waveAmplitude: Float = 0.012f,
    val chipTransitionMillis: Int = 200,
)

private val LocalDamsThemeTokens = compositionLocalOf {
    DamsThemeTokens(
        fond = Color(0xFF050B1A), fondRadial = Color(0xFF0B2740),
        surface = Color(0xFF0A1530), bordure = Color(0xFF1B2C55),
        accent = Color(0xFF38BDF8), accentClair = Color(0xFF22D3EE),
        succes = Color(0xFF34D399), erreur = Color(0xFFEF4444),
        texteFort = Color(0xFFE8F0FF), texteDoux = Color(0xFF9CB2D7),
        lueur = 0.72f, particules = true, style = "neon",
        respirationMillis = 3000, ondeMillis = 900, animationsEnabled = true,
    )
}

val DamsTheme: DamsThemeTokens
    @Composable get() = LocalDamsThemeTokens.current

internal fun resolveDamsThemeTokens(palette: String, dark: Boolean): DamsThemeTokens {
    val style = damsPaletteStyleForId(palette)
    val paletteTokens = when (palette) {
        "1" -> DamsThemeTokens(
            fond = Color(0xFF0A0805), fondRadial = Color(0xFF38240A),
            surface = Color(0xFF191208), bordure = Color(0xFF594018),
            accent = Color(0xFFF5B942), accentClair = Color(0xFFFFD166),
            succes = Color(0xFFFFD166), erreur = Color(0xFFFF6B6B),
            avertissement = Color(0xFFFFD166),
            texteFort = Color(0xFFFFF1D0), texteDoux = Color(0xFFD7C39A),
            lueur = 0.46f, particules = true, style = style,
            respirationMillis = 4000, ondeMillis = 1500,
        )
        "2" -> DamsThemeTokens(
            fond = Color(0xFF04121F), fondRadial = Color(0xFF073B51),
            surface = Color(0xFF0A2034), bordure = Color(0xFF174968),
            accent = Color(0xFF3B82F6), accentClair = Color(0xFF22D3EE),
            succes = Color(0xFF2DD4BF), erreur = Color(0xFFFF6B6B),
            avertissement = Color(0xFFFBBF24),
            texteFort = Color(0xFFE4F4FF), texteDoux = Color(0xFFA2C3D8),
            lueur = 0.66f, particules = true, style = style,
            respirationMillis = 3600, ondeMillis = 1400,
        )
        "3" -> DamsThemeTokens(
            fond = Color(0xFF04120D), fondRadial = Color(0xFF064332),
            surface = Color(0xFF09251C), bordure = Color(0xFF14533E),
            accent = Color(0xFF10B981), accentClair = Color(0xFF6EE7B7),
            succes = Color(0xFF34D399), erreur = Color(0xFFFF6B6B),
            avertissement = Color(0xFFFBBF24),
            texteFort = Color(0xFFE3FFF3), texteDoux = Color(0xFFA6D5C1),
            lueur = 0.58f, particules = true, style = style,
            respirationMillis = 3800, ondeMillis = 1300,
        )
        "4" -> if (dark) DamsThemeTokens(
            fond = Color(0xFF101820), fondRadial = Color(0xFF243B52),
            surface = Color(0xFF182531), bordure = Color(0xFF3A5368),
            accent = Color(0xFF38BDF8), accentClair = Color(0xFFBAE6FD),
            succes = Color(0xFF34D399), erreur = Color(0xFFFF6B6B),
            avertissement = Color(0xFFFBBF24),
            texteFort = Color(0xFFF1F8FF), texteDoux = Color(0xFFBDD0DF),
            lueur = 0.12f, particules = false, style = style,
            respirationMillis = 2200, ondeMillis = 700,
        ) else DamsThemeTokens(
            fond = Color(0xFFF4F8FC), fondRadial = Color(0xFFE2F1FC),
            surface = Color(0xFFFFFFFF), bordure = Color(0xFFB8D0E3),
            accent = Color(0xFF0284C7), accentClair = Color(0xFF38BDF8),
            succes = Color(0xFF059669), erreur = Color(0xFFB42318),
            avertissement = Color(0xFF9A6700),
            texteFort = Color(0xFF102A3A), texteDoux = Color(0xFF456579),
            lueur = 0.08f, particules = true, style = style,
            respirationMillis = 2200, ondeMillis = 700,
        )
        else -> if (dark) DamsThemeTokens(
            fond = Color(0xFF050A15), fondRadial = Color(0xFF0B2A43),
            surface = Color(0xFF0B1930), bordure = Color(0xFF1C3E62),
            accent = Color(0xFF38BDF8), accentClair = Color(0xFF67E8F9),
            succes = Color(0xFF34D399), erreur = Color(0xFFEF4444),
            avertissement = Color(0xFFFBBF24),
            texteFort = Color(0xFFE8F0FF), texteDoux = Color(0xFFA5B8D4),
            lueur = 0.82f, particules = true, style = style,
            respirationMillis = 3000, ondeMillis = 850,
        ) else DamsThemeTokens(
            fond = Color(0xFFF4F8FF), fondRadial = Color(0xFFE5F3FF),
            surface = Color(0xFFFBFDFF), bordure = Color(0xFFCCDAF3),
            accent = Color(0xFF087CB0), accentClair = Color(0xFF22A6C7),
            succes = Color(0xFF059669), erreur = Color(0xFFBA1A1A),
            avertissement = Color(0xFF9A6700),
            texteFort = Color(0xFF102A3A), texteDoux = Color(0xFF496377),
            lueur = 0.12f, particules = false, style = style,
            respirationMillis = 250, ondeMillis = 180,
        )
    }
    if (dark || (palette != "1" && palette != "2" && palette != "3")) {
        return withThemeMotionTokens(paletteTokens)
    }
    val lightSurfaces = when (palette) {
        "1" -> Triple(Color(0xFFFFFAEF), Color(0xFFFFEBC0), Color(0xFFE7C87D))
        "2" -> Triple(Color(0xFFF1FAFF), Color(0xFFDFF4FC), Color(0xFFB9DAE9))
        else -> Triple(Color(0xFFF0FBF5), Color(0xFFDDF6EA), Color(0xFFB2DCC7))
    }
    return withThemeMotionTokens(paletteTokens.copy(
        fond = lightSurfaces.first,
        fondRadial = lightSurfaces.second,
        surface = Color.White,
        bordure = lightSurfaces.third,
        texteFort = Color(0xFF14252F),
        texteDoux = Color(0xFF465E69),
        avertissement = Color(0xFF805400),
        lueur = 0.08f,
        particules = false,
        respirationMillis = 250,
        ondeMillis = 180,
    ))
}

private fun withThemeMotionTokens(tokens: DamsThemeTokens): DamsThemeTokens = tokens.copy(
    ringAlphaMin = tokens.lueur * 0.10f,
    ringAlphaMid = tokens.lueur * 0.24f,
    ringAlphaMax = tokens.lueur * 0.46f,
    ringScaleMax = when (tokens.style) {
        "fluid" -> 1.08f
        "organic" -> 1.06f
        "crystal" -> 1.03f
        else -> 1.12f
    },
    ringScaleMin = if (tokens.style == "organic") 0.94f else 1f,
    idlePowerScaleMax = if (tokens.style == "crystal") 1.01f else 1.025f,
    statusPulseMinAlpha = if (tokens.style == "crystal") 0.92f else 0.72f,
    particleAlphaMin = tokens.lueur * 0.043f,
    particleAlphaMax = tokens.lueur * 0.098f,
    particleRadiusDp = when (tokens.style) {
        "gold" -> 1.8f
        "crystal" -> 0.8f
        else -> 1.35f
    },
    backgroundRadialAlpha = if (tokens.style == "crystal") 0.18f else 0.46f,
    cardAlpha = if (tokens.style == "crystal") 0.94f else 0.76f,
    statusSurfaceAlpha = if (tokens.style == "crystal") 0.96f else 0.86f,
    navigationSurfaceAlpha = if (tokens.style == "crystal") 0.98f else 0.95f,
    headerSurfaceAlpha = if (tokens.style == "crystal") 0.98f else 0.94f,
    shimmerAlpha = if (tokens.style == "gold") 0.14f else 0f,
    shimmerMillis = 6000,
    waveAmplitude = if (tokens.style == "fluid") 0.012f else 0f,
)

internal fun damsPaletteStyleForId(palette: String): String = when (palette) {
    "0" -> "neon"
    "1" -> "gold"
    "2" -> "fluid"
    "3" -> "organic"
    "4" -> "crystal"
    else -> "neon"
}

private fun damsTypography(style: String): Typography {
    val base = Typography()
    val family = when (style) {
        "1", "5", "8" -> FontFamily.SansSerif
        "2", "6" -> FontFamily.Serif
        "3", "7" -> FontFamily.Monospace
        "4" -> FontFamily.Cursive
        else -> FontFamily.Default
    }
    val weight = when (style) {
        "5", "8" -> androidx.compose.ui.text.font.FontWeight.SemiBold
        "6" -> androidx.compose.ui.text.font.FontWeight.Medium
        else -> androidx.compose.ui.text.font.FontWeight.Normal
    }
    val spacing = when (style) {
        "3", "7" -> 0.8.sp
        "4" -> 0.2.sp
        "8", "9" -> 0.5.sp
        else -> 0.sp
    }
    return base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing),
        titleMedium = base.titleMedium.copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing),
        bodyMedium = base.bodyMedium.copy(fontFamily = family, letterSpacing = spacing),
        bodySmall = base.bodySmall.copy(fontFamily = family, letterSpacing = spacing),
        labelLarge = base.labelLarge.copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing),
        labelSmall = base.labelSmall.copy(fontFamily = family, letterSpacing = spacing),
    )
}

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = Color(0xFF0B74B8) // Dams blue
val colorFabActive = Color(0xFF00A9D6) // Dams cyan
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD93F51B5) // Indigo Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

object ThemeManager {
    /** Multiplier stored by the font-size setting; "1" keeps the system font scale. */
    const val DEFAULT_FONT_SCALE = "1"

    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()
    private val _palette = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_DAMS_THEME_PALETTE, "0") ?: "0"
    )
    val palette: StateFlow<String> = _palette.asStateFlow()
    private val _typographyStyle = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_DAMS_TYPOGRAPHY_STYLE, "0") ?: "0"
    )
    val typographyStyle: StateFlow<String> = _typographyStyle.asStateFlow()
    private val _fontScale = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_APP_FONT_SCALE, DEFAULT_FONT_SCALE)
            ?: DEFAULT_FONT_SCALE
    )
    val fontScale: StateFlow<String> = _fontScale.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun setPalette(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_DAMS_THEME_PALETTE, value)
        _palette.value = value
    }

    fun setTypographyStyle(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_DAMS_TYPOGRAPHY_STYLE, value)
        _typographyStyle.value = value
    }

    fun setFontScale(value: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_APP_FONT_SCALE, value)
        _fontScale.value = value
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
        _palette.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_DAMS_THEME_PALETTE, "0") ?: "0"
        _typographyStyle.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_DAMS_TYPOGRAPHY_STYLE, "0") ?: "0"
        _fontScale.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_APP_FONT_SCALE, DEFAULT_FONT_SCALE)
                ?: DEFAULT_FONT_SCALE
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

/**
 * Turns the stored font-size setting into a multiplier applied on top of the system font
 * scale. Unknown or out-of-range values fall back to the neutral multiplier so a corrupt
 * preference can never make the UI unreadable.
 */
internal fun resolveFontScaleMultiplier(value: String): Float =
    value.toFloatOrNull()?.takeIf { it in 0.5f..2.5f } ?: 1f

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val palette by ThemeManager.palette.collectAsState()
    val typographyStyle by ThemeManager.typographyStyle.collectAsState()
    val fontScale by ThemeManager.fontScale.collectAsState()
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, fontScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * resolveFontScaleMultiplier(fontScale),
        )
    }
    val baseColorScheme = when {
        dynamicColor && palette == "0" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
    val definition = resolveDamsThemeTokens(palette, darkTheme)
    val animationEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
    val duration = if (animationEnabled) 400 else 0
    val targetTokens = if (dynamicColor && palette == "0" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        definition.copy(
            accent = baseColorScheme.primary,
            accentClair = baseColorScheme.secondary,
            succes = baseColorScheme.tertiary,
            fond = baseColorScheme.background,
            fondRadial = baseColorScheme.primaryContainer,
            surface = baseColorScheme.surface,
            bordure = baseColorScheme.outlineVariant,
            texteFort = baseColorScheme.onBackground,
            texteDoux = baseColorScheme.onSurfaceVariant,
        )
    } else definition
    val tokens = if (animationEnabled) targetTokens.copy(
        fond = animateColorAsState(targetTokens.fond, tween(duration), label = "themeBackground").value,
        fondRadial = animateColorAsState(targetTokens.fondRadial, tween(duration), label = "themeRadial").value,
        surface = animateColorAsState(targetTokens.surface, tween(duration), label = "themeSurface").value,
        bordure = animateColorAsState(targetTokens.bordure, tween(duration), label = "themeBorder").value,
        accent = animateColorAsState(targetTokens.accent, tween(duration), label = "themeAccent").value,
        accentClair = animateColorAsState(targetTokens.accentClair, tween(duration), label = "themeAccentLight").value,
        succes = animateColorAsState(targetTokens.succes, tween(duration), label = "themeSuccess").value,
        texteFort = animateColorAsState(targetTokens.texteFort, tween(duration), label = "themeText").value,
        texteDoux = animateColorAsState(targetTokens.texteDoux, tween(duration), label = "themeSoftText").value,
    ).copy(animationsEnabled = animationEnabled) else targetTokens.copy(animationsEnabled = false)
    val targetScheme = applyDamsPalette(baseColorScheme, tokens)
    val colorScheme = targetScheme.copy(
        primary = animateColorAsState(targetScheme.primary, tween(duration), label = "schemePrimary").value,
        secondary = animateColorAsState(targetScheme.secondary, tween(duration), label = "schemeSecondary").value,
        tertiary = animateColorAsState(targetScheme.tertiary, tween(duration), label = "schemeTertiary").value,
        background = tokens.fond,
        onBackground = tokens.texteFort,
        surface = tokens.surface,
        onSurface = tokens.texteFort,
        surfaceVariant = tokens.surface,
        onSurfaceVariant = tokens.texteDoux,
        outline = tokens.bordure,
        outlineVariant = tokens.bordure,
        surfaceContainerLowest = tokens.fond,
        surfaceContainerLow = tokens.surface,
        surfaceContainer = tokens.surface,
        surfaceContainerHigh = tokens.surface,
        surfaceContainerHighest = tokens.surface,
    )
    val snackbarController = rememberAppSnackbarController()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalDarkTheme provides darkTheme,
        LocalDamsThemeTokens provides tokens,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = damsTypography(typographyStyle),
            shapes = DamsShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}

internal fun accessibleTextColor(background: Color): Color {
    fun luminance(color: Color): Double {
        fun channel(value: Float): Double {
            val normalized = value.toDouble()
            return if (normalized <= 0.04045) normalized / 12.92 else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
    val backgroundLuminance = luminance(background)
    val lightContrast = 1.05 / (backgroundLuminance + 0.05)
    val darkContrast = (backgroundLuminance + 0.05) / 0.05
    return if (lightContrast >= darkContrast) Color.White else Color(0xFF07111B)
}

private fun applyDamsPalette(base: ColorScheme, tokens: DamsThemeTokens): ColorScheme {
    return base.copy(
        primary = tokens.accent,
        onPrimary = accessibleTextColor(tokens.accent),
        primaryContainer = tokens.surface,
        onPrimaryContainer = tokens.texteFort,
        secondary = tokens.accentClair,
        onSecondary = accessibleTextColor(tokens.accentClair),
        secondaryContainer = tokens.surface,
        onSecondaryContainer = tokens.texteFort,
        tertiary = tokens.succes,
        onTertiary = accessibleTextColor(tokens.succes),
        tertiaryContainer = tokens.surface,
        onTertiaryContainer = tokens.texteFort,
        error = tokens.erreur,
        background = tokens.fond,
        onBackground = tokens.texteFort,
        surface = tokens.surface,
        onSurface = tokens.texteFort,
        surfaceVariant = tokens.surface,
        onSurfaceVariant = tokens.texteDoux,
        outline = tokens.bordure,
        outlineVariant = tokens.bordure,
        surfaceTint = tokens.accent,
    )
}
