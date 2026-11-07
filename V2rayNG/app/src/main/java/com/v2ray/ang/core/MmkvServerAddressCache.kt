package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * Cache persistant (MMKV) de la dernière IP résolue par domaine, avec la date de résolution.
 * Le cache survit aux redémarrages et sert de repli quand la résolution en direct échoue.
 */
internal class MmkvServerAddressCache(
    private val storageKey: String = AppConfig.PREF_SERVER_ADDRESS_CACHE,
) : ServerAddressCache {
    private val lock = Any()

    override fun read(domain: String): CachedServerAddress? = synchronized(lock) {
        runCatching {
            val entry = load().get(domain)
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return@synchronized null
            val ips = entry.get("ips")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
                ?: emptyList()
            val resolvedAt = entry.get("ts")
                ?.takeIf { it.isJsonPrimitive }
                ?.asLong
                ?: 0L
            if (ips.isEmpty()) null else CachedServerAddress(ips, resolvedAt)
        }.getOrNull()
    }

    override fun write(domain: String, ips: List<String>, resolvedAtMillis: Long) {
        synchronized(lock) {
            val root = load()
            root.add(
                domain,
                JsonObject().apply {
                    add("ips", JsonArray().apply { ips.forEach { add(it) } })
                    addProperty("ts", resolvedAtMillis)
                },
            )
            MmkvManager.encodeSettings(storageKey, root.toString())
        }
    }

    private fun load(): JsonObject =
        runCatching {
            MmkvManager.decodeSettingsString(storageKey)
                ?.let { JsonParser.parseString(it) }
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        }.getOrNull() ?: JsonObject()
}
