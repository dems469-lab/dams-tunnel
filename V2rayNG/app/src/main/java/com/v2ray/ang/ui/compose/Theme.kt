package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
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

private val DamsTypography = Typography().run {
    copy(
        bodySmall = bodySmall.copy(fontFamily = FontFamily.Monospace),
        labelSmall = labelSmall.copy(fontFamily = FontFamily.Monospace),
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
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, true)
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

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColor
        else -> LightColor
    }
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
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DamsTypography,
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
