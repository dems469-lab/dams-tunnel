package com.v2ray.ang.ui.main

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.TrafficSnapshot
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.ui.compose.DamsTheme
import kotlinx.coroutines.delay

/**
 * Écran d'accueil premium.
 *
 * L'apparence suit la maquette bleu nuit / cyan, mais toute la logique de
 * connexion reste celle de l'application existante : le bouton Power appelle
 * exactement la même action que la version 1.7.2 et chaque indicateur lit une
 * mesure réelle du moteur ou de l'état du service.
 */
@Composable
internal fun DamsHomeScreen(
    contentPadding: PaddingValues,
    connectionSubtitle: String,
    powerState: HomePowerState,
    canStopService: Boolean,
    serverAlias: String,
    hasServer: Boolean,
    delayMillis: Long?,
    downloadBytesPerSecond: Long,
    uploadBytesPerSecond: Long,
    trafficAvailable: Boolean,
    sessionTotalBytes: Long,
    mobileDailyBytes: Long,
    trafficHistory: List<TrafficSnapshot>,
    mobileIp: String?,
    diagnostic: ConnectionDiagnostic?,
    autoSwitchDefaultServer: Boolean,
    onAutoSwitchDefaultServerChange: (Boolean) -> Unit,
    onOpenServers: () -> Unit,
    onOpenIpFinder: () -> Unit,
    onToggleConnection: () -> Unit,
) {
    val theme = DamsTheme
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val powerSaveMode = remember(context) {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode ?: false
    }
    val animationsActive = resumed && theme.animationsEnabled && !powerSaveMode
    val isConnected = powerState == HomePowerState.Connected

    val elapsedMillis = rememberConnectionClock(isConnected)

    Box(modifier = Modifier.fillMaxSize()) {
        DamsHomeBackground(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandBlock()

            PowerOrb(
                state = powerState,
                animationsActive = animationsActive,
                canStopService = canStopService,
                onClick = onToggleConnection,
            )

            HomeStatusCard(
                state = powerState,
                title = stringResource(powerState.titleRes()).uppercase(),
                subtitle = connectionSubtitle,
                animationsActive = animationsActive,
            )

            ServerCard(
                alias = serverAlias,
                hasServer = hasServer,
                delayMillis = delayMillis,
                isConnected = isConnected,
                onClick = onOpenServers,
            )

            PerformanceRow(
                isConnected = isConnected,
                trafficAvailable = trafficAvailable,
                downloadBytesPerSecond = downloadBytesPerSecond,
                uploadBytesPerSecond = uploadBytesPerSecond,
                delayMillis = delayMillis,
                history = trafficHistory,
            )

            UsageCard(
                sessionTotalBytes = sessionTotalBytes,
                mobileDailyBytes = mobileDailyBytes,
                elapsedMillis = elapsedMillis,
                isConnected = isConnected,
            )

            AutoSwitchCard(
                enabled = autoSwitchDefaultServer,
                onEnabledChange = onAutoSwitchDefaultServerChange,
            )

            IpFinderCard(ip = mobileIp, onClick = onOpenIpFinder)

            diagnostic?.let { DamsDiagnosticCard(it) }
        }
    }
}

