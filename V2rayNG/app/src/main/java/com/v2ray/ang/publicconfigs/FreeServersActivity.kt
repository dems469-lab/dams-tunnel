package com.v2ray.ang.publicconfigs

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.main.ConfigCompletenessStatus
import com.v2ray.ang.ui.main.RankedConfig
import com.v2ray.ang.ui.main.assessConfigCompleteness
import com.v2ray.ang.ui.main.rankConfigs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FreeProvider(
    val id: String,
    val name: String,
    val servers: List<FreeServer>,
)

private data class FreeServer(
    val guid: String,
    val profile: ProfileItem,
    val delayMillis: Long,
)

private enum class FreeServerSort { COMPLETENESS, LATENCY }

internal fun hasMeasuredFreeServerLatency(delayMillis: Long): Boolean = delayMillis > 0L

private suspend fun loadFreeProviders(sort: FreeServerSort): List<FreeProvider> = withContext(Dispatchers.IO) {
    val publicSubscriptions = MmkvManager.decodeSubscriptions().filter { it.guid.startsWith("dams-public-") }
    publicSubscriptions.mapNotNull { subscription ->
        val servers = MmkvManager.decodeServerList(subscription.guid).mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            FreeServer(
                guid = guid,
                profile = profile,
                delayMillis = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: -1L,
            )
        }
        val serversByGuid = servers.associateBy(FreeServer::guid)
        val rankedByCompleteness = rankConfigs(servers.map {
            RankedConfig(it.guid, it.profile, assessConfigCompleteness(it.profile), it.delayMillis)
        })
        val ranked = when (sort) {
            FreeServerSort.COMPLETENESS -> rankedByCompleteness
            FreeServerSort.LATENCY -> rankedByCompleteness.sortedWith(
                compareBy<RankedConfig> { it.delayMillis < 0L }
                    .thenBy { if (it.delayMillis < 0L) Long.MAX_VALUE else it.delayMillis }
            )
        }.mapNotNull { serversByGuid[it.guid] }
        FreeProvider(subscription.guid, subscription.subscription.remarks.ifBlank { subscription.guid }, ranked)
            .takeUnless { it.servers.isEmpty() }
    }
}

class FreeServersActivity : BaseComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        FreeServersScreen(
            onBack = ::finish,
            onSelect = { guid ->
                MmkvManager.setSelectServer(guid)
                SettingsChangeManager.makeSetupGroupTab()
                toastSuccess(R.string.free_servers_selected)
            },
            onDeleteProvider = ::deleteProvider,
            onRefresh = ::refreshPublicServers,
        )
    }

    private fun deleteProvider(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            MmkvManager.removeSubscription(id)
            SettingsChangeManager.makeSetupGroupTab()
            runOnUiThread { toastSuccess(R.string.free_servers_delete) }
        }
    }

    private suspend fun refreshPublicServers() {
        val subscriptions = withContext(Dispatchers.IO) {
            MmkvManager.decodeSubscriptions().filter { it.guid.startsWith("dams-public-") }
        }
        if (subscriptions.isEmpty()) {
            toastError(R.string.free_servers_empty)
            return
        }
        val count = withContext(Dispatchers.IO) {
            subscriptions.sumOf { cache -> AngConfigManager.updateConfigViaSub(cache).configCount }
        }
        SettingsChangeManager.makeSetupGroupTab()
        toastSuccess(getString(R.string.free_servers_refresh_success, count))
    }
}

@Composable
private fun FreeServersScreen(
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onRefresh: suspend () -> Unit,
) {
    var providers by remember { mutableStateOf(emptyList<FreeProvider>()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(FreeServerSort.COMPLETENESS) }
    var deleteTarget by remember { mutableStateOf<FreeProvider?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        loading = true
        try {
            providers = loadFreeProviders(sort)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.free_servers_title), onBackClick = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.free_servers_description), style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !refreshing,
                onClick = {
                    scope.launch {
                        refreshing = true
                        try {
                            onRefresh()
                            load()
                        } finally {
                            refreshing = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (refreshing) R.string.free_servers_loading else R.string.free_servers_refresh))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { sort = FreeServerSort.COMPLETENESS; scope.launch { load() } }) {
                    Text(stringResource(R.string.free_servers_sort_completeness))
                }
                TextButton(onClick = { sort = FreeServerSort.LATENCY; scope.launch { load() } }) {
                    Text(stringResource(R.string.free_servers_sort_latency))
                }
            }
            if (loading) {
                Text(stringResource(R.string.free_servers_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (providers.isEmpty()) {
                Text(stringResource(R.string.free_servers_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    providers.forEach { provider ->
                        item(key = "provider-${provider.id}") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { deleteTarget = provider },
                                    )
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${provider.servers.size} · ${provider.servers.groupingBy { it.profile.configType.protocolScheme.uppercase() }.eachCount().entries.joinToString(" · ") { "${it.key}: ${it.value}" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(provider.servers, key = { it.guid }) { server ->
                            val status = when (assessConfigCompleteness(server.profile).status) {
                                ConfigCompletenessStatus.COMPLETE -> stringResource(R.string.config_status_complete)
                                ConfigCompletenessStatus.PARTIAL -> stringResource(R.string.config_status_partial)
                                ConfigCompletenessStatus.INVALID -> stringResource(R.string.config_status_invalid)
                                ConfigCompletenessStatus.UNVERIFIED -> stringResource(R.string.config_status_unverified)
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelect(server.guid) },
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f)) {
                                        Text(server.profile.remarks, style = MaterialTheme.typography.bodyLarge)
                                        Text("${server.profile.configType.protocolScheme.uppercase()} · $status", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(
                                        if (hasMeasuredFreeServerLatency(server.delayMillis)) "${server.delayMillis} ms" else "—",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.free_servers_remove_provider)) },
            text = { Text(stringResource(R.string.free_servers_remove_provider_message, provider.name)) },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteProvider(provider.id) }) {
                    Text(stringResource(R.string.free_servers_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.free_servers_cancel)) }
            },
        )
    }
}
