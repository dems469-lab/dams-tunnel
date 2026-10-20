package com.v2ray.ang.ipfinder

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
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
    private var rulesText by mutableStateOf("")
    private lateinit var rulesRepository: IpFinderRulesRepository
    private lateinit var loopSettingsRepository: IpFinderLoopSettingsRepository
    private var cyclesText by mutableStateOf(IpFinderLoopSettingsRepository.DEFAULT_CYCLES)
    private var pauseText by mutableStateOf(IpFinderLoopSettingsRepository.DEFAULT_PAUSE)
    private var autoConnectVpn by mutableStateOf(IpFinderLoopSettingsRepository.DEFAULT_AUTO_CONNECT)
    private var wifiActive by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var attempt by mutableStateOf(0)
    private var resultText by mutableStateOf<String?>(null)
    private var totalCycles by mutableStateOf(10)
    private var vpnActuallyConnected by mutableStateOf(false)
    private var vpnStartFailure: String? = null
    private var retryVpnVisible by mutableStateOf(false)
    private var retryAfterVpnPermission = false
    private var showDisconnectVpnConfirmation by mutableStateOf(false)
    private var showRestoreAirplaneConfirmation by mutableStateOf(false)
    private var activityResumed = false
    private var restorationJob: kotlinx.coroutines.Job? = null
    private val timeline = mutableStateListOf<Pair<Long, String>>()
    private var cycleJob: kotlinx.coroutines.Job? = null
    private var cycleStart = 0L
    private var cancelReceiverRegistered = false
    private val searchCancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == IpFinderSearchService.ACTION_CANCEL_SEARCH) cycleJob?.cancel()
        }
    }
    private var vpnStateReceiverRegistered = false
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    vpnActuallyConnected = true
                    vpnStartFailure = null
                }
                AppConfig.MSG_STATE_CONNECTING, AppConfig.MSG_STATE_RECONNECTING -> {
                    vpnActuallyConnected = false
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    vpnActuallyConnected = false
                    vpnStartFailure = getString(R.string.ip_finder_vpn_start_rejected)
                }
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    vpnActuallyConnected = false
                }
            }
        }
    }
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (retryAfterVpnPermission) {
                retryAfterVpnPermission = false
                retryVpnConnection()
            } else continueSearchPreflight()
        } else {
            retryAfterVpnPermission = false
            resultText = getString(R.string.ip_finder_vpn_permission_denied)
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestLocalNetworkPermissionThenContinue()
        else resultText = getString(R.string.toast_permission_denied_for, getString(R.string.permission_notification))
    }
    private val localNetworkPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) continueAfterPermissions()
        else resultText = getString(R.string.toast_permission_denied_for, getString(R.string.permission_local_network))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            searchCancelReceiver,
            IntentFilter(IpFinderSearchService.ACTION_CANCEL_SEARCH),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        cancelReceiverRegistered = true
        ContextCompat.registerReceiver(
            this,
            vpnStateReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        vpnStateReceiverRegistered = true
        MessageHelper.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
        rulesRepository = IpFinderRulesRepository(AndroidIpRuleTextStorage(this))
        rulesText = rulesRepository.load()
        loopSettingsRepository = IpFinderLoopSettingsRepository(AndroidIpFinderLoopSettingsStorage(this))
        loopSettingsRepository.load().let { settings ->
            cyclesText = settings.cycles
            pauseText = settings.pauseSeconds
            autoConnectVpn = settings.connectVpnAutomatically
        }
        IpFinderLoopNumberValidator.cycles(cyclesText).value?.let { totalCycles = it }
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

    override fun onDestroy() {
        if (cancelReceiverRegistered) unregisterReceiver(searchCancelReceiver)
        if (vpnStateReceiverRegistered) unregisterReceiver(vpnStateReceiver)
        super.onDestroy()
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
                0
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

    private fun ruleVerdict(address: String): String {
        val parsed = IpRuleTextParser.parse(rulesText)
        if (parsed.inputError != null) {
            return getString(if (parsed.inputError == IpRuleInputError.TOO_MANY_RULES) {
                R.string.ip_finder_rules_too_many
            } else {
                R.string.ip_finder_rules_too_large
            })
        }
        if (parsed.validRules.isEmpty()) return getString(R.string.ip_finder_rules_none_valid)
        val result = IpRuleMatcher.match(parsed.validRules.joinToString(";"), address)
        return if (result.matches) getString(R.string.ip_finder_rules_match_yes, result.triggeringRule.orEmpty())
        else getString(R.string.ip_finder_rules_match_no)
    }

    private suspend fun restoreAirplaneMode() {
        if (!IpFinderRecoveryStore.isPending() && !IpFinderRecoveryStore.isAirplaneModeEnabled(this)) return
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
        if (restorationJob?.isActive == true) return
        if (loopSettingsRepository.isSearchPending()) {
            resultText = getString(R.string.ip_finder_cycle_interrupted)
            if (IpFinderRecoveryStore.isAirplaneModeEnabled(this)) showRestoreAirplaneConfirmation = true
            else {
                IpFinderRecoveryStore.clear()
                loopSettingsRepository.clearSearchPending()
            }
        } else if (intent.getBooleanExtra(EXTRA_RESTORE_PENDING, false) && IpFinderRecoveryStore.isPending()) {
            showRestoreAirplaneConfirmation = IpFinderRecoveryStore.isAirplaneModeEnabled(this)
        }
    }

    private fun requestSearchStart() {
        if (running) return
        val guid = MmkvManager.getSelectServer()
        val profileExists = !guid.isNullOrBlank() && MmkvManager.decodeServerConfig(guid) != null
        val permissionRequired = SettingsManager.isVpnMode()
        val permissionIntent = if (permissionRequired) VpnService.prepare(this) else null
        when (IpFinderPreflight.decide(
            IpFinderPreflightInput(
                airplaneModeEnabled = IpFinderRecoveryStore.isAirplaneModeEnabled(this),
                selectedProfileExists = profileExists,
                vpnPermissionRequired = permissionRequired,
                vpnPermissionGranted = permissionIntent == null,
                vpnServiceActive = false,
            )
        )) {
            IpFinderPreflightDecision.AIRPLANE_MODE_ON -> {
                resultText = getString(R.string.ip_finder_refuse_airplane_already_on)
                return
            }
            IpFinderPreflightDecision.PROFILE_REQUIRED -> {
                resultText = getString(R.string.ip_finder_select_profile_first)
                return
            }
            IpFinderPreflightDecision.REQUEST_VPN_PERMISSION -> {
                if (permissionIntent != null) {
                vpnPermissionLauncher.launch(permissionIntent)
                return
                }
            }
            else -> Unit
        }
        continueSearchPreflight()
    }

    private fun continueSearchPreflight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestLocalNetworkPermissionThenContinue()
        } else {
            requestLocalNetworkPermissionThenContinue()
        }
    }

    private fun requestLocalNetworkPermissionThenContinue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            } else {
                continueAfterPermissions()
            }
        } else {
            continueAfterPermissions()
        }
    }

    private fun continueAfterPermissions() {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrBlank() || MmkvManager.decodeServerConfig(selectedGuid) == null) {
            resultText = getString(R.string.ip_finder_select_profile_first)
            return
        }
        refreshState()
        if (damsVpnActive) showDisconnectVpnConfirmation = true else startSearchLoop()
    }

    private fun disconnectVpnAndStartSearch() {
        showDisconnectVpnConfirmation = false
        LauncherManager.stopService(this)
        lifecycleScope.launch {
            val stopped = withTimeoutOrNull(VPN_STOP_TIMEOUT_MILLIS) {
                while (true) {
                    refreshState()
                    if (!damsVpnActive) return@withTimeoutOrNull true
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
            if (stopped) startSearchLoop()
            else resultText = getString(R.string.ip_finder_vpn_disconnect_timeout)
        }
    }

    private fun startSearchLoop() {
        if (running || IpFinderRecoveryStore.isAirplaneModeEnabled(this)) {
            resultText = getString(R.string.ip_finder_refuse_airplane_already_on)
            return
        }
        val cycles = IpFinderLoopNumberValidator.cycles(cyclesText).value ?: return
        val pause = IpFinderLoopNumberValidator.pauseSeconds(pauseText).value ?: return
        val parsed = IpRuleTextParser.parse(rulesText)
        if (parsed.inputError != null || parsed.validRules.isEmpty() || !assistantActive || damsVpnActive) {
            resultText = getString(R.string.ip_finder_search_preconditions_changed)
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, IpFinderSearchService::class.java))
        } catch (_: Exception) {
            resultText = getString(R.string.ip_finder_foreground_service_failed)
            return
        }
        attempt = 0
        totalCycles = cycles
        retryVpnVisible = false
        vpnStartFailure = null
        resultText = null
        timeline.clear()
        cycleStart = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loopSettingsRepository.markSearchPending()
        running = true
        cycleJob = lifecycleScope.launch {
            try {
                val coordinator = IpFinderSearchCoordinator(
                    port = ActivitySearchPort(),
                    rulesText = rulesText,
                    onCycleStarted = { attempt = it },
                    onAttempt = { item ->
                        attempt = item.number
                        val message = when {
                            item.cycleFailure != null -> getString(R.string.ip_finder_attempt_failed, item.number, getString(failureText(item.cycleFailure)))
                            item.matchingRule != null -> getString(R.string.ip_finder_attempt_match, item.number, item.address.orEmpty(), item.matchingRule)
                            else -> getString(R.string.ip_finder_attempt_no_match, item.number, item.address ?: "—")
                        }
                        timeline.add((SystemClock.elapsedRealtime() - cycleStart) to message)
                        while (timeline.size > MAX_TIMELINE_LINES) timeline.removeAt(0)
                    }
                )
                var firstAttempt = 1
                var finished = false
                while (firstAttempt <= cycles && !finished) {
                    when (val searchResult = coordinator.run(cycles, pause, firstAttempt)) {
                is IpFinderSearchResult.Found -> {
                            when (val found = IpFinderFoundActionCoordinator(ActivityFoundActionPort(), rulesText)
                                .handle(searchResult.rule, autoConnectVpn)) {
                                is IpFinderFoundActionResult.RetrySearch -> {
                                    val line = getString(
                                        R.string.ip_finder_attempt_address_changed,
                                        searchResult.attempt,
                                        found.address ?: "—"
                                    )
                                    addTimelineLine(line)
                                    firstAttempt = searchResult.attempt + 1
                                    if (firstAttempt <= cycles) delay(pause * 1_000L)
                                    else resultText = getString(R.string.ip_finder_search_exhausted, cycles)
                                }
                                IpFinderFoundActionResult.ProfileRequired -> {
                                    resultText = getString(R.string.ip_finder_select_profile_first)
                                    finished = true
                                }
                                is IpFinderFoundActionResult.VpnFailed -> {
                                    resultText = getString(R.string.ip_finder_vpn_connect_failed, found.address, found.cause)
                                    retryVpnVisible = true
                                    notifyIpFound(resultText.orEmpty())
                                    addTimelineLine(resultText.orEmpty())
                                    finished = true
                                }
                                is IpFinderFoundActionResult.Found -> {
                                    resultText = getString(
                                        if (found.vpnConnected) R.string.ip_finder_found_vpn_connected
                                        else R.string.ip_finder_found_vpn_disconnected,
                                        found.address,
                                        found.rule,
                                        searchResult.attempt
                                    )
                                    notifyIpFound(resultText.orEmpty())
                                    addTimelineLine(resultText.orEmpty())
                                    finished = true
                                }
                            }
                        }
                        is IpFinderSearchResult.Exhausted -> {
                            resultText = getString(R.string.ip_finder_search_exhausted, searchResult.attempts)
                            finished = true
                        }
                        is IpFinderSearchResult.Failed -> {
                            resultText = getString(searchFailureText(searchResult.message))
                            finished = true
                        }
                        IpFinderSearchResult.Cancelled -> {
                            resultText = getString(R.string.ip_finder_event_cancelled)
                            finished = true
                        }
                    }
                }
            } catch (_: CancellationException) {
                resultText = getString(R.string.ip_finder_event_cancelled)
            } catch (_: Exception) {
                resultText = getString(R.string.ip_finder_search_unexpected_failure)
            } finally {
                running = false
                // Garder le marqueur si le mode avion n'a pas été confirmé désactivé :
                // la prochaine ouverture proposera alors la récupération.
                if (IpFinderSearchRecoveryPolicy.canClearLoopMarker(
                        IpFinderRecoveryStore.isPending(),
                        IpFinderRecoveryStore.isAirplaneModeEnabled(this@IpFinderActivity)
                    )
                ) {
                    loopSettingsRepository.clearSearchPending()
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                stopService(Intent(this@IpFinderActivity, IpFinderSearchService::class.java))
                refreshState()
            }
        }
    }

    private fun searchFailureText(failure: IpFinderSearchFailure): Int = when (failure) {
        IpFinderSearchFailure.AIRPLANE_ALREADY_ON -> R.string.ip_finder_refuse_airplane_already_on
        IpFinderSearchFailure.THREE_CONSECUTIVE_NETWORK_FAILURES -> R.string.ip_finder_three_network_failures
        IpFinderSearchFailure.AIRPLANE_RESTORE_FAILED -> R.string.ip_finder_airplane_restore_failed
        IpFinderSearchFailure.CYCLE_FAILED -> R.string.ip_finder_cycle_failed
    }

    private fun addTimelineLine(message: String) {
        timeline.add((SystemClock.elapsedRealtime() - cycleStart) to message)
        while (timeline.size > MAX_TIMELINE_LINES) timeline.removeAt(0)
    }

    private fun notifyIpFound(message: String) {
        runCatching {
            NotificationHelper.notify(
                com.v2ray.ang.enums.NotificationChannelType.IP_FINDER_FOUND,
                this,
                getString(R.string.ip_finder_notification_found_title),
                message
            )
        }
        runCatching {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(500L)
            }
        }
    }

    private fun retryVpnConnection() {
        if (running || !MmkvManager.getSelectServer().let { !it.isNullOrBlank() && MmkvManager.decodeServerConfig(it) != null }) {
            resultText = getString(R.string.ip_finder_select_profile_first)
            return
        }
        if (SettingsManager.isVpnMode()) {
            VpnService.prepare(this)?.let { permissionIntent ->
                retryAfterVpnPermission = true
                vpnPermissionLauncher.launch(permissionIntent)
                return
            }
        }
        retryVpnVisible = false
        lifecycleScope.launch {
            if (damsVpnActive) {
                LauncherManager.stopService(this@IpFinderActivity)
                val stopped = withTimeoutOrNull(VPN_STOP_TIMEOUT_MILLIS) {
                    while (true) {
                        refreshState()
                        if (!damsVpnActive) return@withTimeoutOrNull true
                        delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } ?: false
                if (!stopped) {
                    resultText = getString(R.string.ip_finder_vpn_disconnect_timeout)
                    retryVpnVisible = true
                    return@launch
                }
            }
            try {
                val port = ActivityFoundActionPort()
                port.startVpn()
                val outcome = port.awaitVpnConnected(IpFinderFoundActionCoordinator.VPN_CONNECT_TIMEOUT_MILLIS)
                if (outcome is IpFinderVpnConnectionResult.Connected) {
                    val foundAddress = currentCellularIp.orEmpty()
                    val match = IpRuleMatcher.match(IpRuleTextParser.parse(rulesText).validRules.joinToString(";"), foundAddress)
                    resultText = getString(
                        R.string.ip_finder_found_vpn_connected,
                        foundAddress,
                        match.triggeringRule.orEmpty(),
                        attempt
                    )
                    notifyIpFound(resultText.orEmpty())
                    addTimelineLine(resultText.orEmpty())
                } else {
                    val cause = (outcome as? IpFinderVpnConnectionResult.Failed)?.cause.orEmpty()
                    resultText = getString(R.string.ip_finder_vpn_connect_failed, currentCellularIp.orEmpty(), cause)
                    retryVpnVisible = true
                }
            } catch (_: Exception) {
                resultText = getString(R.string.ip_finder_vpn_connect_failed, currentCellularIp.orEmpty(), getString(R.string.ip_finder_vpn_start_rejected))
                retryVpnVisible = true
            }
        }
    }

    private inner class ActivityFoundActionPort : IpFinderFoundActionPort {
        override fun selectedProfileExists(): Boolean = MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerConfig(it) != null } == true

        override fun currentCellularAddress(): String? = AndroidIpFinderCyclePort(this@IpFinderActivity) {
            IpFinderAirplaneRequest(false, false)
        }.currentCellularAddress()

        override suspend fun startVpn() {
            vpnActuallyConnected = false
            vpnStartFailure = null
            LauncherManager.startService(this@IpFinderActivity)
        }

        override suspend fun awaitVpnConnected(timeoutMillis: Long): IpFinderVpnConnectionResult =
            withTimeoutOrNull(timeoutMillis) {
                while (true) {
                    if (vpnActuallyConnected) return@withTimeoutOrNull IpFinderVpnConnectionResult.Connected
                    vpnStartFailure?.let { return@withTimeoutOrNull IpFinderVpnConnectionResult.Failed(it) }
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE") IpFinderVpnConnectionResult.Failed(getString(R.string.ip_finder_vpn_not_confirmed))
            } ?: IpFinderVpnConnectionResult.Failed(getString(R.string.ip_finder_vpn_not_confirmed))
    }

    private inner class ActivitySearchPort : IpFinderSearchPort {
        override fun isAirplaneModeEnabled(): Boolean = IpFinderRecoveryStore.isAirplaneModeEnabled(this@IpFinderActivity)
        override fun currentCellularAddress(): String? = AndroidIpFinderCyclePort(this@IpFinderActivity) {
            IpFinderAirplaneRequest(false, false)
        }.currentCellularAddress()

        override suspend fun runCycle(): IpFinderCycleResult = IpFinderCycleCoordinator(
            port = AndroidIpFinderCyclePort(this@IpFinderActivity, ::requestAirplaneMode),
            recoveryFlag = IpFinderRecoveryStore,
            onEvent = { event -> eventMessage(event)?.let { label ->
                timeline.add((SystemClock.elapsedRealtime() - cycleStart) to label)
                while (timeline.size > MAX_TIMELINE_LINES) timeline.removeAt(0)
            } }
        ).runSingleCycle()

        override suspend fun restoreAirplaneMode(): Boolean {
            if (!isAirplaneModeEnabled()) return true
            val accepted = requestAirplaneMode(false).accepted
            val restored = accepted && AndroidIpFinderCyclePort(this@IpFinderActivity, ::requestAirplaneMode)
                .awaitAirplaneMode(false, IpFinderCycleCoordinator.DEFAULT_TIMEOUT_MILLIS)
            if (restored) IpFinderRecoveryStore.clear()
            return restored
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
            cellularIpVerdict = currentCellularIp?.let(::ruleVerdict),
            rulesText = rulesText,
            totalCycles = totalCycles,
            cyclesText = cyclesText,
            pauseText = pauseText,
            autoConnectVpn = autoConnectVpn,
            attempt = attempt,
            timeline = timeline.toList(),
            resultText = resultText,
            retryVpnVisible = retryVpnVisible,
            showDisconnectVpnConfirmation = showDisconnectVpnConfirmation,
            showRestoreAirplaneConfirmation = showRestoreAirplaneConfirmation,
            onBack = { if (running) cycleJob?.cancel() else finish() },
            onEnableAssistant = ::openAssistantSettings,
            onAssistantSettings = ::openAssistantSettings,
            onStart = ::requestSearchStart,
            onStop = { if (running) cycleJob?.cancel() else finish() },
            onRulesChanged = { value -> rulesText = value; rulesRepository.save(value) },
            onResetRules = { rulesText = rulesRepository.reset() },
            onPrefixRules = { rulesText = rulesRepository.prefixAndSave(rulesText) },
            onCyclesChanged = { value -> cyclesText = value; loopSettingsRepository.saveCycles(value) },
            onPauseChanged = { value -> pauseText = value; loopSettingsRepository.savePause(value) },
            onAutoConnectVpnChanged = { value -> autoConnectVpn = value; loopSettingsRepository.saveAutoConnect(value) },
            onRetryVpn = ::retryVpnConnection,
            onConfirmDisconnectVpn = ::disconnectVpnAndStartSearch,
            onCancelDisconnectVpn = { showDisconnectVpnConfirmation = false },
            onConfirmRestoreAirplane = {
                showRestoreAirplaneConfirmation = false
                restorationJob = lifecycleScope.launch {
                    restoreAirplaneMode()
                    if (!IpFinderRecoveryStore.isAirplaneModeEnabled(this@IpFinderActivity)) {
                        loopSettingsRepository.clearSearchPending()
                    }
                }
            },
            onCancelRestoreAirplane = { showRestoreAirplaneConfirmation = false }
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
    cellularIpVerdict: String?,
    rulesText: String,
    totalCycles: Int,
    cyclesText: String,
    pauseText: String,
    autoConnectVpn: Boolean,
    attempt: Int,
    timeline: List<Pair<Long, String>>,
    resultText: String?,
    retryVpnVisible: Boolean,
    showDisconnectVpnConfirmation: Boolean,
    showRestoreAirplaneConfirmation: Boolean,
    onBack: () -> Unit,
    onEnableAssistant: () -> Unit,
    onAssistantSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRulesChanged: (String) -> Unit,
    onResetRules: () -> Unit,
    onPrefixRules: () -> Unit,
    onCyclesChanged: (String) -> Unit,
    onPauseChanged: (String) -> Unit,
    onAutoConnectVpnChanged: (Boolean) -> Unit,
    onRetryVpn: () -> Unit,
    onConfirmDisconnectVpn: () -> Unit,
    onCancelDisconnectVpn: () -> Unit,
    onConfirmRestoreAirplane: () -> Unit,
    onCancelRestoreAirplane: () -> Unit
) {
    var showResetConfirmation by remember { mutableStateOf(false) }
    var showPrefixConfirmation by remember { mutableStateOf(false) }
    val parsedRules = remember(rulesText) { IpRuleTextParser.parse(rulesText) }
    val rulesResult = remember(parsedRules, cellularIp) {
        IpRuleMatcher.match(parsedRules.validRules.joinToString(";"), cellularIp.orEmpty())
    }
    val cyclesValidation = IpFinderLoopNumberValidator.cycles(cyclesText)
    val pauseValidation = IpFinderLoopNumberValidator.pauseSeconds(pauseText)
    Scaffold(
        modifier = Modifier.imePadding(),
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
            Text(cellularIpVerdict ?: stringResource(R.string.ip_finder_rules_match_no_ip))
            OutlinedTextField(
                value = rulesText,
                onValueChange = onRulesChanged,
                label = { Text(stringResource(R.string.ip_finder_rules_label)) },
                supportingText = { Text(stringResource(R.string.ip_finder_rules_help)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 240.dp),
                minLines = 5,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Text
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showResetConfirmation = true }) {
                    Text(stringResource(R.string.ip_finder_rules_reset))
                }
                TextButton(onClick = { showPrefixConfirmation = true }) {
                    Text(stringResource(R.string.ip_finder_rules_prefix))
                }
            }
            when (parsedRules.inputError) {
                IpRuleInputError.TOO_MANY_RULES -> Text(stringResource(R.string.ip_finder_rules_too_many))
                IpRuleInputError.TEXT_TOO_LARGE -> Text(stringResource(R.string.ip_finder_rules_too_large))
                null -> {
                    Text(stringResource(R.string.ip_finder_rules_count, rulesResult.activeRuleCount))
                    parsedRules.ignoredRules.take(MAX_VISIBLE_IGNORED_RULES).forEach { ignored ->
                        Text(stringResource(R.string.ip_finder_rules_ignored_line, ignored.source, ignored.lineNumber))
                    }
                    val remainingIgnored = parsedRules.ignoredRules.size - MAX_VISIBLE_IGNORED_RULES
                    if (remainingIgnored > 0) Text(stringResource(R.string.ip_finder_rules_ignored_more, remainingIgnored))
                    if (parsedRules.validRules.isEmpty()) Text(stringResource(R.string.ip_finder_rules_none_valid))
                }
            }
            OutlinedTextField(
                value = cyclesText,
                onValueChange = onCyclesChanged,
                label = { Text(stringResource(R.string.ip_finder_cycles_label)) },
                isError = !cyclesValidation.isValid,
                supportingText = {
                    cyclesValidation.error?.let { Text(stringResource(loopNumberErrorText(it))) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pauseText,
                onValueChange = onPauseChanged,
                label = { Text(stringResource(R.string.ip_finder_pause_label)) },
                isError = !pauseValidation.isValid,
                supportingText = {
                    pauseValidation.error?.let { Text(stringResource(loopNumberErrorText(it))) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = autoConnectVpn, onCheckedChange = onAutoConnectVpnChanged)
                Text(
                    stringResource(R.string.ip_finder_auto_connect_vpn),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Text(stringResource(R.string.ip_finder_attempt, attempt, totalCycles))
            if (running) Text(stringResource(R.string.ip_finder_screen_awake_warning), color = MaterialTheme.colorScheme.error)
            Button(onClick = onStart, enabled = !running && assistantActive &&
                parsedRules.inputError == null && parsedRules.validRules.isNotEmpty() &&
                cyclesValidation.isValid && pauseValidation.isValid, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ip_finder_start_cycle))
            }
            resultText?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (retryVpnVisible) {
                Button(onClick = onRetryVpn, enabled = !running, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ip_finder_connect_vpn_retry))
                }
            }
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
    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.ip_finder_rules_reset_confirm_title)) },
            text = { Text(stringResource(R.string.ip_finder_rules_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { onResetRules(); showResetConfirmation = false }) {
                    Text(stringResource(R.string.ip_finder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.ip_finder_cancel))
                }
            }
        )
    }
    if (showPrefixConfirmation) {
        AlertDialog(
            onDismissRequest = { showPrefixConfirmation = false },
            title = { Text(stringResource(R.string.ip_finder_rules_prefix_confirm_title)) },
            text = { Text(stringResource(R.string.ip_finder_rules_prefix_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { onPrefixRules(); showPrefixConfirmation = false }) {
                    Text(stringResource(R.string.ip_finder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrefixConfirmation = false }) {
                    Text(stringResource(R.string.ip_finder_cancel))
                }
            }
        )
    }
    if (showDisconnectVpnConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelDisconnectVpn,
            title = { Text(stringResource(R.string.ip_finder_vpn_disconnect_title)) },
            text = { Text(stringResource(R.string.ip_finder_vpn_disconnect_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDisconnectVpn) { Text(stringResource(R.string.ip_finder_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDisconnectVpn) { Text(stringResource(R.string.ip_finder_cancel)) }
            }
        )
    }
    if (showRestoreAirplaneConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelRestoreAirplane,
            title = { Text(stringResource(R.string.ip_finder_cycle_interrupted)) },
            text = { Text(stringResource(R.string.ip_finder_recovery_prompt)) },
            confirmButton = {
                TextButton(onClick = onConfirmRestoreAirplane) { Text(stringResource(R.string.ip_finder_restore_airplane)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelRestoreAirplane) { Text(stringResource(R.string.ip_finder_cancel)) }
            }
        )
    }
}

private const val MAX_VISIBLE_IGNORED_RULES = 5
private const val MAX_TIMELINE_LINES = 200
private const val VPN_STOP_TIMEOUT_MILLIS = 15_000L

@androidx.annotation.StringRes
private fun loopNumberErrorText(error: LoopNumberError): Int = when (error) {
    LoopNumberError.EMPTY -> R.string.ip_finder_number_required
    LoopNumberError.NOT_INTEGER -> R.string.ip_finder_number_integer
    LoopNumberError.OUT_OF_RANGE -> R.string.ip_finder_number_range
}
