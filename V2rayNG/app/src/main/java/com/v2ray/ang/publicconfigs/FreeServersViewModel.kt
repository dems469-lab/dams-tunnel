package com.v2ray.ang.publicconfigs

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class FreeServersUiState(
    val catalog: PublicServerCatalog = PublicServerCatalog(emptyList(), emptyMap(), 0),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val filters: PublicServerFilters = PublicServerFilters(),
    val selectedGuids: Set<String> = emptySet(),
    val testing: Boolean = false,
    val testStatus: String? = null,
    val error: String? = null,
)

internal class FreeServersViewModel(application: Application) : BaseViewModel(application) {
    private val _uiState = MutableStateFlow(FreeServersUiState())
    val uiState: StateFlow<FreeServersUiState> = _uiState.asStateFlow()
    private var requestId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            if (safeIntent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID) != requestId) return
            when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    safeIntent.serializable<RealPingResult>("content")
                    load()
                }
                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> _uiState.value = _uiState.value.copy(
                    testStatus = safeIntent.getStringExtra("content").orEmpty()
                )
                AppConfig.MSG_MEASURE_CONFIG_FINISH,
                AppConfig.MSG_MEASURE_CONFIG_CANCEL -> {
                    _uiState.value = _uiState.value.copy(testing = false)
                    requestId = null
                    load()
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            receiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags(),
        )
        load()
    }

    fun updateFilters(filters: PublicServerFilters) {
        _uiState.value = _uiState.value.copy(filters = filters)
        load()
    }

    fun toggleSelection(guid: String) {
        val current = _uiState.value.selectedGuids
        _uiState.value = _uiState.value.copy(
            selectedGuids = if (guid in current) current - guid else current + guid
        )
    }

    fun clearSelection() = _uiState.value.let { _uiState.value = it.copy(selectedGuids = emptySet()) }

    fun testSelected() {
        val guids = _uiState.value.selectedGuids.toList()
        if (guids.isEmpty() || _uiState.value.testing) return
        val id = UUID.randomUUID().toString()
        requestId = id
        _uiState.value = _uiState.value.copy(testing = true, testStatus = null)
        viewModelScope.launch(Dispatchers.IO) {
            MmkvManager.clearAllTestDelayResults(guids)
            MessageHelper.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_START, serverGuids = guids),
                id,
            )
        }
    }

    fun cancelTest() {
        val id = requestId ?: return
        MessageHelper.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL),
            id,
        )
    }

    suspend fun refreshSources(): Int = withContext(Dispatchers.IO) {
        _uiState.value = _uiState.value.copy(refreshing = true)
        try {
            val subscriptions = MmkvManager.decodeSubscriptions().filter { it.guid.startsWith("dams-public-") }
            val count = subscriptions.sumOf { AngConfigManager.updateConfigViaSub(it).configCount }
            SettingsChangeManager.makeSetupGroupTab()
            load()
            count
        } finally {
            _uiState.value = _uiState.value.copy(refreshing = false)
        }
    }

    fun load() {
        val filters = _uiState.value.filters
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                val inputs = withContext(Dispatchers.IO) {
                    MmkvManager.decodeSubscriptions()
                        .filter { it.guid.startsWith("dams-public-") }
                        .flatMap { subscription ->
                            MmkvManager.decodeServerList(subscription.guid).mapNotNull { guid ->
                                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                                PublicServerInput(
                                    guid = guid,
                                    profile = profile,
                                    sourceName = subscription.subscription.remarks.ifBlank { subscription.guid },
                                    sourceUpdatedAt = subscription.subscription.lastUpdated,
                                    delayMillis = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L,
                                    sourceId = subscription.guid,
                                )
                            }
                        }
                }
                withContext(Dispatchers.Default) { buildPublicServerCatalog(inputs, filters) }
            }.onSuccess { catalog ->
                _uiState.value = _uiState.value.copy(catalog = catalog, loading = false)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(loading = false, error = error.message)
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
        super.onCleared()
    }
}
