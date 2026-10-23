package com.v2ray.ang.publicconfigs

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PublicConfigsActivity : BaseComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        PublicConfigsScreen(
            onBack = ::finish,
            onImport = ::importSource,
            onRefresh = ::refreshSources,
        )
    }

    private fun importSource(source: PublicConfigSource) {
        if (!PublicConfigSources.isAllowed(source)) {
            toastError(R.string.public_configs_invalid_source)
            return
        }
        val subscriptionId = "dams-public-${source.id}"
        val item = SubscriptionItem(
            remarks = "Public · ${source.name}",
            url = source.url,
            autoUpdate = false,
            updateInterval = AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            userAgent = "v2rayNG/${BuildConfig.VERSION_NAME}",
        )
        MmkvManager.encodeSubscription(subscriptionId, item)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, item))
            }
            if (result.configCount > 0) {
                SettingsChangeManager.makeSetupGroupTab()
                toastSuccess(getString(R.string.public_configs_import_success, result.configCount))
            } else {
                toastError(R.string.public_configs_import_failed)
            }
        }
    }

    private fun refreshSources() {
        lifecycleScope.launch {
            val subscriptions = MmkvManager.decodeSubscriptions().filter { it.guid.startsWith("dams-public-") }
            if (subscriptions.isEmpty()) {
                toastError(R.string.public_configs_none_imported)
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                subscriptions.fold(0) { count, cache ->
                    count + AngConfigManager.updateConfigViaSub(cache).configCount
                }
            }
            SettingsChangeManager.makeSetupGroupTab()
            toastSuccess(getString(R.string.public_configs_refresh_result, result))
        }
    }
}

@Composable
private fun PublicConfigsScreen(
    onBack: () -> Unit,
    onImport: (PublicConfigSource) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf<String?>(null) }
    val protocols = remember { PublicConfigSources.all.flatMap { it.protocols }.distinct().sorted() }
    val sources = remember(query, selectedProtocol) {
        filterPublicConfigSources(PublicConfigSources.all, query, selectedProtocol)
    }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.public_configs_title), onBackClick = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.public_configs_disclaimer), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.public_configs_refresh)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.public_configs_search)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedProtocol == null,
                    onClick = { selectedProtocol = null },
                    label = { Text(stringResource(R.string.public_configs_all_protocols)) },
                )
                protocols.forEach { protocol ->
                    FilterChip(
                        selected = selectedProtocol == protocol,
                        onClick = { selectedProtocol = protocol },
                        label = { Text(protocol) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.public_configs_source_count, sources.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sources, key = { it.id }) { source ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.name, style = MaterialTheme.typography.titleMedium)
                                Text(source.description, style = MaterialTheme.typography.bodySmall)
                                Text(source.protocols.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            }
                            Button(onClick = { onImport(source) }) { Text(stringResource(R.string.public_configs_import)) }
                        }
                    }
                }
            }
        }
    }
}
