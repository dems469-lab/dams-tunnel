package com.v2ray.ang.publicconfigs

/** Presentation-only semantics shared by the free-server screen and its unit tests. */
internal enum class PublicStatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

internal fun activePublicFilterCount(filters: PublicServerFilters): Int =
    listOf(filters.completeness, filters.availability, filters.verification).count { it != null }

internal fun publicCompletenessTone(completeness: PublicCompleteness): PublicStatusTone = when (completeness) {
    PublicCompleteness.COMPLETE -> PublicStatusTone.SUCCESS
    PublicCompleteness.PARTIAL -> PublicStatusTone.WARNING
    PublicCompleteness.INVALID -> PublicStatusTone.ERROR
    PublicCompleteness.UNVERIFIED -> PublicStatusTone.NEUTRAL
}
