package com.v2ray.ang.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DamsProfileLinkTest {
    @Test
    fun `compact Dams link round trips a profile URI`() {
        val profile = "vmess://eyJhZGQiOiJleGFtcGxlLmNvbSIsInBvcnQiOiI0NDMifQ"

        val link = AngConfigManager.encodeDamsProfileLink(profile)

        assertEquals("dams://dm1lc3M6Ly9leUpoWkdRaU9pSmxlR0Z0Y0d4bExtTnZiU0lzSW5CdmNuUWlPaUkwTkRNaWZR", link)
        assertEquals(profile, AngConfigManager.decodeDamsProfileLink(link))
    }

    @Test
    fun `legacy Dams link remains importable`() {
        val profile = "vless://uuid@example.com:443"
        val compact = AngConfigManager.encodeDamsProfileLink(profile)
        val legacy = "dams://profile/v1/${compact.removePrefix("dams://")}"

        assertEquals(profile, AngConfigManager.decodeDamsProfileLink(legacy))
    }
}
