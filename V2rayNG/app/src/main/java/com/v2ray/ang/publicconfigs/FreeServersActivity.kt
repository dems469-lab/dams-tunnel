package com.v2ray.ang.publicconfigs

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.DamsTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun hasMeasuredFreeServerLatency(delayMillis: Long): Boolean = delayMillis > 0L

class FreeServersActivity : BaseComponentActivity() {
    private val viewModel: FreeServersViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        FreeServersScreen(
            state = state,
            onBack = ::finish,
            onFiltersChanged = viewModel::updateFilters,
            onToggleSelection = viewModel::toggleSelection,
            onTest = viewModel::testSelected,
            onTestServer = viewModel::testServer,
            onClearSelection = viewModel::clearSelection,
            onCancelTest = viewModel::cancelTest,
            onRefresh = {
                lifecycleScope.launch {
                    try {
                        val count = viewModel.refreshSources()
                        toastSuccess(getString(R.string.free_servers_refresh_success, count))
                    } catch (_: Exception) {
                        toastError(R.string.free_servers_refresh_error)
                    }
                }
            },
            onRetry = viewModel::load,
            onSelect = { guid ->
                MmkvManager.setSelectServer(guid)
                SettingsChangeManager.makeSetupGroupTab()
                toastSuccess(R.string.free_servers_selected)
            },
            onAddToMyServers = { guid ->
                lifecycleScope.launch {
                    if (viewModel.addToMyServers(guid) != null) {
                        toastSuccess(R.string.free_servers_added_to_mine)
                    } else {
                        toastError(R.string.free_servers_add_error)
                    }
                }
            },
            onDeleteProvider = ::deleteProvider,
        )
    }

    private fun deleteProvider(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            MmkvManager.removeSubscription(id)
            SettingsChangeManager.makeSetupGroupTab()
            runOnUiThread { toastSuccess(R.string.free_servers_delete) }
            viewModel.load()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeServersScreen(
    state: FreeServersUiState,
    onBack: () -> Unit,
    onFiltersChanged: (PublicServerFilters) -> Unit,
    onToggleSelection: (String) -> Unit,
    onTest: () -> Unit,
    onTestServer: (String) -> Unit,
    onClearSelection: () -> Unit,
    onCancelTest: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onAddToMyServers: (String) -> Unit,
    onDeleteProvider: (String) -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<PublicServerGroup?>(null) }
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var draftFilters by remember(state.filters) { mutableStateOf(state.filters) }
    val filters = state.filters
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val theme = DamsTheme
    val activeFilterCount = activePublicFilterCount(filters)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.free_servers_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.free_servers_count, state.catalog.totalProfiles, state.catalog.groups.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.acc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.fond.copy(alpha = theme.headerSurfaceAlpha),
                    titleContentColor = theme.texteFort,
                    navigationIconContentColor = theme.texteFort,
                ),
            )
        },
        bottomBar = {
            FreeServersBottomActions(
                selectedCount = state.selectedGuids.size,
                refreshing = state.refreshing,
                testing = state.testing,
                animationsEnabled = theme.animationsEnabled && isResumed,
                onTest = if (state.testing) onCancelTest else onTest,
                onRefresh = onRefresh,
            )
        },
        containerColor = theme.fond,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "free-servers-controls") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WarningBanner()
                    FreeServersSearch(
                        query = filters.query,
                        onQueryChange = { onFiltersChanged(filters.copy(query = it)) },
                    )
                    ProtocolChips(
                        filters = filters,
                        counts = state.catalog.protocolCounts,
                        totalCount = state.catalog.totalProfiles,
                        onSelected = { onFiltersChanged(filters.copy(protocol = it)) },
                        animationsEnabled = theme.animationsEnabled && isResumed,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                draftFilters = filters
                                showFilters = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(painterResource(R.drawable.ic_outline_filter_alt_24), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.free_servers_action_filters))
                            if (activeFilterCount > 0) {
                                Spacer(Modifier.width(6.dp))
                                Surface(shape = CircleShape, color = theme.accent.copy(alpha = 0.2f)) {
                                    Text(activeFilterCount.toString(), modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = theme.accent, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showSort = true },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            ) {
                                Icon(painterResource(android.R.drawable.ic_menu_sort_by_size), contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.free_servers_sort_action, sortLabel(filters.sort)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                                PublicSort.entries.forEach { sort ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(sort)) },
                                        onClick = {
                                            showSort = false
                                            onFiltersChanged(filters.copy(sort = sort))
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (state.selectedGuids.isNotEmpty() && !state.testing) {
                        TextButton(onClick = onClearSelection, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(stringResource(R.string.free_servers_clear_selection, state.selectedGuids.size))
                        }
                    }
                    state.testStatus?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = theme.accent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            when {
                state.loading -> itemsIndexed((0..3).toList(), key = { index, _ -> "loading-$index" }) { _, _ -> LoadingServerCard(isResumed && theme.animationsEnabled) }
                state.error -> item(key = "free-servers-load-error") { LoadError(onRetry, isResumed && theme.animationsEnabled) }
                state.catalog.groups.isEmpty() -> item(key = "free-servers-empty") {
                    EmptyServers(
                        hasActiveFilters = filters.query.isNotBlank() || filters.protocol != null || activeFilterCount > 0,
                        onReset = { onFiltersChanged(PublicServerFilters()) },
                    )
                }
                else -> itemsIndexed(state.catalog.groups, key = { _, group -> group.id }) { index, group ->
                    PublicServerGroupCard(
                        group = group,
                        selectedGuids = state.selectedGuids,
                        isExpanded = group.id in expanded,
                        index = index,
                        animationsEnabled = theme.animationsEnabled && isResumed,
                        onExpand = { expanded = if (group.id in expanded) expanded - group.id else expanded + group.id },
                        onToggleSelection = onToggleSelection,
                        onTestServer = onTestServer,
                        onSelect = onSelect,
                        onAddToMyServers = onAddToMyServers,
                        onDelete = { deleteTarget = group },
                    )
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = theme.surface,
        ) {
            FreeServerFilterSheet(
                filters = draftFilters,
                onChange = { draftFilters = it },
                onReset = { draftFilters = draftFilters.copy(completeness = null, availability = null, verification = null) },
                onApply = {
                    onFiltersChanged(draftFilters)
                    showFilters = false
                },
            )
        }
    }

    deleteTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.free_servers_remove_provider)) },
            text = { Text(stringResource(R.string.free_servers_remove_provider_message, group.variants.first().sourceName)) },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDeleteProvider(group.variants.first().sourceId) }) {
                    Text(stringResource(R.string.free_servers_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.free_servers_cancel)) } },
        )
    }
}

