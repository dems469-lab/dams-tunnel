package com.v2ray.ang.ui.logcat

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.ItemDivider
import com.v2ray.ang.ui.compose.NavigationBarsBottomPadding
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.ConsoleEntry
import com.v2ray.ang.util.ConsoleLevel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatActivity : BaseComponentActivity() {
    private val viewModel: LogcatViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        LogcatScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onSaveLogcat = { shareLogcat() }
        )
    }

    private fun shareLogcat() {
        lifecycleScope.launch(Dispatchers.IO) {
            val logText = viewModel.exportText()

            val result = try {
                val shareDir = File(cacheDir, "shared_logs").apply {
                    mkdirs()
                }

                shareDir.listFiles()?.forEach { it.delete() }

                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val logFile = File(shareDir, "dams_console_$timestamp.txt")
                logFile.writeText(logText, Charsets.UTF_8)

                val uri = FileProvider.getUriForFile(
                    this@LogcatActivity,
                    "${packageName}.cache",
                    logFile
                )

                uri to logFile.name
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to save the Xray console", e)
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, result.first)
                    putExtra(Intent.EXTRA_SUBJECT, result.second)
                    putExtra(Intent.EXTRA_TITLE, result.second)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newUri(contentResolver, result.second, result.first)
                }

                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        getString(R.string.logcat_share)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LogcatScreen(
    viewModel: LogcatViewModel,
    onBackClick: () -> Unit,
    onSaveLogcat: () -> Unit
) {
    val context = LocalContext.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward ->
            followTail = !canScrollForward
        }
    }

    LaunchedEffect(entries.size) {
        if (followTail && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.lastIndex)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.console_clear_title)) },
            text = { Text(stringResource(R.string.console_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearConsole()
                    confirmClear = false
                }) {
                    Text(stringResource(R.string.console_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_logcat),
                onBackClick = onBackClick,
                isSearchActive = showSearch,
                searchQuery = searchQuery,
                onSearchQueryChange = {
                    searchQuery = it
                    viewModel.setQuery(it)
                },
                onSearchClose = {
                    searchQuery = ""
                    viewModel.setQuery("")
                    showSearch = false
                },
                searchPlaceholder = stringResource(R.string.menu_item_search),
                actions = {
                    if (!showSearch) {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(
                                painterResource(R.drawable.ic_search_24dp),
                                contentDescription = stringResource(R.string.acc_search)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.copyVisible() }) {
                        Icon(
                            painterResource(R.drawable.ic_copy),
                            contentDescription = stringResource(R.string.acc_copy_log)
                        )
                    }
                    IconButton(onClick = { onSaveLogcat() }) {
                        Icon(
                            painterResource(R.drawable.ic_share_24dp),
                            contentDescription = stringResource(R.string.acc_save_log)
                        )
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(
                            painterResource(R.drawable.ic_delete_24dp),
                            contentDescription = stringResource(R.string.acc_clear_log)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ConsoleFilters(
                selected = filter,
                onSelected = viewModel::setFilter
            )
            if (selected.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    onCopy = viewModel::copySelection,
                    onClear = viewModel::clearSelection
                )
            }
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.console_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScrollbar(listState),
                    contentPadding = NavigationBarsBottomPadding()
                ) {
                    items(items = entries, key = { it.id }) { entry ->
                        ConsoleRow(
                            entry = entry,
                            timeLabel = timeFormat.format(Date(entry.timestampMillis)),
                            isSelected = entry.id in selected,
                            onClick = { viewModel.toggleSelection(entry.id) },
                            onLongClick = { Utils.setClipboard(context, entry.message) }
                        )
                        ItemDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleFilters(
    selected: ConsoleFilter,
    onSelected: (ConsoleFilter) -> Unit,
) {
    val labels = listOf(
        ConsoleFilter.ALL to R.string.console_filter_all,
        ConsoleFilter.INFO to R.string.console_filter_info,
        ConsoleFilter.WARNING to R.string.console_filter_warning,
        ConsoleFilter.ERROR to R.string.console_filter_error,
        ConsoleFilter.SYSTEM to R.string.console_filter_system,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(stringResource(label)) }
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.console_selected_count, count),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onCopy) {
            Text(stringResource(R.string.console_copy_selected))
        }
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.console_clear_selection))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRow(
    entry: ConsoleEntry,
    timeLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val levelColor = when (entry.level) {
        ConsoleLevel.ERROR -> scheme.error
        ConsoleLevel.WARNING -> scheme.tertiary
        ConsoleLevel.SYSTEM -> scheme.primary
        ConsoleLevel.INFO -> scheme.onSurfaceVariant
        ConsoleLevel.DEBUG -> scheme.outline
    }
    val background = when {
        isSelected -> scheme.primary.copy(alpha = 0.16f)
        entry.level == ConsoleLevel.ERROR -> scheme.errorContainer.copy(alpha = 0.35f)
        entry.level == ConsoleLevel.WARNING -> scheme.tertiaryContainer.copy(alpha = 0.30f)
        entry.level == ConsoleLevel.SYSTEM -> scheme.secondaryContainer.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val levelLabel = when (entry.level) {
        ConsoleLevel.ERROR -> R.string.console_level_error
        ConsoleLevel.WARNING -> R.string.console_level_warning
        ConsoleLevel.SYSTEM -> R.string.console_level_system
        ConsoleLevel.INFO -> R.string.console_level_info
        ConsoleLevel.DEBUG -> R.string.console_level_debug
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .semantics { selected = isSelected }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(levelColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(levelLabel),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = levelColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = if (entry.level == ConsoleLevel.ERROR) scheme.onErrorContainer else scheme.onSurface
        )
    }
}
