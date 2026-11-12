package com.v2ray.ang.laboratory.multipath

import com.v2ray.ang.laboratory.data.LaboratoryRepository
import com.v2ray.ang.util.LogUtil

/**
 * Journalisation du laboratoire.
 *
 * Les messages sont construits sans identifiant de connexion. [sanitize] ajoute
 * une seconde barrière : les UUID et les longues chaînes hexadécimales sont
 * masqués avant écriture, en base comme dans la console Xray.
 */
class MultipathEventLogger(
    private val repository: LaboratoryRepository,
) {

    fun info(category: String, message: String) = log(LEVEL_INFO, category, message)

    fun warn(category: String, message: String) = log(LEVEL_WARN, category, message)

    fun error(category: String, message: String) = log(LEVEL_ERROR, category, message)

    /** Enregistre un relais réel et le journalise. */
    fun relay(sessionId: Long?, event: RelayEvent) {
        repository.recordRelay(sessionId, event)
        val from = event.fromRole?.shortLabel ?: "?"
        info(
            CATEGORY_RELAY,
            "Relais $from → ${event.toRole.shortLabel} (${event.reason}, " +
                "${event.fromLatencyMillis} ms → ${event.toLatencyMillis} ms)",
        )
    }

    private fun log(level: String, category: String, message: String) {
        val safe = sanitize(message)
        repository.appendLog(level, category, safe)
        when (level) {
            LEVEL_ERROR -> LogUtil.e(TAG, "[$category] $safe")
            LEVEL_WARN -> LogUtil.w(TAG, "[$category] $safe")
            else -> LogUtil.i(TAG, "[$category] $safe")
        }
    }

    companion object {
        private const val TAG = "DamsMultipath"
        private const val MAX_MESSAGE_LENGTH = 240

        const val LEVEL_INFO = "INFO"
        const val LEVEL_WARN = "WARN"
        const val LEVEL_ERROR = "ERROR"

        const val CATEGORY_ENGINE = "MOTEUR"
        const val CATEGORY_PATH = "CHEMIN"
        const val CATEGORY_RELAY = "RELAIS"
        const val CATEGORY_SERVICE = "SERVICE"
        const val CATEGORY_STORAGE = "STOCKAGE"

        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val LONG_HEX_PATTERN = Regex("\\b[0-9a-fA-F]{20,}\\b")
        private val SECRET_ASSIGNMENT = Regex(
            "(?i)(password|passwd|uuid|id|token|key)\\s*[=:]\\s*\\S+",
        )

        fun sanitize(message: String): String {
            var value = message.replace('\n', ' ').replace('\r', ' ').trim()
            value = UUID_PATTERN.replace(value, "***")
            value = LONG_HEX_PATTERN.replace(value, "***")
            value = SECRET_ASSIGNMENT.replace(value) { match ->
                val key = match.groupValues[1]
                "$key=***"
            }
            if (value.length > MAX_MESSAGE_LENGTH) {
                value = value.take(MAX_MESSAGE_LENGTH - 1) + "…"
            }
            return value
        }
    }
}