@Composable
private fun rememberConnectionClock(isConnected: Boolean): Long {
    // Instant de départ exprimé sur l'horloge monotone du processus ; -1 = aucun
    // tunnel actif. Aucune valeur inventée : sans connexion, le chronomètre est vide.
    var connectedAtElapsed by rememberSaveable { mutableLongStateOf(-1L) }
    var elapsedMillis by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(isConnected) {
        if (isConnected) {
            if (connectedAtElapsed < 0L) connectedAtElapsed = SystemClock.elapsedRealtime()
        } else {
            connectedAtElapsed = -1L
            elapsedMillis = 0L
        }
    }
    LaunchedEffect(isConnected, connectedAtElapsed) {
        val startedAt = connectedAtElapsed
        if (!isConnected || startedAt < 0L) return@LaunchedEffect
        while (true) {
            elapsedMillis = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    return elapsedMillis
}

private fun HomePowerState.titleRes(): Int = when (this) {
    HomePowerState.Disconnected -> R.string.dams_home_state_disconnected
    HomePowerState.Connecting -> R.string.dams_home_state_connecting
    HomePowerState.Reconnecting -> R.string.dams_home_state_reconnecting
    HomePowerState.Connected -> R.string.dams_home_state_connected
    HomePowerState.Error -> R.string.dams_home_state_error
}

// ---------------------------------------------------------------------------
// Arrière-plan : carte du monde numérique et faisceaux du tunnel
// ---------------------------------------------------------------------------

@Composable
private fun DamsHomeBackground(modifier: Modifier = Modifier) {
    val theme = DamsTheme
    val landRows = remember { worldMapRows() }
    val accent = theme.accent
    val accentClair = theme.accentClair
    val fond = theme.fond

    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                0f to fond,
                0.45f to lerp(fond, theme.fondRadial, 0.55f),
                1f to fond,
            )
        )
        // Halo central derrière le bouton Power.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width / 2f, size.height * 0.34f),
                radius = size.width * 0.85f,
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width / 2f, size.height * 0.34f),
        )

        val mapHeight = size.width * 0.52f
        val cellWidth = size.width / DamsWorldMap.COLUMNS
        val dotRadius = (cellWidth * 0.28f).coerceIn(0.8f, 2.4f)
        landRows.forEachIndexed { rowIndex, row ->
            val fade = (1f - rowIndex / DamsWorldMap.ROWS.toFloat()).coerceIn(0f, 1f)
            val points = row.map { Offset(it.x * size.width, it.y * mapHeight) }
            if (points.isNotEmpty()) {
                drawPoints(
                    points = points,
                    pointMode = PointMode.Points,
                    color = accentClair.copy(alpha = 0.10f + 0.30f * fade),
                    strokeWidth = dotRadius * 2f,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Faisceaux diagonaux évoquant le tunnel sécurisé.
        val beamStart = size.height * 0.30f
        repeat(3) { index ->
            val offset = index * 26f - 26f
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, accent.copy(alpha = 0.16f), Color.Transparent),
                ),
                start = Offset(-20f + offset, beamStart + index * 34f),
                end = Offset(size.width * 0.55f, size.height * 0.62f + index * 20f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
            drawLine(
                brush = Brush.linearGradient(
                    colors = listOf(Color.Transparent, accentClair.copy(alpha = 0.12f), Color.Transparent),
                ),
                start = Offset(size.width + 20f - offset, beamStart + index * 34f),
                end = Offset(size.width * 0.45f, size.height * 0.62f + index * 20f),
                strokeWidth = 1.6f,
                cap = StrokeCap.Round,
            )
        }
        // Fondu vers le fond sous la carte pour laisser les cartes lisibles.
        drawRect(
            brush = Brush.verticalGradient(
                0.18f to Color.Transparent,
                0.55f to fond.copy(alpha = 0.55f),
                1f to fond,
            )
        )
    }
}

private fun worldMapRows(): List<List<Offset>> {
    val mask = DamsWorldMap.landMask()
    val rows = ArrayList<List<Offset>>(DamsWorldMap.ROWS)
    val columns = DamsWorldMap.COLUMNS
    val rowsCount = DamsWorldMap.ROWS
    for (row in 0 until rowsCount) {
        val points = ArrayList<Offset>(columns)
        for (column in 0 until columns) {
            if (mask[row * columns + column]) {
                points.add(Offset(column / (columns - 1f), row / (rowsCount - 1f)))
            }
        }
        rows.add(points)
    }
    return rows
}


// ---------------------------------------------------------------------------
// En-tête de marque
// ---------------------------------------------------------------------------

@Composable
private fun BrandBlock() {
    val theme = DamsTheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(theme.accent.copy(alpha = 0.10f), CircleShape)
                    .border(1.dp, theme.accent.copy(alpha = 0.45f), CircleShape)
            )
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            color = theme.texteFort,
            fontSize = 27.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.dams_slogan),
            color = theme.texteDoux,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// Bouton Power et anneaux lumineux
// ---------------------------------------------------------------------------

