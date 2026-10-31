package com.v2ray.ang.ui.logcat

import android.app.Application
import android.os.Build
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.ConsoleEntry
import com.v2ray.ang.util.ConsoleLevel
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.util.XrayConsole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConsoleFilter {
    ALL,
    INFO,
    WARNING,
    ERROR,
    SYSTEM,
}

class LogcatViewModel(application: Application) : BaseViewModel(application) {
    private val _allEntries = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    private val _filter = MutableStateFlow(ConsoleFilter.ALL)
    private val _query = MutableStateFlow("")
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())

    val filter: StateFlow<ConsoleFilter> = _filter.asStateFlow()
    val query: StateFlow<String> = _query.asStateFlow()
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    val entries: StateFlow<List<ConsoleEntry>> =
        combine(_allEntries, _filter, _query) { all, filter, query ->
            filterConsoleEntries(all, filter, query)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var streamJob: Job? = null

    init {
        recordEnvironment()
        observeConsole()
        startStreaming()
    }

    override fun onCleared() {
        streamJob?.cancel()
        streamJob = null
        super.onCleared()
    }

    fun setFilter(value: ConsoleFilter) {
        _filter.value = value
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun toggleSelection(id: Long) {
        _selected.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun copyVisible() {
        copyEntries(entries.value)
    }

    fun copySelection() {
        val selectedIds = _selected.value
        copyEntries(_allEntries.value.filter { it.id in selectedIds })
    }

    /** Text of the currently visible entries, used by the save action. */
    fun exportText(): String = formatConsoleEntries(entries.value)

    fun clearConsole() {
        XrayConsole.clear()
        _selected.value = emptySet()
        _allEntries.value = emptyList()
    }

    private fun copyEntries(list: List<ConsoleEntry>) {
        if (list.isEmpty()) {
            toast(R.string.console_empty)
            return
        }
        Utils.setClipboard(app, formatConsoleEntries(list))
        toastSuccess(R.string.toast_success)
    }

    private fun observeConsole() {
        viewModelScope.launch {
            // Coalesce bursts: at most one snapshot every 200 ms keeps the screen fluid while
            // the core logs. StateFlow already merges concurrent emissions, so no explicit
            // conflate is applied here.
            XrayConsole.revision.collect {
                delay(200)
                val snapshot = withContext(Dispatchers.Default) { XrayConsole.snapshot() }
                _allEntries.value = snapshot
                val aliveIds = snapshot.mapTo(HashSet()) { it.id }
                _selected.update { it.intersect(aliveIds) }
            }
        }
    }

    private fun recordEnvironment() {
        if (environmentRecorded) return
        environmentRecorded = true
        val mode = when {
            SettingsManager.isVpnMode() -> "VPN"
            SettingsManager.isRootMode() -> "Root"
            else -> "Proxy"
        }
        XrayConsole.event("System", "Dams Tunnel ${BuildConfig.VERSION_NAME}")
        XrayConsole.event("System", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
        XrayConsole.event("System", "Mode: $mode")
    }

    private fun startStreaming() {
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                val args = buildLogcatCommandArguments(
                    AppConfig.ANG_PACKAGE,
                    includeTrafficStatsDiagnostics = MmkvManager.decodeSettingsBool(
                        AppConfig.PREF_DIAGNOSTIC_TRAFFIC_STATS,
                        false,
                    ),
                )
                process = ProcessBuilder(*args).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        XrayConsole.ingestLogcatLine(line)
                    }
                }
            } catch (e: Exception) {
                if (isActive) LogUtil.e(AppConfig.TAG, "XrayConsole: log stream stopped", e)
            } finally {
                process?.destroy()
            }
        }
    }

    private companion object {
        @Volatile
        var environmentRecorded = false
    }
}

internal fun buildLogcatCommandArguments(
    packageName: String,
    includeTrafficStatsDiagnostics: Boolean,
): Array<String> {
    val filters = mutableListOf("GoLog:V", "$packageName:V", "AndroidRuntime:E", "System.err:W")
    if (includeTrafficStatsDiagnostics) filters += "DamsTrafficStats:D"
    return arrayOf("logcat", "-v", "time", "-s", *filters.toTypedArray())
}

internal fun matchesConsoleFilter(entry: ConsoleEntry, filter: ConsoleFilter): Boolean = when (filter) {
    ConsoleFilter.ALL -> true
    ConsoleFilter.INFO -> entry.level == ConsoleLevel.INFO || entry.level == ConsoleLevel.DEBUG
    ConsoleFilter.WARNING -> entry.level == ConsoleLevel.WARNING
    ConsoleFilter.ERROR -> entry.level == ConsoleLevel.ERROR
    ConsoleFilter.SYSTEM -> entry.level == ConsoleLevel.SYSTEM
}

internal fun filterConsoleEntries(
    entries: List<ConsoleEntry>,
    filter: ConsoleFilter,
    query: String,
): List<ConsoleEntry> {
    val needle = query.trim()
    return entries.filter { entry ->
        matchesConsoleFilter(entry, filter) &&
            (needle.isEmpty() ||
                entry.message.contains(needle, ignoreCase = true) ||
                entry.tag.contains(needle, ignoreCase = true))
    }
}
internal fun formatConsoleEntries(entries: List<ConsoleEntry>): String {
    val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return entries.joinToString("\n") { entry ->
        val time = timeFormat.format(Date(entry.timestampMillis))
        "$time  ${entry.level.name.padEnd(7)}  ${entry.tag}  ${entry.message}"
    }
}
