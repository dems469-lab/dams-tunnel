package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PremiumSubscriptionInfoParserTest {
    @Test
    fun `parse les champs Marzban et calcule le volume utilise`() {
        val info = PremiumSubscriptionInfoParser.parse(
            "upload=100; download=250; total=1000; expire=1900000000",
        )

        assertEquals(100L, info?.uploadBytes)
        assertEquals(250L, info?.downloadBytes)
        assertEquals(1000L, info?.totalBytes)
        assertEquals(1_900_000_000L, info?.expiresAtEpochSeconds)
        assertEquals(350L, info?.usedBytes)
    }

    @Test
    fun `en-tete absent ou entierement invalide reste inconnu`() {
        assertNull(PremiumSubscriptionInfoParser.parse(null))
        assertNull(PremiumSubscriptionInfoParser.parse("  "))
        assertNull(PremiumSubscriptionInfoParser.parse("upload=nope;other=12"))
    }

    @Test
    fun `champs partiels et malformes sont ignores sans exception`() {
        val info = PremiumSubscriptionInfoParser.parse(
            "upload=-1; download=20; total=bad; expire=0; total=100",
        )

        assertEquals(PremiumSubscriptionInfo(uploadBytes = null, downloadBytes = 20, totalBytes = 100, expiresAtEpochSeconds = 0), info)
    }

    @Test
    fun `addition des compteurs protegee contre le depassement`() {
        val info = PremiumSubscriptionInfo(uploadBytes = Long.MAX_VALUE, downloadBytes = 1)

        assertEquals(Long.MAX_VALUE, info.usedBytes)
    }

    @Test
    fun `statut distingue inconnu actif expirant expire et quota epuise`() {
        val now = 2_000_000_000L
        assertEquals(
            PremiumSubscriptionStatus.UNKNOWN,
            PremiumSubscriptionInfoParser.status(null, now),
        )
        assertEquals(
            PremiumSubscriptionStatus.ACTIVE,
            PremiumSubscriptionInfoParser.status(PremiumSubscriptionInfo(totalBytes = 100), now),
        )
        assertEquals(
            PremiumSubscriptionStatus.EXPIRING_SOON,
            PremiumSubscriptionInfoParser.status(
                PremiumSubscriptionInfo(expiresAtEpochSeconds = now + 86_400), now,
            ),
        )
        assertEquals(
            PremiumSubscriptionStatus.EXPIRED,
            PremiumSubscriptionInfoParser.status(
                PremiumSubscriptionInfo(expiresAtEpochSeconds = now), now,
            ),
        )
        assertEquals(
            PremiumSubscriptionStatus.QUOTA_EXHAUSTED,
            PremiumSubscriptionInfoParser.status(
                PremiumSubscriptionInfo(uploadBytes = 40, downloadBytes = 60, totalBytes = 100), now,
            ),
        )
    }

    @Test
    fun `total et expiration nuls correspondent a illimite ou inconnu`() {
        val info = PremiumSubscriptionInfoParser.parse("upload=10;download=20;total=0;expire=0")

        assertEquals(PremiumSubscriptionStatus.UNLIMITED, PremiumSubscriptionInfoParser.status(info, 2_000_000_000L))
    }
}
