package com.v2ray.ang.publicconfigs

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
}

@Composable
private fun PublicConfigsScreen(
    onBack: () -> Unit,
    onImport: (PublicConfigSource) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sources = remember(query) {
        PublicConfigSources.all.filter { source ->
            query.isBlank() || source.name.contains(query, true) || source.protocols.any { it.contains(query, true) }
        }
    }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.public_configs_title), onBackClick = onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.public_configs_disclaimer), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.public_configs_search)) },
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
