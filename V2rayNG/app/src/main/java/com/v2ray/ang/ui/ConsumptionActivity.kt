package com.v2ray.ang.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class ConsumptionActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val traffic by CoreServiceManager.trafficSnapshot.collectAsStateWithLifecycle()
        ConsumptionScreen(
            traffic = traffic,
            ping = MmkvManager.getSelectServer()?.let(MmkvManager::decodeServerAffiliationInfo)?.testDelayMillis,
            onBack = ::finish,
            onTest = { startActivity(Intent(this, com.v2ray.ang.ui.main.MainActivity::class.java).apply { putExtra(com.v2ray.ang.ui.main.MainActivity.EXTRA_RUN_SPEED_TEST, true) }) },
        )
    }
}

@Composable
private fun ConsumptionScreen(
    traffic: com.v2ray.ang.dto.TrafficSnapshot,
    ping: Long?,
    onBack: () -> Unit,
    onTest: () -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { AppTopBar(title = stringResource(R.string.consumption_title), onBackClick = onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.consumption_description), style = MaterialTheme.typography.bodyMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.consumption_live_title), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Metric(stringResource(R.string.dams_metric_latency), ping?.takeIf { it >= 0 }?.let { "$it ms" } ?: "—")
                        Metric(stringResource(R.string.dams_metric_download), if (traffic.available) traffic.downlinkBytesPerSecond.toSpeedString() else "—")
                        Metric(stringResource(R.string.dams_metric_upload), if (traffic.available) traffic.uplinkBytesPerSecond.toSpeedString() else "—")
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.consumption_today), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.dams_mobile_daily_usage_value, traffic.mobileDailyDownlinkBytes.toTrafficString(), traffic.mobileDailyUplinkBytes.toTrafficString()))
                    Text(stringResource(R.string.dams_tunnel_traffic_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onTest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.consumption_test_button)) }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, style = MaterialTheme.typography.titleSmall) }
}
