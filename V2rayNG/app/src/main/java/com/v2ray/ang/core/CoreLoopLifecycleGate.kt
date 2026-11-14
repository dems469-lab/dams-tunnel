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

    /**
     * Le délai borne l'attente du verrou *et* le bloc : si un arrêt en cours
     * est bloqué dans le moteur natif (JNI non interruptible) et détient la
     * porte, l'arrêt demandé échoue après le délai au lieu de suspendre
     * indéfiniment. `withLock` garantit la libération du verrou en sortie.
     */
    suspend fun stop(block: suspend () -> Boolean): Boolean =
        withTimeoutOrNull(stopTimeoutMillis) {
            mutex.withLock { block() }
        } ?: false

    companion object {
        const val DEFAULT_STOP_TIMEOUT_MILLIS = 5_000L
    }
}
