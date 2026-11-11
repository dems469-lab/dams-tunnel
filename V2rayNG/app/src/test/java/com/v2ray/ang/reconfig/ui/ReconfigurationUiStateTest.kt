package com.v2ray.ang.reconfig.ui

import com.v2ray.ang.reconfig.data.ReconfigPingState
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.reconfig.data.ReconfiguredServer
import com.v2ray.ang.reconfig.engine.ReconfigMethod
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconfigurationUiStateTest {

    private fun server(
        id: Long,
        name: String,
        address: String = "edge.example.com",
        protocol: String = "VLESS",
        method: ReconfigMethod = ReconfigMethod.MAXIT_MOBILE,
        pingState: ReconfigPingState = ReconfigPingState.PENDING,
        pingMs: Long = -1L,
        testState: ReconfigTestState = ReconfigTestState.PENDING,
        functional: Boolean = false,
        createdAt: Long = id,
        isValid: Boolean = true,
    ) = ReconfiguredServer(
        id = id,
        name = name,
        protocol = protocol,
        originalAddress = "origin.example.com",
        transformedAddress = address,
        reconfigurationMethod = method.storageKey,
        target = "Maxit-mobile.orange.bf",
        createdAt = createdAt,
        pingMs = pingMs,
        pingState = pingState,
        testState = testState,
        isFunctional = functional,
        isValid = isValid,
    )

    @Test
    fun `la recherche couvre le nom et les adresses sans tenir compte de la casse`() {
        val servers = listOf(
            server(1, "Paris", address = "paris.example.com"),
            server(2, "Berlin", address = "berlin.example.com"),
        )
        assertEquals(listOf(1L), filterServers(servers, "PARIS", null, null, null).map { it.id })
        assertEquals(listOf(2L), filterServers(servers, "berlin.example", null, null, null).map { it.id })
        assertEquals(listOf(1L, 2L), filterServers(servers, "origin.example", null, null, null).map { it.id })
        assertTrue(filterServers(servers, "tokyo", null, null, null).isEmpty())
    }

    @Test
    fun `les filtres protocole methode et etat se combinent`() {
        val servers = listOf(
            server(1, "A", protocol = "VLESS", functional = true, testState = ReconfigTestState.FUNCTIONAL),
            server(2, "B", protocol = "TROJAN", pingState = ReconfigPingState.SUCCESS, pingMs = 40),
            server(3, "C", protocol = "VMESS", method = ReconfigMethod.API_CONNECT),
            server(4, "D", protocol = "VLESS", isValid = false),
        )

        assertEquals(
            listOf(1L, 4L),
            filterServers(servers, "", "vless", null, null).map { it.id },
        )
        assertEquals(
            listOf(3L),
            filterServers(servers, "", null, ReconfigMethod.API_CONNECT, null).map { it.id },
        )
        assertEquals(
            listOf(2L),
            filterServers(servers, "", null, null, ReconfigurationFilterState.PING_OK).map { it.id },
        )
        assertEquals(
            listOf(1L),
            filterServers(servers, "", null, null, ReconfigurationFilterState.FUNCTIONAL).map { it.id },
        )
        assertEquals(
            listOf(4L),
            filterServers(servers, "", null, null, ReconfigurationFilterState.INVALID).map { it.id },
        )
    }

    @Test
    fun `un ping en echec ne passe pas pour un ping reussi`() {
        val servers = listOf(
            server(1, "A", pingState = ReconfigPingState.TIMEOUT),
            server(2, "B", pingState = ReconfigPingState.SUCCESS, pingMs = 0),
        )
        assertEquals(
            listOf(2L),
            filterServers(servers, "", null, null, ReconfigurationFilterState.PING_OK).map { it.id },
        )
        assertEquals(
            listOf(1L),
            filterServers(servers, "", null, null, ReconfigurationFilterState.PING_FAILED).map { it.id },
        )
    }

    @Test
    fun `le tri par ping place les non mesures en dernier`() {
        val servers = listOf(
            server(1, "A"),
            server(2, "B", pingState = ReconfigPingState.SUCCESS, pingMs = 120),
            server(3, "C", pingState = ReconfigPingState.SUCCESS, pingMs = 35),
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            sortServers(servers, ReconfigurationSort.PING).map { it.id },
        )
    }

    @Test
    fun `le tri par date est decroissant et le tri par etat priorise les fonctionnels`() {
        val servers = listOf(
            server(1, "old", createdAt = 10),
            server(2, "new", createdAt = 30),
            server(3, "middle", createdAt = 20),
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            sortServers(servers, ReconfigurationSort.DATE).map { it.id },
        )

        val ranked = listOf(
            server(1, "pending"),
            server(2, "functional", functional = true, testState = ReconfigTestState.FUNCTIONAL),
            server(3, "invalid", isValid = false, testState = ReconfigTestState.INVALID_CONFIG),
        )
        assertEquals(2L, sortServers(ranked, ReconfigurationSort.STATE).first().id)
    }

    @Test
    fun `le tri par nom ignore la casse`() {
        val servers = listOf(server(1, "beta"), server(2, "Alpha"), server(3, "gamma"))
        assertEquals(
            listOf(2L, 1L, 3L),
            sortServers(servers, ReconfigurationSort.NAME).map { it.id },
        )
    }

    @Test
    fun `les compteurs de l en tete distinguent testés et fonctionnels`() {
        val state = ReconfigurationUiState(
            servers = listOf(
                server(1, "A", functional = true, testState = ReconfigTestState.FUNCTIONAL),
                server(2, "B", testState = ReconfigTestState.UNREACHABLE),
                server(3, "C"),
            ),
            selectedIds = setOf(1L, 2L),
        )
        assertEquals(3, state.totalCount)
        assertEquals(2, state.testedCount)
        assertEquals(1, state.functionalCount)
    }

    @Test
    fun `la progression est bornee meme avec un total nul`() {
        assertEquals(0f, ReconfigurationProgress(ReconfigurationOperation.PING, 0, 0).ratio)
        assertEquals(1f, ReconfigurationProgress(ReconfigurationOperation.TEST, 5, 5).ratio)
        assertEquals(0.5f, ReconfigurationProgress(ReconfigurationOperation.TRANSFER, 1, 2).ratio)
    }
}
