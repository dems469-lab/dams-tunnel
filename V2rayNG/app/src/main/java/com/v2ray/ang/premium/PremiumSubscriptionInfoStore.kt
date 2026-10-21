package com.v2ray.ang.premium

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/** Métadonnées non secrètes de quota/expiration, distinctes du token chiffré. */
internal class PremiumSubscriptionInfoStore(
    private val read: () -> String? = { MmkvManager.decodeSettingsString(STORAGE_KEY) },
    private val write: (String) -> Boolean = { MmkvManager.encodeSettings(STORAGE_KEY, it) },
    private val remove: () -> Unit = { MmkvManager.encodeSettings(STORAGE_KEY, null) },
) {
    @Synchronized
    fun load(): PremiumSubscriptionInfo? = read()?.let {
        JsonUtil.fromJsonSafe(it, PremiumSubscriptionInfo::class.java)
    }

    @Synchronized
    fun save(info: PremiumSubscriptionInfo?) {
        if (info == null) {
            remove()
        } else {
            check(write(JsonUtil.toJson(info))) { "Impossible d'enregistrer les métadonnées Premium" }
        }
    }

    @Synchronized
    fun clear() = remove()

    private companion object {
        const val STORAGE_KEY = "premium_subscription_info_v1"
    }
}
