package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DefaultServerSwitchPolicyTest {
    private val selected = ServerPerformanceCandidate("selected", "VLESS", 1200L)

    @Test
    fun `reste sur le profil selectionne quand option desactivee`() {
        assertNull(chooseFasterDefaultServer(false, true, selected, listOf(ServerPerformanceCandidate("fast", "VLESS", 20))))
    }

    @Test
    fun `ne bascule pas hors groupe par defaut`() {
        assertNull(chooseFasterDefaultServer(true, false, selected, listOf(ServerPerformanceCandidate("fast", "VLESS", 20))))
    }

    @Test
    fun `choisit seulement un protocole identique nettement plus rapide`() {
        assertEquals("fast", chooseFasterDefaultServer(true, true, selected, listOf(
            ServerPerformanceCandidate("other-protocol", "VMess", 10),
            ServerPerformanceCandidate("fast", "VLESS", 200),
        ))?.guid)
    }
}
