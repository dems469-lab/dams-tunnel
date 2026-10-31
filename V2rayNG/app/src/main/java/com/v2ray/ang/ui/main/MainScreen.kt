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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.util.PerformanceDiagnostics
import com.v2ray.ang.handler.MmkvManager
import android.os.SystemClock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    showPremium: Boolean,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val performanceDiagnosticsEnabled by PerformanceDiagnostics.enabledFlow.collectAsStateWithLifecycle()
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val isConnected = uiState.status == MainStatus.Connected
    val canStopService = shouldStopServiceOnToggle(uiState.isRunning, uiState.status)
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val traffic = uiState.traffic
    var autoSwitchDefaultServer by MmkvManager.rememberMmkvBool(
        AppConfig.PREF_AUTO_SWITCH_DEFAULT_SERVER,
        false,
    )

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

    LaunchedEffect(selectedTab, performanceDiagnosticsEnabled, lifecycleOwner) {
        if (performanceDiagnosticsEnabled) {
            val tabName = when (selectedTab) {
                DamsTab.Home -> "Accueil"
                DamsTab.Configs -> "Configs"
                DamsTab.IpFinder -> "IP Finder"
                DamsTab.Settings -> "Paramètres"
            }
            val startedAt = SystemClock.elapsedRealtime()
            withFrameNanos { }
            PerformanceDiagnostics.recordMainTabFirstDraw(
                tabName,
                SystemClock.elapsedRealtime() - startedAt,
            )
        }
        if (selectedTab == DamsTab.Home && performanceDiagnosticsEnabled) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    kotlinx.coroutines.delay(1_000L)
                    PerformanceDiagnostics.recordHomeRecompositionsPerSecond()
                }
            }
        }
    }
    SideEffect {
        if (selectedTab == DamsTab.Home) PerformanceDiagnostics.countHomeRecomposition()
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
                showPremium = showPremium,
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
                                MainMoreMenuAction.Journal -> onNavigate(MainDestination.Logcat)
                                MainMoreMenuAction.ConnectionSharing -> onNavigate(MainDestination.ConnectionSharing)
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
                        onOpenJournal = { onNavigate(MainDestination.Logcat) },
                    )
                }
            },
            bottomBar = {
                DamsNavigationBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        when (tab) {
                            DamsTab.Settings -> onNavigate(MainDestination.Settings)
                            DamsTab.IpFinder -> onNavigate(MainDestination.IpFinder)
                            else -> selectedTab = tab
                        }
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
                    isConnected = isConnected,
                    canStopService = canStopService,
                    groupName = groups.firstOrNull { it.id == uiState.selectedGroupId }
                        ?.let { groupDisplayName(it) },
                    selectedServerName = selectedServer?.profile?.remarks,
                    selectedServerAddress = selectedServer?.profile?.server,
                    protocolName = selectedServer?.profile?.configType?.protocolScheme?.uppercase(),
                    // L'accueil n'affiche que le ping de la session active, jamais
                    // le dernier résultat de test enregistré dans le profil.
                    delayMillis = homeSessionPing(uiState.isRunning, uiState.connectionDelayMillis),
                    downloadBytesPerSecond = traffic.downlinkBytesPerSecond,
                    uploadBytesPerSecond = traffic.uplinkBytesPerSecond,
                    trafficAvailable = traffic.available,
                    mobileDailyUplinkBytes = traffic.mobileDailyUplinkBytes,
                    mobileDailyDownlinkBytes = traffic.mobileDailyDownlinkBytes,
                    trafficHistory = uiState.trafficHistory,
                    mobileIp = uiState.mobileIp,
                    diagnostic = uiState.diagnostic,
                    connectionSubtitle = stringResource(connectionSubtitleRes(uiState.status, uiState.perAppProxyEnabled)),
                    autoSwitchDefaultServer = autoSwitchDefaultServer,
                    onAutoSwitchDefaultServerChange = { autoSwitchDefaultServer = it },
                    onOpenServers = { selectedTab = DamsTab.Configs },
                    onOpenIpFinder = { onNavigate(MainDestination.IpFinder) },
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

                DamsTab.IpFinder -> Unit

                DamsTab.Settings -> Unit
            }
        }
    }
}

