package com.v2ray.ang.ui.main

import java.util.Locale

/**
 * Valeurs affichées par l'écran d'accueil.
 *
 * Tout ce qui est visible ici est calculé à partir de mesures réelles du moteur
 * (débits Xray, latence de session, octets du tunnel) ou de l'état observé du
 * service. Aucune valeur n'est simulée : une mesure absente reste « Indisponible »
 * et n'est jamais remplacée par un nombre inventé.
 */

/** État du bouton Power, dérivé uniquement de l'état réel du service. */
internal enum class HomePowerState {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Error,
}

internal fun homePowerState(
    status: MainStatus,
    isRunning: Boolean,
    diagnostic: ConnectionDiagnostic?,
): HomePowerState = when {
    isRunning && (status == MainStatus.Connecting) -> HomePowerState.Connecting
    isRunning && (status == MainStatus.Reconnecting) -> HomePowerState.Reconnecting
    isRunning -> HomePowerState.Connected
    diagnostic?.hasFailure == true -> HomePowerState.Error
    else -> HomePowerState.Disconnected
}

private val ConnectionDiagnostic?.hasFailure: Boolean
    get() = this != null && (networkAvailable == false || serverReachable == false || tunnelVerified == false)

/** Qualité perçue, calculée à partir de la latence réellement mesurée. */
internal enum class ConnectionQuality {
    Unavailable,
    Weak,
    Fair,
    Good,
    Excellent,
}

internal fun connectionQuality(
    isConnected: Boolean,
    delayMillis: Long?,
): ConnectionQuality {
    if (!isConnected) return ConnectionQuality.Unavailable
    val delay = delayMillis?.takeIf { it >= 0L } ?: return ConnectionQuality.Unavailable
    return when {
        delay <= 60L -> ConnectionQuality.Excellent
        delay <= 120L -> ConnectionQuality.Good
        delay <= 220L -> ConnectionQuality.Fair
        else -> ConnectionQuality.Weak
    }
}

/** Nombre de barres de signal (0 = mesure indisponible, 4 = meilleur). */
internal fun signalBarCount(delayMillis: Long?): Int {
    val delay = delayMillis?.takeIf { it >= 0L } ?: return 0
    return when {
        delay <= 80L -> 4
        delay <= 150L -> 3
        delay <= 250L -> 2
        else -> 1
    }
}

/** Débit en Mbps, arrondi au dixième, à partir des octets par seconde du moteur. */
internal fun bytesPerSecondToMbps(bytesPerSecond: Long): Double =
    (bytesPerSecond.coerceAtLeast(0L) * 8.0) / 1_000_000.0

