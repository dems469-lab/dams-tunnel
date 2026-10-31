package com.v2ray.ang.core

/** Pure policy for health failures and reconnect pacing. */
internal class ConsecutiveHealthFailures(private val threshold: Int = 3) {
    private var failures = 0

    fun record(reachable: Boolean): Boolean {
        failures = if (reachable) 0 else failures + 1
        return failures >= threshold
    }
}

internal fun reconnectBackoffMillis(attempt: Int): Long {
    val delays = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    return delays[attempt.coerceAtLeast(0).coerceAtMost(delays.lastIndex)]
}

internal fun shouldTryFailoverAfterReconnect(attempt: Int): Boolean =
    attempt >= 3 && (attempt - 3) % 3 == 0
