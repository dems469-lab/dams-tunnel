package com.v2ray.ang.reconfig.ui

import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.DamsTheme

/**
 * Écran « Reconfiguration » : serveurs préparés localement, testables et
 * transférables vers Configs.
 *
 * L'écran ne dépend d'aucun service au premier plan : le ping et le test réel
 * sont lancés à la demande et n'interrompent jamais le VPN actif.
 */
class ReconfigurationActivity : BaseComponentActivity() {

    private val viewModel: ReconfigurationViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
        val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

        ReconfigurationScreen(
            state = state,
            animationsEnabled = DamsTheme.animationsEnabled && isResumed,
            onBack = ::finish,
            onRefresh = viewModel::refresh,
            onTestAll = viewModel::testAll,
            onQueryChange = viewModel::setQuery,
            onProtocolFilter = viewModel::setProtocolFilter,
            onMethodFilter = viewModel::setMethodFilter,
            onStateFilter = viewModel::setStateFilter,
            onSort = viewModel::setSort,
            onResetFilters = {
                viewModel.setQuery("")
                viewModel.setProtocolFilter(null)
                viewModel.setMethodFilter(null)
                viewModel.setStateFilter(null)
            },
            onToggleSelection = viewModel::toggleSelection,
            onSelectAllVisible = viewModel::selectAllVisible,
            onClearSelection = viewModel::clearSelection,
            onOpenDetail = viewModel::openDetail,
            onCloseDetail = viewModel::closeDetail,
            onPing = viewModel::pingServer,
            onTest = viewModel::testServer,
            onTransfer = viewModel::transferServer,
            onRequestDelete = viewModel::requestDelete,
            onTestSelection = viewModel::testSelection,
            onTransferSelection = viewModel::transferSelection,
            onDeleteSelection = { viewModel.requestDelete(state.selectedIds) },
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDeleteRequest,
            onCancelOperation = viewModel::cancelOperation,
            onConsumeNotice = viewModel::consumeNotice,
        )
    }
}
