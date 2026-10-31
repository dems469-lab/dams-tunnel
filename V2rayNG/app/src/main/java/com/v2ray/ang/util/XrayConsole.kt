package com.v2ray.ang.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Severity shown by the Xray console. */
enum class ConsoleLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    SYSTEM,
}

/** One immutable line of the in-memory console. */
data class ConsoleEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: ConsoleLevel,
    val tag: String,
    val message: String,
)

/**
 * Bounded, in-memory event buffer behind the Xray console.
 *
 * App events arrive through [LogUtil]; core events are streamed from logcat while the console
 * is visible. Recording is a single allocation and a list append under a short lock, so it
 * never blocks the VPN service, and the buffer is capped to keep memory flat.
 */
object XrayConsole {
    const val MAX_ENTRIES = 1500
    private const val MAX_MESSAGE_CHARS = 4000
    private const val CONTINUATION_WINDOW_MS = 2000L

    private val lock = Any()
    private val buffer = ArrayDeque<ConsoleEntry>()
    private var nextId = 0L
    private var version = 0L
    private val _revision = MutableStateFlow(0L)

    /** Bumped on every change so the UI can poll a snapshot instead of copying on each log. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile
    var enabled: Boolean = true

    fun record(priority: Int, tag: String, message: String, throwable: Throwable? = null) {
        val level = when {
            priority >= Log.ERROR -> ConsoleLevel.ERROR
            priority >= Log.WARN -> ConsoleLevel.WARNING
            priority >= Log.INFO -> ConsoleLevel.INFO
            else -> ConsoleLevel.DEBUG
        }
        append(level, tag, message, throwable)
    }

    fun event(tag: String, message: String) = append(ConsoleLevel.SYSTEM, tag, message, null)

    /** Adds a raw logcat line, merging indented stack-trace lines into the previous entry. */
    fun ingestLogcatLine(raw: String) {
        val parsed = parseLogcatLine(raw)
        if (parsed == null) {
            appendContinuation(raw)
        } else {
            append(parsed.level, parsed.tag, parsed.message, null)
        }
    }

    fun snapshot(): List<ConsoleEntry> = synchronized(lock) { buffer.toList() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            bumpVersion()
        }
    }

    private fun append(level: ConsoleLevel, tag: String, message: String, throwable: Throwable?) {
        if (!enabled) return
        val text = buildString {
            append(message)
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString())
            }
        }.let(LogRedactor::redact).take(MAX_MESSAGE_CHARS)

        synchronized(lock) {
            nextId += 1
            buffer.addLast(ConsoleEntry(nextId, System.currentTimeMillis(), level, tag, text))
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            bumpVersion()
        }
    }

    private fun appendContinuation(text: String) {
        if (!enabled || text.isBlank()) return
        val clean = LogRedactor.redact(text).take(MAX_MESSAGE_CHARS)
        synchronized(lock) {
            val last = buffer.removeLastOrNull() ?: return
            if (System.currentTimeMillis() - last.timestampMillis > CONTINUATION_WINDOW_MS) {
                buffer.addLast(last)
                return
            }
            buffer.addLast(last.copy(message = "${last.message}\n$clean".take(MAX_MESSAGE_CHARS)))
            bumpVersion()
        }
    }

    private fun bumpVersion() {
        version += 1
        _revision.value = version
    }
}

internal data class ParsedLogcatLine(
    val level: ConsoleLevel,
    val tag: String,
    val message: String,
)

private val LOGCAT_LINE = Regex(
    "^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+([VDIWEF])/([^(]+?)\\s*\\(\\s*\\d+\\):\\s?(.*)$"
)

internal fun parseLogcatLine(raw: String): ParsedLogcatLine? {
    val match = LOGCAT_LINE.matchEntire(raw) ?: return null
    val level = when (match.groupValues[1]) {
        "V", "D" -> ConsoleLevel.DEBUG
        "I" -> ConsoleLevel.INFO
        "W" -> ConsoleLevel.WARNING
        "E", "F" -> ConsoleLevel.ERROR
        else -> ConsoleLevel.INFO
    }
    return ParsedLogcatLine(level, match.groupValues[2].trim(), match.groupValues[3])
}
