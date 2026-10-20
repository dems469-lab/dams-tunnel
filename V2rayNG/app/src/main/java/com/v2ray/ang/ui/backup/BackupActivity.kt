package com.v2ray.ang.ui.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog as PlatformAlertDialog
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

enum class BackupLocation(@StringRes val labelRes: Int) {
    Local(R.string.backup_location_local),
    WebDav(R.string.backup_location_webdav)
}

class BackupActivity : HelperBaseComponentActivity() {

    companion object {
        const val EXTRA_DAMS_URI = "extra_dams_uri"
        const val EXTRA_OPEN_DAMS_EXPORT = "extra_open_dams_export"
        private const val DAMS_MAX_ATTEMPTS = 5
    }

    private val viewModel: BackupViewModel by viewModels()
    private var pendingDamsUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeViewModel()
        viewModel.cleanupOldBackupTempFiles(cacheDir)
        (intent.getStringExtra(EXTRA_DAMS_URI)?.let { Uri.parse(it) } ?: intent.data)?.let {
            showDamsImportDialog(it)
        }
    }

    private fun showDamsImportDialog(uri: Uri, requestedAttempts: Int? = null) {
        pendingDamsUri = uri
        val attemptKey = damsAttemptKey(uri)
        val remainingAttempts = (requestedAttempts
            ?: MmkvManager.decodeSettingsInt(attemptKey, DAMS_MAX_ATTEMPTS)).coerceIn(0, DAMS_MAX_ATTEMPTS)
        if (remainingAttempts == 0) {
            toastError(R.string.dams_file_invalid)
            return
        }
        val password = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.title_dams_password)
        }
        PlatformAlertDialog.Builder(this)
            .setTitle(getString(R.string.title_dams_restore_attempts, remainingAttempts))
            .setView(password)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val file = File(cacheDir, "import_${System.currentTimeMillis()}.dams")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use(input::copyTo)
                        }
                        viewModel.restoreDamsConfiguration(
                            cacheDir,
                            file,
                            password.text.toString(),
                            remainingAttempts,
                        )
                    } catch (e: Exception) {
                        file.delete()
                        LogUtil.e(AppConfig.TAG, "Failed to read Dams import", e)
                        toastError(R.string.dams_file_invalid)
                    }
                }
            }
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.viewModelEvent.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupViewModelEvent.ShareFile -> {
                            handleShareFile(event.filePath)
                        }

                        is BackupViewModel.BackupViewModelEvent.ExportLocal -> {
                            handleExportLocal(event.cachePath, event.targetUri)
                        }

                        is BackupViewModel.BackupViewModelEvent.RestoreSuccess -> {
                            pendingDamsUri?.let { MmkvManager.encodeSettings(damsAttemptKey(it), DAMS_MAX_ATTEMPTS) }
                            pendingDamsUri = null
                            SettingsManager.initApp(this@BackupActivity)
                            finish()
                        }

                        is BackupViewModel.BackupViewModelEvent.DamsRestoreFailed -> {
                            pendingDamsUri?.let {
                                MmkvManager.encodeSettings(damsAttemptKey(it), event.remainingAttempts)
                            }
                            pendingDamsUri?.let { uri ->
                                if (event.remainingAttempts > 0) {
                                    showDamsImportDialog(uri, event.remainingAttempts)
                                } else {
                                    toastError(R.string.dams_file_invalid)
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    private fun damsAttemptKey(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "dams_import_attempts_$digest"
    }

    @Composable
    override fun ScreenContent() {
        BackupScreen(
            isLoadingState = viewModel.isLoading,
            webDavConfigState = viewModel.webDavConfig,
            onBackupOptionSelected = { location ->
                when (location) {
                    BackupLocation.Local -> backupViaLocal()
                    BackupLocation.WebDav -> viewModel.backupViaWebDav(cacheDir, getString(R.string.app_name))
                }
            },
            onShareClick = { viewModel.shareBackup(cacheDir, getString(R.string.app_name)) },
            onDamsExport = { password, expiry -> exportDams(password, expiry) },
            openDamsExport = intent.getBooleanExtra(EXTRA_OPEN_DAMS_EXPORT, false),
            onRestoreOptionSelected = { location ->
                when (location) {
                    BackupLocation.Local -> restoreViaLocal()
                    BackupLocation.WebDav -> viewModel.restoreViaWebDav(cacheDir)
                }
            },
            onCleanupProfiles = viewModel::cleanupProfileStorage,
            onWebDavSave = { config -> viewModel.saveWebDavConfig(config) },
            onBackClick = { finish() }
        )
    }

    private fun handleShareFile(filePath: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("application/zip")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            this,
                            BuildConfig.APPLICATION_ID + ".cache",
                            File(filePath)
                        )
                    ),
                getString(R.string.title_configuration_share)
            )
        )
    }

    private fun handleExportLocal(cachePath: String, targetUri: Uri) {
        try {
            contentResolver.openOutputStream(targetUri)?.use { output ->
                File(cachePath).inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            File(cachePath).delete()
            toastSuccess(R.string.toast_success)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to copy backup to Uri", e)
            toastError(R.string.toast_failure)
        }
    }

    private fun backupViaLocal() {
        val dateFormatted = SimpleDateFormat(
            "yyyy-MM-dd-HH-mm-ss",
            Locale.getDefault()
        ).format(System.currentTimeMillis())
        val defaultFileName = "${getString(R.string.app_name)}_${dateFormatted}.zip"

        launchCreateDocument(defaultFileName) { uri ->
            if (uri != null) {
                viewModel.prepareBackupForUri(cacheDir, getString(R.string.app_name), uri)
            }
        }
    }

    private fun exportDams(password: String, expiresAt: Long?) {
        val dateFormatted = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault())
            .format(System.currentTimeMillis())
        launchCreateDocument("${getString(R.string.app_name)}_$dateFormatted.dams") { uri ->
            if (uri != null) {
                viewModel.prepareDamsBackupForUri(
                    cacheDir,
                    getString(R.string.app_name),
                    uri,
                    password,
                    expiresAt,
                )
            }
        }
    }

    private fun restoreViaLocal() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            try {
                val targetFile =
                    File(cacheDir.absolutePath, "${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri).use { input ->
                    targetFile.outputStream().use { fileOut ->
                        input?.copyTo(fileOut)
                    }
                }
                viewModel.restoreConfiguration(cacheDir, targetFile)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Error during file restore", e)
                toastError(R.string.toast_failure)
            }
        }
    }
}

