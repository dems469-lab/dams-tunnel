package com.v2ray.ang.core

/** Automatic failover is opt-in and restricted to the built-in default group. */
internal fun shouldTryDefaultServerFailover(
    enabled: Boolean,
    selectedSubscriptionId: String?,
    defaultSubscriptionId: String,
): Boolean = enabled && selectedSubscriptionId == defaultSubscriptionId
