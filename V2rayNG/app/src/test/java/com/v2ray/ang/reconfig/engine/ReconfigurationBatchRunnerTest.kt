package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.reconfig.data.PrepareSummary
import com.v2ray.ang.reconfig.data.PreparedReconfiguration
import com.v2ray.ang.reconfig.data.ReconfigurationStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconfigurationBatchRunnerTest {

    private class FakeStore : ReconfigurationStore {
        val saved = mutableListOf<PreparedReconfiguration>()
        var duplicateCount = 0

        override suspend fun savePrepared(
            items: List<PreparedReconfiguration>,
            now: Long,
        ): PrepareSummary {
            saved += items
            return PrepareSummary(items.size, duplicateCount)
        }
    }

    // Le moteur est un objet sans état : aucun codec ni dépendance Android
    // n'est nécessaire pour tester le traitement par lots sur la JVM.
    private fun createRunner(store: ReconfigurationStore) =
        ReconfigurationBatchRunner(store)

    private fun profile(address: String) = ProfileItem.create(EConfigType.VLESS).apply {
        server = address
        serverPort = "443"
        password = "uuid"
        security = "tls"
        sni = "sni.example.com"
    }

    @Test
    fun `traite chaque configuration et rapporte la progression`() = runBlocking {
        val store = FakeStore()
        val runner = createRunner(store)
        val progress = mutableListOf<Pair<Int, Int>>()

        val result = runner.run(
            inputs = listOf(
                "a" to profile("a.example.com"),
                "b" to profile("b.example.com"),
                "c" to profile(""), // invalide
            ),
            method = ReconfigMethod.API_CONNECT,
            target = "api-connect.orange.bf",
            onProgress = { processed, total -> progress += processed to total },
        )

        assertEquals(2, result.prepared)
        assertEquals(1, result.ignored)
        assertEquals(0, result.originalsModified)
        assertFalse(result.cancelled)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(2, store.saved.size)
        assertEquals(
            1,
            result.failures[ReconfigurationStatus.INVALID_ADDRESS],
        )
    }

    @Test
    fun `compte les doublons sans echouer`() = runBlocking {
        val store = FakeStore().apply { duplicateCount = 0 }
        val runner = createRunner(store)
        val result = runner.run(
            inputs = listOf("a" to profile("a.example.com")),
            method = ReconfigMethod.MAXIT_MOBILE,
            target = "Maxit-mobile.orange.bf",
        )
        assertEquals(1, result.prepared)
        assertEquals(0, result.duplicates)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `une cible invalide ignore tout sans rien enregistrer`() = runBlocking {
        val store = FakeStore()
        val runner = createRunner(store)
        val result = runner.run(
            inputs = listOf("a" to profile("a.example.com"), "b" to profile("b.example.com")),
            method = ReconfigMethod.CUSTOM,
            target = "",
        )
        assertEquals(0, result.prepared)
        assertEquals(2, result.ignored)
        assertEquals(2, result.failures[ReconfigurationStatus.INVALID_TARGET])
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `une liste vide ne fait rien`() = runBlocking {
        val store = FakeStore()
        val runner = createRunner(store)
        val result = runner.run(emptyList(), ReconfigMethod.API_CONNECT, "api-connect.orange.bf")
        assertEquals(0, result.processed)
        assertFalse(result.cancelled)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `le lot s arrete quand la coroutine est annulee`() = runBlocking {
        val store = FakeStore()
        val runner = createRunner(store)
        var cancelJob: Job? = null
        cancelJob = launch {
            runner.run(
                inputs = (1..50).map { "id$it" to profile("s$it.example.com") },
                method = ReconfigMethod.API_CONNECT,
                target = "api-connect.orange.bf",
                onProgress = { processed, _ ->
                    if (processed == 2) cancelJob?.cancel()
                },
            )
        }
        cancelJob.join()
        assertTrue(cancelJob.isCancelled)
        assertTrue(store.saved.size <= 3, "trop de lignes enregistrees apres annulation : ${store.saved.size}")
    }
}