private enum class DamsTab {
    Home, Configs, IpFinder, Settings
}

@Composable
private fun DamsNavigationBar(selected: DamsTab, onSelect: (DamsTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        listOf(
            Triple(DamsTab.Home, R.drawable.ic_home_24dp, R.string.dams_tab_home),
            Triple(DamsTab.Configs, R.drawable.ic_subscriptions_24dp, R.string.dams_tab_configs),
            Triple(DamsTab.IpFinder, R.drawable.ic_ip_finder_24dp, R.string.dams_tab_ip_finder),
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
private fun DamsHeader(onMenuClick: () -> Unit, onOpenJournal: () -> Unit) {
    var showMoreMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { showMoreMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert_24dp),
                        contentDescription = stringResource(R.string.acc_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.title_logcat)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_logcat_24dp),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMoreMenu = false
                            onOpenJournal()
                        },
                    )
                }
            }
            IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_menu_24dp),
                    contentDescription = stringResource(R.string.acc_open_menu),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
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
        }
    }
}

@Composable
private fun DamsDashboard(
    contentPadding: PaddingValues,
    displayText: String,
    isRunning: Boolean,
    isConnected: Boolean,
    canStopService: Boolean,
    groupName: String?,
    selectedServerName: String?,
    selectedServerAddress: String?,
    protocolName: String?,
    delayMillis: Long?,
    downloadBytesPerSecond: Long,
    uploadBytesPerSecond: Long,
    trafficAvailable: Boolean,
    mobileDailyUplinkBytes: Long,
    mobileDailyDownlinkBytes: Long,
    trafficHistory: List<com.v2ray.ang.dto.TrafficSnapshot>,
    mobileIp: String?,
    diagnostic: ConnectionDiagnostic?,
    connectionSubtitle: String,
    autoSwitchDefaultServer: Boolean,
    onAutoSwitchDefaultServerChange: (Boolean) -> Unit,
    onOpenServers: () -> Unit,
    onOpenIpFinder: () -> Unit,
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
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val ringAlpha: Float
    val ringScale: Float
    if (resumed && isConnected) {
        val transition = rememberInfiniteTransition(label = "connectedPulse")
        ringAlpha = transition.animateFloat(
            initialValue = 0.32f,
            targetValue = 0.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ringAlpha",
        ).value
        ringScale = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ringScale",
        ).value
    } else {
        ringAlpha = 0.14f
        ringScale = 1f
    }
    val borderColor = if (isConnected) Color(0xFF22C55E) else Color(0xFF1E4A8A)
    val borderWidth by animateDpAsState(
        targetValue = if (isConnected) 2.dp else 1.dp,
        label = "borderWidth",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
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
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .size(204.dp)
                        .graphicsLayer { scaleX = ringScale; scaleY = ringScale }
                        .background(Color(0xFF22C55E).copy(alpha = ringAlpha), CircleShape)
                ) {}
            }
            Surface(
                modifier = Modifier
                    .size(188.dp)
                    .clip(CircleShape)
                    .border(
                        width = borderWidth,
                        color = borderColor,
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
                        tint = if (isConnected) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)),
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
                        text = connectionSubtitle,
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

        if (!isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.default_server_switch_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.default_server_switch_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = autoSwitchDefaultServer,
                        onCheckedChange = onAutoSwitchDefaultServerChange,
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenIpFinder),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.large,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ip_finder_drawer_entry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = mobileIp ?: stringResource(R.string.dams_metric_unavailable),
                        style = MaterialTheme.typography.displaySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.dams_mobile_ip_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
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
private fun trafficMetric(isRunning: Boolean, available: Boolean, bytesPerSecond: Long): String = when {
    !isRunning -> "—"
    !available -> stringResource(R.string.dams_metric_unavailable)
    else -> bytesPerSecond.toSpeedString()
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
