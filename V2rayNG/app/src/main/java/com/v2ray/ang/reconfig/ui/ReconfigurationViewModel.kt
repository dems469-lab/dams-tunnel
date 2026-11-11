package com.v2ray.ang.reconfig.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.reconfig.data.ConfigsTransferManager
import com.v2ray.ang.reconfig.data.ReconfigPingState
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.reconfig.data.ReconfigurationRepository
import com.v2ray.ang.reconfig.engine.ReconfigMethod
import com.v2ray.ang.reconfig.net.ServerConnectivityTester
import com.v2ray.ang.reconfig.net.ServerPingManager
import com.v2ray.ang.ui.base.BaseViewModel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * État et opérations de l'écran Reconfiguration.
 *
 * Le chargement, les mesures et les transferts s'exécutent hors du thread
 * principal. Les opérations longues sont annulables et n'empêchent jamais
 * l'utilisateur de continuer à naviguer.
 */
internal class ReconfigurationViewModel(application: Application) : BaseViewModel(application) {

    private val repository = ReconfigurationRepository(application)
    private val transferManager = ConfigsTransferManager()
    private val tester = ServerConnectivityTester(application)

    private val _uiState = MutableStateFlow(ReconfigurationUiState())
    val uiState: StateFlow<ReconfigurationUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var operationJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ServerConnectivityTester.purgeTemporaryProfiles()
        }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val servers = repository.loadAll()
            _uiState.update { state ->
                state.copy(
                    servers = servers,
                    loading = false,
                    // Une sélection ne doit jamais référencer une ligne disparue.
                    selectedIds = state.selectedIds intersect servers.map { it.id }.toSet(),
                    detailId = state.detailId?.takeIf { id -> servers.any { it.id == id } },
                )
            }
        }
    }

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    fun setProtocolFilter(value: String?) = _uiState.update { it.copy(protocolFilter = value) }

    fun setMethodFilter(value: ReconfigMethod?) = _uiState.update { it.copy(methodFilter = value) }

    fun setStateFilter(value: ReconfigurationFilterState?) = _uiState.update { it.copy(stateFilter = value) }

    fun setSort(value: ReconfigurationSort) = _uiState.update { it.copy(sort = value) }

    fun toggleSelection(id: Long) = _uiState.update { state ->
        state.copy(selectedIds = if (id in state.selectedIds) state.selectedIds - id else state.selectedIds + id)
    }

    fun selectAllVisible() = _uiState.update { state ->
        state.copy(selectedIds = state.selectedIds + state.visibleServers.map { it.id })
    }

    fun clearSelection() = _uiState.update { it.copy(selectedIds = emptySet()) }

    fun openDetail(id: Long) = _uiState.update { it.copy(detailId = id) }

    fun closeDetail() = _uiState.update { it.copy(detailId = null) }

    fun consumeNotice() = _uiState.update { it.copy(notice = null) }

    fun requestDelete(ids: Set<Long>) = _uiState.update { it.copy(deleteConfirmation = ids) }

    fun cancelDeleteRequest() = _uiState.update { it.copy(deleteConfirmation = null) }

    fun confirmDelete() {
        val ids = _uiState.value.deleteConfirmation ?: return
        _uiState.update { it.copy(deleteConfirmation = null) }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { repository.delete(ids) }
            refresh()
            _uiState.update {
                it.copy(notice = ReconfigurationNotice(R.string.reconfig_delete_done, listOf(deleted)))
            }
        }
    }

    // ---------- ping ----------

    fun pingServer(id: Long) = pingServers(setOf(id))

    fun pingSelection() = pingServers(_uiState.value.selectedIds)

    fun pingAll() = pingServers(_uiState.value.servers.map { it.id }.toSet())

    private fun pingServers(ids: Set<Long>) {
        if (ids.isEmpty() || _uiState.value.busy) return
        operationJob = viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) { repository.findByIds(ids) }
            if (targets.isEmpty()) return@launch
            val total = targets.size
            val processed = AtomicInteger(0)
            val semaphore = Semaphore(PING_CONCURRENCY)
            _uiState.update { it.copy(progress = ReconfigurationProgress(ReconfigurationOperation.PING, 0, total)) }
            try {
                coroutineScope {
                    targets.map { server ->
                        async {
                            semaphore.withPermit {
                                val port = server.transformedPort.toIntOrNull() ?: -1
                                _uiState.update { state ->
                                    state.copy(pingRunning = state.pingRunning + server.id)
                                }
                                val outcome = ServerPingManager.ping(server.transformedAddress, port)
                                withContext(Dispatchers.IO) {
                                    repository.updatePing(
                                        server.id,
                                        outcome.state,
                                        outcome.latencyMs,
                                        System.currentTimeMillis(),
                                    )
                                }
                                applyPing(server.id, outcome.state, outcome.latencyMs)
                                val done = processed.incrementAndGet()
                                _uiState.update { state ->
                                    state.copy(
                                        pingRunning = state.pingRunning - server.id,
                                        progress = ReconfigurationProgress(
                                            ReconfigurationOperation.PING,
                                            done,
                                            total,
                                        ),
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                _uiState.update { it.copy(progress = null, pingRunning = emptySet()) }
            }
        }.also { job ->
            job.invokeOnCompletion { if (job.isCancelled) cancelProgressNotice() }
        }
    }

    // ---------- test réel ----------

    fun testServer(id: Long) = testServers(setOf(id))

    fun testSelection() = testServers(_uiState.value.selectedIds)

    fun testAll() = testServers(_uiState.value.servers.map { it.id }.toSet())

    private fun testServers(ids: Set<Long>) {
        if (ids.isEmpty() || _uiState.value.busy) return
        operationJob = viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) { repository.findByIds(ids) }
            if (targets.isEmpty()) return@launch
            val total = targets.size
            val processed = AtomicInteger(0)
            var functional = 0
            var notFunctional = 0
            var invalid = 0
            _uiState.update { it.copy(progress = ReconfigurationProgress(ReconfigurationOperation.TEST, 0, total)) }
            try {
                // Le testeur limite déjà la concurrence native à trois instances.
                targets.chunked(ServerConnectivityTester.MAX_CONCURRENT_TESTS).forEach { chunk ->
                    chunk.map { server ->
                        async {
                            val profile = transferManager.decodeProfile(server)
                            _uiState.update { state -> state.copy(testRunning = state.testRunning + server.id) }
                            _uiState.update { state ->
                                state.copy(
                                    servers = state.servers.map {
                                        if (it.id == server.id) it.copy(testState = ReconfigTestState.RUNNING) else it
                                    },
                                )
                            }
                            if (profile == null) {
                                invalid++
                                withContext(Dispatchers.IO) {
                                    repository.updateTest(
                                        server.id,
                                        ReconfigTestState.INVALID_CONFIG,
                                        functional = false,
                                        at = System.currentTimeMillis(),
                                        error = "profile",
                                    )
                                }
                                applyTest(server.id, ReconfigTestState.INVALID_CONFIG, functional = false)
                            } else {
                                val outcome = tester.test(profile)
                                if (outcome.state == ReconfigTestState.FUNCTIONAL) functional++ else if (
                                    outcome.state == ReconfigTestState.INVALID_CONFIG
                                ) invalid++ else notFunctional++
                                withContext(Dispatchers.IO) {
                                    repository.updateTest(
                                        server.id,
                                        outcome.state,
                                        functional = outcome.state == ReconfigTestState.FUNCTIONAL,
                                        at = System.currentTimeMillis(),
                                        error = outcome.detail,
                                    )
                                }
                                applyTest(
                                    server.id,
                                    outcome.state,
                                    functional = outcome.state == ReconfigTestState.FUNCTIONAL,
                                )
                            }
                            val done = processed.incrementAndGet()
                            _uiState.update { state ->
                                state.copy(
                                    testRunning = state.testRunning - server.id,
                                    progress = ReconfigurationProgress(ReconfigurationOperation.TEST, done, total),
                                )
                            }
                        }
                    }.awaitAll()
                }
                _uiState.update {
                    it.copy(
                        notice = ReconfigurationNotice(
                            R.string.reconfig_test_summary,
                            listOf(functional, notFunctional, invalid),
                            error = notFunctional > 0,
                        ),
                    )
                }
            } finally {
                _uiState.update { it.copy(progress = null, testRunning = emptySet()) }
            }
        }.also { job ->
            job.invokeOnCompletion { if (job.isCancelled) cancelProgressNotice() }
        }
    }

    // ---------- transfert ----------

    fun transferSelection() = transfer(_uiState.value.selectedIds)

    fun transferServer(id: Long) = transfer(setOf(id))

    fun transferDetail() {
        val id = _uiState.value.detailId ?: return
        _uiState.update { it.copy(detailId = null) }
        transfer(setOf(id))
    }

    private fun transfer(ids: Set<Long>) {
        if (ids.isEmpty() || _uiState.value.busy) return
        operationJob = viewModelScope.launch {
            val targets = withContext(Dispatchers.IO) { repository.findByIds(ids) }
            if (targets.isEmpty()) return@launch
            _uiState.update {
                it.copy(progress = ReconfigurationProgress(ReconfigurationOperation.TRANSFER, 0, targets.size))
            }
            try {
                val summary = withContext(Dispatchers.IO) { transferManager.transfer(targets) }
                if (summary.removableIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) { repository.delete(summary.removableIds.toSet()) }
                }
                _uiState.update {
                    it.copy(
                        progress = ReconfigurationProgress(
                            ReconfigurationOperation.TRANSFER,
                            targets.size,
                            targets.size,
                        ),
                        notice = ReconfigurationNotice(
                            R.string.reconfig_transfer_done,
                            listOf(summary.added, summary.alreadyPresent, summary.invalid, summary.failed),
                            error = summary.failed > 0 || summary.invalid > 0,
                        ),
                    )
                }
                refresh()
            } finally {
                _uiState.update { it.copy(progress = null) }
            }
        }.also { job ->
            job.invokeOnCompletion { if (job.isCancelled) cancelProgressNotice() }
        }
    }

    fun cancelOperation() {
        val job = operationJob
        operationJob = null
        job?.cancel()
        _uiState.update {
            it.copy(
                progress = null,
                pingRunning = emptySet(),
                testRunning = emptySet(),
                notice = ReconfigurationNotice(R.string.reconfig_op_cancelled),
            )
        }
    }

    private fun cancelProgressNotice() {
        _uiState.update {
            it.copy(
                progress = null,
                pingRunning = emptySet(),
                testRunning = emptySet(),
                notice = ReconfigurationNotice(R.string.reconfig_op_cancelled),
            )
        }
    }

    private fun applyPing(id: Long, state: ReconfigPingState, latencyMs: Long) = _uiState.update { current ->
        current.copy(
            servers = current.servers.map { server ->
                if (server.id == id) {
                    server.copy(
                        pingState = if (state == ReconfigPingState.RUNNING) ReconfigPingState.PENDING else state,
                        pingMs = latencyMs,
                        lastPingAt = System.currentTimeMillis(),
                    )
                } else {
                    server
                }
            },
        )
    }

    private fun applyTest(id: Long, state: ReconfigTestState, functional: Boolean) = _uiState.update { current ->
        current.copy(
            servers = current.servers.map { server ->
                if (server.id == id) {
                    server.copy(
                        testState = state,
                        isFunctional = functional,
                        lastTestAt = System.currentTimeMillis(),
                    )
                } else {
                    server
                }
            },
        )
    }

    private companion object {
        /** Connexions TCP simultanées pour le ping : volontairement modéré. */
        const val PING_CONCURRENCY = 6
    }
}
