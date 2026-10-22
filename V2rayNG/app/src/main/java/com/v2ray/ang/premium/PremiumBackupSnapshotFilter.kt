package com.v2ray.ang.premium

import com.google.gson.JsonParser

/** Accès au snapshot MMKV temporaire, sans toucher aux bases actives de l'application. */
internal interface PremiumBackupSnapshotStore {
    fun keys(database: String): Set<String>
    fun readString(database: String, key: String): String?
    fun remove(database: String, keys: Collection<String>)
    fun writeString(database: String, key: String, value: String)
}

/** Retire les profils et métadonnées Premium d'une copie MMKV avant toute création d'archive. */
internal object PremiumBackupSnapshotFilter {
    private const val MAIN = "MAIN"
    private const val PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val SERVER_RAW = "SERVER_RAW"
    private const val SERVER_AFF = "SERVER_AFF"
    private const val SUB = "SUB"
    private const val SETTING = "SETTING"
    private const val PREMIUM_SERVER_LIST = "SUB_SERVERS_${PremiumGroup.ID}"
    private const val SUB_IDS = "SUB_IDS"
    private const val SELECTED_SERVER = "SELECTED_SERVER"

    private val premiumSettingKeys = setOf(
        "premium_expiry_state_v1",
        "premium_subscription_info_v1",
        "pref_premium_internal_enabled",
    )

    fun apply(store: PremiumBackupSnapshotStore) {
        val groupGuids = decodeStringList(store.readString(MAIN, PREMIUM_SERVER_LIST))
        val profileGuids = store.keys(PROFILE_FULL_CONFIG)
        val subIds = decodeStringList(store.readString(MAIN, SUB_IDS))
        val premiumGuids = profileGuids.filterTo(groupGuids.toMutableSet()) { guid ->
            val json = store.readString(PROFILE_FULL_CONFIG, guid)
                ?: error("Profil illisible dans la copie de sauvegarde")
            val subscriptionId = try {
                JsonParser.parseString(json).asJsonObject
                    .get("subscriptionId")?.asString.orEmpty()
            } catch (_: Exception) {
                error("Profil illisible dans la copie de sauvegarde")
            }
            PremiumIsolationPolicy.isPremiumGroup(subscriptionId)
        }

        val premiumStateExists = premiumGuids.isNotEmpty() ||
            PREMIUM_SERVER_LIST in store.keys(MAIN) ||
            PremiumGroup.ID in subIds ||
            PremiumGroup.ID in store.keys(SUB) ||
            store.keys(SETTING).any { it in premiumSettingKeys }
        if (!premiumStateExists) return

        store.remove(PROFILE_FULL_CONFIG, premiumGuids)
        store.remove(SERVER_RAW, premiumGuids)
        store.remove(SERVER_AFF, premiumGuids)
        store.remove(SUB, listOf(PremiumGroup.ID))
        store.remove(MAIN, listOf(PREMIUM_SERVER_LIST))

        val subIdsValue = store.readString(MAIN, SUB_IDS)
        if (subIdsValue != null) {
            val filteredSubIds = subIds.filterNot(PremiumIsolationPolicy::isPremiumGroup)
            if (filteredSubIds != subIds) {
                val filteredSubIdsJson = com.google.gson.JsonArray()
                filteredSubIds.forEach(filteredSubIdsJson::add)
                store.writeString(MAIN, SUB_IDS, filteredSubIdsJson.toString())
            }
        }

        if (store.readString(MAIN, SELECTED_SERVER) in premiumGuids) {
            store.remove(MAIN, listOf(SELECTED_SERVER))
        }
        store.remove(SETTING, premiumSettingKeys)
    }

    private fun decodeStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            JsonParser.parseString(json).asJsonArray.map { it.asString }
        } catch (_: Exception) {
            error("Index de sauvegarde illisible")
        }
    }
}