@Composable
private fun PowerOrb(
    state: HomePowerState,
    animationsActive: Boolean,
    canStopService: Boolean,
    onClick: () -> Unit,
) {
    val theme = DamsTheme
    val targetColor = when (state) {
        HomePowerState.Connected -> theme.succes
        HomePowerState.Connecting, HomePowerState.Reconnecting -> theme.accentClair
        HomePowerState.Error -> theme.erreur
        HomePowerState.Disconnected -> theme.accent
    }
    val orbColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(if (animationsActive) theme.ondeMillis else 0),
        label = "powerOrbColor",
    )
    val rotationMillis = when (state) {
        HomePowerState.Connecting, HomePowerState.Reconnecting -> 1_600
        HomePowerState.Connected -> 5_000
        else -> 9_000
    }
    val spinning = animationsActive && state != HomePowerState.Disconnected

    val infinite = rememberInfiniteTransition(label = "powerOrb")
    val rotation = if (spinning) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(rotationMillis, easing = LinearEasing)),
            label = "powerOrbRotation",
        ).value
    } else 0f
    val breath = if (animationsActive) {
        infinite.animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(theme.respirationMillis, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "powerOrbBreath",
        ).value
    } else 1f
    val pulse = if (animationsActive && state == HomePowerState.Connected) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing)),
            label = "powerOrbPulse",
        ).value
    } else null

    val actionLabel = stringResource(if (canStopService) R.string.acc_stop else R.string.acc_start)
    val stateLabel = stringResource(state.titleRes())

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(248.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = actionLabel
                stateDescription = stateLabel
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outer = size.minDimension / 2f - 2f
            // Anneau extérieur avec arc lumineux tournant.
            drawCircle(
                color = orbColor.copy(alpha = 0.16f + 0.20f * breath),
                radius = outer,
                center = center,
                style = Stroke(width = 1.6f.dp.toPx()),
            )
            val sweep = when (state) {
                HomePowerState.Connected -> 120f
                HomePowerState.Connecting, HomePowerState.Reconnecting -> 80f
                HomePowerState.Error -> 300f
                HomePowerState.Disconnected -> 60f
            }
            rotate(rotation, center) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, orbColor.copy(alpha = 0.95f), Color.Transparent),
                        center = center,
                    ),
                    startAngle = 0f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - outer, center.y - outer),
                    size = Size(outer * 2f, outer * 2f),
                    style = Stroke(width = 2.6f.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // Second anneau, plus discret.
            drawCircle(
                color = orbColor.copy(alpha = 0.10f + 0.12f * breath),
                radius = outer * 0.86f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )
            // Onde qui s'échappe lorsque le tunnel est établi.
            pulse?.let { progress ->
                drawCircle(
                    color = orbColor.copy(alpha = (1f - progress) * 0.30f),
                    radius = outer * (0.74f + progress * 0.32f),
                    center = center,
                    style = Stroke(width = (2f - progress * 1.4f).dp.toPx()),
                )
            }
            // Lueur interne du cœur.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(orbColor.copy(alpha = 0.28f), Color.Transparent),
                    center = center,
                    radius = outer * 0.78f,
                ),
                radius = outer * 0.78f,
                center = center,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.26f),
                        theme.surface.copy(alpha = 0.94f),
                        theme.surface,
                    ),
                    center = center,
                    radius = outer * 0.66f,
                ),
                radius = outer * 0.66f,
                center = center,
            )
            drawCircle(
                color = orbColor.copy(alpha = 0.55f + 0.30f * breath),
                radius = outer * 0.66f,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_power_24dp),
            contentDescription = null,
            modifier = Modifier
                .size(62.dp)
                .graphicsLayer { alpha = 0.85f + 0.15f * breath },
            tint = orbColor,
        )
    }
}

// ---------------------------------------------------------------------------
// Cartes d'état, serveur et mesures
// ---------------------------------------------------------------------------

@Composable
private fun NeonCard(
    modifier: Modifier = Modifier,
    borderColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val theme = DamsTheme
    val shape = RoundedCornerShape(18.dp)
    val background = Brush.verticalGradient(
        colors = listOf(
            theme.surface.copy(alpha = 0.92f),
            theme.surface.copy(alpha = 0.78f),
        )
    )
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else Modifier
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, borderColor.copy(alpha = 0.55f)), shape)
            .then(clickableModifier)
    ) {
        content()
    }
}