@Composable
private fun WarningBanner() {
    val theme = DamsTheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = theme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, theme.avertissement.copy(alpha = 0.55f)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(android.R.drawable.ic_dialog_alert), contentDescription = null, tint = theme.avertissement, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.free_servers_description), color = theme.avertissement, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun FreeServersSearch(query: String, onQueryChange: (String) -> Unit) {
    val theme = DamsTheme
    val searchDescription = stringResource(R.string.free_servers_search)
    val selectionColors = TextSelectionColors(handleColor = theme.accent, backgroundColor = theme.accent.copy(alpha = 0.28f))
    androidx.compose.runtime.CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(theme.surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null, tint = theme.texteDoux, modifier = Modifier.padding(start = 14.dp).size(22.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    .semantics { contentDescription = searchDescription },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.texteFort),
                cursorBrush = SolidColor(theme.accent),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) Text(stringResource(R.string.free_servers_search), color = theme.texteDoux, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun ProtocolChips(
    filters: PublicServerFilters,
    counts: Map<PublicProtocol, Int>,
    totalCount: Int,
    onSelected: (PublicProtocol?) -> Unit,
    animationsEnabled: Boolean,
) {
    val theme = DamsTheme
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val protocols = listOf(null) + PublicProtocol.entries
        protocols.forEach { protocol ->
            val selected = filters.protocol == protocol
            val containerTarget = if (selected) theme.accent.copy(alpha = 0.20f) else theme.surface
            val container = if (animationsEnabled) {
                animateColorAsState(
                    targetValue = containerTarget,
                    animationSpec = tween(theme.chipTransitionMillis),
                    label = "protocolChipColor",
                ).value
            } else containerTarget
            FilterChip(
                selected = selected,
                onClick = { onSelected(protocol) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (protocol == null) stringResource(R.string.free_servers_all) else protocolLabel(protocol))
                        Spacer(Modifier.width(5.dp))
                        Text((if (protocol == null) totalCount else counts[protocol] ?: 0).toString(), color = if (selected) theme.accent else theme.texteDoux)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = container,
                    selectedLabelColor = theme.accent,
                    labelColor = theme.texteFort,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = theme.bordure,
                    selectedBorderColor = theme.accent,
                ),
            )
        }
    }
}

