package com.v2ray.ang.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Attend la vérification lancée par une tentative, sans bloquer les tentatives suivantes. */
internal suspend fun awaitNextReconnectVerification(
    previousJob: Job?,
    currentJob: () -> Job?,
    timeoutMillis: Long = DEFAULT_RECONNECT_VERIFICATION_TIMEOUT_MILLIS,
) {
    withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val verification = currentJob()
            if (verification != null && verification !== previousJob) {
                verification.join()
                return@withTimeoutOrNull
            }
            delay(100L)
        }
    }
}

internal const val DEFAULT_RECONNECT_VERIFICATION_TIMEOUT_MILLIS = 12_000L
