package com.v2ray.ang.publicconfigs

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun hasMeasuredFreeServerLatency(delayMillis: Long): Boolean = delayMillis > 0L

class FreeServersActivity : BaseComponentActivity() {
    private val viewModel: FreeServersViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        FreeServersScreen(
            state = state,
            onBack = ::finish,
            onFiltersChanged = viewModel::updateFilters,
            onToggleSelection = viewModel::toggleSelection,
            onTest = viewModel::testSelected,
            onTestServer = viewModel::testServer,
            onClearSelection = viewModel::clearSelection,
            onCancelTest = viewModel::cancelTest,
            onRefresh = {
                lifecycleScope.launch {
                    try {
                        val count = viewModel.refreshSources()
                        toastSuccess(getString(R.string.free_servers_refresh_success, count))
                    } catch (_: Exception) {
                        toastError(R.string.free_servers_refresh_error)
                    }
                }
            },
            onSelect = { guid ->
                MmkvManager.setSelectServer(guid)
                SettingsChangeManager.makeSetupGroupTab()
                toastSuccess(R.string.free_servers_selected)
            },
            onDeleteProvider = ::deleteProvider,
        )
    }

    private fun deleteProvider(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            MmkvManager.removeSubscription(id)
            SettingsChangeManager.makeSetupGroupTab()
            runOnUiThread { toastSuccess(R.string.free_servers_delete) }
            viewModel.load()
        }
    }
}

