package com.v2ray.ang.reconfig.ui

import com.v2ray.ang.reconfig.data.ReconfigPingState
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.reconfig.data.ReconfiguredServer
import com.v2ray.ang.reconfig.engine.ReconfigMethod

internal enum class ReconfigurationSort {
    NAME,
    PING,
    DATE,
    STATE,
}

internal enum class ReconfigurationFilterState {
    PENDING,
    PING_OK,
    PING_FAILED,
    FUNCTIONAL,
    NOT_FUNCTIONAL,
    INVALID,
}

internal enum class ReconfigurationOperation {
    PING,
    TEST,
    TRANSFER,
}

internal data class ReconfigurationProgress(
    val operation: ReconfigurationOperation,
    val processed: Int,
    val total: Int,
) {
    val ratio: Float get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Message ponctuel affiché à l'utilisateur. Les valeurs sont des numéros de
 * ressource et des nombres : aucun contenu de configuration n'y transite.
 */
internal data class ReconfigurationNotice(
    val textRes: Int,
    val args: List<Any> = emptyList(),
    val error: Boolean = false,
    val actionRes: Int? = null,
)

internal data class ReconfigurationUiState(
    val loading: Boolean = true,
    val servers: List<ReconfiguredServer> = emptyList(),
    val query: String = "",
    val protocolFilter: String? = null,
    val methodFilter: ReconfigMethod? = null,
    val stateFilter: ReconfigurationFilterState? = null,
    val sort: ReconfigurationSort = ReconfigurationSort.DATE,
    val selectedIds: Set<Long> = emptySet(),
    val detailId: Long? = null,
    val progress: ReconfigurationProgress? = null,
    val notice: ReconfigurationNotice? = null,
    val deleteConfirmation: Set<Long>? = null,
    val pingRunning: Set<Long> = emptySet(),
    val testRunning: Set<Long> = emptySet(),
) {
    val visibleServers: List<ReconfiguredServer>
        get() = sortServers(
            filterServers(servers, query, protocolFilter, methodFilter, stateFilter),
            sort,
        )

    val detail: ReconfiguredServer? get() = detailId?.let { id -> servers.firstOrNull { it.id == id } }

    val totalCount: Int get() = servers.size
    val testedCount: Int
        get() = servers.count { it.testState != ReconfigTestState.PENDING && it.testState != ReconfigTestState.RUNNING }
    val functionalCount: Int get() = servers.count { it.isFunctional }
    val selectedVisibleCount: Int get() = visibleServers.count { it.id in selectedIds }
    val busy: Boolean get() = progress != null
}

internal fun matchesStateFilter(server: ReconfiguredServer, filter: ReconfigurationFilterState?): Boolean =
    when (filter) {
        null -> true
        ReconfigurationFilterState.PENDING ->
            server.testState == ReconfigTestState.PENDING && server.pingState == ReconfigPingState.PENDING
        ReconfigurationFilterState.PING_OK -> server.hasLatency
        ReconfigurationFilterState.PING_FAILED -> server.pingState in PING_FAILURE_STATES
        ReconfigurationFilterState.FUNCTIONAL -> server.isFunctional
        ReconfigurationFilterState.NOT_FUNCTIONAL -> server.testState in TEST_FAILURE_STATES
        ReconfigurationFilterState.INVALID -> !server.isValid
    }

/**
 * Filtrage par nom, adresse, protocole, méthode et état. Fonction pure, testée
 * sur la JVM.
 */
internal fun filterServers(
    servers: List<ReconfiguredServer>,
    query: String,
    protocol: String?,
    method: ReconfigMethod?,
    state: ReconfigurationFilterState?,
): List<ReconfiguredServer> {
    val needle = query.trim().lowercase()
    return servers.filter { server ->
        val matchesQuery = needle.isEmpty() ||
            server.name.lowercase().contains(needle) ||
            server.transformedAddress.lowercase().contains(needle) ||
            server.originalAddress.lowercase().contains(needle) ||
            server.protocol.lowercase().contains(needle) ||
            server.target.lowercase().contains(needle)
        val matchesProtocol = protocol == null || server.protocol.equals(protocol, ignoreCase = true)
        val matchesMethod = method == null || server.method == method
        matchesQuery && matchesProtocol && matchesMethod && matchesStateFilter(server, state)
    }
}

internal fun sortServers(servers: List<ReconfiguredServer>, sort: ReconfigurationSort): List<ReconfiguredServer> =
    when (sort) {
        ReconfigurationSort.NAME -> servers.sortedBy { it.name.lowercase() }
        ReconfigurationSort.PING -> servers.sortedBy { if (it.hasLatency) it.pingMs else Long.MAX_VALUE }
        ReconfigurationSort.DATE -> servers.sortedByDescending { it.createdAt }
        ReconfigurationSort.STATE -> servers.sortedBy { stateRank(it) }
    }

private fun stateRank(server: ReconfiguredServer): Int = when {
    server.isFunctional -> 0
    server.testState == ReconfigTestState.UNREACHABLE -> 1
    server.testState == ReconfigTestState.INVALID_CONFIG ||
        server.testState == ReconfigTestState.CORE_ERROR -> 2
    server.hasLatency -> 3
    else -> 4
}

private val PING_FAILURE_STATES = setOf(
    ReconfigPingState.TIMEOUT,
    ReconfigPingState.DNS_ERROR,
    ReconfigPingState.HOST_UNREACHABLE,
    ReconfigPingState.CONNECTION_ERROR,
    ReconfigPingState.ERROR,
)

private val TEST_FAILURE_STATES = setOf(
    ReconfigTestState.UNREACHABLE,
    ReconfigTestState.INVALID_CONFIG,
    ReconfigTestState.CORE_ERROR,
    ReconfigTestState.CANCELLED,
)
