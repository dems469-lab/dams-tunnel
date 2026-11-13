package com.v2ray.ang.failover

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.AutoFailoverPolicy
import com.v2ray.ang.core.FailoverEvent
import com.v2ray.ang.core.ServerHealthSnapshot
import com.v2ray.ang.core.ServerHealthStore
import com.v2ray.ang.extension.isGroupType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tout ce que l'écran affiche vient de mesures réelles ou de l'état persisté. */
internal data class FailoverSettingsState(
    val enabled: Boolean = true,
    val activeName: String = "",
    val activeAddress: String = "",
    val activeScore: Int? = null,
    val activeLatencyMillis: Long? = null,
    val activeLossPercent: Int? = null,
    val bestName: String = "",
    val bestScore: Int? = null,
    val bestLatencyMillis: Long? = null,
    val lastVerificationAt: Long = 0L,
    val lastSwitchAt: Long = 0L,
    val history: List<FailoverEvent> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Réglages et état de la bascule automatique classique.
 *
 * L'écran ne déclenche aucune bascule : il affiche la décision possible, les
 * mesures qui la fondent et l'historique de ce qui a réellement été appliqué.
 */
internal class FailoverSettingsViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(FailoverSettingsState())
    val uiState: StateFlow<FailoverSettingsState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER, enabled)
        _uiState.value = _uiState.value.copy(enabled = enabled)
    }

    fun refresh() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) { load() }
            _uiState.value = state
        }
    }

    private fun load(): FailoverSettingsState {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        val activeProfile = selectedGuid.takeIf { it.isNotBlank() }
            ?.let { MmkvManager.decodeServerConfig(it) }
        val activeHealth = selectedGuid.takeIf { it.isNotBlank() }
            ?.let { ServerHealthStore.snapshot(it) }

        val candidates = configsCandidates()
        val best = candidates.maxByOrNull { it.score }
        val lastVerification = (candidates + listOfNotNull(activeHealth))
            .maxOfOrNull { it.lastTestedAtMillis } ?: 0L

        return FailoverSettingsState(
            enabled = MmkvManager.decodeSettingsBool(
                AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER,
                true,
            ),
            activeName = activeProfile?.remarks.orEmpty(),
            activeAddress = activeProfile?.let { "${it.server}:${it.serverPort}" }.orEmpty(),
            activeScore = activeHealth?.takeIf { it.samples > 0 }?.score,
            activeLatencyMillis = activeHealth?.takeIf { it.samples > 0 }?.latencyMillis,
            activeLossPercent = activeHealth?.takeIf { it.samples > 0 }?.lossPercent,
            bestName = best?.guid?.let { MmkvManager.decodeServerConfig(it)?.remarks }.orEmpty(),
            bestScore = best?.score,
            bestLatencyMillis = best?.latencyMillis,
            lastVerificationAt = lastVerification,
            lastSwitchAt = MmkvManager.getLastFailoverAt(),
            history = ServerHealthStore.history(),
            loading = false,
        )
    }

    /** Seuls les serveurs de Configs, réellement mesurés et frais, sont classés. */
    private fun configsCandidates(): List<ServerHealthSnapshot> {
        val now = System.currentTimeMillis()
        return MmkvManager.decodeAllServerList().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (profile.configType.isGroupType()) return@mapNotNull null
            if (profile.subscriptionId.startsWith(PUBLIC_GROUP_PREFIX)) return@mapNotNull null
            val snapshot = ServerHealthStore.snapshot(guid)
            if (snapshot.samples <= 0) return@mapNotNull null
            if (!AutoFailoverPolicy.isFresh(snapshot, now)) return@mapNotNull null
            if (snapshot.availabilityPercent < MIN_USABLE_AVAILABILITY) return@mapNotNull null
            snapshot
        }
    }

    private companion object {
        const val PUBLIC_GROUP_PREFIX = "dams-public-"
        const val MIN_USABLE_AVAILABILITY = 50
    }
}