@Composable
private fun FreeServerFilterSheet(
    filters: PublicServerFilters,
    onChange: (PublicServerFilters) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.free_servers_action_filters), style = MaterialTheme.typography.titleLarge, color = DamsTheme.texteFort)
        FilterSection(
            title = stringResource(R.string.free_servers_filter_completeness),
            values = listOf(
                null to stringResource(R.string.free_servers_filter_all),
                PublicCompleteness.COMPLETE to stringResource(R.string.free_servers_filter_complete),
                PublicCompleteness.PARTIAL to stringResource(R.string.free_servers_filter_partial),
                PublicCompleteness.INVALID to stringResource(R.string.free_servers_filter_invalid),
                PublicCompleteness.UNVERIFIED to stringResource(R.string.config_status_unverified),
            ),
            selected = filters.completeness,
            onSelect = { onChange(filters.copy(completeness = it)) },
        )
        FilterSection(
            title = stringResource(R.string.free_servers_filter_availability),
            values = listOf(
                null to stringResource(R.string.free_servers_filter_all),
                PublicAvailability.RESPONDED to stringResource(R.string.free_servers_filter_responded),
                PublicAvailability.NO_RESPONSE to stringResource(R.string.free_servers_filter_no_response),
                PublicAvailability.UNKNOWN to stringResource(R.string.free_servers_filter_unknown),
            ),
            selected = filters.availability,
            onSelect = { onChange(filters.copy(availability = it)) },
        )
        FilterSection(
            title = stringResource(R.string.free_servers_filter_verification),
            values = listOf(
                null to stringResource(R.string.free_servers_filter_all),
                PublicVerification.TESTED to stringResource(R.string.free_servers_filter_tested),
                PublicVerification.UNTESTED to stringResource(R.string.free_servers_filter_untested),
            ),
            selected = filters.verification,
            onSelect = { onChange(filters.copy(verification = it)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onReset, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.free_servers_reset)) }
            Button(onClick = onApply, modifier = Modifier.weight(1f).height(48.dp)) { Text(stringResource(R.string.free_servers_apply)) }
        }
    }
}

@Composable
private fun <T> FilterSection(title: String, values: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = DamsTheme.texteFort)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (value, label) ->
                FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun FreeServersBottomActions(
    selectedCount: Int,
    refreshing: Boolean,
    testing: Boolean,
    animationsEnabled: Boolean,
    onTest: () -> Unit,
    onRefresh: () -> Unit,
) {
    val theme = DamsTheme
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.fillMaxWidth()
            .animateContentSize(animationSpec = if (animationsEnabled) spring(stiffness = Spring.StiffnessMediumLow) else tween(0))
            .background(Brush.verticalGradient(listOf(theme.fond.copy(alpha = 0f), theme.fond, theme.fond)))
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp + bottomInset)
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = selectedCount > 0 || testing,
            modifier = Modifier.weight(1f).height(56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(if (testing) stringResource(R.string.free_servers_cancel_test) else stringResource(R.string.free_servers_test_selected, selectedCount), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.weight(1f).height(56.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Icon(painterResource(R.drawable.ic_restore_24dp), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(if (refreshing) R.string.free_servers_refreshing else R.string.free_servers_refresh_short), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoadingServerCard(animate: Boolean) {
    val theme = DamsTheme
    val shimmerAlpha = if (animate && theme.animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "freeServerLoadingShimmer")
        transition.animateFloat(
            initialValue = 0.12f,
            targetValue = 0.30f,
            animationSpec = infiniteRepeatable(tween(theme.shimmerMillis), repeatMode = RepeatMode.Reverse),
            label = "freeServerLoadingAlpha",
        ).value
    } else 0.18f
    Card(
        modifier = Modifier.fillMaxWidth().height(154.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surface),
        border = BorderStroke(1.dp, theme.bordure),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBar(Modifier.fillMaxWidth(0.62f).height(22.dp), shimmerAlpha)
            SkeletonBar(Modifier.fillMaxWidth(0.82f).height(16.dp), shimmerAlpha)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBar(Modifier.width(110.dp).height(28.dp), shimmerAlpha)
                SkeletonBar(Modifier.width(105.dp).height(28.dp), shimmerAlpha)
            }
            SkeletonBar(Modifier.fillMaxWidth(0.50f).height(18.dp), shimmerAlpha)
        }
    }
}

@Composable
private fun SkeletonBar(modifier: Modifier, alpha: Float) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(DamsTheme.accent.copy(alpha = alpha)))
}

