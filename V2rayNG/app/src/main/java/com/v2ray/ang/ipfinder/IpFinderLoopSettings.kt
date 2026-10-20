package com.v2ray.ang.ipfinder

interface IpFinderLoopSettingsStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

data class IpFinderLoopSettings(val cycles: String, val pauseSeconds: String)

class IpFinderLoopSettingsRepository(private val storage: IpFinderLoopSettingsStorage) {
    fun load(): IpFinderLoopSettings {
        val cycles = storage.read(CYCLES_KEY) ?: DEFAULT_CYCLES.also { storage.write(CYCLES_KEY, it) }
        val pause = storage.read(PAUSE_KEY) ?: DEFAULT_PAUSE.also { storage.write(PAUSE_KEY, it) }
        return IpFinderLoopSettings(cycles, pause)
    }

    fun saveCycles(value: String) = storage.write(CYCLES_KEY, value)
    fun savePause(value: String) = storage.write(PAUSE_KEY, value)
    fun markSearchPending() = storage.write(SEARCH_PENDING_KEY, "true")
    fun clearSearchPending() = storage.write(SEARCH_PENDING_KEY, "false")
    fun isSearchPending(): Boolean = storage.read(SEARCH_PENDING_KEY) == "true"

    companion object {
        const val DEFAULT_CYCLES = "10"
        const val DEFAULT_PAUSE = "5"
        const val CYCLES_KEY = "loop_cycles"
        const val PAUSE_KEY = "loop_pause_seconds"
        const val SEARCH_PENDING_KEY = "search_in_progress"
    }
}

/** Le marqueur de recherche ne peut être effacé que si aucune restauration n'est en attente. */
object IpFinderSearchRecoveryPolicy {
    fun canClearLoopMarker(recoveryPending: Boolean, airplaneModeEnabled: Boolean): Boolean =
        !recoveryPending && !airplaneModeEnabled
}

enum class LoopNumberError { EMPTY, NOT_INTEGER, OUT_OF_RANGE }

data class LoopNumberValidation(val value: Int? = null, val error: LoopNumberError? = null) {
    val isValid: Boolean get() = value != null && error == null
}

object IpFinderLoopNumberValidator {
    fun cycles(text: String): LoopNumberValidation = validate(text, 1, 1_000)
    fun pauseSeconds(text: String): LoopNumberValidation = validate(text, 3, 120)

    private fun validate(text: String, minimum: Int, maximum: Int): LoopNumberValidation {
        if (text.isEmpty()) return LoopNumberValidation(error = LoopNumberError.EMPTY)
        if (text.any { it !in '0'..'9' }) return LoopNumberValidation(error = LoopNumberError.NOT_INTEGER)
        val value = text.toIntOrNull() ?: return LoopNumberValidation(error = LoopNumberError.OUT_OF_RANGE)
        if (value !in minimum..maximum) return LoopNumberValidation(error = LoopNumberError.OUT_OF_RANGE)
        return LoopNumberValidation(value = value)
    }
}
