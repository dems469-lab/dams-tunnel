package com.v2ray.ang.premium

/** Scénarios locaux réservés au build TEST ; cette source n'effectue aucun accès réseau. */
enum class PremiumDemoScenario {
    ACTIVE,
    EXPIRED,
    QUOTA_EXHAUSTED,
}

internal class PremiumDemoSubscriptionDataSource(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : PremiumSubscriptionDataSource {
    @Volatile
    var scenario: PremiumDemoScenario = PremiumDemoScenario.ACTIVE
        private set

    fun selectScenario(value: PremiumDemoScenario) {
        scenario = value
    }

    override suspend fun activate(codeOrUrl: String): PremiumActivationResult.Imported = imported()

    override suspend fun refreshStoredToken(): PremiumActivationResult.Imported = imported()

    override fun readStoredTokenState(): PremiumStoredTokenResult = PremiumStoredTokenResult.Present

    override fun expiryState(): PremiumExpiryState = if (scenario == PremiumDemoScenario.EXPIRED) {
        PremiumExpiryState(
            firstConfirmedAtMillis = nowEpochSeconds() * 1000L - PremiumExpiryPolicy.PERMANENT_REMOVAL_DELAY_MILLIS,
            lastCountedConfirmationAtMillis = nowEpochSeconds() * 1000L,
            confirmationCount = 2,
        )
    } else {
        PremiumExpiryState()
    }

    override fun subscriptionInfo(): PremiumSubscriptionInfo = infoForScenario()

    override fun removeSubscriptionManually(): Int = 0

    private fun imported() = PremiumActivationResult.Imported(profileCount = 3, info = infoForScenario())

    private fun infoForScenario(): PremiumSubscriptionInfo {
        val now = nowEpochSeconds()
        return when (scenario) {
            PremiumDemoScenario.ACTIVE -> PremiumSubscriptionInfo(
                uploadBytes = 1_200_000_000L,
                downloadBytes = 2_300_000_000L,
                totalBytes = 20_000_000_000L,
                expiresAtEpochSeconds = now + 10L * 24L * 60L * 60L,
            )
            PremiumDemoScenario.EXPIRED -> PremiumSubscriptionInfo(
                uploadBytes = 100_000_000L,
                downloadBytes = 400_000_000L,
                totalBytes = 20_000_000_000L,
                expiresAtEpochSeconds = now - 60L,
            )
            PremiumDemoScenario.QUOTA_EXHAUSTED -> PremiumSubscriptionInfo(
                uploadBytes = 2_000_000_000L,
                downloadBytes = 3_000_000_000L,
                totalBytes = 5_000_000_000L,
                expiresAtEpochSeconds = now + 30L * 24L * 60L * 60L,
            )
        }
    }
}
