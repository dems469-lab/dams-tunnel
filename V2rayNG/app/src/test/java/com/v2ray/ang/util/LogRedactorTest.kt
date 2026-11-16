package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogRedactorTest {
    @Test
    fun `keeps hosts addresses ports and stack frames readable`() {
        val line = "failed to connect to vpn.example.net/203.0.113.9 (port 8442) " +
            "at com.v2ray.ang.core.CoreNativeManager.measureOutboundDelay(Native Method)"
        val result = LogRedactor.redact(line)
        assertTrue(result.contains("vpn.example.net"))
        assertTrue(result.contains("203.0.113.9"))
        assertTrue(result.contains("8442"))
        assertTrue(result.contains("com.v2ray.ang.core.CoreNativeManager.measureOutboundDelay"))
        assertFalse(result.contains("masqué"))
    }

    @Test
    fun `keeps bracketed and bare IPv6 addresses readable`() {
        val result = LogRedactor.redact("host=[2001:db8::1]:443 remote 2001:4860:4860::8888")
        assertTrue(result.contains("[2001:db8::1]:443"))
        assertTrue(result.contains("2001:4860:4860::8888"))
    }

    @Test
    fun `keeps full error messages and URLs readable`() {
        val result = LogRedactor.redact(
            "Get \"https://www.gstatic.com/generate_204\": net/http: TLS handshake timeout",
        )
        assertTrue(result.contains("https://www.gstatic.com/generate_204"))
        assertTrue(result.contains("TLS handshake timeout"))
    }

    @Test
    fun `redacts authentication identifiers`() {
        val result = LogRedactor.redact(
            "uuid=6ef396d0-c711-11f1-b734-205c6d5f5d78 host=example.net ip=192.0.2.1",
        )
        assertFalse(result.contains("6ef396d0-c711-11f1-b734-205c6d5f5d78"))
        assertTrue(result.contains("example.net"))
        assertTrue(result.contains("192.0.2.1"))
    }

    @Test
    fun `redacts supported profile links including encoded payloads`() {
        listOf(
            "dams://dm1lc3M6Ly9zZWNyZXQ",
            "vless://user@example.net:443",
            "vmess://eyJpZCI6InNlY3JldCJ9",
            "trojan://secret@example.net:443",
            "ss://YWVzLTI1Ni1nY206cGFzcw@example.net:443",
        ).forEach { link -> assertFalse(LogRedactor.redact("import=$link").contains(link)) }
    }

    @Test
    fun `redacts URL credentials and secret query parameters`() {
        val result = LogRedactor.redact("https://alice:p%40ss@example.org/?password=hunter2&token=abc123")
        listOf("alice", "p%40ss", "hunter2", "abc123").forEach { assertFalse(result.contains(it)) }
    }
}
