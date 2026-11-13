package com.v2ray.ang.tester

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import java.util.Date

/**
 * Écran Tester : deux listes cloisonnées, deux mesures distinctes.
 *
 * Le test de tunnel traverse réellement Xray ; le ping réseau ne teste que la
 * joignabilité TCP. L'écran ne présente jamais l'un comme l'autre, et un débit
 * non mesuré reste affiché comme non mesuré.
 */
class TesterActivity : BaseComponentActivity() {

    private val viewModel: TesterViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun ScreenContent() {
        BackHandler { finish() }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tester_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_24dp),
                                contentDescription = stringResource(R.string.tester_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .navigationBarsPadding(),
            ) {
                ScopeSelector(
                    state = state,
                    onScope = viewModel::setScope,
                )
                KindSelector(
                    state = state,
                    onKind = viewModel::setKind,
                )
                TestControls(
                    state = state,
                    onStart = viewModel::startTest,
                    onCancel = viewModel::cancelTest,
                    onConcurrency = viewModel::setConcurrency,
                )
                if (state.testing && !state.progress.isNullOrBlank()) {
                    Text(
                        text = state.progress.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.tester_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                when {
                    state.loading -> LoadingRow()
                    state.error -> Text(
                        text = stringResource(R.string.tester_error),
                        modifier = Modifier.padding(16.dp),
                    )

                    state.rows.isEmpty() -> Text(
                        text = stringResource(R.string.tester_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.guid }) { row ->
                            TesterServerCard(row = row, kind = state.kind)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ScopeSelector(state: TesterUiState, onScope: (TesterScope) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.scope == TesterScope.CONFIGS,
            enabled = !state.testing,
            onClick = { onScope(TesterScope.CONFIGS) },
            label = {
                Text(stringResource(R.string.tester_scope_configs, state.configs.size))
            },
        )
        FilterChip(
            selected = state.scope == TesterScope.FREE,
            enabled = !state.testing,
            onClick = { onScope(TesterScope.FREE) },
            label = {
                Text(stringResource(R.string.tester_scope_free, state.free.size))
            },
        )
    }
}

@Composable
private fun KindSelector(state: TesterUiState, onKind: (TesterKind) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.kind == TesterKind.TUNNEL,
            onClick = { onKind(TesterKind.TUNNEL) },
            label = { Text(stringResource(R.string.tester_kind_tunnel)) },
        )
        FilterChip(
            selected = state.kind == TesterKind.TCP,
            onClick = { onKind(TesterKind.TCP) },
            label = { Text(stringResource(R.string.tester_kind_tcp)) },
        )
    }
}

@Composable
private fun TestControls(
    state: TesterUiState,
    onStart: (TesterKind) -> Unit,
    onCancel: () -> Unit,
    onConcurrency: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onStart(state.kind) },
                    enabled = !state.testing && state.rows.isNotEmpty(),
                ) {
                    Text(
                        stringResource(
                            if (state.kind == TesterKind.TUNNEL) {
                                R.string.tester_run_tunnel
                            } else {
                                R.string.tester_run_tcp
                            },
                        ),
                    )
                }
                if (state.testing) {
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.tester_cancel))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.tester_parallel, state.concurrency),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedButton(
                    onClick = { onConcurrency(state.concurrency - 1) },
                    enabled = !state.testing && state.concurrency > AppConfig.TESTER_CONCURRENCY_MIN,
                ) {
                    Text("−")
                }
                OutlinedButton(
                    onClick = { onConcurrency(state.concurrency + 1) },
                    enabled = !state.testing && state.concurrency < AppConfig.TESTER_CONCURRENCY_MAX,
                ) {
                    Text("+")
                }
            }
        }
    }
}

@Composable
private fun TesterServerCard(row: TesterRow, kind: TesterKind) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = detailLine(row),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            MeasurementBadge(row = row, kind = kind)
        }
    }
}

@Composable
private fun MeasurementBadge(row: TesterRow, kind: TesterKind) {
    when {
        row.running -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
        !row.measurement.hasMeasure(kind) -> Text(
            text = stringResource(R.string.tester_never),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        row.measurement.delayOf(kind) < 0L -> Text(
            text = stringResource(R.string.tester_failed),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            text = "${row.measurement.delayOf(kind)} ms",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun detailLine(row: TesterRow): String {
    val tunnel = measurementLabel(row, TesterKind.TUNNEL)
    val tcp = measurementLabel(row, TesterKind.TCP)
    val loss = row.lossPercent?.let { stringResource(R.string.tester_loss, it) }
        ?: stringResource(R.string.tester_loss_none)
    val throughput = stringResource(R.string.tester_throughput)
    return listOf(tunnel, tcp, loss, throughput).joinToString(" · ")
}

@Composable
private fun measurementLabel(row: TesterRow, kind: TesterKind): String {
    val prefix = stringResource(
        if (kind == TesterKind.TUNNEL) R.string.tester_tunnel_short else R.string.tester_tcp_short,
    )
    val value = when {
        !row.measurement.hasMeasure(kind) -> stringResource(R.string.tester_never)
        row.measurement.delayOf(kind) < 0L -> stringResource(R.string.tester_failed)
        else -> "${row.measurement.delayOf(kind)} ms"
    }
    val at = row.measurement.atOf(kind)
    val time = if (at > 0L) {
        " " + DateFormat.getTimeFormat(LocalContext.current).format(Date(at))
    } else {
        ""
    }
    return "$prefix $value$time"
}
