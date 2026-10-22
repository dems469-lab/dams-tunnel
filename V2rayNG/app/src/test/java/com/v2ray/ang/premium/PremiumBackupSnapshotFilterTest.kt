package com.v2ray.ang.premium

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumBackupSnapshotFilterTest {
    @Test
    fun `archive excludes Premium payloads and keeps free configuration`() {
        val store = FakeSnapshotStore(
            mapOf(
                "MAIN" to mutableMapOf(
                    "SUB_SERVERS_${PremiumGroup.ID}" to "[\"premium-guid\"]",
                    "SUB_IDS" to "[\"free-sub\",\"${PremiumGroup.ID}\"]",
                    "SELECTED_SERVER" to "premium-guid",
                ),
                "PROFILE_FULL_CONFIG" to mutableMapOf(
                    "premium-guid" to "{\"subscriptionId\":\"${PremiumGroup.ID}\",\"remarks\":\"PREMIUM_PRIVATE_SENTINEL\"}",
                    "free-guid" to "{\"subscriptionId\":\"free-sub\",\"remarks\":\"free-profile\"}",
                ),
                "SERVER_RAW" to mutableMapOf(
                    "premium-guid" to "PREMIUM_PRIVATE_SENTINEL",
                    "free-guid" to "free-raw",
                ),
                "SERVER_AFF" to mutableMapOf("premium-guid" to "premium-affiliation"),
                "SUB" to mutableMapOf(
                    PremiumGroup.ID to "https://premium.invalid/sub/secret",
                    "free-sub" to "https://free.invalid/sub/token",
                ),
                "SETTING" to mutableMapOf(
                    "premium_expiry_state_v1" to "premium-expiry",
                    "premium_subscription_info_v1" to "premium-quota",
                    "ordinary-setting" to "kept",
                ),
            ),
        )

        PremiumBackupSnapshotFilter.apply(store)
        val archive = createArchive(store.snapshot)
        val archiveText = readArchive(archive)

        assertFalse(archiveText.contains(PremiumGroup.ID))
        assertFalse(archiveText.contains("PREMIUM_PRIVATE_SENTINEL"))
        assertFalse(archiveText.contains("premium.invalid"))
        assertTrue(archiveText.contains("free-profile"))
        assertTrue(archiveText.contains("free.invalid"))
        assertTrue(archiveText.contains("ordinary-setting"))
    }

    @Test
    fun `snapshot without Premium remains unchanged`() {
        val original = mapOf(
            "MAIN" to mutableMapOf("SUB_IDS" to "[\"free-sub\"]"),
            "PROFILE_FULL_CONFIG" to mutableMapOf(
                "free-guid" to "{\"subscriptionId\":\"free-sub\",\"remarks\":\"free-profile\"}",
            ),
            "SUB" to mutableMapOf("free-sub" to "free-subscription"),
            "SETTING" to mutableMapOf("ordinary-setting" to "kept"),
        )
        val store = FakeSnapshotStore(original)

        PremiumBackupSnapshotFilter.apply(store)

        assertEquals(original, store.snapshot)
        assertEquals(createArchive(original), createArchive(store.snapshot))
    }

    private fun createArchive(snapshot: Map<String, Map<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(0)
            snapshot.forEach { (database, entries) ->
                entries.forEach { (key, value) ->
                    zip.putNextEntry(ZipEntry("$database/$key").apply { time = 0L })
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }
        return output.toByteArray()
    }

    private fun readArchive(archive: ByteArray): String = ZipInputStream(archive.inputStream()).use { zip ->
        buildString {
            while (true) {
                val entry = zip.nextEntry ?: break
                append(entry.name)
                append(zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private class FakeSnapshotStore(initial: Map<String, Map<String, String>>) : PremiumBackupSnapshotStore {
        val snapshot: MutableMap<String, MutableMap<String, String>> =
            initial.mapValuesTo(mutableMapOf()) { (_, entries) -> entries.toMutableMap() }

        override fun keys(database: String): Set<String> = snapshot[database]?.keys.orEmpty()

        override fun readString(database: String, key: String): String? = snapshot[database]?.get(key)

        override fun remove(database: String, keys: Collection<String>) {
            snapshot[database]?.keys?.removeAll(keys.toSet())
        }

        override fun writeString(database: String, key: String, value: String) {
            snapshot.getOrPut(database) { mutableMapOf() }[key] = value
        }
    }
}