internal fun formatMbps(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f", bytesPerSecondToMbps(bytesPerSecond))

/** Chronomètre de session au format HH:MM:SS. */
internal fun formatConnectionDuration(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

/**
 * Ramène un échantillon de débit à une hauteur relative [0f..1f] pour le
 * graphique. Une série vide renvoie une liste vide : le graphique reste alors
 * vide au lieu d'afficher une courbe inventée.
 */
internal fun sparklineFractions(values: List<Long>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.max().coerceAtLeast(1L)
    return values.map { (it.coerceAtLeast(0L).toFloat() / max.toFloat()).coerceIn(0f, 1f) }
}

/**
 * Nom affichable d'une configuration.
 *
 * Un « remarques » saisi par l'utilisateur peut contenir un nom d'hôte, une
 * adresse ou un port : dans ce cas il est remplacé par un libellé générique.
 * Seul un véritable alias lisible est affiché tel quel ; la configuration
 * réelle n'est jamais exposée.
 */
internal fun publicServerAlias(remarks: String?, fallback: String): String {
    val text = remarks?.trim().orEmpty()
    if (text.isEmpty()) return fallback
    return if (looksLikeConnectionData(text)) fallback else text
}

private val schemePattern = Regex("[A-Za-z][A-Za-z0-9+.-]*://")
private val credentialPattern = Regex("@")
private val uuidPattern = Regex(
    "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"
)
private val ipv4Pattern = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
private val ipv6Pattern = Regex("(?i)(?:\\[[0-9a-f:]{3,}]|\\b(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}\\b)")
private val hostPattern = Regex("(?i)\\b[\\w-]+(?:\\.[\\w-]+)*\\.[a-z]{2,}\\b(?::\\d{1,5})?")
private val portSuffixPattern = Regex(":\\d{1,5}\\b")

internal fun looksLikeConnectionData(text: String): Boolean =
    schemePattern.containsMatchIn(text) ||
        credentialPattern.containsMatchIn(text) ||
        uuidPattern.containsMatchIn(text) ||
        ipv4Pattern.containsMatchIn(text) ||
        ipv6Pattern.containsMatchIn(text) ||
        hostPattern.containsMatchIn(text) ||
        portSuffixPattern.containsMatchIn(text)

private val accentRemovals = mapOf(
    'à' to 'a', 'â' to 'a', 'ä' to 'a', 'á' to 'a', 'ã' to 'a', 'å' to 'a',
    'ç' to 'c', 'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
    'ñ' to 'n', 'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'ö' to 'o', 'õ' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ý' to 'y', 'ÿ' to 'y',
)

internal fun normalizeAlias(alias: String): String =
    alias.lowercase(Locale.ROOT).map { accentRemovals[it] ?: it }.joinToString("")

private data class CountryAlias(val flag: String, val keywords: List<String>)

private val countryAliases = listOf(
    CountryAlias("🇫🇷", listOf("france", "paris", "marseille", "lyon")),
    CountryAlias("🇩🇪", listOf("allemagne", "germany", "frankfurt", "berlin", "munich")),
    CountryAlias("🇳🇱", listOf("pays-bas", "pays bas", "netherlands", "amsterdam", "hollande")),
    CountryAlias("🇬🇧", listOf("royaume-uni", "royaume uni", "united kingdom", "uk", "londres", "london", "manchester")),
    CountryAlias("🇺🇸", listOf("etats-unis", "etats unis", "united states", "usa", "new york", "los angeles", "dallas", "seattle", "miami", "chicago")),
    CountryAlias("🇨🇦", listOf("canada", "montreal", "toronto", "vancouver")),
    CountryAlias("🇨🇭", listOf("suisse", "switzerland", "zurich", "geneve")),
    CountryAlias("🇸🇪", listOf("suede", "sweden", "stockholm")),
    CountryAlias("🇳🇴", listOf("norvege", "norway", "oslo")),
    CountryAlias("🇫🇮", listOf("finlande", "finland", "helsinki")),
    CountryAlias("🇩🇰", listOf("danemark", "denmark", "copenhagen")),
    CountryAlias("🇪🇸", listOf("espagne", "spain", "madrid", "barcelone")),
    CountryAlias("🇮🇹", listOf("italie", "italy", "milan", "rome")),
    CountryAlias("🇵🇹", listOf("portugal", "lisbonne")),
    CountryAlias("🇧🇪", listOf("belgique", "belgium", "bruxelles")),
    CountryAlias("🇦🇹", listOf("autriche", "austria", "vienne")),
    CountryAlias("🇵🇱", listOf("pologne", "poland", "varsovie")),
    CountryAlias("🇨🇿", listOf("republique tcheque", "czech", "prague")),
    CountryAlias("🇷🇴", listOf("roumanie", "romania", "bucarest")),
    CountryAlias("🇹🇷", listOf("turquie", "turkey", "istanbul")),
    CountryAlias("🇷🇺", listOf("russie", "russia", "moscou", "moscow")),
    CountryAlias("🇺🇦", listOf("ukraine", "kiev", "kyiv")),
    CountryAlias("🇯🇵", listOf("japon", "japan", "tokyo", "osaka")),
    CountryAlias("🇰🇷", listOf("coree", "korea", "seoul")),
    CountryAlias("🇨🇳", listOf("chine", "china", "shanghai")),
    CountryAlias("🇭🇰", listOf("hong kong", "hongkong")),
    CountryAlias("🇸🇬", listOf("singapour", "singapore")),
    CountryAlias("🇮🇳", listOf("inde", "india", "mumbai")),
    CountryAlias("🇦🇺", listOf("australie", "australia", "sydney")),
    CountryAlias("🇧🇷", listOf("bresil", "brazil", "sao paulo")),
    CountryAlias("🇲🇽", listOf("mexique", "mexico")),
    CountryAlias("🇦🇷", listOf("argentine", "argentina")),
    CountryAlias("🇨🇱", listOf("chili", "chile")),
    CountryAlias("🇿🇦", listOf("afrique du sud", "south africa", "johannesburg")),
    CountryAlias("🇪🇬", listOf("egypte", "egypt", "le caire")),
    CountryAlias("🇲🇦", listOf("maroc", "morocco", "casablanca")),
    CountryAlias("🇩🇿", listOf("algerie", "algeria")),
    CountryAlias("🇹🇳", listOf("tunisie", "tunisia")),
    CountryAlias("🇦🇪", listOf("emirats", "uae", "dubai")),
    CountryAlias("🇸🇦", listOf("arabie saoudite", "saudi")),
    CountryAlias("🇶🇦", listOf("qatar")),
    CountryAlias("🇮🇱", listOf("israel", "tel aviv")),
    CountryAlias("🇮🇷", listOf("iran", "teheran")),
    CountryAlias("🇻🇳", listOf("vietnam")),
    CountryAlias("🇹🇭", listOf("thailande", "thailand", "bangkok")),
    CountryAlias("🇲🇾", listOf("malaisie", "malaysia")),
    CountryAlias("🇮🇩", listOf("indonesie", "indonesia", "jakarta")),
    CountryAlias("🇵🇭", listOf("philippines", "manille")),
    CountryAlias("🇹🇼", listOf("taiwan", "taipei")),
    CountryAlias("🇳🇿", listOf("nouvelle-zelande", "new zealand")),
)

/** Drapeau associé à l'alias affiché ; globe neutre quand rien ne correspond. */
internal fun flagForAlias(alias: String?): String {
    val normalized = normalizeAlias(alias.orEmpty())
    if (normalized.isBlank()) return "🌐"
    return countryAliases.firstOrNull { country ->
        country.keywords.any { keyword -> containsWholeWord(normalized, keyword) }
    }?.flag ?: "🌐"
}

private fun containsWholeWord(text: String, word: String): Boolean {
    var index = text.indexOf(word)
    while (index >= 0) {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + word.length)
        val startOk = before == null || !before.isLetterOrDigit()
        val endOk = after == null || !after.isLetterOrDigit()
        if (startOk && endOk) return true
        index = text.indexOf(word, startIndex = index + 1)
    }
    return false
}
