package com.v2ray.ang.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    @Test
    fun `redacts supported profile links including encoded payloads`() {
        listOf("dams://dm1lc3M6Ly9zZWNyZXQ", "vless://user@example.net:443", "vmess://eyJpZCI6InNlY3JldCJ9", "trojan://secret@example.net:443", "ss://YWVzLTI1Ni1nY206cGFzcw@example.net:443")
            .forEach { link -> assertFalse(LogRedactor.redact("import=$link").contains(link)) }
    }

    @Test
    fun `redacts URL credentials and secret query parameters`() {
        val result = LogRedactor.redact("https://alice:p%40ss@example.org/?password=hunter2&token=abc123")
        listOf("alice", "p%40ss", "hunter2", "abc123").forEach { assertFalse(result.contains(it)) }
    }

    @Test
    fun `redacts bracketed and bare IPv6 addresses`() {
        val result = LogRedactor.redact("host=[2001:db8::1]:443 remote 2001:4860:4860::8888")
        assertFalse(result.contains("2001:db8::1"))
        assertFalse(result.contains("2001:4860:4860::8888"))
    }

    @Test
    fun `connection failure report keeps protocol and error but masks outbound secrets and host`() {
        val result = LogRedactor.formatConnectionFailure(
            protocol = "VMESS",
            error = "xray handshake failed",
            generatedConfig = """{"address":"203.0.113.42","id":"uuid-secret","password":"pass-secret","serverName":"edge.example.net"}""",
        )

        assertTrue(result.contains("protocol=VMESS"))
        assertTrue(result.contains("xray handshake failed"))
        listOf("203.0.113.42", "uuid-secret", "pass-secret", "edge.example.net").forEach {
            assertFalse(result.contains(it))
        }
        assertTrue(result.contains("203.***.***.42"))
    }
}
