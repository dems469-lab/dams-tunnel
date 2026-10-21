package com.v2ray.ang.premium

/** Champs facultatifs de l'en-tête Marzban `subscription-userinfo`. */
data class PremiumSubscriptionInfo(
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochSeconds: Long? = null,
) {
    val usedBytes: Long?
        get() = if (uploadBytes == null || downloadBytes == null) null else {
            if (Long.MAX_VALUE - uploadBytes < downloadBytes) Long.MAX_VALUE
            else uploadBytes + downloadBytes
        }
}

enum class PremiumSubscriptionStatus {
    UNKNOWN,
    ACTIVE,
    EXPIRING_SOON,
    EXPIRED,
    QUOTA_EXHAUSTED,
}

/** Résultat pur, indépendant d'Android, pour tester les statuts sans réseau ni horloge système. */
object PremiumSubscriptionInfoParser {
    fun parse(header: String?): PremiumSubscriptionInfo? {
        if (header.isNullOrBlank()) return null
        val fields = mutableMapOf<String, Long>()
        header.split(';').forEach { fragment ->
            val separator = fragment.indexOf('=')
            if (separator <= 0 || separator == fragment.lastIndex) return@forEach
            val key = fragment.substring(0, separator).trim().lowercase()
            if (key !in KNOWN_FIELDS || key in fields) return@forEach
            val value = fragment.substring(separator + 1).trim().toLongOrNull()
                ?.takeIf { it >= 0 } ?: return@forEach
            fields[key] = value
        }
        if (fields.isEmpty()) return null
        return PremiumSubscriptionInfo(
            uploadBytes = fields["upload"],
            downloadBytes = fields["download"],
            totalBytes = fields["total"],
            expiresAtEpochSeconds = fields["expire"],
        )
    }

    fun status(
        info: PremiumSubscriptionInfo?,
        nowEpochSeconds: Long,
        expiringSoonWithinSeconds: Long = DEFAULT_EXPIRING_SOON_SECONDS,
    ): PremiumSubscriptionStatus {
        if (info == null) return PremiumSubscriptionStatus.UNKNOWN
        val expiry = info.expiresAtEpochSeconds?.takeIf { it > 0L }
        if (expiry != null && expiry <= nowEpochSeconds) return PremiumSubscriptionStatus.EXPIRED

        val total = info.totalBytes?.takeIf { it > 0L }
        val used = info.usedBytes
        if (total != null && used != null && used >= total) {
            return PremiumSubscriptionStatus.QUOTA_EXHAUSTED
        }
        if (expiry != null && expiry - nowEpochSeconds <= expiringSoonWithinSeconds) {
            return PremiumSubscriptionStatus.EXPIRING_SOON
        }
        return if (expiry != null || total != null) PremiumSubscriptionStatus.ACTIVE
        else PremiumSubscriptionStatus.UNKNOWN
    }

    private const val DEFAULT_EXPIRING_SOON_SECONDS = 3L * 24L * 60L * 60L
    private val KNOWN_FIELDS = setOf("upload", "download", "total", "expire")
}
