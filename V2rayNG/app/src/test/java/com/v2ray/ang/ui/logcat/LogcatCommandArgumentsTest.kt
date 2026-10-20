package com.v2ray.ang.ui.logcat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogcatCommandArgumentsTest {
    @Test
    fun `traffic statistics tag is omitted when diagnostic is disabled`() {
        val args = buildLogcatCommandArguments("com.v2ray.ang", includeTrafficStatsDiagnostics = false)

        assertFalse(args.contains("DamsTrafficStats:D"))
        assertTrue(args.contains("com.v2ray.ang:V"))
    }

    @Test
    fun `traffic statistics tag is included only when diagnostic is enabled`() {
        val args = buildLogcatCommandArguments("com.v2ray.ang", includeTrafficStatsDiagnostics = true)

        assertTrue(args.contains("DamsTrafficStats:D"))
    }
}