@Composable
private fun FreeServersScreen(
    state: FreeServersUiState,
    onBack: () -> Unit,
    onFiltersChanged: (PublicServerFilters) -> Unit,
    onToggleSelection: (String) -> Unit,
    onTest: () -> Unit,
    onTestServer: (String) -> Unit,
    onClearSelection: () -> Unit,
    onCancelTest: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDeleteProvider: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<PublicServerGroup?>(null) }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val filters = state.filters
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.free_servers_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.free_servers_description), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = filters.query,
                        onValueChange = { onFiltersChanged(filters.copy(query = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.free_servers_search)) },
                    )
                    Text(stringResource(R.string.free_servers_protocols), style = MaterialTheme.typography.labelLarge)
                    FilterRow(
                        labels = listOf(null to stringResource(R.string.free_servers_all)) + PublicProtocol.entries.map {
                            it to stringResource(R.string.free_servers_protocol_count, protocolLabel(it), state.catalog.protocolCounts[it] ?: 0)
                        },
                        selected = filters.protocol,
                        onSelected = { onFiltersChanged(filters.copy(protocol = it)) },
                    )
                    FilterRow(
                        labels = listOf(null to stringResource(R.string.free_servers_filter_all), PublicCompleteness.COMPLETE to stringResource(R.string.free_servers_filter_complete), PublicCompleteness.PARTIAL to stringResource(R.string.free_servers_filter_partial), PublicCompleteness.INVALID to stringResource(R.string.free_servers_filter_invalid), PublicCompleteness.UNVERIFIED to stringResource(R.string.config_status_unverified)),
                        selected = filters.completeness,
                        onSelected = { onFiltersChanged(filters.copy(completeness = it)) },
                    )
                    FilterRow(
                        labels = listOf(null to stringResource(R.string.free_servers_filter_all), PublicAvailability.RESPONDED to stringResource(R.string.free_servers_filter_responded), PublicAvailability.NO_RESPONSE to stringResource(R.string.free_servers_filter_no_response), PublicAvailability.UNKNOWN to stringResource(R.string.free_servers_filter_unknown)),
                        selected = filters.availability,
                        onSelected = { onFiltersChanged(filters.copy(availability = it)) },
                    )
                    FilterRow(
                        labels = listOf(null to stringResource(R.string.free_servers_filter_all), PublicVerification.TESTED to stringResource(R.string.free_servers_filter_tested), PublicVerification.UNTESTED to stringResource(R.string.free_servers_filter_untested)),
                        selected = filters.verification,
                        onSelected = { onFiltersChanged(filters.copy(verification = it)) },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PublicSort.COMPLETENESS, PublicSort.LATENCY, PublicSort.SOURCE_UPDATED).forEach { sort ->
                            FilterChip(selected = filters.sort == sort, onClick = { onFiltersChanged(filters.copy(sort = sort)) }, label = {
                                Text(stringResource(when (sort) {
                                    PublicSort.COMPLETENESS -> R.string.free_servers_sort_completeness
                                    PublicSort.LATENCY -> R.string.free_servers_sort_latency
                                    PublicSort.SOURCE_UPDATED -> R.string.free_servers_sort_updated
                                }))
                            })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRefresh, enabled = !state.refreshing) { Text(stringResource(if (state.refreshing) R.string.free_servers_loading else R.string.free_servers_refresh)) }
                        Button(onClick = if (state.testing) onCancelTest else onTest, enabled = state.testing || state.selectedGuids.isNotEmpty()) {
                            Text(if (state.testing) stringResource(R.string.free_servers_cancel_test) else stringResource(R.string.free_servers_test_selected, state.selectedGuids.size))
                        }
                    }
                    Text(stringResource(R.string.free_servers_count, state.catalog.totalProfiles, state.catalog.groups.size), style = MaterialTheme.typography.labelMedium)
                    if (state.selectedGuids.isNotEmpty() && !state.testing) {
                        TextButton(onClick = onClearSelection) { Text(stringResource(R.string.free_servers_clear_selection, state.selectedGuids.size)) }
                    }
                    state.testStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (state.loading) Text(stringResource(R.string.free_servers_loading))
                    else if (state.error) Text(stringResource(R.string.free_servers_load_error), color = MaterialTheme.colorScheme.error)
                    else if (state.catalog.groups.isEmpty()) Text(stringResource(R.string.free_servers_empty))
                }
            }
            if (!state.loading && !state.error) {
                items(state.catalog.groups, key = { it.id }) { group ->
                    PublicServerGroupCard(group, state.selectedGuids, group.id in expanded, { expanded = if (group.id in expanded) expanded - group.id else expanded + group.id }, onToggleSelection, onTestServer, onSelect, { deleteTarget = group })
                }
            }
        }
    }
    deleteTarget?.let { group ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text(stringResource(R.string.free_servers_remove_provider)) }, text = { Text(stringResource(R.string.free_servers_remove_provider_message, group.variants.first().sourceName)) }, confirmButton = { TextButton(onClick = { deleteTarget = null; onDeleteProvider(group.variants.first().sourceId) }) { Text(stringResource(R.string.free_servers_delete)) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.free_servers_cancel)) } })
    }
}

@Composable
private fun <T> FilterRow(labels: List<Pair<T?, String>>, selected: T?, onSelected: (T?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEach { (value, label) -> FilterChip(selected = selected == value, onClick = { onSelected(value) }, label = { Text(label) }) } }
}

