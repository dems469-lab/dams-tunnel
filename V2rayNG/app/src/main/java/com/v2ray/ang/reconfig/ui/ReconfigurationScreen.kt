package com.v2ray.ang.reconfig.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.reconfig.data.ReconfigPingState
import com.v2ray.ang.reconfig.data.ReconfigTestState
import com.v2ray.ang.reconfig.data.ReconfiguredServer
import com.v2ray.ang.reconfig.engine.ReconfigMethod
import com.v2ray.ang.ui.compose.DamsTheme
import java.util.Date
import kotlinx.coroutines.delay

private val KNOWN_PROTOCOLS = listOf("VLESS", "VMESS", "TROJAN")

/**
 * Écran de gestion des serveurs reconfigurés.
 *
 * Toutes les actions restent explicites : le ping et le test réel sont des
 * opérations distinctes qui nécessitent Internet, tandis que la transformation
 * d'origine était entièrement locale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReconfigurationScreen(
    state: ReconfigurationUiState,
    animationsEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onQueryChange: (String) -> Unit,
    onProtocolFilter: (String?) -> Unit,
    onMethodFilter: (ReconfigMethod?) -> Unit,
    onStateFilter: (ReconfigurationFilterState?) -> Unit,
    onSort: (ReconfigurationSort) -> Unit,
    onResetFilters: () -> Unit,
    onToggleSelection: (Long) -> Unit,
    onSelectAllVisible: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    onPing: (Long) -> Unit,
    onTest: (Long) -> Unit,
    onTransfer: (Long) -> Unit,
    onRequestDelete: (Set<Long>) -> Unit,
    onTestSelection: () -> Unit,
    onTransferSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onCancelOperation: () -> Unit,
    onConsumeNotice: () -> Unit,
) {
    val theme = DamsTheme
    val busy = state.busy

    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            delay(4000)
            onConsumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.reconfig_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                R.string.reconfig_count,
                                state.totalCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.texteDoux,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back_24dp),
                            contentDescription = stringResource(R.string.reconfig_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onTestAll, enabled = !busy && state.servers.isNotEmpty()) {
                        Text(stringResource(R.string.reconfig_test_all))
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(
                            painterResource(R.drawable.ic_refresh_24dp),
                            contentDescription = stringResource(R.string.reconfig_refresh),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.fond.copy(alpha = theme.headerSurfaceAlpha),
                    titleContentColor = theme.texteFort,
                    navigationIconContentColor = theme.texteFort,
                    actionIconContentColor = theme.accent,
                ),
            )
        },
        bottomBar = {
            if (state.selectedIds.isNotEmpty()) {
                ReconfigurationBottomActions(
                    selectedCount = state.selectedIds.size,
                    busy = busy,
                    onTest = onTestSelection,
                    onTransfer = onTransferSelection,
                    onDelete = onDeleteSelection,
                )
            }
        },
        containerColor = theme.fond,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "reconfig-overview") {
                ReconfigurationOverview(state)
            }

            state.notice?.let { notice ->
                item(key = "reconfig-notice") {
                    ReconfigurationNoticeBanner(notice)
                }
            }

            state.progress?.let { progress ->
                item(key = "reconfig-progress") {
                    ReconfigurationProgressCard(progress, onCancelOperation)
                }
            }

            item(key = "reconfig-search") {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.reconfig_search)) },
                    leadingIcon = {
                        Icon(
                            painterResource(R.drawable.ic_search_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }

            item(key = "reconfig-filters") {
                ReconfigurationFilters(
                    state = state,
                    onProtocolFilter = onProtocolFilter,
                    onMethodFilter = onMethodFilter,
                    onStateFilter = onStateFilter,
                    onSort = onSort,
                )
            }

            if (state.visibleServers.isNotEmpty()) {
                item(key = "reconfig-selection") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.reconfig_selection_count, state.selectedIds.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.texteDoux,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSelectAllVisible) {
                            Text(stringResource(R.string.reconfig_select_all))
                        }
                        if (state.selectedIds.isNotEmpty()) {
                            TextButton(onClick = onClearSelection) {
                                Text(stringResource(R.string.reconfig_clear_selection))
                            }
                        }
                    }
                }
            }

            when {
                state.loading && state.servers.isEmpty() -> item(key = "reconfig-loading") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = theme.accent)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.reconfig_loading),
                            color = theme.texteDoux,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                state.servers.isEmpty() -> item(key = "reconfig-empty") {
                    ReconfigurationEmpty(
                        textRes = R.string.reconfig_empty,
                        onReset = null,
                    )
                }

                state.visibleServers.isEmpty() -> item(key = "reconfig-no-match") {
                    ReconfigurationEmpty(
                        textRes = R.string.reconfig_no_match,
                        onReset = onResetFilters,
                    )
                }

                else -> items(state.visibleServers, key = { it.id }) { server ->
                    ReconfigurationServerCard(
                        server = server,
                        selected = server.id in state.selectedIds,
                        busy = busy,
                        animationsEnabled = animationsEnabled,
                        onToggleSelection = { onToggleSelection(server.id) },
                        onOpen = { onOpenDetail(server.id) },
                        onPing = { onPing(server.id) },
                        onTest = { onTest(server.id) },
                        onTransfer = { onTransfer(server.id) },
                        onDelete = { onRequestDelete(setOf(server.id)) },
                    )
                }
            }
        }
    }

    state.detail?.let { server ->
        ModalBottomSheet(
            onDismissRequest = onCloseDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = theme.surface,
        ) {
            ReconfigurationDetailSheet(
                server = server,
                busy = busy,
                onClose = onCloseDetail,
                onPing = { onPing(server.id) },
                onTest = { onTest(server.id) },
                onTransfer = { onTransfer(server.id) },
                onDelete = {
                    onCloseDetail()
                    onRequestDelete(setOf(server.id))
                },
            )
        }
    }

    state.deleteConfirmation?.let { ids ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.reconfig_delete_title)) },
            text = { Text(stringResource(R.string.reconfig_delete_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text(stringResource(R.string.reconfig_delete_confirm), color = theme.erreur)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(R.string.reconfig_cancel))
                }
            },
        )
    }
}

@Composable
private fun ReconfigurationOverview(state: ReconfigurationUiState) {
    val theme = DamsTheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReconfigurationStat(
                    value = state.totalCount.toString(),
                    label = stringResource(R.string.reconfig_stat_total),
                    tone = theme.texteFort,
                    modifier = Modifier.weight(1f),
                )
                ReconfigurationStat(
                    value = state.testedCount.toString(),
                    label = stringResource(R.string.reconfig_stat_tested),
                    tone = theme.accent,
                    modifier = Modifier.weight(1f),
                )
                ReconfigurationStat(
                    value = state.functionalCount.toString(),
                    label = stringResource(R.string.reconfig_stat_functional),
                    tone = theme.succes,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(R.string.reconfig_local_note),
                style = MaterialTheme.typography.bodySmall,
                color = theme.texteDoux,
            )
        }
    }
}

@Composable
private fun ReconfigurationStat(
    value: String,
    label: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val theme = DamsTheme
    Column(
        modifier = modifier
            .background(theme.fond.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = tone,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.texteDoux,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReconfigurationNoticeBanner(notice: ReconfigurationNotice) {
    val theme = DamsTheme
    val tone = if (notice.error) theme.erreur else theme.succes
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = tone.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.45f)),
    ) {
        Text(
            text = stringResource(notice.textRes, *notice.args.toTypedArray()),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = tone,
        )
    }
}

@Composable
private fun ReconfigurationProgressCard(
    progress: ReconfigurationProgress,
    onCancel: () -> Unit,
) {
    val theme = DamsTheme
    val label = when (progress.operation) {
        ReconfigurationOperation.PING -> stringResource(
            R.string.reconfig_progress_ping, progress.processed, progress.total,
        )
        ReconfigurationOperation.TEST -> stringResource(
            R.string.reconfig_progress_test, progress.processed, progress.total,
        )
        ReconfigurationOperation.TRANSFER -> stringResource(
            R.string.reconfig_progress_transfer, progress.processed, progress.total,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.texteFort,
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.reconfig_progress_cancel), color = theme.avertissement)
                }
            }
            LinearProgressIndicator(
                progress = { progress.ratio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = theme.accent,
                trackColor = theme.bordure.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ReconfigurationFilters(
    state: ReconfigurationUiState,
    onProtocolFilter: (String?) -> Unit,
    onMethodFilter: (ReconfigMethod?) -> Unit,
    onStateFilter: (ReconfigurationFilterState?) -> Unit,
    onSort: (ReconfigurationSort) -> Unit,
) {
    val theme = DamsTheme
    var showMethod by remember { mutableStateOf(false) }
    var showState by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = state.protocolFilter == null,
                onClick = { onProtocolFilter(null) },
                label = { Text(stringResource(R.string.reconfig_filter_all)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = theme.accent.copy(alpha = 0.22f),
                    selectedLabelColor = theme.accent,
                ),
            )
            KNOWN_PROTOCOLS.forEach { protocol ->
                FilterChip(
                    selected = state.protocolFilter.equals(protocol, ignoreCase = true),
                    onClick = { onProtocolFilter(protocol) },
                    label = { Text(protocol, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = theme.accent.copy(alpha = 0.22f),
                        selectedLabelColor = theme.accent,
                    ),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterDropdown(
                label = stringResource(R.string.reconfig_filter_method),
                value = state.methodFilter?.let { methodLabel(it) }
                    ?: stringResource(R.string.reconfig_filter_all),
                expanded = showMethod,
                onExpand = { showMethod = true },
                onDismiss = { showMethod = false },
                modifier = Modifier.weight(1f),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reconfig_filter_all)) },
                    onClick = {
                        showMethod = false
                        onMethodFilter(null)
                    },
                )
                ReconfigMethod.entries.forEach { method ->
                    DropdownMenuItem(
                        text = { Text(methodLabel(method)) },
                        onClick = {
                            showMethod = false
                            onMethodFilter(method)
                        },
                    )
                }
            }

            FilterDropdown(
                label = stringResource(R.string.reconfig_filter_state),
                value = state.stateFilter?.let { stateFilterLabel(it) }
                    ?: stringResource(R.string.reconfig_filter_all),
                expanded = showState,
                onExpand = { showState = true },
                onDismiss = { showState = false },
                modifier = Modifier.weight(1f),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reconfig_filter_all)) },
                    onClick = {
                        showState = false
                        onStateFilter(null)
                    },
                )
                ReconfigurationFilterState.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(stateFilterLabel(filter)) },
                        onClick = {
                            showState = false
                            onStateFilter(filter)
                        },
                    )
                }
            }

            FilterDropdown(
                label = stringResource(R.string.reconfig_sort),
                value = sortLabel(state.sort),
                expanded = showSort,
                onExpand = { showSort = true },
                onDismiss = { showSort = false },
                modifier = Modifier.weight(1f),
            ) {
                ReconfigurationSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(sort)) },
                        onClick = {
                            showSort = false
                            onSort(sort)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterDropdown(
    label: String,
    value: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = DamsTheme.texteDoux,
                    maxLines = 1,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painterResource(R.drawable.ic_expand_more_24dp),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            content()
        }
    }
}

@Composable
private fun ReconfigurationServerCard(
    server: ReconfiguredServer,
    selected: Boolean,
    busy: Boolean,
    animationsEnabled: Boolean,
    onToggleSelection: () -> Unit,
    onOpen: () -> Unit,
    onPing: () -> Unit,
    onTest: () -> Unit,
    onTransfer: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = DamsTheme
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                theme.accent.copy(alpha = if (animationsEnabled) 0.14f else 0.12f)
            } else {
                theme.surface.copy(alpha = theme.cardAlpha)
            },
        ),
        border = BorderStroke(1.dp, if (selected) theme.accent.copy(alpha = 0.55f) else theme.bordure),
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, top = 12.dp, end = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = theme.texteFort,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${server.protocol} · ${methodLabel(server.method)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.texteDoux,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier.padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.reconfig_detail_original_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.texteDoux,
                )
                Text(
                    "${server.originalAddress}:${server.originalPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.texteDoux,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.reconfig_detail_transformed_address),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.texteDoux,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "${server.transformedAddress}:${server.transformedPort}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = theme.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PingPill(server)
                TestPill(server)
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPing, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.reconfig_ping))
                }
                TextButton(onClick = onTest, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.reconfig_test))
                }
                TextButton(onClick = onTransfer, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.reconfig_add))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = !busy, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_delete_24dp),
                        contentDescription = stringResource(R.string.reconfig_delete),
                        tint = theme.erreur,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PingPill(server: ReconfiguredServer) {
    val theme = DamsTheme
    val (label, tone) = when (server.pingState) {
        ReconfigPingState.PENDING -> stringResource(R.string.reconfig_ping_pending) to theme.texteDoux
        ReconfigPingState.RUNNING -> stringResource(R.string.reconfig_ping_running) to theme.accent
        ReconfigPingState.SUCCESS -> stringResource(
            R.string.reconfig_ping_success, server.pingMs,
        ) to theme.succes
        ReconfigPingState.TIMEOUT -> stringResource(R.string.reconfig_ping_timeout) to theme.erreur
        ReconfigPingState.DNS_ERROR -> stringResource(R.string.reconfig_ping_dns) to theme.erreur
        ReconfigPingState.HOST_UNREACHABLE -> stringResource(R.string.reconfig_ping_unreachable) to theme.erreur
        ReconfigPingState.CONNECTION_ERROR -> stringResource(R.string.reconfig_ping_refused) to theme.erreur
        ReconfigPingState.ERROR -> stringResource(R.string.reconfig_ping_error) to theme.erreur
    }
    StatusPill(
        label = "${stringResource(R.string.reconfig_ping_only_tcp)} · $label",
        tone = tone,
    )
}

@Composable
private fun TestPill(server: ReconfiguredServer) {
    val theme = DamsTheme
    val (label, tone) = when (server.testState) {
        ReconfigTestState.PENDING -> stringResource(R.string.reconfig_test_pending) to theme.texteDoux
        ReconfigTestState.RUNNING -> stringResource(R.string.reconfig_test_running) to theme.accent
        ReconfigTestState.FUNCTIONAL -> stringResource(R.string.reconfig_test_functional) to theme.succes
        ReconfigTestState.UNREACHABLE -> stringResource(R.string.reconfig_test_unreachable) to theme.erreur
        ReconfigTestState.INVALID_CONFIG -> stringResource(R.string.reconfig_test_invalid) to theme.erreur
        ReconfigTestState.CORE_ERROR -> stringResource(R.string.reconfig_test_core_error) to theme.erreur
        ReconfigTestState.CANCELLED -> stringResource(R.string.reconfig_test_cancelled) to theme.avertissement
    }
    StatusPill(label = label, tone = tone)
}

@Composable
private fun StatusPill(label: String, tone: Color) {
    Surface(
        shape = CircleShape,
        color = tone.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.4f)),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = tone,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReconfigurationBottomActions(
    selectedCount: Int,
    busy: Boolean,
    onTest: () -> Unit,
    onTransfer: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = DamsTheme
    Surface(color = theme.fond.copy(alpha = theme.navigationSurfaceAlpha)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onTest,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(stringResource(R.string.reconfig_test_selection, selectedCount))
            }
            OutlinedButton(
                onClick = onTransfer,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text(stringResource(R.string.reconfig_add_selection, selectedCount))
            }
            TextButton(
                onClick = onDelete,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(40.dp),
            ) {
                Text(
                    stringResource(R.string.reconfig_delete_selection, selectedCount),
                    color = if (busy) theme.texteDoux else theme.erreur,
                )
            }
        }
    }
}

@Composable
private fun ReconfigurationDetailSheet(
    server: ReconfiguredServer,
    busy: Boolean,
    onClose: () -> Unit,
    onPing: () -> Unit,
    onTest: () -> Unit,
    onTransfer: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = DamsTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.reconfig_detail_title),
            style = MaterialTheme.typography.titleMedium,
            color = theme.texteFort,
        )
        Text(
            server.name,
            style = MaterialTheme.typography.bodySmall,
            color = theme.texteDoux,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        DetailRow(stringResource(R.string.reconfig_detail_protocol), server.protocol)
        DetailRow(stringResource(R.string.reconfig_detail_method), methodLabel(server.method))
        DetailRow(stringResource(R.string.reconfig_detail_target), server.target, monospace = true)
        DetailRow(
            stringResource(R.string.reconfig_detail_original_address),
            "${server.originalAddress}:${server.originalPort}",
            monospace = true,
        )
        DetailRow(
            stringResource(R.string.reconfig_detail_transformed_address),
            "${server.transformedAddress}:${server.transformedPort}",
            monospace = true,
            highlight = true,
        )
        DetailRow(stringResource(R.string.reconfig_detail_ping), pingText(server))
        DetailRow(stringResource(R.string.reconfig_detail_test), testText(server))
        DetailRow(
            stringResource(R.string.reconfig_detail_created),
            formatDate(server.createdAt),
        )
        DetailRow(
            stringResource(R.string.reconfig_detail_last_test),
            formatDate(server.lastTestAt),
        )

        Text(
            stringResource(R.string.reconfig_detail_note),
            style = MaterialTheme.typography.bodySmall,
            color = theme.texteDoux,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onPing, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reconfig_ping))
            }
            OutlinedButton(onClick = onTest, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reconfig_test))
            }
        }
        Button(onClick = onTransfer, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reconfig_add))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reconfig_close))
            }
            TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.reconfig_delete), color = theme.erreur)
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    highlight: Boolean = false,
) {
    val theme = DamsTheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.texteDoux,
            modifier = Modifier.width(132.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) theme.accent else theme.texteFort,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReconfigurationEmpty(textRes: Int, onReset: (() -> Unit)?) {
    val theme = DamsTheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_reconfig_24dp),
            contentDescription = null,
            tint = theme.texteDoux,
            modifier = Modifier.size(36.dp),
        )
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyMedium,
            color = theme.texteDoux,
        )
        if (onReset != null) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.reconfig_reset_filters), color = theme.accent)
            }
        }
    }
}

@Composable
private fun methodLabel(method: ReconfigMethod): String = stringResource(
    when (method) {
        ReconfigMethod.MAXIT_MOBILE -> R.string.reconfig_method_maxit
        ReconfigMethod.API_CONNECT -> R.string.reconfig_method_api_connect
        ReconfigMethod.API_GATEWAY -> R.string.reconfig_method_api_gateway
        ReconfigMethod.CUSTOM -> R.string.reconfig_method_custom
    },
)

@Composable
private fun stateFilterLabel(filter: ReconfigurationFilterState): String = stringResource(
    when (filter) {
        ReconfigurationFilterState.PENDING -> R.string.reconfig_pill_pending
        ReconfigurationFilterState.PING_OK -> R.string.reconfig_pill_ping_ok
        ReconfigurationFilterState.PING_FAILED -> R.string.reconfig_pill_ping_failed
        ReconfigurationFilterState.FUNCTIONAL -> R.string.reconfig_pill_functional
        ReconfigurationFilterState.NOT_FUNCTIONAL -> R.string.reconfig_pill_not_functional
        ReconfigurationFilterState.INVALID -> R.string.reconfig_pill_invalid
    },
)

@Composable
private fun sortLabel(sort: ReconfigurationSort): String = stringResource(
    when (sort) {
        ReconfigurationSort.NAME -> R.string.reconfig_sort_name
        ReconfigurationSort.PING -> R.string.reconfig_sort_ping
        ReconfigurationSort.DATE -> R.string.reconfig_sort_date
        ReconfigurationSort.STATE -> R.string.reconfig_sort_state
    },
)

@Composable
private fun pingText(server: ReconfiguredServer): String = when (server.pingState) {
    ReconfigPingState.PENDING -> stringResource(R.string.reconfig_ping_pending)
    ReconfigPingState.RUNNING -> stringResource(R.string.reconfig_ping_running)
    ReconfigPingState.SUCCESS -> stringResource(R.string.reconfig_ping_success, server.pingMs)
    ReconfigPingState.TIMEOUT -> stringResource(R.string.reconfig_ping_timeout)
    ReconfigPingState.DNS_ERROR -> stringResource(R.string.reconfig_ping_dns)
    ReconfigPingState.HOST_UNREACHABLE -> stringResource(R.string.reconfig_ping_unreachable)
    ReconfigPingState.CONNECTION_ERROR -> stringResource(R.string.reconfig_ping_refused)
    ReconfigPingState.ERROR -> stringResource(R.string.reconfig_ping_error)
}

@Composable
private fun testText(server: ReconfiguredServer): String = when (server.testState) {
    ReconfigTestState.PENDING -> stringResource(R.string.reconfig_test_pending)
    ReconfigTestState.RUNNING -> stringResource(R.string.reconfig_test_running)
    ReconfigTestState.FUNCTIONAL -> stringResource(R.string.reconfig_test_functional)
    ReconfigTestState.UNREACHABLE -> stringResource(R.string.reconfig_test_unreachable)
    ReconfigTestState.INVALID_CONFIG -> stringResource(R.string.reconfig_test_invalid)
    ReconfigTestState.CORE_ERROR -> stringResource(R.string.reconfig_test_core_error)
    ReconfigTestState.CANCELLED -> stringResource(R.string.reconfig_test_cancelled)
}

private fun formatDate(timestamp: Long): String =
    if (timestamp <= 0L) {
        "—"
    } else {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(Date(timestamp))
    }