@Composable
private fun HomeStatusCard(
    state: HomePowerState,
    title: String,
    subtitle: String,
    animationsActive: Boolean,
) {
    val theme = DamsTheme
    val dotColor = when (state) {
        HomePowerState.Connected -> theme.succes
        HomePowerState.Connecting, HomePowerState.Reconnecting -> theme.accentClair
        HomePowerState.Error -> theme.erreur
        HomePowerState.Disconnected -> theme.accent
    }
    val titleColor = when (state) {
        HomePowerState.Connected -> theme.succes
        HomePowerState.Error -> theme.erreur
        HomePowerState.Connecting, HomePowerState.Reconnecting -> theme.accentClair
        HomePowerState.Disconnected -> theme.texteFort
    }
    val blinking = animationsActive &&
        (state == HomePowerState.Connecting || state == HomePowerState.Reconnecting)
    val dotAlpha = if (blinking) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(theme.ondeMillis, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        ).value
    } else 1f

    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = dotColor.copy(alpha = 0.75f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(dotColor.copy(alpha = dotAlpha), dotColor.copy(alpha = dotAlpha * 0.15f))
                    ),
                    radius = size.minDimension / 2f,
                )
            }
            Column(Modifier.padding(start = 14.dp)) {
                Crossfade(
                    targetState = title,
                    animationSpec = tween(if (animationsActive) theme.ondeMillis else 0),
                    label = "homeStatus",
                ) { text ->
                    Text(
                        text = text,
                        color = titleColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = subtitle,
                    color = theme.texteDoux,
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServerCard(
    alias: String,
    hasServer: Boolean,
    delayMillis: Long?,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    val theme = DamsTheme
    val flag = flagForAlias(alias.takeIf { hasServer })
    val bars = if (isConnected) signalBarCount(delayMillis) else 0
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = theme.accent,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(theme.fond.copy(alpha = 0.75f), CircleShape)
                    .border(1.dp, theme.accent.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = flag, fontSize = 21.sp, maxLines = 1)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = alias,
                    color = theme.texteFort,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.dams_home_server_selected),
                    color = theme.texteDoux,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SignalBars(bars = bars, tint = if (isConnected) theme.succes else theme.accent)
            Box(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .width(1.dp)
                    .height(28.dp)
                    .background(theme.bordure.copy(alpha = 0.8f))
            )
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right_24dp),
                contentDescription = stringResource(R.string.dams_home_open_servers),
                tint = theme.accent,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(22.dp),
            )
        }
    }
}

@Composable
private fun SignalBars(bars: Int, tint: Color) {
    val theme = DamsTheme
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.height(20.dp),
    ) {
        repeat(4) { index ->
            val active = index < bars
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((6 + index * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) tint else theme.texteDoux.copy(alpha = 0.25f)
                    )
            )
        }
    }
}

