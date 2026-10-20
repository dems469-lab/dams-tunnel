package com.v2ray.ang.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.logcat.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    logcatViewModel: LogcatViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val canStopService = shouldStopServiceOnToggle(uiState.isRunning, uiState.status)
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val traffic = uiState.traffic

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(DamsTab.Home) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        showRemoveConfirm = ServerDeleteTarget(guid, profileName)
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    val selectedServersFlow = remember(uiState.selectedGroupId) {
        mainViewModel.serversForGroup(uiState.selectedGroupId)
    }
    val selectedServers by selectedServersFlow.collectAsStateWithLifecycle()
    val selectedServer = selectedServers.firstOrNull { it.guid == selectedGuid }
        ?: selectedServers.firstOrNull()

    LaunchedEffect(selectedTab) {
        if (selectedTab == DamsTab.Journal) logcatViewModel.loadLogcat()
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (selectedTab == DamsTab.Configs) {
                    MainTopBar(
                        isLoading = isLoading,
                        showSearch = showSearch,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { query: String ->
                            searchQuery = query
                            onAction(MainAction.Search(query))
                        },
                        onSearchClose = {
                            searchQuery = ""
                            onAction(MainAction.Search(""))
                            showSearch = false
                        },
                        onSearchToggle = { show: Boolean -> showSearch = show },
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onAction = onAction,
                        onMoreMenuAction = { action ->
                            when (action) {
                                MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                                MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                                MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                                MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                                MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                            }
                        }
                    )
                } else {
                    DamsHeader(
                        onMenuClick = { scope.launch { drawerState.open() } },
                        includeStatusBarInsets = selectedTab == DamsTab.Home,
                    )
                }
            },
            bottomBar = {
                DamsNavigationBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        if (tab == DamsTab.Settings) onNavigate(MainDestination.Settings)
                        else selectedTab = tab
                    },
                )
            },
            floatingActionButton = {},
        ) { innerPadding ->
            when (selectedTab) {
                DamsTab.Home -> DamsDashboard(
                    contentPadding = innerPadding,
                    displayText = displayText,
                    isRunning = isRunning,
                    canStopService = canStopService,
                    groupName = groups.firstOrNull { it.id == uiState.selectedGroupId }?.remarks,
                    selectedServerName = selectedServer?.profile?.remarks,
                    selectedServerAddress = selectedServer?.profile?.server,
                    protocolName = selectedServer?.profile?.configType?.protocolScheme?.uppercase(),
                    delayMillis = uiState.connectionDelayMillis ?: selectedServer?.testDelayMillis,
                    downloadBytesPerSecond = traffic.downlinkBytesPerSecond,
                    uploadBytesPerSecond = traffic.uplinkBytesPerSecond,
                    trafficHistory = uiState.trafficHistory,
                    diagnostic = uiState.diagnostic,
                    onOpenServers = { selectedTab = DamsTab.Configs },
                    onTestConnection = { onAction(MainAction.TestCurrentServer) },
                    onToggleConnection = { onAction(MainAction.ToggleService) },
                )

                DamsTab.Configs -> {
                    if (groups.isNotEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                        ) {
                            DamsStatusCard(displayText = displayText, isRunning = isRunning)
                            if (groups.size > 1) {
                                GroupTabBar(
                                    groups = groups,
                                    selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                                    mainViewModel = mainViewModel,
                                    onTabClick = { targetIndex ->
                                        scope.launch {
                                            pagerState.navigateToPageOptimized(
                                                targetPage = targetIndex,
                                                animateAdjacentPage = true
                                            )
                                        }
                                    }
                                )
                            }

                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                userScrollEnabled = true,
                                beyondViewportPageCount = 1,
                                key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                            ) { page ->
                                val group = groups.getOrNull(page) ?: return@HorizontalPager
                                GroupPagerPage(
                                    groupId = group.id,
                                    mainViewModel = mainViewModel,
                                    selectedGuid = selectedGuid,
                                    locateTarget = uiState.locateTarget,
                                    doubleColumnDisplay = doubleColumnDisplay,
                                    searchQuery = searchQuery,
                                    lazyListStates = lazyListStates,
                                    lazyGridStates = lazyGridStates,
                                    onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                    onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                                    onShareServer = { guid, profile -> shareTarget = Triple(guid, profile, false) },
                                    onMoreServer = { guid, profile -> shareTarget = Triple(guid, profile, true) },
                                    onRemoveServer = removeServer,
                                    contentPadding = PaddingValues(start = 0.dp, top = 0.dp, end = 0.dp, bottom = 16.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(stringResource(R.string.dams_no_server_selected), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.dams_select_server_prompt),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                            )
                            Button(onClick = { onAction(MainAction.ImportConfigLocal) }) {
                                Text(stringResource(R.string.menu_item_add_config))
                            }
                        }
                    }
                }

                DamsTab.Journal -> JournalTabContent(
                    contentPadding = innerPadding,
                    logcatViewModel = logcatViewModel,
                )

                DamsTab.Settings -> Unit
            }
        }
    }
}

