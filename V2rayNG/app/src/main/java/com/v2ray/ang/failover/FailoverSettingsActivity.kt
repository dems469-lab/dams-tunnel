package com.v2ray.ang.failover

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.FailoverEvent
import com.v2ray.ang.ui.base.BaseComponentActivity
import java.util.Date

/**
 * Réglages de la bascule automatique : activation, serveur actif, meilleur
 * serveur mesuré, dernière mesure et historique des bascules réellement
 * appliquées (ou annulées).
 */
class FailoverSettingsActivity : BaseComponentActivity() {

    private val viewModel: FailoverSettingsViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val none = stringResource(R.string.failover_none)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.failover_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_24dp),
                                contentDescription = stringResource(R.string.tester_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    EnabledCard(
                        enabled = state.enabled,
                        onEnabled = viewModel::setEnabled,
                    )
                }
                item {
                    InfoCard(
                        title = stringResource(R.string.failover_active_server),
                        name = state.activeName.ifBlank { none },
                        address = state.activeAddress,
                        score = state.activeScore,
                        latency = state.activeLatencyMillis,
                        loss = state.activeLossPercent,
                    )
                }
                item {
                    InfoCard(
                        title = stringResource(R.string.failover_best_server),
                        name = state.bestName.ifBlank { none },
                        address = "",
                        score = state.bestScore,
                        latency = state.bestLatencyMillis,
                        loss = null,
                    )
                }
                item {
                    DetailCard(
                        lastVerificationAt = state.lastVerificationAt,
                        lastSwitchAt = state.lastSwitchAt,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.failover_history),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.history.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.failover_history_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.history) { event ->
                        HistoryRow(event)
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.failover_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnabledCard(enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.failover_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.failover_switch_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    name: String,
    address: String,
    score: Int?,
    latency: Long?,
    loss: Int?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (address.isNotBlank()) {
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (score == null) {
                Text(
                    text = stringResource(R.string.failover_no_measure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                val scoreText = stringResource(R.string.failover_score, score)
                val latencyText = if (latency != null && latency >= 0L) {
                    stringResource(R.string.failover_latency, latency)
                } else {
                    null
                }
                val lossText = if (loss != null) stringResource(R.string.failover_loss, loss) else null
                Text(
                    text = listOfNotNull(scoreText, latencyText, lossText).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailCard(lastVerificationAt: Long, lastSwitchAt: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    R.string.failover_last_verification,
                    formatTime(lastVerificationAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (lastSwitchAt > 0L) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.failover_last_switch, formatTime(lastSwitchAt)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HistoryRow(event: FailoverEvent) {
    val reason = when (event.reason) {
        "active_degraded" -> stringResource(R.string.failover_reason_degraded)
        "better_score" -> stringResource(R.string.failover_reason_better)
        else -> event.reason
    }
    val result = when (event.result) {
        "success" -> stringResource(R.string.failover_result_success)
        "rollback" -> stringResource(R.string.failover_result_rollback)
        else -> event.result
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${event.fromName} → ${event.toName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$reason · $result · ${formatTime(event.atMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun formatTime(atMillis: Long): String {
    if (atMillis <= 0L) return stringResource(R.string.failover_never)
    return DateFormat.getDateTimeFormat(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        LocalContext.current,
    ).format(Date(atMillis))
}
