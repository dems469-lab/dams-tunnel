package com.v2ray.ang.ui.backup

import kotlinx.coroutines.CancellationException

/** Restores the saved state if an import fails after it has begun writing MMKV data. */
internal suspend fun restoreWithRollback(
    createSafetyCopy: suspend () -> Boolean,
    restoreImport: suspend () -> Boolean,
    restoreSafetyCopy: suspend () -> Boolean,
): Boolean {
    check(createSafetyCopy()) { "Unable to create a safety copy before restore" }
    try {
        if (restoreImport()) return true
    } catch (cancelled: CancellationException) {
        runCatching { restoreSafetyCopy() }
        throw cancelled
    } catch (failure: Exception) {
        if (!runCatching { restoreSafetyCopy() }.getOrDefault(false)) {
            failure.addSuppressed(IllegalStateException("Failed to restore the pre-import safety copy"))
        }
        throw failure
    }
    check(restoreSafetyCopy()) { "Failed to restore the pre-import safety copy" }
    return false
}
