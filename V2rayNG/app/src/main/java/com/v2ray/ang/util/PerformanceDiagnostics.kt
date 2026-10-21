package com.v2ray.ang.util

import android.content.Context
import android.os.SystemClock
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mesures locales opt-in. Aucune URL, adresse IP, configuration ou identifiant n'est stocké. */
object PerformanceDiagnostics {
    private const val PREFS_NAME = "performance_diagnostics"
    private const val PREFS_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100

    private val coldStartRecorded = AtomicBoolean(false)
    private val homeRecompositions = AtomicInteger(0)
    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow = _enabledFlow.asStateFlow()
    @Volatile private var appContext: Context? = null
    @Volatile private var processStartedAt = 0L
    @Volatile private var enabled = false

    fun initialize(context: Context, startedAtElapsedRealtime: Long) {
        appContext = context.applicationContext
        processStartedAt = startedAtElapsedRealtime
        enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_DIAGNOSTIC_PERFORMANCE, false)
        _enabledFlow.value = enabled
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DIAGNOSTIC_PERFORMANCE, value)
        enabled = value
        _enabledFlow.value = value
        if (!value) clear()
    }

    fun refreshEnabled() = setEnabled(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DIAGNOSTIC_PERFORMANCE, false),
    )

    fun recordColdStart(firstDrawElapsedRealtime: Long) {
        if (!isEnabled() || !coldStartRecorded.compareAndSet(false, true)) return
        append("Démarrage à froid (début processus → premier affichage) : ${firstDrawElapsedRealtime - processStartedAt} ms")
    }

    fun recordScreenFirstDraw(screenName: String, durationMillis: Long) {
        if (!isEnabled()) return
        append("Premier affichage — $screenName : ${durationMillis.coerceAtLeast(0)} ms")
    }

    fun countHomeRecomposition() {
        if (isEnabled()) homeRecompositions.incrementAndGet()
    }

    fun recordHomeRecompositionsPerSecond() {
        if (!isEnabled()) return
        append("Actualisations de l'accueil : ${homeRecompositions.getAndSet(0)} / s")
    }

    fun snapshot(): List<String> {
        if (!isEnabled()) return emptyList()
        val context = appContext ?: return emptyList()
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_ENTRIES, "")
            .orEmpty()
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return (saved + "Mémoire Java utilisée : ${usedBytes / (1024 * 1024)} Mio").takeLast(MAX_ENTRIES + 1)
    }

    fun clear() {
        synchronized(this) {
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(PREFS_ENTRIES)?.apply()
        }
    }

    private fun append(entry: String) {
        val context = appContext ?: return
        synchronized(this) {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val entries = preferences.getString(PREFS_ENTRIES, "")
                .orEmpty()
                .lineSequence()
                .filter(String::isNotBlank)
                .toMutableList()
            entries += "${System.currentTimeMillis()} | $entry"
            preferences.edit().putString(PREFS_ENTRIES, entries.takeLast(MAX_ENTRIES).joinToString("\n")).apply()
        }
    }
}
