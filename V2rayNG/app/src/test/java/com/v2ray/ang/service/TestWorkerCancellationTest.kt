package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TestWorkerCancellationTest {
    @Test
    fun `request cancels only its own worker`() {
        assertTrue(shouldCancelTestRequest("public-a", "public-a"))
        assertFalse(shouldCancelTestRequest("public-a", "main-bulk"))
        assertTrue(shouldCancelTestRequest("public-a", ""))
    }
}
