package com.v2ray.ang.laboratory.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.laboratory.data.LaboratoryLogRow
import com.v2ray.ang.laboratory.data.PathAggregate
import com.v2ray.ang.laboratory.data.RelayEventRow
import com.v2ray.ang.laboratory.multipath.EngineState
import com.v2ray.ang.laboratory.multipath.PathLinkState
import com.v2ray.ang.laboratory.multipath.PathRole
import com.v2ray.ang.ui.compose.DamsTheme
import com.v2ray.ang.ui.compose.DamsThemeTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private val HERO_SHAPE = RoundedCornerShape(22.dp)
private val CARD_SHAPE = RoundedCornerShape(18.dp)
private val BADGE_SHAPE = RoundedCornerShape(10.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LaboratoryScreen(
    state: LaboratoryUiState,
    onBack: () -> Unit,
    onActivate: () -> Unit,
    onConfirmActivation: () -> Unit,
    onCancelActivation: () -> Unit,
    onDeactivate: () -> Unit,
    onBackToClassic: () -> Unit,
    onProbeNow: () -> Unit,
    onOpenPathPicker: (PathRole) -> Unit,
    onClosePathPicker: () -> Unit,
    onSelectPath: (PathRole, String?) -> Unit,
    onHybridChanged: (Boolean) -> Unit,
    onAutoFailoverChanged: (Boolean) -> Unit,
    onProbeIntervalChanged: (Int) -> Unit,
    onRequestReset: () -> Unit,
    onConfirmReset: () -> Unit,
    onCancelReset: () -> Unit,
    onConsumeNotice: () -> Unit,
    onConsumeError: () -> Unit,
) {
    val theme = DamsTheme

    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            delay(4200)
            onConsumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.laboratory_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.laboratory_topbar_subtitle),
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
                            contentDescription = stringResource(R.string.laboratory_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onProbeNow, enabled = state.isActive && !state.busy) {
                        Icon(
                            painterResource(R.drawable.ic_refresh_24dp),
                            contentDescription = stringResource(R.string.laboratory_probe_now),
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
        containerColor = theme.fond,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "lab-hero") { LaboratoryHero(state) }

            state.error?.let { error ->
                item(key = "lab-error") {
                    LaboratoryBanner(
                        text = error,
                        color = theme.erreur,
                        onDismiss = onConsumeError,
                    )
                }
            }
            state.notice?.let { notice ->
                item(key = "lab-notice") {
                    LaboratoryBanner(
                        text = notice,
                        color = theme.accent,
                        onDismiss = onConsumeNotice,
                    )
                }
            }

            item(key = "lab-controls") {
                LaboratoryControls(
                    state = state,
                    onActivate = onActivate,
                    onDeactivate = onDeactivate,
                    onBackToClassic = onBackToClassic,
                    onProbeNow = onProbeNow,
                )
            }

            item(key = "lab-paths-title") { SectionTitle(stringResource(R.string.laboratory_section_paths)) }
            items(state.paths, key = { it.role.tag }) { path ->
                PathCard(
                    path = path,
                    active = state.isActive,
                    preferred = state.preferredRole == path.role,
                    selected = path.role.tag in state.selector,
                    onClick = { onOpenPathPicker(path.role) },
                )
            }
            item(key = "lab-paths-hint") {
                Text(
                    stringResource(R.string.laboratory_paths_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.texteDoux,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            item(key = "lab-settings") {
                LaboratorySettingsCard(
                    state = state,
                    onHybridChanged = onHybridChanged,
                    onAutoFailoverChanged = onAutoFailoverChanged,
                    onProbeIntervalChanged = onProbeIntervalChanged,
                )
            }

            item(key = "lab-stats") { LaboratoryStatisticsCard(state) }

            item(key = "lab-history-title") {
                SectionTitle(stringResource(R.string.laboratory_section_history))
            }
            item(key = "lab-history") { RelayHistoryCard(state.relayHistory) }

            item(key = "lab-logs-title") { SectionTitle(stringResource(R.string.laboratory_section_logs)) }
            item(key = "lab-logs") { LaboratoryLogsCard(state.logs) }

            item(key = "lab-disclaimer") { LaboratoryDisclaimer() }

            item(key = "lab-reset") {
                TextButton(
                    onClick = onRequestReset,
                    enabled = !state.isActive && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.laboratory_reset),
                        color = if (state.isActive) theme.texteDoux else theme.erreur,
                    )
                }
            }
        }
    }

    if (state.showActivationWarning) {
        AlertDialog(
            onDismissRequest = onCancelActivation,
            title = { Text(stringResource(R.string.laboratory_warning_title)) },
            text = { Text(stringResource(R.string.laboratory_warning_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmActivation) {
                    Text(stringResource(R.string.laboratory_warning_confirm), color = theme.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelActivation) {
                    Text(stringResource(R.string.laboratory_cancel))
                }
            },
        )
    }

    if (state.showResetConfirmation) {
        AlertDialog(
            onDismissRequest = onCancelReset,
            title = { Text(stringResource(R.string.laboratory_reset_title)) },
            text = { Text(stringResource(R.string.laboratory_reset_message)) },
            confirmButton = {
                TextButton(onClick = onConfirmReset) {
                    Text(stringResource(R.string.laboratory_reset_confirm), color = theme.erreur)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelReset) {
                    Text(stringResource(R.string.laboratory_cancel))
                }
            },
        )
    }

    state.editingPath?.let { path ->
        PathPickerDialog(
            role = path.role,
            currentGuid = path.guid,
            profiles = state.profiles,
            onSelect = { guid -> onSelectPath(path.role, guid) },
            onDismiss = onClosePathPicker,
        )
    }
}

// ── En-tête ──────────────────────────────────────────────────────────────────

@Composable
private fun LaboratoryHero(state: LaboratoryUiState) {
    val theme = DamsTheme
    val statusColor = engineColor(state.engineState, theme)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HERO_SHAPE)
            .background(
                Brush.linearGradient(
                    listOf(
                        theme.accent.copy(alpha = 0.22f),
                        theme.fondRadial,
                        theme.surface,
                    )
                )
            )
            .border(BorderStroke(1.dp, theme.accent.copy(alpha = 0.35f)), HERO_SHAPE)
            .padding(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.laboratory_hero_name),
                    color = theme.texteFort,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = theme.accent.copy(alpha = 0.14f),
                    shape = BADGE_SHAPE,
                    border = BorderStroke(1.dp, theme.accent.copy(alpha = 0.45f)),
                ) {
                    Text(
                        stringResource(R.string.laboratory_experimental_badge),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = theme.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                }
            }

            Text(
                stringResource(R.string.laboratory_hero_subtitle),
                color = theme.texteDoux,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(color = statusColor, animated = state.isActive && theme.animationsEnabled)
                Text(
                    engineLabel(state.engineState),
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text("·", color = theme.texteDoux)
                Text(
                    when {
                        state.serviceRunning -> stringResource(R.string.laboratory_service_running)
                        state.serviceStateKnown -> stringResource(R.string.laboratory_service_stopped)
                        else -> stringResource(R.string.laboratory_service_unknown)
                    },
                    color = theme.texteDoux,
                    fontSize = 13.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MiniChip(stringResource(R.string.laboratory_chip_paths), theme)
                MiniChip(stringResource(R.string.laboratory_chip_engine), theme)
                MiniChip(
                    if (state.hybridEnabled) {
                        stringResource(R.string.laboratory_chip_hybrid)
                    } else {
                        stringResource(R.string.laboratory_chip_primary)
                    },
                    theme,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, animated: Boolean) {
    val transition = rememberInfiniteTransition(label = "lab-dot")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "lab-dot-alpha",
    )
    val alpha = if (animated) pulse else 1f
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.7f)), CircleShape),
    )
}