private enum class DamsTab {
    Home, Configs, Journal, Settings
}

@Composable
private fun DamsNavigationBar(selected: DamsTab, onSelect: (DamsTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(DamsTab.Home, R.drawable.ic_home_24dp, R.string.dams_tab_home),
            Triple(DamsTab.Configs, R.drawable.ic_subscriptions_24dp, R.string.dams_tab_configs),
            Triple(DamsTab.Journal, R.drawable.ic_logcat_24dp, R.string.dams_tab_journal),
            Triple(DamsTab.Settings, R.drawable.ic_settings_24dp, R.string.title_settings),
        ).forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(icon), contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun DamsHeader(onMenuClick: () -> Unit, includeStatusBarInsets: Boolean) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (includeStatusBarInsets) it.statusBarsPadding() else it }
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.app_name).uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
                Text(
                    text = stringResource(R.string.dams_version_format, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu_24dp),
                    contentDescription = stringResource(R.string.acc_open_menu),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun DamsDashboard(
    contentPadding: PaddingValues,
    displayText: String,
    isRunning: Boolean,
    canStopService: Boolean,
    groupName: String?,
    selectedServerName: String?,
    selectedServerAddress: String?,
    protocolName: String?,
    delayMillis: Long?,
    downloadBytesPerSecond: Long,
    uploadBytesPerSecond: Long,
    trafficHistory: List<com.v2ray.ang.dto.TrafficSnapshot>,
    diagnostic: ConnectionDiagnostic?,
    onOpenServers: () -> Unit,
    onTestConnection: () -> Unit,
    onToggleConnection: () -> Unit,
) {
    val powerActionLabel = stringResource(if (canStopService) R.string.acc_stop else R.string.acc_start)
    val serverSummary = if (selectedServerAddress.isNullOrBlank()) {
        stringResource(R.string.dams_select_server_prompt)
    } else {
        // The target address is sensitive metadata.  It must not be exposed on the
        // dashboard or accidentally captured in a screenshot.
        stringResource(R.string.dams_connection_details_hidden)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.dams_slogan),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .size(204.dp)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), CircleShape)
                ) {}
            }
            Surface(
                modifier = Modifier
                    .size(188.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isRunning) 2.dp else 1.dp,
                        color = if (isRunning) MaterialTheme.colorScheme.secondary else Color(0xFF1E4A8A),
                        shape = CircleShape,
                    )
                    .clickable(role = Role.Button, onClick = onToggleConnection)
                    .semantics {
                        contentDescription = powerActionLabel
                        stateDescription = displayText
                    },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = CircleShape,
                tonalElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_power_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = if (isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(14.dp)
                        .background(
                            if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Column {
                    Text(
                        text = displayText.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isRunning) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(if (isRunning) R.string.dams_privacy_protected else R.string.dams_ready_to_connect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenServers),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (selectedServerAddress.isNullOrBlank()) {
                            selectedServerName ?: groupName ?: stringResource(R.string.dams_no_server_selected)
                        } else {
                            stringResource(R.string.dams_active_configuration)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = serverSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val latency = when {
                delayMillis == null || delayMillis == 0L -> "—"
                delayMillis < 0 -> stringResource(R.string.dams_metric_unavailable)
                else -> "$delayMillis ms"
            }
            if (maxWidth < 360.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        DashboardMetric(Modifier.weight(1f), stringResource(R.string.dams_metric_latency), latency, onTestConnection)
                        DashboardMetric(Modifier.weight(1f), stringResource(R.string.dams_metric_download), if (isRunning) downloadBytesPerSecond.toSpeedString() else "—")
                    }
                    DashboardMetric(Modifier.fillMaxWidth(), stringResource(R.string.dams_metric_upload), if (isRunning) uploadBytesPerSecond.toSpeedString() else "—")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DashboardMetric(Modifier.weight(1f), stringResource(R.string.dams_metric_latency), latency, onTestConnection)
                    DashboardMetric(Modifier.weight(1f), stringResource(R.string.dams_metric_download), if (isRunning) downloadBytesPerSecond.toSpeedString() else "—")
                    DashboardMetric(Modifier.weight(1f), stringResource(R.string.dams_metric_upload), if (isRunning) uploadBytesPerSecond.toSpeedString() else "—")
                }
            }
        }
        if (isRunning && trafficHistory.size > 1) {
            val graphColor = MaterialTheme.colorScheme.secondary
            val uploadGraphColor = MaterialTheme.colorScheme.primary
            Card(
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                    val max = trafficHistory.maxOf { maxOf(it.uplinkBytesPerSecond, it.downlinkBytesPerSecond) }
                        .coerceAtLeast(1L).toFloat()
                    val downlinkPath = Path()
                    val uplinkPath = Path()
                    trafficHistory.forEachIndexed { index, point ->
                        val x = size.width * index / (trafficHistory.size - 1).toFloat()
                        val downlinkY = size.height * (1f - point.downlinkBytesPerSecond / max)
                        val uplinkY = size.height * (1f - point.uplinkBytesPerSecond / max)
                        if (index == 0) {
                            downlinkPath.moveTo(x, downlinkY)
                            uplinkPath.moveTo(x, uplinkY)
                        } else {
                            downlinkPath.lineTo(x, downlinkY)
                            uplinkPath.lineTo(x, uplinkY)
                        }
                    }
                    drawPath(
                        downlinkPath,
                        graphColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                    drawPath(
                        uplinkPath,
                        uploadGraphColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                    )
                }
            }
        }
        diagnostic?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DiagnosticRow(stringResource(R.string.diagnostic_network), result.networkAvailable)
                    DiagnosticRow(stringResource(R.string.diagnostic_server), result.serverReachable)
                    DiagnosticRow(stringResource(R.string.diagnostic_tunnel), result.tunnelVerified)
                    result.pingMillis?.let { ping ->
                        Text("Ping : $ping ms", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: Boolean?) {
    val symbol = when (value) {
        true -> "✅"
        false -> "❌"
        null -> "…"
    }
    Text("$symbol  $label", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DashboardMetric(
    modifier: Modifier,
    title: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.height(96.dp).then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun JournalTabContent(contentPadding: PaddingValues, logcatViewModel: LogcatViewModel) {
    val logEntries by logcatViewModel.logEntries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = { logcatViewModel.loadLogcat() }) {
                Icon(painterResource(R.drawable.ic_restore_24dp), contentDescription = stringResource(R.string.acc_refresh))
            }
            IconButton(onClick = { logcatViewModel.copyLogcat() }) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.acc_copy_log))
            }
            IconButton(onClick = { scope.launch(Dispatchers.IO) { logcatViewModel.clearLogcat() } }) {
                Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_clear_log))
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(items = logEntries, key = { it.key }) { entry ->
                Text(
                    text = entry.text,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun DamsStatusCard(
    displayText: String,
    isRunning: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(com.v2ray.ang.R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(58.dp)
            )
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = stringResource(com.v2ray.ang.R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
