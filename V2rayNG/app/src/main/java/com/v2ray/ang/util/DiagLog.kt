package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journalisation de diagnostic uniquement : aucune décision, aucun état, aucun effet sur la
 * connexion. Les valeurs journalisées ne contiennent ni adresse de serveur, ni identifiant,
 * ni secret.
 *
 * Le journal de l'application ([XrayConsole]) masque automatiquement les hôtes, adresses et
 * identifiants via [LogRedactor]. Les noms de classes sont donc journalisés sous leur forme
 * simple et les trames applicatives au format `Classe#methode:ligne`, afin que la pile reste
 * lisible après masquage. La pile complète, non masquée sur les trames, part uniquement vers
 * logcat.
 */
object DiagLog {
    private const val APP_FRAME_PREFIX = "com.v2ray.ang."
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun timestamp(): String = synchronized(timeFormat) { timeFormat.format(Date()) }

    /** Une ligne horodatée par étape du démarrage. */
    fun step(step: String, details: String = "") {
        val suffix = if (details.isBlank()) "" else " $details"
        LogUtil.i(AppConfig.TAG, "DIAG ${timestamp()} $step$suffix")
    }

    /**
     * Trace une exception : classe, message, chaîne des causes, trames applicatives et pile
     * complète. [resource] nomme la ressource concernée (ex. descripteur TUN déjà fermé).
     */
    fun exception(context: String, error: Throwable, resource: String? = null) {
        val resourceText = resource?.let { " resource=$it" }.orEmpty()
        val head = "DIAG ${timestamp()} $context$resourceText " +
            "type=${error.javaClass.simpleName} message=${LogRedactor.redact(error.message.orEmpty())}"

        val journalTail = StringBuilder()
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (depth == 0) {
                journalTail.append(" appFrames=")
                journalTail.append(appFrames(cause).ifEmpty { "aucune" })
            } else {
                journalTail.append(" cause[$depth]=${cause.javaClass.simpleName}")
                journalTail.append("(${LogRedactor.redact(cause.message.orEmpty())})")
            }
            cause = cause.cause
            depth++
        }
        LogUtil.e(AppConfig.TAG, head + journalTail)

        // Pile complète : logcat uniquement, pour ne pas la faire dégrader par le masquage
        // des hôtes du journal.
        Log.e(AppConfig.TAG, fullStack(error))
    }

    private fun appFrames(error: Throwable): String =
        error.stackTrace
            .filter { it.className.startsWith(APP_FRAME_PREFIX) }
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}#${frame.methodName}:${frame.lineNumber}"
            }

    private fun fullStack(error: Throwable): String = buildString {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) append("Caused by: ")
            append(LogRedactor.redact(cause.toString())).append('\n')
            cause.stackTrace.forEach { frame -> append("\tat ").append(frame).append('\n') }
            cause = cause.cause
            depth++
        }
    }

    private const val MAX_CAUSE_DEPTH = 5
}
