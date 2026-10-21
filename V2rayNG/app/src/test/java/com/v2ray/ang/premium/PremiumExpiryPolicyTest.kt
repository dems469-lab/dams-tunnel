package com.v2ray.ang.premium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PremiumExpiryPolicyTest {
    @Test
    fun `first expiry confirmation marks expired and retains profiles`() {
        val first = PremiumExpiryPolicy.recordExpiration(PremiumExpiryState(), NOW)

        assertEquals(PremiumExpiryAction.MARK_EXPIRED, first.action)
        assertTrue(first.state.isExpired)
        assertEquals(1, first.state.confirmationCount)
        assertFalse(first.state.profilesDeleted)
    }

    @Test
    fun `second confirmation before six hours does not count`() {
        val first = PremiumExpiryPolicy.recordExpiration(PremiumExpiryState(), NOW).state
        val second = PremiumExpiryPolicy.recordExpiration(
            first,
            NOW + PremiumExpiryPolicy.MIN_CONFIRMATION_INTERVAL_MILLIS - 1,
        )

        assertEquals(1, second.state.confirmationCount)
        assertEquals(PremiumExpiryAction.KEEP_EXPIRED, second.action)
        assertFalse(second.state.profilesDeleted)
    }

    @Test
    fun `two confirmations six hours apart retain profiles until seven days`() {
        val first = PremiumExpiryPolicy.recordExpiration(PremiumExpiryState(), NOW).state
        val second = PremiumExpiryPolicy.recordExpiration(
            first,
            NOW + PremiumExpiryPolicy.MIN_CONFIRMATION_INTERVAL_MILLIS,
        )

        assertEquals(2, second.state.confirmationCount)
        assertEquals(PremiumExpiryAction.KEEP_EXPIRED, second.action)
        assertFalse(second.state.profilesDeleted)
    }

    @Test
    fun `profiles are deleted only after two confirmations and seven days`() {
        val first = PremiumExpiryPolicy.recordExpiration(PremiumExpiryState(), NOW).state
        val afterSixHours = PremiumExpiryPolicy.recordExpiration(
            first,
            NOW + PremiumExpiryPolicy.MIN_CONFIRMATION_INTERVAL_MILLIS,
        ).state
        val afterSevenDays = PremiumExpiryPolicy.recordExpiration(
            afterSixHours,
            NOW + PremiumExpiryPolicy.PERMANENT_REMOVAL_DELAY_MILLIS,
        )

        assertEquals(PremiumExpiryAction.DELETE_PROFILES, afterSevenDays.action)
        assertTrue(afterSevenDays.state.profilesDeleted)
    }

    @Test
    fun `active response resets expiry confirmations`() {
        val expired = PremiumExpiryState(NOW, NOW, confirmationCount = 1)

        assertEquals(PremiumExpiryState(), PremiumExpiryPolicy.recordActive(expired).state)
        assertTrue(expired.isExpired)
    }

    @Test
    fun `indeterminate refresh retains expired state without deleting profiles`() {
        val expired = PremiumExpiryState(NOW, NOW, confirmationCount = 1)
        val evaluation = PremiumExpiryPolicy.retainOnIndeterminate(expired)

        assertEquals(expired, evaluation.state)
        assertEquals(PremiumExpiryAction.KEEP_EXPIRED, evaluation.action)
    }

    @Test
    fun `403 and 404 confirm expiry while other failures do not`() {
        assertEquals(
            PremiumExpiryObservation.HTTP_EXPIRED,
            PremiumExpiryPolicy.observeHttpResponse(403, null, false, NOW / 1000),
        )
        assertEquals(
            PremiumExpiryObservation.HTTP_EXPIRED,
            PremiumExpiryPolicy.observeHttpResponse(404, null, false, NOW / 1000),
        )
        assertEquals(
            PremiumExpiryObservation.INDETERMINATE,
            PremiumExpiryPolicy.observeHttpResponse(500, null, false, NOW / 1000),
        )
    }

    @Test
    fun `expired date in user info confirms expiry`() {
        val observation = PremiumExpiryPolicy.observeHttpResponse(
            httpCode = 200,
            info = PremiumSubscriptionInfo(expiresAtEpochSeconds = NOW / 1000),
            hasUsableContent = true,
            nowEpochSeconds = NOW / 1000,
        )

        assertEquals(PremiumExpiryObservation.HEADER_EXPIRED, observation)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
