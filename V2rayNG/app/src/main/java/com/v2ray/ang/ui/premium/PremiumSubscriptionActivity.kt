package com.v2ray.ang.ui.premium

import android.text.format.Formatter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.premium.PremiumExpiryState
import com.v2ray.ang.premium.PremiumBuildVariantExtension
import com.v2ray.ang.premium.PremiumBuildVariantExtensionLoader
import com.v2ray.ang.premium.PremiumFeatureGate
import com.v2ray.ang.premium.PremiumSubscriptionInfo
import com.v2ray.ang.premium.PremiumSubscriptionInfoParser
import com.v2ray.ang.premium.PremiumSubscriptionRepository
import com.v2ray.ang.premium.PremiumSubscriptionStatus
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import java.util.Date
import kotlin.math.ceil

class PremiumSubscriptionActivity : BaseComponentActivity() {
    private var premiumFeatureEnabled by mutableStateOf(false)
    private val variantExtension by lazy { PremiumBuildVariantExtensionLoader.load(this) }

    private val viewModel: PremiumSubscriptionViewModel by viewModels {
        PremiumSubscriptionViewModel.Factory(
            variantExtension?.dataSource ?: PremiumSubscriptionRepository(application),
            premiumFeatureEnabled,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        premiumFeatureEnabled = PremiumFeatureGate.isEnabled()
        if (!premiumFeatureEnabled) finish()
    }

    @Composable
    override fun ScreenContent() {
        if (!premiumFeatureEnabled) return
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { viewModel.loadSavedState() }
        LaunchedEffect(state.messageRes) {
            if (state.messageRes == R.string.premium_activation_success ||
                state.messageRes == R.string.premium_refresh_success
            ) {
                SettingsChangeManager.makeSetupGroupTab()
            }
        }
        BackHandler { finish() }
        PremiumSubscriptionScreen(
            state = state,
            onCodeChanged = viewModel::onCodeChanged,
            onActivate = viewModel::activate,
            onRefresh = viewModel::refresh,
            onRemove = viewModel::requestRemove,
            onDismissRemove = viewModel::dismissRemoveConfirmation,
            onConfirmRemove = viewModel::confirmRemove,
            variantExtension = variantExtension,
            onSelectVariantOption = { option ->
                variantExtension?.selectOption(option)
                viewModel.loadSavedState()
            },
            onBack = { finish() },
        )
    }
}

@Composable
private fun PremiumSubscriptionScreen(
    state: PremiumSubscriptionUiState,
    onCodeChanged: (String) -> Unit,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onDismissRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    variantExtension: PremiumBuildVariantExtension?,
    onSelectVariantOption: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val info = state.info
    val expiryStatus = if (state.expiry.isExpired) PremiumSubscriptionStatus.EXPIRED else null
    val status = expiryStatus ?: info?.let {
        PremiumSubscriptionInfoParser.status(it, System.currentTimeMillis() / 1000L)
    } ?: if (state.hasSubscription) PremiumSubscriptionStatus.UNKNOWN else null
    val usedBytes = info?.usedBytes
    val totalBytes = info?.totalBytes
    val usedText = usedBytes?.let { Formatter.formatFileSize(context, it) }
        ?: stringResource(R.string.premium_status_unknown)
    val totalText = when (totalBytes) {
        0L -> stringResource(R.string.premium_status_unlimited)
        null -> stringResource(R.string.premium_status_unknown)
        else -> Formatter.formatFileSize(context, totalBytes)
    }
    val progress = if (totalBytes != null && totalBytes > 0L && usedBytes != null) {
        (usedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else null

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.premium_title),
                onBackClick = onBack,
                isLoading = state.isLoading,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(NavigationBarsBottomPadding())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (variantExtension?.hasOptions == true) {
                variantExtension.Options(onSelectVariantOption)
            }

            if (state.requiresCodeReentry) {
                Text(
                    text = stringResource(R.string.premium_reentry_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChanged,
                label = { Text(stringResource(R.string.premium_code_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onActivate,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.premium_activate))
            }

            if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.messageRes?.let { message ->
                Text(
                    text = stringResource(message),
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (status != null) {
                SubscriptionStatusCard(
                    status = status,
                    info = info,
                    expiry = state.expiry,
                    usedText = usedText,
                    totalText = totalText,
                    progress = progress,
                )
            } else {
                Text(
                    text = stringResource(R.string.premium_not_activated),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (state.hasSubscription) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.premium_refresh)) }
                    OutlinedButton(
                        onClick = onRemove,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.premium_remove)) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (state.showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text(stringResource(R.string.premium_remove_confirm_title)) },
            text = { Text(stringResource(R.string.premium_remove_confirm_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmRemove) {
                    Text(stringResource(R.string.premium_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemove) {
                    Text(stringResource(R.string.premium_cancel))
                }
            },
        )
    }
}

@Composable
private fun SubscriptionStatusCard(
    status: PremiumSubscriptionStatus,
    info: PremiumSubscriptionInfo?,
    expiry: PremiumExpiryState,
    usedText: String,
    totalText: String,
    progress: Float?,
) {
    val context = LocalContext.current
    val statusText = when (status) {
        PremiumSubscriptionStatus.ACTIVE -> stringResource(R.string.premium_status_active)
        PremiumSubscriptionStatus.EXPIRING_SOON -> stringResource(R.string.premium_status_expiring)
        PremiumSubscriptionStatus.EXPIRED -> stringResource(R.string.premium_status_expired)
        PremiumSubscriptionStatus.QUOTA_EXHAUSTED -> stringResource(R.string.premium_status_quota)
        PremiumSubscriptionStatus.UNLIMITED -> stringResource(R.string.premium_status_unlimited)
        PremiumSubscriptionStatus.UNKNOWN -> stringResource(R.string.premium_status_unknown)
    }
    val expirySeconds = info?.expiresAtEpochSeconds?.takeIf {
        it > 0L && it <= Long.MAX_VALUE / 1000L
    }
    val daysRemaining = expirySeconds?.let {
        ceil((it - System.currentTimeMillis() / 1000.0) / SECONDS_PER_DAY).toInt().coerceAtLeast(0)
    }
    val expirationDate = expirySeconds?.let {
        android.text.format.DateFormat.getDateFormat(context).format(Date(it * 1000L))
    }
    val cardDescription = stringResource(R.string.premium_progress_description, usedText, totalText)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(statusText, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            if (daysRemaining != null) {
                Text(
                    text = pluralStringResource(R.plurals.premium_days_remaining, daysRemaining, daysRemaining),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(stringResource(R.string.premium_data_usage, usedText, totalText), style = MaterialTheme.typography.bodyLarge)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = cardDescription },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (expirationDate != null) {
                Text(stringResource(R.string.premium_expiration_date, expirationDate))
            } else if (expiry.isExpired || info?.expiresAtEpochSeconds == null) {
                Text(stringResource(R.string.premium_date_unknown), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val SECONDS_PER_DAY = 86_400.0
