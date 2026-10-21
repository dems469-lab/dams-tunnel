package com.v2ray.ang.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReconnectVerificationWaitTest {
    @Test
    fun waitsForTheNewVerificationAndReturnsWhenItFinishes() = runBlocking {
        val oldJob = Job()
        var activeJob: Job? = oldJob
        val waiter = async {
            awaitNextReconnectVerification(oldJob, { activeJob }, timeoutMillis = 500)
        }
        delay(20)
        val nextJob = launch { delay(40) }
        activeJob = nextJob

        waiter.await()
        assertTrue(nextJob.isCompleted)
    }

    @Test
    fun missingVerificationTimesOutInsteadOfWaitingForTheFullReconnectWindow() = runBlocking {
        val startedAt = System.nanoTime()

        awaitNextReconnectVerification(previousJob = null, currentJob = { null }, timeoutMillis = 40)

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMillis < 500, "wait should remain bounded, was ${elapsedMillis}ms")
    }
}