@Composable
fun BackupScreen(
    isLoadingState: StateFlow<Boolean>,
    webDavConfigState: StateFlow<WebDavConfig?>,
    onBackupOptionSelected: (BackupLocation) -> Unit,
    onShareClick: () -> Unit,
    onDamsExport: (String, Long?) -> Unit,
    openDamsExport: Boolean = false,
    onRestoreOptionSelected: (BackupLocation) -> Unit,
    onCleanupProfiles: () -> Unit,
    onWebDavSave: (WebDavConfig) -> Unit,
    onBackClick: () -> Unit
) {
    val isLoading by isLoadingState.collectAsState()
    val currentWebDavConfig by webDavConfigState.collectAsState()
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    var showDamsExportDialog by remember { mutableStateOf(openDamsExport) }

    val webDavSummary = currentWebDavConfig?.baseUrl

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_configuration_backup_restore),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_backup_24dp),
                title = stringResource(R.string.title_configuration_backup),
                onClick = { showBackupDialog = true }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_share_24dp),
                title = stringResource(R.string.title_configuration_share),
                onClick = onShareClick
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_backup_24dp),
                title = stringResource(R.string.title_dams_export_action),
                onClick = { showDamsExportDialog = true }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_restore_24dp),
                title = stringResource(R.string.title_configuration_restore),
                onClick = { showRestoreDialog = true }
            )
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_delete_24dp),
                title = stringResource(R.string.title_profile_storage_cleanup),
                subtitle = stringResource(R.string.summary_profile_storage_cleanup),
                onClick = { showCleanupDialog = true }
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_settings_24dp),
                title = stringResource(R.string.title_webdav_config_setting),
                subtitle = webDavSummary,
                onClick = { showWebDavDialog = true }
            )
            NavigationBarsSpacer()
        }
    }

    if (showBackupDialog) {
        SelectListDialog(
            title = stringResource(R.string.title_configuration_backup),
            options = BackupLocation.entries,
            optionText = { stringResource(it.labelRes) },
            onSelected = { location ->
                showBackupDialog = false
                onBackupOptionSelected(location)
            },
            onDismiss = { showBackupDialog = false }
        )
    }
    if (showRestoreDialog) {
        SelectListDialog(
            title = stringResource(R.string.title_configuration_restore),
            options = BackupLocation.entries,
            optionText = { stringResource(it.labelRes) },
            onSelected = { location ->
                showRestoreDialog = false
                onRestoreOptionSelected(location)
            },
            onDismiss = { showRestoreDialog = false }
        )
    }
    if (showCleanupDialog) {
        DeleteConfirmDialog(
            message = stringResource(R.string.message_profile_storage_cleanup),
            onConfirm = onCleanupProfiles,
            onDismiss = { showCleanupDialog = false }
        )
    }
    if (showWebDavDialog) {
        WebDavInputDialog(
            initialConfig = currentWebDavConfig,
            onSave = {
                showWebDavDialog = false
                onWebDavSave(it)
            },
            onDismiss = { showWebDavDialog = false }
        )
    }
    if (showDamsExportDialog) {
        DamsExportDialog(
            onDismiss = { showDamsExportDialog = false },
            onExport = { password, expiry ->
                showDamsExportDialog = false
                onDamsExport(password, expiry)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DamsExportDialog(
    onDismiss: () -> Unit,
    onExport: (String, Long?) -> Unit,
) {
    val endOfSelectedDayMillis = 24L * 60L * 60L * 1000L
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf<Long?>(null) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = expiry)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_dams_export)) },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.title_dams_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = passwordConfirmation,
                    onValueChange = { passwordConfirmation = it },
                    label = { Text(stringResource(R.string.title_dams_password_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                TextButton(onClick = { showDatePicker = true }) {
                    Text(
                        expiry?.let {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date(it))
                        } ?: stringResource(R.string.dams_no_expiry)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = password.isNotEmpty() && password == passwordConfirmation,
                onClick = { onExport(password, expiry) }
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker returns the beginning of the selected day.  A file
                    // selected for today must remain valid for the whole day, not
                    // expire immediately at midnight.
                    expiry = datePickerState.selectedDateMillis?.plus(endOfSelectedDayMillis)
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.action_cancel)) } }
        ) { DatePicker(state = datePickerState) }
    }
}

