package com.v2ray.ang.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun `redacts identifiers addresses and hosts`() {
        val result = LogRedactor.redact(
            "uuid=6ef396d0-c711-11f1-b734-205c6d5f5d78 host=example.net ip=192.0.2.1"
        )
        assertFalse(result.contains("6ef396d0-c711-11f1-b734-205c6d5f5d78"))
        assertFalse(result.contains("example.net"))
        assertFalse(result.contains("192.0.2.1"))
        assertTrue(result.contains("masqué"))
    }
}
