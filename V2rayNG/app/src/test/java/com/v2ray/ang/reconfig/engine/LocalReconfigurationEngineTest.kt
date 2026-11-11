package com.v2ray.ang.reconfig.engine

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReconfigurationEngineTest {

    // Le codec Android n'est pas disponible sur la JVM : la validation
    // structurelle, elle, est entièrement testée ici.
    private val engine = LocalReconfigurationEngine(codec = null)

    private fun vlessWs(): ProfileItem = ProfileItem.create(EConfigType.VLESS).apply {
        remarks = "Paris"
        server = "edge.example.com"
        serverPort = "443"
        password = "11111111-2222-3333-4444-555555555555"
        method = "none"
        network = "ws"
        security = "tls"
        sni = "sni.example.com"
        host = "host.example.com"
        path = "/ray"
        alpn = "h2,http/1.1"
        fingerPrint = "chrome"
        insecure = false
    }

    @Test
    fun `vless websocket tls remplace uniquement l adresse`() {
        val original = vlessWs()
        val outcome = engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf")

        assertTrue(outcome.isSuccess, "statut inattendu : ${outcome.status}")
        val transformed = outcome.transformed!!
        assertEquals("api-connect.orange.bf", transformed.server)
        // L'original n'est jamais modifié.
        assertEquals("edge.example.com", original.server)
        // Tout le reste est préservé, y compris SNI et Host.
        assertEquals("443", transformed.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", transformed.password)
        assertEquals("ws", transformed.network)
        assertEquals("tls", transformed.security)
        assertEquals("sni.example.com", transformed.sni)
        assertEquals("host.example.com", transformed.host)
        assertEquals("/ray", transformed.path)
        assertEquals("h2,http/1.1", transformed.alpn)
        assertEquals("chrome", transformed.fingerPrint)
        assertEquals(EConfigType.VLESS, transformed.configType)
    }

    @Test
    fun `vless grpc reality conserve service name et cles`() {
        val original = ProfileItem.create(EConfigType.VLESS).apply {
            server = "edge.example.com"
            serverPort = "8443"
            password = "11111111-2222-3333-4444-555555555555"
            network = "grpc"
            serviceName = "grpc-service"
            mode = "gun"
            authority = "authority.example.com"
            security = "reality"
            sni = "sni.example.com"
            publicKey = "public-key"
            shortId = "abcd"
            fingerPrint = "firefox"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.API_GATEWAY, "apigw-maxit2.orange.bf")

        assertTrue(outcome.isSuccess)
        val transformed = outcome.transformed!!
        assertEquals("apigw-maxit2.orange.bf", transformed.server)
        assertEquals("8443", transformed.serverPort)
        assertEquals("grpc", transformed.network)
        assertEquals("grpc-service", transformed.serviceName)
        assertEquals("gun", transformed.mode)
        assertEquals("authority.example.com", transformed.authority)
        assertEquals("reality", transformed.security)
        assertEquals("public-key", transformed.publicKey)
        assertEquals("abcd", transformed.shortId)
        assertEquals("sni.example.com", transformed.sni)
    }

    @Test
    fun `vmess websocket conserve identifiant et parametres`() {
        val original = ProfileItem.create(EConfigType.VMESS).apply {
            server = "vmess.example.com"
            serverPort = "443"
            password = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            method = "auto"
            network = "ws"
            security = "tls"
            sni = "sni.example.com"
            host = "cdn.example.com"
            path = "/vm"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf")

        assertTrue(outcome.isSuccess)
        val transformed = outcome.transformed!!
        assertEquals("api-connect.orange.bf", transformed.server)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", transformed.password)
        assertEquals("auto", transformed.method)
        assertEquals("ws", transformed.network)
        assertEquals("tls", transformed.security)
        assertEquals("sni.example.com", transformed.sni)
        assertEquals("cdn.example.com", transformed.host)
        assertEquals("/vm", transformed.path)
    }

    @Test
    fun `trojan websocket conserve le mot de passe et le transport`() {
        val original = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "trojan.example.com"
            serverPort = "443"
            password = "secret-password"
            network = "ws"
            security = "tls"
            sni = "sni.example.com"
            host = "cdn.example.com"
            path = "/tr"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")

        assertTrue(outcome.isSuccess)
        val transformed = outcome.transformed!!
        assertEquals("Maxit-mobile.orange.bf.trojan.example.com", transformed.server)
        assertEquals("secret-password", transformed.password)
        assertEquals("ws", transformed.network)
        assertEquals("tls", transformed.security)
        assertEquals("sni.example.com", transformed.sni)
        assertEquals("cdn.example.com", transformed.host)
        assertEquals("/tr", transformed.path)
        assertEquals("443", transformed.serverPort)
    }

    @Test
    fun `le prefixe n est applique qu une seule fois`() {
        val original = ProfileItem.create(EConfigType.VLESS).apply {
            server = "Maxit-mobile.orange.bf.edge.example.com"
            serverPort = "443"
            password = "uuid"
            security = "tls"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertEquals(ReconfigurationStatus.ALREADY_TRANSFORMED, outcome.status)
        assertNull(outcome.transformed)
    }

    @Test
    fun `le prefixe refuse une adresse ip`() {
        val original = ProfileItem.create(EConfigType.VLESS).apply {
            server = "192.0.2.10"
            serverPort = "443"
            password = "uuid"
            security = "tls"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertEquals(ReconfigurationStatus.INVALID_ADDRESS, outcome.status)
    }

    @Test
    fun `une adresse ip peut etre remplacee`() {
        val original = ProfileItem.create(EConfigType.VLESS).apply {
            server = "192.0.2.10"
            serverPort = "443"
            password = "uuid"
            security = "tls"
        }
        val outcome = engine.reconfigure(original, ReconfigMethod.CUSTOM, "gateway.example.com")
        assertTrue(outcome.isSuccess)
        assertEquals("gateway.example.com", outcome.transformed!!.server)
    }

    @Test
    fun `refuse un port invalide`() {
        val original = vlessWs().apply { serverPort = "0" }
        assertEquals(
            ReconfigurationStatus.INVALID_PORT,
            engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
        original.serverPort = "abc"
        assertEquals(
            ReconfigurationStatus.INVALID_PORT,
            engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
    }

    @Test
    fun `refuse un protocole non pris en charge`() {
        val original = ProfileItem.create(EConfigType.SHADOWSOCKS).apply {
            server = "ss.example.com"
            serverPort = "8388"
            password = "secret"
            method = "aes-256-gcm"
        }
        assertEquals(
            ReconfigurationStatus.UNSUPPORTED_PROTOCOL,
            engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
    }

    @Test
    fun `refuse une cible invalide`() {
        val original = vlessWs()
        assertEquals(
            ReconfigurationStatus.INVALID_TARGET,
            engine.reconfigure(original, ReconfigMethod.CUSTOM, " ").status,
        )
        assertEquals(
            ReconfigurationStatus.INVALID_TARGET,
            engine.reconfigure(original, ReconfigMethod.CUSTOM, "https://x.example.com").status,
        )
    }

    @Test
    fun `refuse une adresse source vide`() {
        val original = vlessWs().apply { server = "" }
        assertEquals(
            ReconfigurationStatus.INVALID_ADDRESS,
            engine.reconfigure(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
    }

    @Test
    fun `la methode personnalisee accepte un domaine ou une ip`() {
        val original = vlessWs()
        assertTrue(engine.reconfigure(original, ReconfigMethod.CUSTOM, "gateway.example.com").isSuccess)
        assertTrue(engine.reconfigure(original, ReconfigMethod.CUSTOM, "192.0.2.10").isSuccess)
    }

    @Test
    fun `un echec ne produit jamais de configuration`() {
        val outcome = engine.reconfigure(vlessWs(), ReconfigMethod.MAXIT_MOBILE, "")
        assertFalse(outcome.isSuccess)
        assertNull(outcome.transformed)
        assertNull(outcome.transformedConfig)
        assertEquals(ReconfigurationStatus.INVALID_TARGET, outcome.status)
    }
}
