package com.v2ray.ang.publicconfigs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FreeServersPresentationTest {
    @Test
    fun `active filter badge counts only the three bottom sheet filter groups`() {
        assertEquals(0, activePublicFilterCount(PublicServerFilters()))
        assertEquals(1, activePublicFilterCount(PublicServerFilters(completeness = PublicCompleteness.COMPLETE)))
        assertEquals(
            3,
            activePublicFilterCount(
                PublicServerFilters(
                    completeness = PublicCompleteness.PARTIAL,
                    availability = PublicAvailability.RESPONDED,
                    verification = PublicVerification.TESTED,
                    protocol = PublicProtocol.VLESS,
                    query = "test",
                )
            ),
        )
    }

    @Test
    fun `completeness maps to semantic status tones for themed colors`() {
        assertEquals(PublicStatusTone.SUCCESS, publicCompletenessTone(PublicCompleteness.COMPLETE))
        assertEquals(PublicStatusTone.WARNING, publicCompletenessTone(PublicCompleteness.PARTIAL))
        assertEquals(PublicStatusTone.ERROR, publicCompletenessTone(PublicCompleteness.INVALID))
        assertEquals(PublicStatusTone.NEUTRAL, publicCompletenessTone(PublicCompleteness.UNVERIFIED))
    }
}
