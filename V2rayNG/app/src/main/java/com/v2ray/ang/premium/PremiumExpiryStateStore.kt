package com.v2ray.ang.premium

import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil

/** Persiste uniquement l'état non secret d'expiration dans le stockage de réglages existant. */
internal class PremiumExpiryStateStore(
    private val read: () -> String? = { MmkvManager.decodeSettingsString(STORAGE_KEY) },
    private val write: (String) -> Boolean = { MmkvManager.encodeSettings(STORAGE_KEY, it) },
    private val remove: () -> Unit = { MmkvManager.encodeSettings(STORAGE_KEY, null) },
) {
    @Synchronized
    fun load(): PremiumExpiryState = read()?.let {
        JsonUtil.fromJsonSafe(it, PremiumExpiryState::class.java)
    } ?: PremiumExpiryState()

    @Synchronized
    fun save(state: PremiumExpiryState) {
        check(write(JsonUtil.toJson(state))) { "Impossible d'enregistrer l'état d'expiration Premium" }
    }

    @Synchronized
    fun clear() = remove()

    private companion object {
        const val STORAGE_KEY = "premium_expiry_state_v1"
    }
}
