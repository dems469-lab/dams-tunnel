package com.v2ray.ang.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Sérialise les opérations de démarrage et d'arrêt du cœur. */
internal class CoreLoopLifecycleGate(
    private val stopTimeoutMillis: Long = DEFAULT_STOP_TIMEOUT_MILLIS,
) {
    private val mutex = Mutex()

    suspend fun <T> start(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun stop(block: suspend () -> Boolean): Boolean = mutex.withLock {
        withTimeoutOrNull(stopTimeoutMillis) {
            block()
        } ?: false
    }

    companion object {
        const val DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
