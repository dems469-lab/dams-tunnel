package com.v2ray.ang.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    val isDarkTheme = LocalDarkTheme.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var showDashboard by rememberSaveable { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
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
            },
            bottomBar = {
                Column {
                    MainBottomBar(
                        displayText = displayText,
                        isRunning = isRunning,
                        isDarkTheme = isDarkTheme,
                        includeNavigationBars = false,
                        onAction = onAction
                    )
                    DamsNavigationBar(
                        selected = if (showDashboard) DamsTab.Home else DamsTab.Servers,
                        onSelect = { tab ->
                            when (tab) {
                                DamsTab.Home -> showDashboard = true
                                DamsTab.Servers -> showDashboard = false
                                DamsTab.Journal -> onNavigate(MainDestination.Logcat)
                                DamsTab.Settings -> onNavigate(MainDestination.Settings)
                            }
                        }
                    )
                }
            },
            floatingActionButton = {},
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current

            if (showDashboard) {
                DamsDashboard(
                    contentPadding = innerPadding,
                    displayText = displayText,
                    isRunning = isRunning,
                    groupName = groups.firstOrNull { it.id == uiState.selectedGroupId }?.remarks,
                    selectedServerName = selectedServer?.profile?.remarks,
                    selectedServerAddress = selectedServer?.profile?.server,
                    protocolName = selectedServer?.profile?.configType?.protocolScheme?.uppercase(),
                    delayMillis = selectedServer?.testDelayMillis,
                    profileCount = selectedServers.size,
                    onOpenServers = { showDashboard = false },
                    onTestConnection = { onAction(MainAction.TestCurrentServer) },
                )
            } else if (groups.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    DamsStatusCard(
                        displayText = displayText,
                        isRunning = isRunning,
                    )
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
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            contentPadding = PaddingValues(
                                start = 0.dp,
                                top = 0.dp,
                                end = 0.dp,
                                bottom = 80.dp
                            )
                        )
                    }
                }
            }
        }
    }
}

private enum class DamsTab {
    Home, Servers, Journal, Settings
}

@Composable
private fun DamsNavigationBar(selected: DamsTab, onSelect: (DamsTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            Triple(DamsTab.Home, R.drawable.ic_qu_switch_24dp, R.string.dams_tab_home),
            Triple(DamsTab.Servers, R.drawable.ic_subscriptions_24dp, R.string.dams_tab_servers),
            Triple(DamsTab.Journal, R.drawable.ic_logcat_24dp, R.string.dams_tab_journal),
            Triple(DamsTab.Settings, R.drawable.ic_settings_24dp, R.string.title_settings),
        ).forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { androidx.compose.material3.Icon(painterResource(icon), contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun DamsDashboard(
    contentPadding: PaddingValues,
    displayText: String,
    isRunning: Boolean,
    groupName: String?,
    selectedServerName: String?,
    selectedServerAddress: String?,
    protocolName: String?,
    delayMillis: Long?,
    profileCount: Int,
    onOpenServers: () -> Unit,
    onTestConnection: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF06275B)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(Color(0xFF061B43), Color(0xFF0878C9))))
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.size(70.dp).clip(CircleShape),
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(R.string.dams_secure_connection), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            displayText,
                            color = if (isRunning) Color(0xFF7DFFB0) else Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            stringResource(if (isRunning) R.string.dams_privacy_protected else R.string.dams_ready_to_connect),
                            color = Color(0xFFD7E9FF),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(58.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = painterResource(R.drawable.ic_subscriptions_24dp),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(selectedServerName ?: groupName ?: stringResource(R.string.dams_no_server_selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selectedServerAddress ?: stringResource(R.string.dams_select_server_prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashboardMetric(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dams_metric_latency),
                value = when {
                    delayMillis == null || delayMillis == 0L -> "—"
                    delayMillis < 0 -> stringResource(R.string.dams_metric_unavailable)
                    else -> "$delayMillis ms"
                },
                onClick = onTestConnection,
            )
            DashboardMetric(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dams_metric_profiles),
                value = profileCount.toString(),
                onClick = onOpenServers,
            )
            DashboardMetric(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.dams_metric_protocol),
                value = protocolName ?: "—",
                onClick = onOpenServers,
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.dams_server_group), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(groupName ?: stringResource(R.string.dams_no_server_selected), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DashboardMetric(
    modifier: Modifier,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(112.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
