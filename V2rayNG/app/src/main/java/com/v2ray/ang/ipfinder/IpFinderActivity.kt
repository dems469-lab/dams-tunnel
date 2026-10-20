package com.v2ray.ang.ipfinder

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.service.CoreVpnService
import com.v2ray.ang.ui.base.BaseComponentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class IpFinderActivity : BaseComponentActivity() {
    private var assistantActive by mutableStateOf(false)
    private var assistantServiceDeclared by mutableStateOf(false)
    private var assistantServiceResolved by mutableStateOf(false)
    private var currentAssistant by mutableStateOf("")
    private var damsVpnActive by mutableStateOf(false)
    private var currentCellularIp by mutableStateOf<String?>(null)
    private var wifiActive by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var attempt by mutableStateOf(0)
    private var resultText by mutableStateOf<String?>(null)
    private var activityResumed = false
    private var restorationJob: kotlinx.coroutines.Job? = null
    private val timeline = mutableStateListOf<Pair<Long, String>>()
    private var cycleJob: kotlinx.coroutines.Job? = null
    private var cycleStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IpFinderRecoveryStore.initialize(this)
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
        activityResumed = true
        refreshState()
        maybeRestorePending()
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    private fun refreshState() {
        assistantActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            Settings.Secure.getString(contentResolver, "assistant")?.startsWith("$packageName/") == true
        }
        val assistantComponent = ComponentName(this, DamsVoiceInteractionService::class.java)
        assistantServiceDeclared = runCatching {
            packageManager.getServiceInfo(assistantComponent, 0)
            true
        }.getOrDefault(false)
        assistantServiceResolved = runCatching {
            val resolved = packageManager.resolveService(
                Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.serviceInfo
            resolved?.name == assistantComponent.className && resolved.enabled
        }.getOrDefault(false)
        currentAssistant = getCurrentAssistantLabel()
        val runningServices = (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
        damsVpnActive = CoreServiceManager.isRunning() || runningServices.any {
            it.service.className == CoreVpnService::class.java.name
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val networks = connectivity.allNetworks
        wifiActive = networks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        currentCellularIp = AndroidIpFinderCyclePort(this) { IpFinderAirplaneRequest(false, false) }
            .currentCellularAddress()
    }

    private fun openAssistantSettings() {
        val voiceSettingsOpened = runCatching {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        }.isSuccess
        if (voiceSettingsOpened) return
        val defaultSettingsOpened = runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.isSuccess
        if (!defaultSettingsOpened) {
            Toast.makeText(this, R.string.ip_finder_assistant_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun getCurrentAssistantLabel(): String {
        val settingValue = Settings.Secure.getString(contentResolver, "voice_interaction_service")
            ?: Settings.Secure.getString(contentResolver, "assistant")
        val currentPackage = ComponentName.unflattenFromString(settingValue)?.packageName
            ?: settingValue?.substringBefore('/')?.takeIf { it.contains('.') }
        return currentPackage?.let { assistantPackage ->
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(assistantPackage, 0)
                ).toString()
            }.getOrDefault(assistantPackage)
        } ?: getString(R.string.ip_finder_no_current_assistant)
    }

    private suspend fun requestAirplaneMode(enabled: Boolean): IpFinderAirplaneRequest {
        if (!assistantActive) return IpFinderAirplaneRequest(false, false)
        val resumed = withTimeoutOrNull(FOREGROUND_RETURN_TIMEOUT_MILLIS) {
            while (!activityResumed) delay(100)
            true
        } ?: false
        if (!resumed) return IpFinderAirplaneRequest(false, false, foregroundTimeout = true)
        val requestId = UUID.randomUUID().toString()
        val preferences = getSharedPreferences(IpFinderVoiceContract.PREFS, MODE_PRIVATE)
        preferences.edit().remove("$requestId:dispatch").remove("$requestId:task_started").apply()
        val args = Bundle().apply {
            putBoolean(IpFinderVoiceContract.EXTRA_SET_AIRPLANE_MODE, true)
            putString(IpFinderVoiceContract.EXTRA_REQUEST_ID, requestId)
            putBoolean(IpFinderVoiceContract.EXTRA_AIRPLANE_ENABLED, enabled)
        }
        return try {
            startLocalVoiceInteraction(args)
            val dispatch = withTimeoutOrNull(8_000) {
                while (true) {
                    preferences.getString("$requestId:dispatch", null)?.let { return@withTimeoutOrNull it }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") "error"
            }
            if (dispatch != "ok") IpFinderAirplaneRequest(false, false)
            else {
                val activityStarted = withTimeoutOrNull(3_000) {
                    while (!preferences.getBoolean("$requestId:task_started", false)) delay(100)
                    true
                } ?: false
                IpFinderAirplaneRequest(true, activityStarted)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            IpFinderAirplaneRequest(false, false)
        } finally {
            preferences.edit().remove("$requestId:dispatch").remove("$requestId:task_started").apply()
        }
    }

    private fun startCycle() {
        if (running || !assistantActive || damsVpnActive) return
        attempt = 1
        resultText = null
        timeline.clear()
        cycleStart = android.os.SystemClock.elapsedRealtime()
        running = true
        cycleJob = lifecycleScope.launch {
            val coordinator = IpFinderCycleCoordinator(
                port = AndroidIpFinderCyclePort(this@IpFinderActivity, ::requestAirplaneMode),
                recoveryFlag = IpFinderRecoveryStore,
                onEvent = { event -> eventMessage(event)?.let { label ->
                    timeline.add((android.os.SystemClock.elapsedRealtime() - cycleStart) to label)
                } }
            )
            try {
                when (val result = coordinator.runSingleCycle()) {
                    is IpFinderCycleResult.Success -> resultText = getString(R.string.ip_finder_cycle_success, result.addressAfter)
                    is IpFinderCycleResult.Failure -> resultText = getString(failureText(result.reason))
                    IpFinderCycleResult.Cancelled -> resultText = getString(R.string.ip_finder_event_cancelled)
                }
            } catch (_: CancellationException) {
                resultText = getString(R.string.ip_finder_event_cancelled)
            } finally {
                running = false
                refreshState()
            }
        }
    }

    private fun eventMessage(event: IpFinderCycleEvent): String? = when (event) {
        IpFinderCycleEvent.WIFI_ACTIVE -> getString(R.string.ip_finder_event_wifi)
        IpFinderCycleEvent.INITIAL_ADDRESS -> getString(R.string.ip_finder_event_before,
            AndroidIpFinderCyclePort(this) { IpFinderAirplaneRequest(false, false) }.currentCellularAddress() ?: "—")
        IpFinderCycleEvent.AIRPLANE_ON_REQUESTED -> getString(R.string.ip_finder_event_request_on)
        IpFinderCycleEvent.SYSTEM_ACTIVITY_DETECTED -> getString(R.string.ip_finder_event_system_activity)
        IpFinderCycleEvent.SYSTEM_ACTIVITY_NOT_DETECTED -> getString(R.string.ip_finder_event_system_activity_missing)
        IpFinderCycleEvent.AIRPLANE_ON_CONFIRMED -> getString(R.string.ip_finder_event_airplane_on)
        IpFinderCycleEvent.CELLULAR_LOST -> getString(R.string.ip_finder_event_cellular_lost)
        IpFinderCycleEvent.AIRPLANE_OFF_REQUESTED -> getString(R.string.ip_finder_event_request_off)
        IpFinderCycleEvent.AIRPLANE_OFF_CONFIRMED -> getString(R.string.ip_finder_event_airplane_off)
        IpFinderCycleEvent.CELLULAR_RETURNED -> getString(R.string.ip_finder_event_cellular_back)
        IpFinderCycleEvent.FINAL_ADDRESS -> getString(R.string.ip_finder_event_after,
            AndroidIpFinderCyclePort(this) { IpFinderAirplaneRequest(false, false) }.currentCellularAddress() ?: "—")
        IpFinderCycleEvent.RESTORE_FAILED -> getString(R.string.ip_finder_event_restore_failed)
        IpFinderCycleEvent.FOREGROUND_RETURN_TIMEOUT -> getString(R.string.ip_finder_event_failure_foreground)
    }

    private fun failureText(reason: IpFinderCycleFailure): Int = when (reason) {
        IpFinderCycleFailure.NO_INITIAL_CELLULAR_ADDRESS -> R.string.ip_finder_event_failure_no_ip
        IpFinderCycleFailure.ASSISTANT_REQUEST_REJECTED -> R.string.ip_finder_event_failure_request
        IpFinderCycleFailure.AIRPLANE_ON_TIMEOUT -> R.string.ip_finder_event_failure_airplane_on
        IpFinderCycleFailure.CELLULAR_DID_NOT_DISCONNECT -> R.string.ip_finder_event_failure_cellular
        IpFinderCycleFailure.AIRPLANE_OFF_TIMEOUT -> R.string.ip_finder_event_failure_airplane_off
        IpFinderCycleFailure.CELLULAR_RETURN_TIMEOUT -> R.string.ip_finder_event_failure_cellular_back
        IpFinderCycleFailure.RECOVERY_STORAGE_FAILURE -> R.string.ip_finder_event_failure_recovery_storage
        IpFinderCycleFailure.FOREGROUND_RETURN_TIMEOUT -> R.string.ip_finder_event_failure_foreground
    }

    private suspend fun restoreAirplaneMode() {
        if (!IpFinderRecoveryStore.isPending()) return
        if (!IpFinderRecoveryStore.isAirplaneModeEnabled(this)) {
            IpFinderRecoveryStore.clear()
            resultText = getString(R.string.ip_finder_recovery_already_off)
            return
        }
        if (!assistantActive) {
            resultText = getString(R.string.ip_finder_recovery_role_needed)
            return
        }
        val accepted = requestAirplaneMode(false).accepted
        val restored = accepted && AndroidIpFinderCyclePort(this) { requestAirplaneMode(it) }
            .awaitAirplaneMode(false, IpFinderCycleCoordinator.DEFAULT_TIMEOUT_MILLIS)
        if (restored) {
            IpFinderRecoveryStore.clear()
            resultText = getString(R.string.ip_finder_recovery_done)
        }
    }

    private fun maybeRestorePending() {
        if (!intent.getBooleanExtra(EXTRA_RESTORE_PENDING, false) || restorationJob?.isActive == true) return
        if (IpFinderRecoveryStore.isPending()) {
            restorationJob = lifecycleScope.launch { restoreAirplaneMode() }
        }
    }

    @Composable
    override fun ScreenContent() {
        IpFinderSetupScreen(
            assistantActive = assistantActive,
            assistantServiceDeclared = assistantServiceDeclared,
            assistantServiceResolved = assistantServiceResolved,
            currentAssistant = currentAssistant,
            vpnActive = damsVpnActive,
            running = running,
            wifiActive = wifiActive,
            cellularIp = currentCellularIp,
            attempt = attempt,
            timeline = timeline.toList(),
            resultText = resultText,
            onBack = { if (running) cycleJob?.cancel() else finish() },
            onEnableAssistant = ::openAssistantSettings,
            onAssistantSettings = ::openAssistantSettings,
            onStart = ::startCycle,
            onStop = { if (running) cycleJob?.cancel() else finish() }
        )
    }

    companion object {
        const val EXTRA_RESTORE_PENDING = "com.v2ray.ang.ipfinder.RESTORE_PENDING"
        private const val FOREGROUND_RETURN_TIMEOUT_MILLIS = 30_000L
    }
}

@Composable
private fun IpFinderSetupScreen(
    assistantActive: Boolean,
    assistantServiceDeclared: Boolean,
    assistantServiceResolved: Boolean,
    currentAssistant: String,
    vpnActive: Boolean,
    running: Boolean,
    wifiActive: Boolean,
    cellularIp: String?,
    attempt: Int,
    timeline: List<Pair<Long, String>>,
    resultText: String?,
    onBack: () -> Unit,
    onEnableAssistant: () -> Unit,
    onAssistantSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Text(stringResource(R.string.ip_finder_stop))
        }
    }) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.ip_finder_back)) }
            Text(stringResource(R.string.ip_finder_test_entry), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.ip_finder_intro), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.ip_finder_diagnostic_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ip_finder_diagnostic_declared, stringResource(if (assistantServiceDeclared) R.string.ip_finder_yes else R.string.ip_finder_no)))
            Text(stringResource(R.string.ip_finder_diagnostic_resolved, stringResource(if (assistantServiceResolved) R.string.ip_finder_yes else R.string.ip_finder_no)))
            Text(stringResource(R.string.ip_finder_diagnostic_role, stringResource(if (assistantActive) R.string.ip_finder_yes else R.string.ip_finder_no)))
            Text(stringResource(R.string.ip_finder_diagnostic_current, currentAssistant))
            Text(stringResource(if (assistantActive) R.string.ip_finder_assistant_active else R.string.ip_finder_assistant_inactive))
            Button(onClick = onEnableAssistant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_enable_assistant))
            }
            OutlinedButton(onClick = onAssistantSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_restore_assistant))
            }
            Text(stringResource(if (vpnActive) R.string.ip_finder_vpn_active else R.string.ip_finder_vpn_inactive))
            Text(stringResource(if (wifiActive) R.string.ip_finder_wifi_active else R.string.ip_finder_wifi_inactive))
            Text(stringResource(R.string.ip_finder_cellular_ip, cellularIp ?: "—"))
            Text(stringResource(R.string.ip_finder_attempt, attempt))
            Button(onClick = onStart, enabled = !running && assistantActive && !vpnActive, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_start_cycle))
            }
            resultText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.ip_finder_timeline), style = MaterialTheme.typography.titleMedium)
            timeline.forEach { (elapsed, textId) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("+${elapsed / 1000}s", modifier = Modifier.padding(end = 10.dp))
                    Text(textId)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
