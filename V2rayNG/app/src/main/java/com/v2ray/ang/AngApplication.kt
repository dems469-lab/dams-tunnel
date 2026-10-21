package com.v2ray.ang

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.util.PerformanceDiagnostics

class AngApplication : Application() {
    private val processStartElapsedRealtime = SystemClock.elapsedRealtime()
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)
        PerformanceDiagnostics.initialize(this, processStartElapsedRealtime)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Apply the Dams brand defaults once; the user can change theme settings afterwards.
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_DAMS_BRANDING_MIGRATED, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, false)
            MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "2")
            MmkvManager.encodeSettings(AppConfig.PREF_DAMS_BRANDING_MIGRATED, true)
        }

        // The previous Dams default selected light mode; migrate that default to the requested dark look.
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_DAMS_DARK_THEME_MIGRATED, false)) {
            if (MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") == "1") {
                MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, "2")
            }
            MmkvManager.encodeSettings(AppConfig.PREF_DAMS_DARK_THEME_MIGRATED, true)
        }

        // Initialize theme state from MMKV
        ThemeManager.refresh()
    }
}