@Composable
private fun MiniChip(text: String, theme: DamsThemeTokens) {
    Surface(
        color = theme.fond.copy(alpha = 0.55f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, theme.bordure),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            color = theme.texteDoux,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
        )
    }
}

// ── Contrôles ────────────────────────────────────────────────────────────────

@Composable
private fun LaboratoryControls(
    state: LaboratoryUiState,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onBackToClassic: () -> Unit,
    onProbeNow: () -> Unit,
) {
    val theme = DamsTheme
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
        shape = CARD_SHAPE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = theme.accent,
                    )
                    Text(
                        state.busyLabel ?: stringResource(R.string.laboratory_busy_generic),
                        color = theme.texteFort,
                        fontSize = 13.sp,
                    )
                }
            }

            if (!state.isActive) {
                Text(
                    stringResource(R.string.laboratory_control_idle_message),
                    color = theme.texteDoux,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onActivate,
                    enabled = !state.busy && state.selectionComplete && state.vpnModeAvailable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.accent,
                        contentColor = theme.fond,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_flash_on_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.laboratory_activate),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (!state.vpnModeAvailable) {
                    Text(
                        stringResource(R.string.laboratory_notice_vpn_mode_required),
                        color = theme.avertissement,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        label = stringResource(R.string.laboratory_metric_selector),
                        value = state.selector
                            .mapNotNull { PathRole.fromTag(it)?.shortLabel }
                            .joinToString(" · ")
                            .ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = stringResource(R.string.laboratory_metric_uptime),
                        value = uptimeLabel(state),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = onBackToClassic,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.accent,
                        contentColor = theme.fond,
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        stringResource(R.string.laboratory_back_to_classic),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDeactivate,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, theme.erreur.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.erreur),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.laboratory_deactivate))
                    }
                    OutlinedButton(
                        onClick = onProbeNow,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, theme.bordure),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = theme.texteFort),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(stringResource(R.string.laboratory_probe_now))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    val theme = DamsTheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.fond.copy(alpha = 0.45f))
            .border(BorderStroke(1.dp, theme.bordure), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(Locale.ROOT), color = theme.texteDoux, fontSize = 9.sp, letterSpacing = 0.8.sp)
        Text(value, color = theme.texteFort, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

// ── Cartes de chemin ────────────────────────────────────────────────────────

@Composable
private fun PathCard(
    path: LaboratoryPathUi,
    active: Boolean,
    preferred: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = DamsTheme
    val color = pathStateColor(path.state, theme)
    val enabled = !active

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CARD_SHAPE)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, if (preferred && active) color.copy(alpha = 0.8f) else theme.bordure),
        shape = CARD_SHAPE,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(BADGE_SHAPE)
                        .background(
                            Brush.linearGradient(
                                listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0.12f))
                            )
                        )
                        .border(BorderStroke(1.dp, color.copy(alpha = 0.6f)), BADGE_SHAPE),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        path.role.shortLabel,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        path.name,
                        color = theme.texteFort,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(path.role.label)
                            if (path.protocol.isNotBlank()) append(" · ").append(path.protocol)
                            if (preferred && active) append(" · ").append("PRÉFÉRÉ")
                        },
                        color = theme.texteDoux,
                        fontSize = 10.sp,
                        letterSpacing = 0.4.sp,
                    )
                }
                StateChip(path.state)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PathMetric(
                    stringResource(R.string.laboratory_metric_ping),
                    if (path.latencyMillis > 0) "${path.latencyMillis} ms" else "—",
                    Modifier.weight(1f),
                )
                PathMetric(
                    stringResource(R.string.laboratory_metric_loss),
                    if (path.sampleCount > 0) "${path.lossPercent} %" else "—",
                    Modifier.weight(1f),
                )
                PathMetric(
                    stringResource(R.string.laboratory_metric_quality),
                    if (path.sampleCount > 0) "${path.qualityScore}/100" else "—",
                    Modifier.weight(1f),
                )
            }

            LinearProgressIndicator(
                progress = { (path.qualityScore.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50)),
                color = color,
                trackColor = theme.fond.copy(alpha = 0.7f),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (path.sampleCount > 0) {
                        stringResource(
                            R.string.laboratory_path_samples,
                            path.sampleCount,
                            path.availabilityPercent,
                        )
                    } else {
                        stringResource(R.string.laboratory_path_no_measure)
                    },
                    color = theme.texteDoux,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!active) {
                    Text(
                        stringResource(R.string.laboratory_path_change),
                        color = theme.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PathMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val theme = DamsTheme
    Column(modifier = modifier) {
        Text(label.uppercase(Locale.ROOT), color = theme.texteDoux, fontSize = 9.sp, letterSpacing = 0.7.sp)
        Text(value, color = theme.texteFort, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StateChip(state: PathLinkState) {
    val theme = DamsTheme
    val color = pathStateColor(state, theme)
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            pathStateLabel(state),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ── Réglages ────────────────────────────────────────────────────────────────

@Composable
private fun LaboratorySettingsCard(
    state: LaboratoryUiState,
    onHybridChanged: (Boolean) -> Unit,
    onAutoFailoverChanged: (Boolean) -> Unit,
    onProbeIntervalChanged: (Int) -> Unit,
) {
    val theme = DamsTheme
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
        shape = CARD_SHAPE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.laboratory_section_settings), modifier = Modifier.padding(bottom = 4.dp))
            ToggleRow(
                title = stringResource(R.string.laboratory_hybrid_title),
                subtitle = stringResource(R.string.laboratory_hybrid_desc),
                checked = state.hybridEnabled,
                enabled = !state.busy,
                onCheckedChange = onHybridChanged,
            )
            HorizontalDivider(color = theme.bordure.copy(alpha = 0.6f))
            ToggleRow(
                title = stringResource(R.string.laboratory_autofailover_title),
                subtitle = stringResource(R.string.laboratory_autofailover_desc),
                checked = state.autoFailoverEnabled,
                enabled = !state.busy,
                onCheckedChange = onAutoFailoverChanged,
            )
            HorizontalDivider(color = theme.bordure.copy(alpha = 0.6f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        R.string.laboratory_interval_value,
                        state.probeIntervalSeconds,
                    ),
                    color = theme.texteFort,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.laboratory_interval_desc),
                    color = theme.texteDoux,
                    fontSize = 11.sp,
                )
                Slider(
                    value = state.probeIntervalSeconds.toFloat(),
                    onValueChange = { onProbeIntervalChanged(it.toInt()) },
                    valueRange = 20f..180f,
                    steps = 7,
                    enabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val theme = DamsTheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = theme.texteFort, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = theme.texteDoux, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = theme.fond,
                checkedTrackColor = theme.accent,
            ),
        )
    }
}

