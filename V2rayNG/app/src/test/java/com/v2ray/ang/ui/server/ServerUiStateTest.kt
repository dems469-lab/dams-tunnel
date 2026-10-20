package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerUiStateTest {
    @Test
    fun `new profile editor verifies certificates by default`() {
        assertFalse(ServerUiState(configType = EConfigType.VLESS).allowInsecure)
    }

    @Test
    fun `new profile model with secure default stays secure in editor`() {
        val newProfile = ProfileItem.create(EConfigType.VLESS).apply { insecure = false }

        assertFalse(ServerUiState.fromProfileItem(newProfile).allowInsecure)
    }

    @Test
    fun `legacy saved profile with missing insecure flag keeps previous editor behavior`() {
        val existingProfile = ProfileItem.create(EConfigType.VLESS)

        assertTrue(ServerUiState.fromProfileItem(existingProfile).allowInsecure)
    }
}
