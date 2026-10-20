package com.v2ray.ang.dto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class V2rayNShareItemTest {
    @Test
    fun `allow insecure accepts numeric exports`() {
        assertTrue(item("1").toProfileItem().insecure == true)
        assertFalse(item("0").toProfileItem().insecure == true)
    }

    @Test
    fun `missing or unknown allow insecure defaults to certificate verification`() {
        assertFalse(item(null).toProfileItem().insecure == true)
        assertFalse(item("unexpected").toProfileItem().insecure == true)
    }

    private fun item(allowInsecure: String?): V2rayNShareItem = V2rayNShareItem(
        IndexId = null,
        ConfigType = 6,
        ConfigVersion = null,
        Subid = null,
        IsSub = null,
        PreSocksPort = null,
        Remarks = "test",
        Address = "example.com",
        Port = 443,
        Password = "secret",
        Username = null,
        Network = "ws",
        StreamSecurity = "tls",
        AllowInsecure = allowInsecure,
        Sni = "example.com",
        Alpn = null,
        Fingerprint = null,
        PublicKey = null,
        ShortId = null,
        SpiderX = null,
        Mldsa65Verify = null,
        CertSha = null,
        EchConfigList = null,
        VerifyPeerCertByName = null,
        Finalmask = null,
        ProtoExtraObj = null,
        TransportExtraObj = V2rayNShareItem.V2rayNTransportExtraShareItem(
            RawHeaderType = null,
            Host = null,
            Path = "/ws",
            XhttpMode = null,
            XhttpExtra = null,
            GrpcAuthority = null,
            GrpcServiceName = null,
            GrpcMode = null,
            KcpHeaderType = null,
            KcpSeed = null,
            KcpMtu = null,
        ),
    )
}
