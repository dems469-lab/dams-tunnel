package com.v2ray.ang.service

/** Petite garde idempotente pour équilibrer l'acquisition et la libération du verrou CPU. */
internal class VpnWakeLockLease(
    private val acquireLock: () -> Unit,
    private val releaseLock: () -> Unit,
) {
    private var held = false

    @Synchronized
    fun acquire() {
        if (held) return
        acquireLock()
        held = true
    }

    @Synchronized
    fun release() {
        if (!held) return
        releaseLock()
        held = false
    }
}