@Composable
private fun PerformanceRow(
    isConnected: Boolean,
    trafficAvailable: Boolean,
    downloadBytesPerSecond: Long,
    uploadBytesPerSecond: Long,
    delayMillis: Long?,
    history: List<TrafficSnapshot>,
) {
    val theme = DamsTheme
    val downloadHistory = remember(history) { history.map { it.downlinkBytesPerSecond } }
    val uploadHistory = remember(history) { history.map { it.uplinkBytesPerSecond } }
    val downloadText = when {
        !isConnected -> stringResource(R.string.dams_home_metric_idle)
        !trafficAvailable -> stringResource(R.string.dams_metric_unavailable)
        else -> formatMbps(downloadBytesPerSecond)
    }
    val uploadText = when {
        !isConnected -> stringResource(R.string.dams_home_metric_idle)
        !trafficAvailable -> stringResource(R.string.dams_metric_unavailable)
        else -> formatMbps(uploadBytesPerSecond)
    }
    val latencyText = when {
        !isConnected -> stringResource(R.string.dams_home_metric_idle)
        delayMillis == null || delayMillis < 0L -> stringResource(R.string.dams_metric_unavailable)
        else -> delayMillis.toString()
    }
    val quality = connectionQuality(isConnected, delayMillis)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.ic_download_24dp,
            label = stringResource(R.string.dams_metric_download),
            value = downloadText,
            unit = if (isConnected && trafficAvailable) "Mbps" else null,
            accent = theme.succes,
            sparkline = downloadHistory,
            showSparkline = true,
        )
        MetricCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.ic_upload_24dp,
            label = stringResource(R.string.dams_metric_upload),
            value = uploadText,
            unit = if (isConnected && trafficAvailable) "Mbps" else null,
            accent = theme.accentClair,
            sparkline = uploadHistory,
            showSparkline = true,
        )
        MetricCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            iconRes = R.drawable.ic_timer_24dp,
            label = stringResource(R.string.dams_metric_latency),
            value = latencyText,
            unit = if (isConnected && delayMillis != null && delayMillis >= 0L) "ms" else null,
            accent = theme.accent,
            signalBars = if (isConnected) signalBarCount(delayMillis) else 0,
        )
        QualityCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            quality = quality,
            accent = theme.succes,
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    iconRes: Int,
    label: String,
    value: String,
    unit: String?,
    accent: Color,
    sparkline: List<Long> = emptyList(),
    showSparkline: Boolean = false,
    signalBars: Int? = null,
) {
    val theme = DamsTheme
    NeonCard(
        modifier = modifier,
        borderColor = accent.copy(alpha = 0.75f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(accent.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                color = theme.texteDoux,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f, fill = false),
                    color = theme.texteFort,
                    fontSize = if (value.length > 5) 11.sp else 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unit != null) {
                    Text(
                        text = " $unit",
                        color = theme.texteDoux,
                        fontSize = 10.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                showSparkline -> Sparkline(
                    values = sparkline,
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                )

                signalBars != null -> SignalBars(
                    bars = signalBars,
                    tint = accent,
                )
            }
        }
    }
}

@Composable
private fun QualityCard(
    modifier: Modifier = Modifier,
    quality: ConnectionQuality,
    accent: Color,
) {
    val theme = DamsTheme
    val label = stringResource(
        when (quality) {
            ConnectionQuality.Excellent -> R.string.dams_home_quality_excellent
            ConnectionQuality.Good -> R.string.dams_home_quality_good
            ConnectionQuality.Fair -> R.string.dams_home_quality_fair
            ConnectionQuality.Weak -> R.string.dams_home_quality_weak
            ConnectionQuality.Unavailable -> R.string.dams_metric_unavailable
        }
    )
    val progress = when (quality) {
        ConnectionQuality.Excellent -> 1f
        ConnectionQuality.Good -> 0.75f
        ConnectionQuality.Fair -> 0.5f
        ConnectionQuality.Weak -> 0.25f
        ConnectionQuality.Unavailable -> 0f
    }
    val ringColor = when (quality) {
        ConnectionQuality.Excellent -> theme.succes
        ConnectionQuality.Good -> accent
        ConnectionQuality.Fair -> theme.avertissement
        ConnectionQuality.Weak -> theme.erreur
        ConnectionQuality.Unavailable -> theme.texteDoux
    }
    NeonCard(
        modifier = modifier,
        borderColor = ringColor.copy(alpha = 0.7f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(ringColor.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, ringColor.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_check_24dp),
                    contentDescription = null,
                    tint = ringColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dams_home_quality_title),
                color = theme.texteDoux,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 3.5f.dp.toPx()
                    drawCircle(
                        color = theme.texteDoux.copy(alpha = 0.20f),
                        style = Stroke(width = stroke),
                    )
                    if (progress > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.ic_shield_check_24dp),
                    contentDescription = null,
                    tint = ringColor.copy(alpha = if (progress > 0f) 0.95f else 0.35f),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = ringColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val fractions = remember(values) { sparklineFractions(values) }
    Canvas(modifier = modifier) {
        val midY = size.height / 2f
        if (fractions.size < 2) {
            drawLine(
                color = color.copy(alpha = 0.25f),
                start = Offset(0f, midY),
                end = Offset(size.width, midY),
                strokeWidth = 1f,
            )
            return@Canvas
        }
        val stepX = size.width / (fractions.size - 1)
        val path = Path()
        fractions.forEachIndexed { index, fraction ->
            val x = index * stepX
            val y = size.height - (fraction * (size.height - 2f)) - 1f
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fillPath = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.32f), Color.Transparent),
            ),
        )
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ---------------------------------------------------------------------------
// Données, basculement, IP Finder
// ---------------------------------------------------------------------------

