package com.v2ray.ang.premium

/** État persistant non secret du cycle d'expiration de l'abonnement Premium. */
data class PremiumExpiryState(
    val firstConfirmedAtMillis: Long? = null,
    val lastCountedConfirmationAtMillis: Long? = null,
    val confirmationCount: Int = 0,
    val profilesDeleted: Boolean = false,
) {
    val isExpired: Boolean get() = firstConfirmedAtMillis != null
}

enum class PremiumExpiryAction { ACTIVE, INDETERMINATE, MARK_EXPIRED, KEEP_EXPIRED, DELETE_PROFILES }

data class PremiumExpiryEvaluation(
    val state: PremiumExpiryState,
    val action: PremiumExpiryAction,
)

enum class PremiumExpiryObservation { HTTP_EXPIRED, HEADER_EXPIRED, ACTIVE, INDETERMINATE }

/** Toute interprétation des réponses HTTP/expiration Premium est centralisée ici. */
object PremiumExpiryPolicy {
    const val MIN_CONFIRMATION_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
    const val PERMANENT_REMOVAL_DELAY_MILLIS = 7L * 24L * 60L * 60L * 1000L

    fun observeHttpResponse(
        httpCode: Int,
        info: PremiumSubscriptionInfo?,
        hasUsableContent: Boolean,
        nowEpochSeconds: Long,
    ): PremiumExpiryObservation = when {
        httpCode == HTTP_FORBIDDEN || httpCode == HTTP_NOT_FOUND -> PremiumExpiryObservation.HTTP_EXPIRED
        httpCode in SUCCESS_RANGE &&
            PremiumSubscriptionInfoParser.status(info, nowEpochSeconds) == PremiumSubscriptionStatus.EXPIRED ->
            PremiumExpiryObservation.HEADER_EXPIRED
        httpCode in SUCCESS_RANGE && hasUsableContent -> PremiumExpiryObservation.ACTIVE
        else -> PremiumExpiryObservation.INDETERMINATE
    }

    fun responseMayContainSubscriptionContent(httpCode: Int): Boolean = httpCode in SUCCESS_RANGE

    /** Les secours doivent tous confirmer un 403/404; une date d'expiration échue suffit. */
    fun aggregate(observations: List<PremiumExpiryObservation>, endpointCount: Int): PremiumExpiryObservation = when {
        observations.any { it == PremiumExpiryObservation.HEADER_EXPIRED } -> PremiumExpiryObservation.HEADER_EXPIRED
        endpointCount > 0 && observations.size == endpointCount &&
            observations.all { it == PremiumExpiryObservation.HTTP_EXPIRED } -> PremiumExpiryObservation.HTTP_EXPIRED
        observations.any { it == PremiumExpiryObservation.ACTIVE } -> PremiumExpiryObservation.ACTIVE
        else -> PremiumExpiryObservation.INDETERMINATE
    }

    fun recordExpiration(previous: PremiumExpiryState, nowMillis: Long): PremiumExpiryEvaluation {
        if (previous.profilesDeleted) {
            return PremiumExpiryEvaluation(previous, PremiumExpiryAction.KEEP_EXPIRED)
        }
        val first = previous.firstConfirmedAtMillis
        if (first == null) {
            val next = PremiumExpiryState(
                firstConfirmedAtMillis = nowMillis,
                lastCountedConfirmationAtMillis = nowMillis,
                confirmationCount = 1,
            )
            return PremiumExpiryEvaluation(next, PremiumExpiryAction.MARK_EXPIRED)
        }

        val last = previous.lastCountedConfirmationAtMillis ?: first
        val count = if (nowMillis >= last && nowMillis - last >= MIN_CONFIRMATION_INTERVAL_MILLIS) {
            previous.confirmationCount + 1
        } else {
            previous.confirmationCount
        }
        val countedAt = if (count > previous.confirmationCount) nowMillis else last
        val remove = count >= REQUIRED_CONFIRMATIONS && nowMillis >= first &&
            nowMillis - first >= PERMANENT_REMOVAL_DELAY_MILLIS
        val next = previous.copy(
            lastCountedConfirmationAtMillis = countedAt,
            confirmationCount = count,
            profilesDeleted = remove,
        )
        return PremiumExpiryEvaluation(
            next,
            if (remove) PremiumExpiryAction.DELETE_PROFILES else PremiumExpiryAction.KEEP_EXPIRED,
        )
    }

    /** Une réponse valide non expirée remet à zéro la série; réseau/vides ne l'appellent pas. */
    fun recordActive(previous: PremiumExpiryState): PremiumExpiryEvaluation {
        val next = if (previous.isExpired || previous.profilesDeleted) PremiumExpiryState() else previous
        return PremiumExpiryEvaluation(next, PremiumExpiryAction.ACTIVE)
    }

    fun retainOnIndeterminate(previous: PremiumExpiryState): PremiumExpiryEvaluation =
        PremiumExpiryEvaluation(previous, if (previous.isExpired) PremiumExpiryAction.KEEP_EXPIRED else PremiumExpiryAction.INDETERMINATE)

    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val REQUIRED_CONFIRMATIONS = 2
    private val SUCCESS_RANGE = 200..299
}
