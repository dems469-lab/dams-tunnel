package com.v2ray.ang.reconfig.engine

import com.google.gson.JsonParser
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalReconfigurationEngineTest {

    private fun vless(address: String = "edge.example.com", port: String = "443") =
        ProfileItem.create(EConfigType.VLESS).apply {
            remarks = "Paris"
            server = address
            serverPort = port
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

    private fun transform(
        profile: ProfileItem,
        method: ReconfigMethod,
        target: String,
    ): ReconfigurationOutcome = LocalReconfigurationEngine.reconfigure(profile, method, target)

    @Test
    fun `vless api connect remplace l adresse et force le port 443`() {
        val original = vless()
        val outcome = transform(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf")

        assertTrue(outcome.isSuccess, "statut inattendu : ${outcome.status}")
        val transformed = outcome.transformed!!
        assertEquals("api-connect.orange.bf", transformed.server)
        assertEquals("443", transformed.serverPort)
        // L'original n'est jamais modifié.
        assertEquals("edge.example.com", original.server)
        // Tout le reste est préservé, y compris SNI et Host.
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
        val outcome = transform(original, ReconfigMethod.API_GATEWAY, "apigw-maxit2.orange.bf")

        assertTrue(outcome.isSuccess)
        val transformed = outcome.transformed!!
        assertEquals("apigw-maxit2.orange.bf", transformed.server)
        assertEquals("443", transformed.serverPort)
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
    fun `vmess conserve identifiant et parametres`() {
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
        val outcome = transform(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf")

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
    fun `trojan prefixe le domaine avec maxit`() {
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
        val outcome = transform(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")

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
    fun `le port d origine est toujours remplace par 443`() {
        val outcome = transform(vless(port = "8443"), ReconfigMethod.API_CONNECT, "api-connect.orange.bf")
        assertTrue(outcome.isSuccess)
        assertEquals("443", outcome.transformed!!.serverPort)
    }

    @Test
    fun `une adresse ip utilise le sni puis le host`() {
        val viaSni = transform(vless(address = "192.0.2.10"), ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertEquals("Maxit-mobile.orange.bf.sni.example.com", viaSni.transformed!!.server)

        val viaHost = transform(
            vless(address = "192.0.2.10").apply { sni = null },
            ReconfigMethod.MAXIT_MOBILE,
            "Maxit-mobile.orange.bf",
        )
        assertEquals("Maxit-mobile.orange.bf.host.example.com", viaHost.transformed!!.server)
    }

    @Test
    fun `une adresse ip sans sni ni host est laissee intacte`() {
        val original = vless(address = "192.0.2.10").apply {
            sni = null
            host = null
        }
        val outcome = transform(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertTrue(outcome.isSuccess)
        assertEquals("192.0.2.10", outcome.transformed!!.server)
    }

    @Test
    fun `une adresse ip avec api connect est remplacee directement`() {
        val outcome = transform(vless(address = "192.0.2.10"), ReconfigMethod.API_CONNECT, "api-connect.orange.bf")
        assertTrue(outcome.isSuccess)
        assertEquals("api-connect.orange.bf", outcome.transformed!!.server)
    }

    @Test
    fun `un prefixe deja present n est pas repete`() {
        val original = vless(address = "Maxit-mobile.orange.bf.edge.example.com")
        val outcome = transform(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertTrue(outcome.isSuccess)
        assertEquals("Maxit-mobile.orange.bf.edge.example.com", outcome.transformed!!.server)
        assertEquals("443", outcome.transformed!!.serverPort)
    }

    @Test
    fun `les hotes d api ne sont jamais modifies`() {
        listOf("api-connect.orange.bf", "apigw-maxit2.orange.bf").forEach { host ->
            val outcome = transform(vless(address = host), ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
            assertTrue(outcome.isSuccess, "hôte $host")
            assertEquals(host, outcome.transformed!!.server)
        }
    }

    @Test
    fun `une cible personnalisee est appliquee comme prefixe`() {
        val outcome = transform(vless(), ReconfigMethod.CUSTOM, "gateway.example.com")
        assertTrue(outcome.isSuccess)
        assertEquals("gateway.example.com.edge.example.com", outcome.transformed!!.server)
    }

    @Test
    fun `une cible personnalisee ipv4 est acceptee`() {
        val outcome = transform(vless(), ReconfigMethod.CUSTOM, "192.0.2.10")
        assertTrue(outcome.isSuccess)
        assertEquals("192.0.2.10.edge.example.com", outcome.transformed!!.server)
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
            transform(original, ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
    }

    @Test
    fun `refuse une adresse source vide`() {
        assertEquals(
            ReconfigurationStatus.INVALID_ADDRESS,
            transform(vless(address = ""), ReconfigMethod.API_CONNECT, "api-connect.orange.bf").status,
        )
    }

    @Test
    fun `refuse une cible personnalisee invalide`() {
        assertEquals(
            ReconfigurationStatus.INVALID_TARGET,
            transform(vless(), ReconfigMethod.CUSTOM, " ").status,
        )
        assertEquals(
            ReconfigurationStatus.INVALID_TARGET,
            transform(vless(), ReconfigMethod.CUSTOM, "https://x.example.com").status,
        )
        assertEquals(
            ReconfigurationStatus.INVALID_TARGET,
            transform(vless(), ReconfigMethod.CUSTOM, "2001:db8::1").status,
        )
    }

    @Test
    fun `un echec ne produit jamais de configuration`() {
        val outcome = transform(vless(), ReconfigMethod.CUSTOM, "")
        assertFalse(outcome.isSuccess)
        assertNull(outcome.transformed)
        assertNull(outcome.transformedConfig)
        assertEquals(ReconfigurationStatus.INVALID_TARGET, outcome.status)
    }

    @Test
    fun `l original n est jamais modifie`() {
        val original = vless()
        val before = original.copy()
        transform(original, ReconfigMethod.MAXIT_MOBILE, "Maxit-mobile.orange.bf")
        assertEquals(before, original)
    }

    @Test
    fun `processLink vless applique le prefixe et force le port`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@edge.example.com:8443" +
            "?type=ws&security=tls&sni=sni.example.com&host=host.example.com&path=%2Fray#Paris"
        val result = LocalReconfigurationEngine.processLink(link, "MAXIT")

        assertTrue(result.success, result.errorMessage.orEmpty())
        assertEquals(
            "vless://11111111-2222-3333-4444-555555555555@Maxit-mobile.orange.bf.edge.example.com:443" +
                "?type=ws&security=tls&sni=sni.example.com&host=host.example.com&path=%2Fray#Paris",
            result.newLink,
        )
    }

    @Test
    fun `processLink trojan accepte un mot de passe contenant un arobase`() {
        val link = "trojan://p@ss@trojan.example.com:443?security=tls&sni=sni.example.com#T"
        val result = LocalReconfigurationEngine.processLink(link, "MAXIT")

        assertTrue(result.success, result.errorMessage.orEmpty())
        assertEquals(
            "trojan://p@ss@Maxit-mobile.orange.bf.trojan.example.com:443?security=tls&sni=sni.example.com#T",
            result.newLink,
        )
    }

    @Test
    fun `processLink refuse un protocole inconnu`() {
        val result = LocalReconfigurationEngine.processLink("ss://abc@example.com:8388", "MAXIT")
        assertFalse(result.success)
        assertNull(result.newLink)
    }

    @Test
    fun `vless stocke un JSON Xray vnext et jamais le lien brut`() {
        val outcome = transform(vless(), ReconfigMethod.API_CONNECT, "api-connect.orange.bf")

        val config = outcome.transformedConfig
        assertTrue(config != null)
        assertFalse(config!!.startsWith("vless://"))
        assertTrue(outcome.transformed!!.insecure == true)

        val root = JsonParser.parseString(config).asJsonObject
        assertEquals("vless", root.get("protocol").asString)
        val settings = root.getAsJsonObject("settings")
        assertTrue(settings.has("vnext"))
        assertFalse(settings.has("servers"))
        assertEquals(
            "11111111-2222-3333-4444-555555555555",
            settings.getAsJsonArray("vnext")[0].asJsonObject
                .getAsJsonArray("users")[0].asJsonObject
                .get("id").asString,
        )
    }

    @Test
    fun `trojan stocke un JSON Xray servers et jamais vnext`() {
        val trojan = ProfileItem.create(EConfigType.TROJAN).apply {
            server = "trojan.example.com"
            serverPort = "8443"
            password = "p@ss"
            network = "ws"
            security = "tls"
            sni = "trojan.example.com"
            host = "trojan.example.com"
            path = "/trojanws"
        }

        val outcome = transform(trojan, ReconfigMethod.MAXIT_MOBILE, "")
        val config = outcome.transformedConfig
        assertTrue(config != null)

        val root = JsonParser.parseString(config!!).asJsonObject
        assertEquals("trojan", root.get("protocol").asString)
        val settings = root.getAsJsonObject("settings")
        assertTrue(settings.has("servers"))
        assertFalse(settings.has("vnext"))
        val server = settings.getAsJsonArray("servers")[0].asJsonObject
        assertEquals("p@ss", server.get("password").asString)
        assertEquals(443, server.get("port").asInt)
    }
}