@Composable
private fun UsageCard(
    sessionTotalBytes: Long,
    mobileDailyBytes: Long,
    elapsedMillis: Long,
    isConnected: Boolean,
) {
    val theme = DamsTheme
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = theme.accent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UsageHalf(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.ic_database_24dp,
                title = stringResource(R.string.dams_home_data_used),
                value = sessionTotalBytes.toTrafficString(),
                caption = stringResource(
                    R.string.dams_home_mobile_today,
                    mobileDailyBytes.toTrafficString(),
                ),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(42.dp)
                    .background(theme.bordure.copy(alpha = 0.8f))
            )
            UsageHalf(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
                iconRes = R.drawable.ic_timer_24dp,
                title = stringResource(R.string.dams_home_duration),
                value = if (isConnected) {
                    formatConnectionDuration(elapsedMillis)
                } else {
                    stringResource(R.string.dams_home_metric_idle)
                },
                caption = null,
                monospace = true,
            )
        }
    }
}

@Composable
private fun UsageHalf(
    modifier: Modifier = Modifier,
    iconRes: Int,
    title: String,
    value: String,
    caption: String?,
    monospace: Boolean = false,
) {
    val theme = DamsTheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(theme.accent.copy(alpha = 0.10f), CircleShape)
                .border(1.dp, theme.accent.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = theme.accentClair,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                text = title,
                color = theme.texteDoux,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                color = theme.texteFort,
                fontSize = if (monospace) 17.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    color = theme.texteDoux.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AutoSwitchCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val theme = DamsTheme
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = theme.accentClair,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(theme.accentClair.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt_24dp),
                    contentDescription = null,
                    tint = theme.accentClair,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.default_server_switch_title),
                    color = theme.texteFort,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.default_server_switch_summary),
                    color = theme.texteDoux,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 3,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = theme.fond,
                    checkedTrackColor = theme.accentClair,
                    uncheckedThumbColor = theme.texteDoux,
                    uncheckedTrackColor = theme.surface,
                    uncheckedBorderColor = theme.bordure,
                ),
            )
        }
    }
}

@Composable
private fun IpFinderCard(ip: String?, onClick: () -> Unit) {
    val theme = DamsTheme
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = theme.accent,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(theme.accent.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_location_pin_24dp),
                    contentDescription = null,
                    tint = theme.accentClair,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.ip_finder_drawer_entry),
                    color = theme.texteDoux,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ip ?: stringResource(R.string.dams_metric_unavailable),
                    color = theme.accentClair,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.dams_mobile_ip_source),
                    color = theme.texteDoux,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right_24dp),
                contentDescription = stringResource(R.string.dams_home_open_ip_finder),
                tint = theme.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Diagnostic (affiché uniquement quand une vérification a échoué)
// ---------------------------------------------------------------------------

@Composable
internal fun DamsDiagnosticCard(diagnostic: ConnectionDiagnostic) {
    val theme = DamsTheme
    if (diagnostic.networkAvailable != false &&
        diagnostic.serverReachable != false &&
        diagnostic.tunnelVerified != false
    ) {
        return
    }
    NeonCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = theme.erreur,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DiagnosticRow(stringResource(R.string.diagnostic_network), diagnostic.networkAvailable)
            DiagnosticRow(stringResource(R.string.diagnostic_server), diagnostic.serverReachable)
            DiagnosticRow(stringResource(R.string.diagnostic_tunnel), diagnostic.tunnelVerified)
            diagnostic.pingMillis?.let { ping ->
                Text(
                    text = "Ping : $ping ms",
                    color = theme.texteDoux,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: Boolean?) {
    val theme = DamsTheme
    val symbol = when (value) {
        true -> "✓"
        false -> "✕"
        null -> "…"
    }
    val color = when (value) {
        true -> theme.succes
        false -> theme.erreur
        null -> theme.texteDoux
    }
    Text(
        text = "$symbol  $label",
        color = color,
        fontSize = 12.sp,
    )
}