@Composable
private fun WebDavInputDialog(
    initialConfig: WebDavConfig?,
    onSave: (WebDavConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(initialConfig?.baseUrl ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var remotePath by remember { mutableStateOf(initialConfig?.remoteBasePath ?: "/") }

    val fields = listOf(
        InputField(
            label = stringResource(R.string.title_webdav_url),
            value = url
        ),
        InputField(
            label = stringResource(R.string.title_webdav_user),
            value = username
        ),
        InputField(
            label = stringResource(R.string.title_webdav_pass),
            value = password,
            visualTransformation = VisualTransformation.None
        ),
        InputField(
            label = stringResource(R.string.title_webdav_remote_path),
            value = remotePath
        )
    )

    InputDialog(
        title = stringResource(R.string.title_webdav_config_setting),
        fields = fields,
        onFieldChange = { index, value ->
            when (index) {
                0 -> url = value
                1 -> username = value
                2 -> password = value
                3 -> remotePath = value
            }
        },
        confirmText = stringResource(R.string.menu_item_save_config),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = {
            onSave(
                WebDavConfig(
                    baseUrl = url.trim(),
                    username = username.trim().ifEmpty { null },
                    password = password,
                    remoteBasePath = remotePath.trim().ifEmpty { AppConfig.WEBDAV_BACKUP_DIR }
                )
            )
        },
        onDismiss = onDismiss
    )
}