@Composable
private fun PublicServerGroupCard(group: PublicServerGroup, selectedGuids: Set<String>, isExpanded: Boolean, onExpand: () -> Unit, onToggleSelection: (String) -> Unit, onTestServer: (String) -> Unit, onSelect: (String) -> Unit, onDelete: () -> Unit) {
    val first = group.variants.first()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(first.name.ifBlank { first.details.address }, style = MaterialTheme.typography.titleMedium)
            Text(
                "${protocolLabel(first.protocol)} · ${first.sourceName}",
                modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onDelete),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when (first.assessment.completeness) {
                    PublicCompleteness.COMPLETE -> stringResource(R.string.free_servers_filter_complete)
                    PublicCompleteness.PARTIAL -> stringResource(R.string.free_servers_filter_partial)
                    PublicCompleteness.INVALID -> stringResource(R.string.free_servers_filter_invalid)
                    PublicCompleteness.UNVERIFIED -> stringResource(R.string.config_status_unverified)
                },
                color = MaterialTheme.colorScheme.primary,
            )
            Text(when (group.availability) { PublicAvailability.RESPONDED -> stringResource(R.string.free_servers_responded); PublicAvailability.NO_RESPONSE -> stringResource(R.string.free_servers_no_response); PublicAvailability.UNKNOWN -> stringResource(R.string.free_servers_not_tested) }, style = MaterialTheme.typography.bodySmall)
            Text(first.delayMillis?.let { "$it ms" } ?: stringResource(R.string.free_servers_not_tested), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExpand) { Text(stringResource(if (isExpanded) R.string.free_servers_collapse else R.string.free_servers_details)) }
                TextButton(onClick = { onToggleSelection(first.guid) }) { Text(stringResource(if (first.guid in selectedGuids) R.string.free_servers_selected else R.string.free_servers_select)) }
                TextButton(onClick = { onSelect(first.guid) }) { Text(stringResource(R.string.free_servers_choose)) }
                TextButton(onClick = { onTestServer(first.guid) }) { Text(stringResource(R.string.free_servers_test)) }
            }
            if (group.variants.size > 1) Text(stringResource(R.string.free_servers_group_variants, group.variants.size))
            if (isExpanded) {
                group.variants.forEach { variant ->
                    Text("${variant.sourceName} · ${variant.name}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (variant.sourceUpdatedAt > 0L) stringResource(
                            R.string.free_servers_updated_at,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(variant.sourceUpdatedAt)),
                        ) else stringResource(R.string.free_servers_updated_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Details(variant.details)
                    if (variant.guid != first.guid) {
                        Row {
                            TextButton(onClick = { onToggleSelection(variant.guid) }) { Text(stringResource(R.string.free_servers_select)) }
                            TextButton(onClick = { onTestServer(variant.guid) }) { Text(stringResource(R.string.free_servers_test)) }
                            TextButton(onClick = { onSelect(variant.guid) }) { Text(stringResource(R.string.free_servers_choose)) }
                        }
                    }
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.free_servers_delete)) }
            }
        }
    }
}

@Composable
private fun Details(details: PublicServerDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.free_servers_field_address) + ": " + details.address)
        Text(stringResource(R.string.free_servers_field_port) + ": " + details.port)
        Text(stringResource(R.string.free_servers_field_transport) + ": " + details.transport)
        if (details.security.isNotBlank()) Text(stringResource(R.string.free_servers_field_security) + ": " + details.security)
        if (details.path.isNotBlank()) Text(stringResource(R.string.free_servers_field_path) + ": " + details.path)
        if (details.serverName.isNotBlank()) Text(stringResource(R.string.free_servers_field_sni) + ": " + details.serverName)
        if (details.headerHost.isNotBlank()) Text(stringResource(R.string.free_servers_field_host) + ": " + details.headerHost)
        if (details.serviceName.isNotBlank()) Text(stringResource(R.string.free_servers_field_service) + ": " + details.serviceName)
        if (details.authenticationPresent) Text(stringResource(R.string.free_servers_field_secret))
        if (details.publicKeyPresent) Text(stringResource(R.string.free_servers_field_public_key))
    }
}

private fun protocolLabel(protocol: PublicProtocol): String = when (protocol) {
    PublicProtocol.VLESS -> "VLESS"
    PublicProtocol.VMESS -> "VMess"
    PublicProtocol.TROJAN -> "Trojan"
    PublicProtocol.SHADOWSOCKS -> "Shadowsocks"
    PublicProtocol.SOCKS -> "SOCKS"
    PublicProtocol.HTTP -> "HTTP"
    PublicProtocol.HYSTERIA -> "Hysteria"
    PublicProtocol.HYSTERIA2 -> "Hysteria2"
    PublicProtocol.WIREGUARD -> "WireGuard"
    PublicProtocol.CUSTOM -> "Custom"
}
