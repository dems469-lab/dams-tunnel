package com.v2ray.ang.premium

/** Identité stable du groupe Premium ; ce groupe n'est pas un abonnement standard. */
object PremiumGroup {
    const val ID = "dams-premium-v1"
    const val NAME = "Premium"
}

/** Règles pures d'accès aux profils Premium. */
internal object PremiumProfilePolicy {
    fun shouldShowProfiles(featureEnabled: Boolean, profileCount: Int): Boolean =
        featureEnabled && profileCount > 0

    fun blocksConnection(groupId: String?, subscriptionExpired: Boolean): Boolean =
        PremiumIsolationPolicy.isPremiumGroup(groupId) && subscriptionExpired

    fun isShareAllowed(groupId: String?): Boolean = !PremiumIsolationPolicy.isPremiumGroup(groupId)

    fun <T> appendVisibleGroup(
        groups: List<T>,
        premiumGroup: T,
        featureEnabled: Boolean,
        profileCount: Int,
    ): List<T> = if (shouldShowProfiles(featureEnabled, profileCount)) {
        groups + premiumGroup
    } else {
        groups
    }
}

/** Règles partagées par les opérations globales et leurs tests JVM. */
internal object PremiumIsolationPolicy {
    fun isPremiumGroup(groupId: String?): Boolean = groupId == PremiumGroup.ID

    fun includeInStandardSubscriptionUpdate(groupId: String): Boolean = !isPremiumGroup(groupId)

    fun includeInGeneralCleanup(groupId: String?): Boolean = !isPremiumGroup(groupId)

    fun retainFromGlobalDeletion(groupId: String?): Boolean = isPremiumGroup(groupId)

    fun <T> filterGeneralCleanup(items: Iterable<T>, groupId: (T) -> String?): List<T> =
        items.filter { includeInGeneralCleanup(groupId(it)) }

    fun <T> retainPremium(items: Iterable<T>, groupId: (T) -> String?): List<T> =
        items.filter { retainFromGlobalDeletion(groupId(it)) }
}
