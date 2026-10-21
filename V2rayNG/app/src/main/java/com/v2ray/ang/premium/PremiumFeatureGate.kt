package com.v2ray.ang.premium

import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager

/** Le build et le réglage interne doivent tous deux autoriser Premium. */
object PremiumFeatureGate {
    fun isEnabled(): Boolean = isEnabled(
        buildEnabled = BuildConfig.PREMIUM_ENABLED,
        internalEnabled = MmkvManager.decodeSettingsBool(
            AppConfig.PREF_PREMIUM_INTERNAL_ENABLED,
            BuildConfig.PREMIUM_ENABLED,
        ),
    )

    internal fun setInternalEnabled(enabled: Boolean) {
        if (BuildConfig.PREMIUM_ENABLED) {
            MmkvManager.encodeSettings(AppConfig.PREF_PREMIUM_INTERNAL_ENABLED, enabled)
        }
    }

    internal fun isEnabled(buildEnabled: Boolean, internalEnabled: Boolean): Boolean =
        buildEnabled && internalEnabled
}
