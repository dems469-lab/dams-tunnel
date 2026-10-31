package com.v2ray.ang.ui.sharing

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class ConnectionSharingActivity : BaseComponentActivity() {
    private val viewModel: ConnectionSharingViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        ConnectionSharingScreen(viewModel = viewModel, onBackClick = { finish() })
    }
}

class ConnectionSharingViewModel(application: android.app.Application) :
    androidx.lifecycle.AndroidViewModel(application) {
    private val _ips = MutableStateFlow<List<String>>(emptyList())
    val ips: StateFlow<List<String>> = _ips.asStateFlow()

    private val _sharing = MutableStateFlow(MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING))
    val sharing: StateFlow<Boolean> = _sharing.asStateFlow()

    val socksPort: Int get() = SettingsManager.getSocksPort()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _ips.value = collectLocalIps(getApplication())
        }
    }

    fun setSharing(enabled: Boolean) {
        _sharing.value = enabled
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, enabled)
    }

    private fun collectLocalIps(context: Context): List<String> {
        val result = linkedSetOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val lp: LinkProperties? = network?.let { cm.getLinkProperties(it) }
            lp?.linkAddresses?.forEach { addr ->
                val ip = addr.address
                if (ip is Inet4Address && !ip.isLoopbackAddress) result += ip.hostAddress.orEmpty()
            }
        } catch (_: Exception) {
        }
        try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { iface ->
                if (!iface.isUp || iface.isLoopback) return@forEach
                iface.inetAddresses.asSequence().forEach { addr ->
                    if (addr is Inet4Address && !addr.isLoopbackAddress) result += addr.hostAddress.orEmpty()
                }
            }
        } catch (_: Exception) {
        }
        return result.toList()
    }
}

@Composable
fun ConnectionSharingScreen(viewModel: ConnectionSharingViewModel, onBackClick: () -> Unit) {
    val context = LocalContext.current
    val ips by viewModel.ips.collectAsStateWithLifecycle()
    val sharing by viewModel.sharing.collectAsStateWithLifecycle()
    val port = viewModel.socksPort

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.title_connection_sharing), onBackClick = onBackClick) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.connection_sharing_toggle), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.connection_sharing_toggle_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = sharing, onCheckedChange = viewModel::setSharing)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.connection_sharing_restart),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(stringResource(R.string.connection_sharing_ip_label), style = MaterialTheme.typography.titleSmall)
            }
            if (ips.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.connection_sharing_no_ip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ips) { ip ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ip, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${stringResource(R.string.connection_sharing_port_label)}: $port",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                Utils.setClipboard(context, "$ip:$port")
                            }) {
                                Text(stringResource(R.string.connection_sharing_copy))
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.connection_sharing_note), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.connection_sharing_instructions), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}