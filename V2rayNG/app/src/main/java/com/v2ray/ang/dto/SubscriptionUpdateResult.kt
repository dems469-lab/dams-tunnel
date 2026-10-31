package com.v2ray.ang.dto

/**
 * Result of subscription update operation
 */
data class SubscriptionUpdateResult(
    val configCount: Int = 0,      // Total configs updated
    val successCount: Int = 0,     // Subscriptions updated successfully
    val failureCount: Int = 0,     // Subscriptions failed to update
    val skipCount: Int = 0,        // Subscriptions skipped (disabled)
    val ignoredConfigCount: Int = 0,
    val ignoredReasons: Map<String, Int> = emptyMap()
) {
    /**
     * Combine two results by adding their counts
     */
    operator fun plus(other: SubscriptionUpdateResult): SubscriptionUpdateResult {
        return SubscriptionUpdateResult(
            configCount = this.configCount + other.configCount,
            successCount = this.successCount + other.successCount,
            failureCount = this.failureCount + other.failureCount,
            skipCount = this.skipCount + other.skipCount,
            ignoredConfigCount = this.ignoredConfigCount + other.ignoredConfigCount,
            ignoredReasons = (this.ignoredReasons.keys + other.ignoredReasons.keys)
                .associateWith { (this.ignoredReasons[it] ?: 0) + (other.ignoredReasons[it] ?: 0) }
        )
    }
}
