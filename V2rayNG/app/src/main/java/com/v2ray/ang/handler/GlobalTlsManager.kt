package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/**
 * Applies the global TLS switch to the profiles stored in "My servers".
 *
 * Only protocols that accept a plain TLS layer are touched. Trojan always needs TLS, and
 * reality/xtls based profiles already provide their own transport security, so the switch
 * never degrades them.
 */
object GlobalTlsManager {
    const val TLS = "tls"

    fun supportsGlobalTls(configType: EConfigType): Boolean = when (configType) {
        EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN -> true
        else -> false
    }

    /**
     * Applies [enabled] to [profile] and returns true when the profile was modified.
     */
    fun apply(profile: ProfileItem, enabled: Boolean): Boolean {
        if (!supportsGlobalTls(profile.configType)) return false
        val current = profile.security.orEmpty()
        return if (enabled) {
            // Anything non-empty is already secured (tls, reality, xtls); leave it alone.
            if (current.isNotEmpty()) {
                false
            } else {
                profile.security = TLS
                true
            }
        } else {
            // Trojan cannot run without TLS and reality/xtls are not plain TLS.
            if (profile.configType == EConfigType.TROJAN || current != TLS) {
                false
            } else {
                profile.security = ""
                true
            }
        }
    }

    /**
     * Applies the switch to every profile of "My servers".
     * @return the number of profiles that were actually modified.
     */
    fun applyToMyServers(enabled: Boolean): Int {
        var changed = 0
        MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID).forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            if (apply(profile, enabled)) {
                MmkvManager.encodeServerConfig(guid, profile)
                changed++
            }
        }
        return changed
    }
}