@Composable
private fun EmptyServers(hasActiveFilters: Boolean, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null, tint = DamsTheme.texteDoux, modifier = Modifier.size(38.dp))
        Text(
            stringResource(if (hasActiveFilters) R.string.free_servers_no_results else R.string.free_servers_empty),
            color = DamsTheme.texteDoux,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (hasActiveFilters) OutlinedButton(onClick = onReset) { Text(stringResource(R.string.free_servers_reset_filters)) }
    }
}

@Composable
private fun LoadError(onRetry: () -> Unit, animate: Boolean) {
    val offset = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (animate) {
            offset.animateTo(-4f, tween(45))
            offset.animateTo(4f, tween(70))
            offset.animateTo(-2f, tween(60))
            offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        } else offset.snapTo(0f)
    }
    Card(
        modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = if (animate) offset.value else 0f },
        colors = CardDefaults.cardColors(containerColor = DamsTheme.surface),
        border = BorderStroke(1.dp, DamsTheme.erreur.copy(alpha = if (animate) 0.85f else 0.55f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(android.R.drawable.ic_dialog_alert), contentDescription = null, tint = DamsTheme.erreur, modifier = Modifier.size(30.dp))
            Text(stringResource(R.string.free_servers_load_error), color = DamsTheme.erreur, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRetry) { Text(stringResource(R.string.free_servers_retry)) }
        }
    }
}

