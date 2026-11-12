package com.v2ray.ang.laboratory.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.laboratory.service.LaboratoryTunnelGateway
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.DamsTheme

/**
 * Écran d'entrée du laboratoire Multipath.
 *
 * L'ouverture de l'écran ne démarre **rien** : le moteur expérimental n'est
 * lancé que par une action explicite, après confirmation si un VPN classique
 * tourne déjà. La permission VPN est demandée ici, au moment de l'activation,
 * et non à l'ouverture.
 */
class LaboratoryActivity : BaseComponentActivity() {

    private val viewModel: LaboratoryViewModel by viewModels()
    private var pendingActivation = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val wasPending = pendingActivation
        pendingActivation = false
        if (wasPending && result.resultCode == Activity.RESULT_OK) {
            viewModel.requestActivation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.attachGateway(LaboratoryTunnelGateway(this))
    }

    override fun onResume() {
        super.onResume()
        viewModel.setScreenVisible(true)
    }

    override fun onPause() {
        viewModel.setScreenVisible(false)
        super.onPause()
    }

    @Composable
    override fun ScreenContent() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val theme = DamsTheme
        var showExitDialog by remember { mutableStateOf(false) }

        LaunchedEffect(state.exitRequested) {
            if (state.exitRequested) {
                viewModel.consumeExit()
                finish()
            }
        }

        val requestActivation: () -> Unit = {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                pendingActivation = true
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.requestActivation()
            }
        }

        BackHandler {
            if (state.isActive) {
                showExitDialog = true
            } else {
                finish()
            }
        }

        LaboratoryScreen(
            state = state,
            onBack = {
                if (state.isActive) showExitDialog = true else finish()
            },
            onActivate = requestActivation,
            onConfirmActivation = viewModel::confirmActivation,
            onCancelActivation = viewModel::cancelActivation,
            onDeactivate = viewModel::requestDeactivation,
            onBackToClassic = viewModel::requestBackToClassic,
            onProbeNow = viewModel::probeNow,
            onOpenPathPicker = viewModel::openPathPicker,
            onClosePathPicker = viewModel::closePathPicker,
            onSelectPath = viewModel::selectPath,
            onHybridChanged = viewModel::setHybrid,
            onAutoFailoverChanged = viewModel::setAutoFailover,
            onProbeIntervalChanged = viewModel::setProbeInterval,
            onRequestReset = viewModel::requestReset,
            onConfirmReset = viewModel::confirmReset,
            onCancelReset = viewModel::cancelReset,
            onConsumeNotice = viewModel::consumeNotice,
            onConsumeError = viewModel::consumeError,
        )

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text(androidx.compose.ui.res.stringResource(R.string.laboratory_exit_title)) },
                text = { Text(androidx.compose.ui.res.stringResource(R.string.laboratory_exit_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            viewModel.requestBackToClassic()
                        },
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.laboratory_exit_confirm),
                            color = theme.erreur,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(androidx.compose.ui.res.stringResource(R.string.laboratory_cancel))
                    }
                },
            )
        }
    }
}