// ── Statistiques ────────────────────────────────────────────────────────────

@Composable
private fun LaboratoryStatisticsCard(state: LaboratoryUiState) {
    val theme = DamsTheme
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
        shape = CARD_SHAPE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.laboratory_section_stats))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    stringResource(R.string.laboratory_stats_quality),
                    "${state.sessionStats.averageQuality}/100",
                    Modifier.weight(1f),
                )
                MetricTile(
                    stringResource(R.string.laboratory_stats_latency),
                    if (state.sessionStats.averageLatencyMillis > 0) {
                        "${state.sessionStats.averageLatencyMillis} ms"
                    } else {
                        "—"
                    },
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    stringResource(R.string.laboratory_stats_loss),
                    "${state.sessionStats.averageLossPercent} %",
                    Modifier.weight(1f),
                )
                MetricTile(
                    stringResource(R.string.laboratory_stats_relays),
                    "${state.sessionStats.relays}",
                    Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    R.string.laboratory_stats_totals,
                    state.counters.sessions,
                    state.counters.samples,
                    state.counters.relays,
                ),
                color = theme.texteDoux,
                fontSize = 11.sp,
            )

            if (state.aggregates.isNotEmpty()) {
                HorizontalDivider(color = theme.bordure.copy(alpha = 0.6f))
                Text(
                    stringResource(R.string.laboratory_aggregates_title),
                    color = theme.texteFort,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                state.aggregates.forEach { aggregate -> AggregateRow(aggregate) }
            }
        }
    }
}

