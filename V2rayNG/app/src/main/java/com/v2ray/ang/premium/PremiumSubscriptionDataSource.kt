package com.v2ray.ang.premium

/** Contrat de données isolant l'interface Premium du réseau, du chiffrement et du stockage MMKV. */
interface PremiumSubscriptionDataSource {
    suspend fun activate(codeOrUrl: String): PremiumActivationResult
    suspend fun refreshStoredToken(): PremiumActivationResult
    fun readStoredTokenState(): PremiumStoredTokenResult
    fun expiryState(): PremiumExpiryState
    fun subscriptionInfo(): PremiumSubscriptionInfo?
    fun removeSubscriptionManually(): Int
}
