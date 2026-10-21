package com.v2ray.ang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DamsReleaseDestinationTest {
    @Test
    fun updateDestinationIsTheDamsReleasePage() {
        assertEquals(
            "https://github.com/dems469-lab/dams-tunnel/releases",
            AppConfig.DAMS_RELEASES_URL,
        )
        assertFalse(AppConfig.DAMS_RELEASES_URL.contains("2dust", ignoreCase = true))
    }
}