@Composable
private fun AggregateRow(aggregate: PathAggregate) {
    val theme = DamsTheme
    val role = PathRole.fromTag(aggregate.roleTag)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            role?.shortLabel ?: "?",
            color = theme.accent,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.width(18.dp),
        )
        Text(
            aggregate.remarks.ifBlank { role?.label.orEmpty() },
            color = theme.texteFort,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (aggregate.averageLatencyMillis > 0) {
                stringResource(
                    R.string.laboratory_aggregate_row,
                    aggregate.samples,
                    aggregate.averageLatencyMillis,
                    aggregate.averageQuality,
                )
            } else {
                stringResource(R.string.laboratory_aggregate_no_latency, aggregate.samples)
            },
            color = theme.texteDoux,
            fontSize = 10.sp,
        )
    }
}

// ── Historique et journaux ──────────────────────────────────────────────────

@Composable
private fun RelayHistoryCard(relays: List<RelayEventRow>) {
    val theme = DamsTheme
    LaboratoryPanel {
        if (relays.isEmpty()) {
            Text(
                stringResource(R.string.laboratory_history_empty),
                color = theme.texteDoux,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            relays.forEach { relay ->
                RelayRow(relay)
                HorizontalDivider(color = theme.bordure.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun RelayRow(relay: RelayEventRow) {
    val theme = DamsTheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatTime(relay.recordedAt),
            color = theme.texteDoux,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(58.dp),
        )
        Text(
            "${PathRole.fromTag(relay.fromTag)?.shortLabel ?: "?"} → " +
                (PathRole.fromTag(relay.toTag)?.shortLabel ?: "?"),
            color = theme.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(46.dp),
        )
        Text(
            relayReasonLabel(relay.reason),
            color = theme.texteFort,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            latencyPair(relay.fromLatencyMillis, relay.toLatencyMillis),
            color = theme.texteDoux,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun LaboratoryLogsCard(logs: List<LaboratoryLogRow>) {
    val theme = DamsTheme
    LaboratoryPanel {
        if (logs.isEmpty()) {
            Text(
                stringResource(R.string.laboratory_logs_empty),
                color = theme.texteDoux,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                logs.take(14).forEach { log ->
                    val color = when (log.level) {
                        "ERROR" -> theme.erreur
                        "WARN" -> theme.avertissement
                        else -> theme.texteDoux
                    }
                    Text(
                        "${formatTime(log.recordedAt)} [${log.category}] ${log.message}",
                        color = color,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LaboratoryPanel(content: @Composable () -> Unit) {
    val theme = DamsTheme
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.surface.copy(alpha = theme.cardAlpha)),
        border = BorderStroke(1.dp, theme.bordure),
        shape = CARD_SHAPE,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun LaboratoryDisclaimer() {
    val theme = DamsTheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.laboratory_disclaimer),
            color = theme.texteDoux,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
        Text(
            stringResource(R.string.laboratory_disclaimer_battery),
            color = theme.texteDoux,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun LaboratoryBanner(text: String, color: Color, onDismiss: () -> Unit) {
    val theme = DamsTheme
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDismiss),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = theme.texteFort,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(Locale.ROOT),
        modifier = modifier,
        color = DamsTheme.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )
}

// ── Sélecteur de serveur ────────────────────────────────────────────────────

@Composable
private fun PathPickerDialog(
    role: PathRole,
    currentGuid: String?,
    profiles: List<com.v2ray.ang.laboratory.multipath.MultipathConfigRepository.LaboratoryProfile>,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = DamsTheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.laboratory_picker_title, role.label)) },
        text = {
            if (profiles.isEmpty()) {
                Text(stringResource(R.string.laboratory_picker_empty))
            } else {
                Column(
                    modifier = Modifier
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelect(profile.guid) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = profile.guid == currentGuid,
                                onClick = { onSelect(profile.guid) },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                    selectedColor = theme.accent,
                                ),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    profile.remarks,
                                    color = theme.texteFort,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${profile.protocol} · ${profile.server}",
                                    color = theme.texteDoux,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.laboratory_close), color = theme.accent)
            }
        },
    )
}

// ── Libellés ────────────────────────────────────────────────────────────────

@Composable
private fun engineLabel(state: EngineState): String = when (state) {
    EngineState.IDLE -> stringResource(R.string.laboratory_engine_idle)
    EngineState.PREPARING -> stringResource(R.string.laboratory_engine_preparing)
    EngineState.STOPPING_CLASSIC -> stringResource(R.string.laboratory_engine_stopping_classic)
    EngineState.STARTING -> stringResource(R.string.laboratory_engine_starting)
    EngineState.ACTIVE -> stringResource(R.string.laboratory_engine_active)
    EngineState.STOPPING -> stringResource(R.string.laboratory_engine_stopping)
    EngineState.FAILED -> stringResource(R.string.laboratory_engine_failed)
}

private fun engineColor(state: EngineState, theme: DamsThemeTokens): Color = when (state) {
    EngineState.ACTIVE -> theme.succes
    EngineState.FAILED -> theme.erreur
    EngineState.PREPARING,
    EngineState.STOPPING_CLASSIC,
    EngineState.STARTING,
    EngineState.STOPPING -> theme.avertissement

    EngineState.IDLE -> theme.texteDoux
}

@Composable
private fun pathStateLabel(state: PathLinkState): String = when (state) {
    PathLinkState.IDLE -> stringResource(R.string.laboratory_state_idle)
    PathLinkState.MONITORED -> stringResource(R.string.laboratory_state_monitored)
    PathLinkState.READY -> stringResource(R.string.laboratory_state_ready)
    PathLinkState.CARRYING -> stringResource(R.string.laboratory_state_carrying)
    PathLinkState.UNAVAILABLE -> stringResource(R.string.laboratory_state_unavailable)
}

private fun pathStateColor(state: PathLinkState, theme: DamsThemeTokens): Color = when (state) {
    PathLinkState.CARRYING -> theme.succes
    PathLinkState.READY -> theme.accentClair
    PathLinkState.MONITORED -> theme.accent
    PathLinkState.UNAVAILABLE -> theme.erreur
    PathLinkState.IDLE -> theme.texteDoux
}

private fun relayReasonLabel(reason: String): String = when (reason) {
    "initial" -> "initialisation"
    "primary-degraded" -> "principal dégradé"
    "primary-recovered" -> "principal rétabli"
    "rebalanced" -> "rééquilibrage"
    "mode-change" -> "changement de mode"
    "selector-change" -> "sélecteur modifié"
    else -> reason
}

private fun latencyPair(from: Long, to: Long): String {
    val fromText = if (from > 0) "$from ms" else "—"
    val toText = if (to > 0) "$to ms" else "—"
    return "$fromText → $toText"
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

@Composable
private fun uptimeLabel(state: LaboratoryUiState): String {
    val startedAt = state.sessionStats.startedAtMillis
    if (startedAt <= 0L) return "—"
    val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).coerceAtLeast(0L)
    return stringResource(R.string.laboratory_uptime_value, minutes)
}
