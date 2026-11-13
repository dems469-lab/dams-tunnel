package com.v2ray.ang.tester

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.ServerHealthStore
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.isGroupType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.Utils
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TesterUiState(
    val scope: TesterScope = TesterScope.CONFIGS,
    val kind: TesterKind = TesterKind.TUNNEL,
    val loading: Boolean = true,
    val testing: Boolean = false,
    val runningKind: TesterKind? = null,
    val runningScope: TesterScope? = null,
    val progress: String? = null,
    val concurrency: Int = AppConfig.TESTER_CONCURRENCY_DEFAULT,
    val configs: List<TesterRow> = emptyList(),
    val free: List<TesterRow> = emptyList(),
    val error: Boolean = false,
) {
    val rows: List<TesterRow> get() = if (scope == TesterScope.CONFIGS) configs else free
}

/**
 * Écran Tester : deux listes cloisonnées (Configs / serveurs gratuits), deux
 * natures de mesure distinctes (test de tunnel réel / ping réseau), une file
 * dédiée, annulable et reprenable. Aucun résultat n'est inventé : une ligne
 * jamais mesurée affiche « non mesuré », un échec affiche « échec ».
 */
internal class TesterViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(TesterUiState())
    val uiState: StateFlow<TesterUiState> = _uiState.asStateFlow()

    private var configsRows: List<TesterRow> = emptyList()
    private var freeRows: List<TesterRow> = emptyList()
    private var requestId: String? = null
    private var loadJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            if (safeIntent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) != requestId) return
            when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val result = safeIntent.serializable<RealPingResult>("content") ?: return
                    applyResult(result)
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> _uiState.value = _uiState.value.copy(
                    progress = safeIntent.getStringExtra("content").orEmpty(),
                )

                AppConfig.MSG_MEASURE_CONFIG_FINISH,
                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> finishTest()
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
        _uiState.value = _uiState.value.copy(
            concurrency = MmkvManager.decodeSettingsInt(
                AppConfig.PREF_TESTER_CONCURRENCY,
                AppConfig.TESTER_CONCURRENCY_DEFAULT,
            ).coerceIn(AppConfig.TESTER_CONCURRENCY_MIN, AppConfig.TESTER_CONCURRENCY_MAX),
        )
        refresh()
    }

    fun setScope(scope: TesterScope) {
        if (_uiState.value.testing) return
        _uiState.value = _uiState.value.copy(scope = scope)
        publish()
    }

    fun setKind(kind: TesterKind) {
        if (_uiState.value.kind == kind) return
        _uiState.value = _uiState.value.copy(kind = kind)
        publish()
    }

    fun setConcurrency(value: Int) {
        val clamped = value.coerceIn(
            AppConfig.TESTER_CONCURRENCY_MIN,
            AppConfig.TESTER_CONCURRENCY_MAX,
        )
        MmkvManager.encodeSettings(AppConfig.PREF_TESTER_CONCURRENCY, clamped)
        _uiState.value = _uiState.value.copy(concurrency = clamped)
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = false)
            try {
                val rows = withContext(Dispatchers.IO) { loadRows() }
                configsRows = rows.filter { it.scope == TesterScope.CONFIGS }
                freeRows = rows.filter { it.scope == TesterScope.FREE }
                _uiState.value = _uiState.value.copy(loading = false)
                publish()
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(loading = false, error = true)
            }
        }
    }

    /** Lance la mesure réelle de toute la liste affichée, dans sa propre file. */
    fun startTest(kind: TesterKind) {
        val state = _uiState.value
        if (state.testing) return
        val scope = state.scope
        val targets = (if (scope == TesterScope.CONFIGS) configsRows else freeRows)
            .filter { it.guid.isNotBlank() }
        if (targets.isEmpty()) return

        val id = UUID.randomUUID().toString()
        requestId = id
        val targetGuids = targets.map { it.guid }.toHashSet()
        configsRows = configsRows.map { if (it.guid in targetGuids) it.copy(running = true) else it }
        freeRows = freeRows.map { if (it.guid in targetGuids) it.copy(running = true) else it }
        _uiState.value = _uiState.value.copy(
            testing = true,
            kind = kind,
            runningKind = kind,
            runningScope = scope,
            progress = null,
        )
        publish()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Une nouvelle mesure remplace l'ancienne valeur : on efface
                // uniquement la nature testée, pas l'autre.
                MessageHelper.sendMsg2TestService(
                    getApplication(),
                    TestServiceMessage(
                        key = AppConfig.MSG_MEASURE_CONFIG_START,
                        serverGuids = targets.map { it.guid },
                        onlyTcp = kind == TesterKind.TCP,
                        concurrency = state.concurrency,
                    ),
                    id,
                )
            }
        }
    }

    fun cancelTest() {
        val id = requestId ?: return
        MessageHelper.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL),
            id,
        )
        finishTest()
    }

    private fun applyResult(result: RealPingResult) {
        val kind = _uiState.value.runningKind ?: TesterKind.TUNNEL
        // L'écriture est immédiate : un résultat affiché est déjà persisté.
        TesterMeasurementStore.record(result.guid, kind, result.delayMillis)
        configsRows = configsRows.map { row ->
            if (row.guid == result.guid) row.updated() else row
        }
        freeRows = freeRows.map { row ->
            if (row.guid == result.guid) row.updated() else row
        }
        publish()
    }

    private fun finishTest() {
        requestId = null
        configsRows = configsRows.map { it.copy(running = false) }
        freeRows = freeRows.map { it.copy(running = false) }
        _uiState.value = _uiState.value.copy(
            testing = false,
            runningKind = null,
            runningScope = null,
            progress = null,
        )
        publish()
    }

    private fun TesterRow.updated(): TesterRow {
        val measurement = TesterMeasurementStore.read(guid)
        val health = ServerHealthStore.snapshot(guid)
        return copy(
            measurement = measurement,
            lossPercent = if (health.samples > 0) health.lossPercent else null,
            running = false,
        )
    }

    private fun publish() {
        val kind = _uiState.value.kind
        _uiState.value = _uiState.value.copy(
            configs = sortRows(configsRows, kind),
            free = sortRows(freeRows, kind),
        )
    }

    /** Rapidité d'abord, puis stabilité mesurée ; non mesurés en dernier. */
    private fun sortRows(rows: List<TesterRow>, kind: TesterKind): List<TesterRow> =
        rows.sortedWith(
            compareBy(
                { it.rank(kind) },
                { it.lossPercent ?: Int.MAX_VALUE },
                { it.name.lowercase() },
            ),
        )

    private fun loadRows(): List<TesterRow> {
        val publicPrefix = "dams-public-"
        return MmkvManager.decodeAllServerList().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (profile.configType.isGroupType()) return@mapNotNull null
            if (profile.server.isNullOrBlank() || profile.serverPort.isNullOrBlank()) {
                return@mapNotNull null
            }
            val scope = if (profile.subscriptionId.startsWith(publicPrefix)) {
                TesterScope.FREE
            } else {
                TesterScope.CONFIGS
            }
            val health = ServerHealthStore.snapshot(guid)
            TesterRow(
                guid = guid,
                name = profile.remarks.ifBlank { profile.server.orEmpty() },
                address = "${profile.server}:${profile.serverPort}",
                scope = scope,
                measurement = TesterMeasurementStore.read(guid),
                lossPercent = if (health.samples > 0) health.lossPercent else null,
            )
        }.distinctBy { it.guid }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        super.onCleared()
    }
}
