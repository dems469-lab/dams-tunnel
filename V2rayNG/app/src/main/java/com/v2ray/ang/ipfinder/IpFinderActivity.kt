package com.v2ray.ang.ipfinder

import android.app.role.RoleManager
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.ui.base.BaseComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IpFinderActivity : BaseComponentActivity() {
    private var assistantActive by mutableStateOf(false)
    private var damsVpnActive by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    refreshState()
                    delay(750)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun refreshState() {
        assistantActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            Settings.Secure.getString(contentResolver, "assistant")
                ?.let { it.startsWith("$packageName/") } == true
        }
        val runningServices = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
        damsVpnActive = CoreServiceManager.isRunning() || runningServices.any {
            it.service.className == CoreVpnService::class.java.name
        }
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ip_finder_assistant_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    override fun ScreenContent() {
        val roleRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshState()
        }
        IpFinderSetupScreen(
            assistantActive = assistantActive,
            vpnActive = damsVpnActive,
            onBack = ::finish,
            onEnableAssistant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = getSystemService(RoleManager::class.java)
                    if (roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true) {
                        roleRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                    } else openAssistantSettings()
                } else openAssistantSettings()
            },
            onAssistantSettings = ::openAssistantSettings
        )
    }
}

@Composable
private fun IpFinderSetupScreen(
    assistantActive: Boolean,
    vpnActive: Boolean,
    onBack: () -> Unit,
    onEnableAssistant: () -> Unit,
    onAssistantSettings: () -> Unit
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.ip_finder_back)) }
            Text(stringResource(R.string.ip_finder_test_entry), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ip_finder_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (assistantActive) R.string.ip_finder_assistant_active else R.string.ip_finder_assistant_inactive),
                style = MaterialTheme.typography.titleMedium
            )
            Button(onClick = onEnableAssistant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_enable_assistant))
            }
            OutlinedButton(onClick = onAssistantSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_restore_assistant))
            }
            Text(
                stringResource(if (vpnActive) R.string.ip_finder_vpn_active else R.string.ip_finder_vpn_inactive),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_start_cycle))
            }
            Text(stringResource(R.string.ip_finder_cycle_pending), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
}