@Composable
private fun PublicServerGroupCard(
    group: PublicServerGroup,
    selectedGuids: Set<String>,
    isExpanded: Boolean,
    index: Int,
    animationsEnabled: Boolean,
    onExpand: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onTestServer: (String) -> Unit,
    onSelect: (String) -> Unit,
    onAddToMyServers: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val first = group.variants.first()
    val selectionDescription = stringResource(R.string.free_servers_select_server, first.name)
    val interaction = remember(group.id) { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && animationsEnabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "publicServerCardPress",
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed && animationsEnabled) DamsTheme.accent else DamsTheme.bordure,
        animationSpec = if (animationsEnabled) spring(stiffness = Spring.StiffnessMediumLow) else tween(0),
        label = "publicServerCardBorder",
    )
    var entered by remember(group.id) { mutableStateOf(!animationsEnabled || index >= 8) }
    LaunchedEffect(group.id, index, animationsEnabled) {
        if (animationsEnabled && index < 8) {
            delay(index * 60L)
            entered = true
        } else entered = true
    }
    AnimatedVisibility(
        visible = entered,
        enter = if (animationsEnabled) fadeIn(tween(220)) + slideInVertically(tween(220)) { 16.dp.roundToPx() } else fadeIn(tween(0)),
    ) {
        Card(
            onClick = onExpand,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().scale(if (animationsEnabled) scale else 1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = DamsTheme.surface.copy(alpha = DamsTheme.cardAlpha)),
            border = BorderStroke(if (pressed) 1.5.dp else 1.dp, borderColor),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = first.guid in selectedGuids,
                        onCheckedChange = { onToggleSelection(first.guid) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = selectionDescription },
                    )
                    Text(first.name.ifBlank { first.details.address }, modifier = Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = DamsTheme.texteFort)
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = DamsTheme.accent.copy(alpha = 0.16f)) {
                        Text(protocolLabel(first.protocol), modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = DamsTheme.accent, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                Text(
                    stringResource(R.string.free_servers_source_type, first.sourceName, first.details.transport.ifBlank { protocolLabel(first.protocol) }),
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = {},
                        onLongClickLabel = stringResource(R.string.free_servers_remove_provider_action),
                        onLongClick = onDelete,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DamsTheme.texteDoux,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        label = stringResource(when (first.assessment.completeness) {
                            PublicCompleteness.COMPLETE -> R.string.free_servers_filter_complete
                            PublicCompleteness.PARTIAL -> R.string.free_servers_filter_partial
                            PublicCompleteness.INVALID -> R.string.free_servers_filter_invalid
                            PublicCompleteness.UNVERIFIED -> R.string.config_status_unverified
                        }),
                        tone = publicCompletenessTone(first.assessment.completeness),
                    )
                    val testLabel = when {
                        group.bestMeasuredLatency != null -> "${group.bestMeasuredLatency} ms"
                        group.availability == PublicAvailability.NO_RESPONSE -> stringResource(R.string.free_servers_no_response)
                        group.variants.all { it.verification == PublicVerification.UNTESTED } -> stringResource(R.string.free_servers_not_tested)
                        else -> stringResource(R.string.free_servers_filter_unknown)
                    }
                    StatusPill(label = testLabel, tone = if (group.bestMeasuredLatency != null) PublicStatusTone.SUCCESS else PublicStatusTone.NEUTRAL)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.free_servers_group_variants_short, group.variants.size),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DamsTheme.texteDoux,
                    )
                    IconButton(onClick = onExpand) {
                        Icon(painterResource(R.drawable.ic_about_24dp), contentDescription = stringResource(if (isExpanded) R.string.free_servers_collapse else R.string.free_servers_details), tint = DamsTheme.accent)
                    }
                    Button(onClick = { onSelect(first.guid) }, contentPadding = PaddingValues(horizontal = 18.dp)) {
                        Text(stringResource(R.string.free_servers_use))
                    }
                }
                androidx.compose.animation.AnimatedVisibility(visible = isExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        group.variants.forEach { variant ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${variant.sourceName} · ${variant.name}", style = MaterialTheme.typography.labelLarge, color = DamsTheme.texteFort)
                                Text(
                                    if (variant.sourceUpdatedAt > 0L) stringResource(
                                        R.string.free_servers_updated_at,
                                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(variant.sourceUpdatedAt)),
                                    ) else stringResource(R.string.free_servers_updated_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DamsTheme.texteDoux,
                                )
                                Details(variant.details)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (variant.guid != first.guid) TextButton(onClick = { onSelect(variant.guid) }) { Text(stringResource(R.string.free_servers_use)) }
                                    TextButton(onClick = { onTestServer(variant.guid) }) { Text(stringResource(R.string.free_servers_test)) }
                                    TextButton(onClick = { onAddToMyServers(variant.guid) }) { Text(stringResource(R.string.free_servers_add_to_mine)) }
                                }
                            }
                        }
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.free_servers_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, tone: PublicStatusTone) {
    val color = when (tone) {
        PublicStatusTone.SUCCESS -> DamsTheme.succes
        PublicStatusTone.WARNING -> DamsTheme.avertissement
        PublicStatusTone.ERROR -> DamsTheme.erreur
        PublicStatusTone.NEUTRAL -> DamsTheme.texteDoux
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun Details(details: PublicServerDetails) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.free_servers_field_address) + ": " + details.address)
        Text(stringResource(R.string.free_servers_field_port) + ": " + details.port)
        Text(stringResource(R.string.free_servers_field_transport) + ": " + details.transport)
        if (details.security.isNotBlank()) Text(stringResource(R.string.free_servers_field_security) + ": " + details.security)
        if (details.path.isNotBlank()) Text(stringResource(R.string.free_servers_field_path) + ": " + details.path)
        if (details.serverName.isNotBlank()) Text(stringResource(R.string.free_servers_field_sni) + ": " + details.serverName)
        if (details.headerHost.isNotBlank()) Text(stringResource(R.string.free_servers_field_host) + ": " + details.headerHost)
        if (details.serviceName.isNotBlank()) Text(stringResource(R.string.free_servers_field_service) + ": " + details.serviceName)
        if (details.authenticationPresent) Text(stringResource(R.string.free_servers_field_secret))
        if (details.publicKeyPresent) Text(stringResource(R.string.free_servers_field_public_key))
    }
}

private fun protocolLabel(protocol: PublicProtocol): String = when (protocol) {
    PublicProtocol.VLESS -> "VLESS"
    PublicProtocol.VMESS -> "VMess"
    PublicProtocol.TROJAN -> "Trojan"
    PublicProtocol.SHADOWSOCKS -> "Shadowsocks"
    PublicProtocol.SOCKS -> "SOCKS"
    PublicProtocol.HTTP -> "HTTP"
    PublicProtocol.HYSTERIA -> "Hysteria"
    PublicProtocol.HYSTERIA2 -> "Hysteria2"
    PublicProtocol.WIREGUARD -> "WireGuard"
    PublicProtocol.CUSTOM -> "Custom"
}

@Composable
private fun sortLabel(sort: PublicSort): String = stringResource(when (sort) {
    PublicSort.COMPLETENESS -> R.string.free_servers_sort_name_completeness
    PublicSort.LATENCY -> R.string.free_servers_sort_name_latency
    PublicSort.SOURCE_UPDATED -> R.string.free_servers_sort_name_updated
})
