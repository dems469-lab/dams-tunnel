package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumRestoreSnapshotMergerTest {
    @Test
    fun `restore keeps device Premium profiles and imports free profiles`() {
        val current = Snapshot(
            mapOf(
                "MAIN" to mutableMapOf(
                    "SUB_SERVERS_${PremiumGroup.ID}" to "[\"premium-current\"]",
                    "SUB_IDS" to "[\"${PremiumGroup.ID}\"]",
                ),
                "PROFILE_FULL_CONFIG" to mutableMapOf(
                    "premium-current" to "{\"subscriptionId\":\"${PremiumGroup.ID}\",\"remarks\":\"local-premium\"}",
                ),
                "SERVER_RAW" to mutableMapOf("premium-current" to "local-raw"),
                "SERVER_AFF" to mutableMapOf("premium-current" to "local-aff"),
                "SETTING" to mutableMapOf("premium_expiry_state_v1" to "local-expiry"),
            ),
        )
        val incoming = Snapshot(
            mapOf(
                "MAIN" to mutableMapOf("SUB_IDS" to "[\"free-sub\"]"),
                "PROFILE_FULL_CONFIG" to mutableMapOf(
                    "free-imported" to "{\"subscriptionId\":\"free-sub\",\"remarks\":\"free\"}",
                    "premium-from-archive" to "{\"subscriptionId\":\"${PremiumGroup.ID}\",\"remarks\":\"not-imported\"}",
                ),
                "SERVER_RAW" to mutableMapOf("premium-from-archive" to "not-imported"),
                "SERVER_AFF" to mutableMapOf(),
                "SUB" to mutableMapOf(),
                "SETTING" to mutableMapOf("ordinary-setting" to "imported"),
            ),
        )

        PremiumRestoreSnapshotMerger.apply(current, incoming)

        assertTrue(incoming.readString("PROFILE_FULL_CONFIG", "premium-current")!!.contains("local-premium"))
        assertTrue(incoming.readString("PROFILE_FULL_CONFIG", "free-imported")!!.contains("free"))
        assertFalse(incoming.keys("PROFILE_FULL_CONFIG").contains("premium-from-archive"))
        assertEquals("local-raw", incoming.readString("SERVER_RAW", "premium-current"))
        assertEquals("local-aff", incoming.readString("SERVER_AFF", "premium-current"))
        assertEquals("local-expiry", incoming.readString("SETTING", "premium_expiry_state_v1"))
        assertTrue(incoming.readString("MAIN", "SUB_IDS")!!.contains(PremiumGroup.ID))
        assertTrue(incoming.readString("MAIN", "SUB_IDS")!!.contains("free-sub"))
    }

    @Test
    fun `restore on a new device strips archived Premium and keeps free profiles`() {
        val current = Snapshot(emptyMap())
        val incoming = Snapshot(
            mapOf(
                "MAIN" to mutableMapOf(
                    "SUB_IDS" to "[\"free-sub\",\"${PremiumGroup.ID}\"]",
                    "SUB_SERVERS_${PremiumGroup.ID}" to "[\"premium-archive\"]",
                ),
                "PROFILE_FULL_CONFIG" to mutableMapOf(
                    "free-imported" to "{\"subscriptionId\":\"free-sub\",\"remarks\":\"free\"}",
                    "premium-archive" to "{\"subscriptionId\":\"${PremiumGroup.ID}\",\"remarks\":\"premium\"}",
                ),
                "SUB" to mutableMapOf(PremiumGroup.ID to "premium-subscription"),
                "SETTING" to mutableMapOf("premium_subscription_info_v1" to "premium-info"),
            ),
        )

        PremiumRestoreSnapshotMerger.apply(current, incoming)

        assertEquals(setOf("free-imported"), incoming.keys("PROFILE_FULL_CONFIG"))
        assertFalse(incoming.readString("MAIN", "SUB_IDS").orEmpty().contains(PremiumGroup.ID))
        assertFalse(incoming.keys("SUB").contains(PremiumGroup.ID))
        assertFalse(incoming.keys("SETTING").contains("premium_subscription_info_v1"))
    }

    private class Snapshot(initial: Map<String, MutableMap<String, String>>) : PremiumBackupSnapshotStore {
        private val values = initial.mapValuesTo(mutableMapOf()) { (_, entries) -> entries.toMutableMap() }

        override fun keys(database: String): Set<String> = values[database]?.keys.orEmpty()

        override fun readString(database: String, key: String): String? = values[database]?.get(key)

        override fun remove(database: String, keys: Collection<String>) {
            values[database]?.keys?.removeAll(keys.toSet())
        }

        override fun writeString(database: String, key: String, value: String) {
            values.getOrPut(database) { mutableMapOf() }[key] = value
        }
    }
}
