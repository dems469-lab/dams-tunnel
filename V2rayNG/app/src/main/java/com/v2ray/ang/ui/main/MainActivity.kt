package com.v2ray.ang.ui.main

import android.content.Intent
import android.app.KeyguardManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.AlertDialog as ComposeAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ipfinder.IpFinderActivity
import com.v2ray.ang.ipfinder.IpFinderRecoveryStore
import com.v2ray.ang.publicconfigs.PublicConfigsActivity
import com.v2ray.ang.premium.PremiumFeatureGate
import com.v2ray.ang.premium.PremiumExpiryStateStore
import com.v2ray.ang.premium.PremiumProfilePolicy
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.ConsumptionActivity
import com.v2ray.ang.ui.TutorialsActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.logcat.LogcatViewModel
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.premium.PremiumSubscriptionActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.util.BackupFileTooLargeException
import com.v2ray.ang.util.checkBackupFileSize
import com.v2ray.ang.util.getDeclaredFileSize
import com.v2ray.ang.util.readBytesLimited
import com.v2ray.ang.util.DamsFileCodec
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.PerformanceDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseComponentActivity() {

    companion object {
        const val EXTRA_RUN_SPEED_TEST = "com.v2ray.ang.extra.RUN_SPEED_TEST"
    }

    private var premiumFeatureEnabled by mutableStateOf(false)
    private var showPremiumExpiredPrompt by mutableStateOf(false)
    private var appUnlocked = false
    private var recoveryPromptShown = false
    private val appLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) appUnlocked = true else finish()
    }

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val logcatViewModel: LogcatViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        premiumFeatureEnabled = PremiumFeatureGate.isEnabled()
        IpFinderRecoveryStore.initialize(this)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        if (intent.getBooleanExtra(EXTRA_RUN_SPEED_TEST, false)) {
            window.decorView.post { mainViewModel.testCurrentServerRealPing() }
        }
    }

    override fun onResume() {
        super.onResume()
        PerformanceDiagnostics.refreshEnabled()
        if (!appUnlocked && MmkvManager.decodeSettingsBool(AppConfig.PREF_APP_LOCK, false)) {
            val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (!keyguard.isDeviceSecure) {
                toastError(R.string.app_lock_not_available)
                appUnlocked = true
                return
            }
            keyguard.createConfirmDeviceCredentialIntent(
                getString(R.string.app_name),
                getString(R.string.app_lock_prompt),
            )?.let(appLockLauncher::launch) ?: finish()
        } else {
            appUnlocked = true
        }

        if (appUnlocked) offerIpFinderRecovery()

        if (SettingsChangeManager.consumeSetupGroupTab()) {
            mainViewModel.refreshUiSettings()
            mainViewModel.onAction(MainAction.RefreshGroups)
        }
        if (SettingsChangeManager.consumeRestartService()) {
            LauncherManager.restartService(this)
        }
    }

    private fun offerIpFinderRecovery() {
        if (recoveryPromptShown || !IpFinderRecoveryStore.isPending()) return
        if (!IpFinderRecoveryStore.isAirplaneModeEnabled(this)) {
            IpFinderRecoveryStore.clear()
            return
        }
        recoveryPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.ip_finder_recovery_title)
            .setMessage(R.string.ip_finder_recovery_message)
            .setPositiveButton(R.string.ip_finder_recovery_action) { _, _ ->
                startActivity(Intent(this, IpFinderActivity::class.java)
                    .putExtra(IpFinderActivity.EXTRA_RESTORE_PENDING, true))
            }
            .setNegativeButton(R.string.ip_finder_recovery_cancel, null)
            .show()
    }

    @Composable
    override fun ScreenContent() {
        var showEmptyPerAppWarning by rememberSaveable { mutableStateOf(false) }
        BackHandler { moveTaskToBack(false) }
        MainScreen(
            mainViewModel = mainViewModel,
            logcatViewModel = logcatViewModel,
            showPremium = premiumFeatureEnabled,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> {
                        val state = mainViewModel.uiState.value
                        if (!shouldStopServiceOnToggle(state.isRunning, state.status) &&
                            mainViewModel.shouldWarnEmptyPerAppProxySelection()
                        ) {
                            showEmptyPerAppWarning = true
                        } else {
                            handleFabAction()
                        }
                    }
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    MainAction.OpenPublicConfigs -> startActivity(Intent(this, PublicConfigsActivity::class.java))
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    is MainAction.ShareDamsFile -> {
                        if (MmkvManager.isPremiumProfileGuid(action.guid)) {
                            toast(R.string.premium_export_excluded)
                        } else {
                            startActivity(
                                Intent(this, BackupActivity::class.java)
                                    .putExtra(BackupActivity.EXTRA_OPEN_DAMS_EXPORT, true)
                                    .putExtra(BackupActivity.EXTRA_DAMS_PROFILE_GUID, action.guid)
                            )
                        }
                    }
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
        if (showEmptyPerAppWarning) {
            ComposeAlertDialog(
                onDismissRequest = { showEmptyPerAppWarning = false },
                title = { Text(stringResource(R.string.per_app_empty_warning_title)) },
                text = { Text(stringResource(R.string.per_app_empty_warning_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showEmptyPerAppWarning = false
                        mainViewModel.useGlobalProxyMode()
                        requestServiceStart()
                    }) { Text(stringResource(R.string.per_app_empty_use_global)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showEmptyPerAppWarning = false
                        navigateTo(MainDestination.PerAppProxy)
                    }) { Text(stringResource(R.string.per_app_empty_choose_apps)) }
                },
            )
        }
        if (showPremiumExpiredPrompt) {
            ComposeAlertDialog(
                onDismissRequest = { showPremiumExpiredPrompt = false },
                title = { Text(stringResource(R.string.premium_expired_connect_title)) },
                text = { Text(stringResource(R.string.premium_expired_connect_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showPremiumExpiredPrompt = false
                        navigateTo(MainDestination.PremiumSubscription)
                    }) { Text(stringResource(R.string.premium_menu_my_subscription)) }
                },
                dismissButton = {
                    TextButton(onClick = { showPremiumExpiredPrompt = false }) {
                        Text(stringResource(R.string.premium_cancel))
                    }
                },
            )
        }
    }

    private fun shareToClipboard(guid: String): Boolean {
        if (MmkvManager.isPremiumProfileGuid(guid)) {
            toast(R.string.premium_export_excluded)
            return false
        }
        return AngConfigManager.shareDamsProfileLink2Clipboard(this, guid) == 0
    }

    private fun shareFullContentAsync(guid: String) {
        if (MmkvManager.isPremiumProfileGuid(guid)) {
            toast(R.string.premium_export_excluded)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PremiumSubscription -> Intent(this, PremiumSubscriptionActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.IpFinder -> Intent(this, IpFinderActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.Consumption -> Intent(this, ConsumptionActivity::class.java)
            MainDestination.Tutorials -> Intent(this, TutorialsActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        val state = mainViewModel.uiState.value
        if (shouldStopServiceOnToggle(state.isRunning, state.status)) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        val selectedProfile = mainViewModel.uiState.value.selectedGuid
            ?.let(MmkvManager::decodeServerConfig)
        if (PremiumProfilePolicy.blocksConnection(
                selectedProfile?.subscriptionId,
                PremiumExpiryStateStore().load().isExpired,
            )
        ) {
            showPremiumExpiredPrompt = true
            return
        }
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (!hasSelectedProfile(mainViewModel.uiState.value.selectedGuid)) {
            toast(R.string.toast_no_profile_selected)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        selectFasterDefaultServerIfRequested()
        mainViewModel.markManualStartRequested()
        LauncherManager.startService(this)
    }

    private fun selectFasterDefaultServerIfRequested() {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER, false)) return
        val selectedGuid = mainViewModel.uiState.value.selectedGuid ?: return
        val selected = MmkvManager.decodeServerConfig(selectedGuid) ?: return
        if (selected.subscriptionId != AppConfig.DEFAULT_SUBSCRIPTION_ID) return
        val selectedDelay = MmkvManager.decodeServerAffiliationInfo(selectedGuid)?.testDelayMillis ?: -1L
        val selectedCandidate = ServerPerformanceCandidate(
            selectedGuid,
            selected.configType.protocolScheme,
            selectedDelay,
        )
        val candidates = MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID).mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: -1L
            ServerPerformanceCandidate(guid, profile.configType.protocolScheme, delay)
        }
        val replacement = chooseFasterDefaultServer(true, true, selectedCandidate, candidates) ?: return
        MmkvManager.setSelectServer(replacement.guid)
        toastSuccess(R.string.default_server_switched)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard (${e.javaClass.simpleName})")
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                checkBackupFileSize(contentResolver.getDeclaredFileSize(uri))
                val header = contentResolver.openInputStream(uri)?.use { input ->
                    ByteArray(5).also { bytes -> input.read(bytes) }
                }
                if (header != null && DamsFileCodec.isDamsFile(header)) {
                    startActivity(
                        Intent(this, BackupActivity::class.java).putExtra(
                            BackupActivity.EXTRA_DAMS_URI,
                            uri.toString()
                        )
                    )
                    return@launchFileChooser
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    mainViewModel.onAction(
                        MainAction.ImportBatchConfig(input.readBytesLimited().toString(Charsets.UTF_8))
                    )
                }
            } catch (e: BackupFileTooLargeException) {
                toastError(R.string.backup_file_too_large)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI (${e.javaClass.simpleName})")
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
