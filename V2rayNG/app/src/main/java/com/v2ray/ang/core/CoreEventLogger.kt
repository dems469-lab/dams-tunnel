package com.v2ray.ang.core

import com.v2ray.ang.util.LogUtil

/**
 * Journalise les événements du superviseur.
 *
 * Chaque ligne est autoportante : horodatage, événement, état précédent, état
 * courant, origine, tentative, temps écoulé depuis la dernière connexion
 * vérifiée et motif. Les exceptions sont transmises à `LogUtil` en entier, avec
 * leur pile : une erreur du moteur n'est jamais réduite à un booléen.
 *
 * Aucune donnée sensible n'est écrite ici : les motifs sont des codes courts
 * (`native_loop_stopped`, `probe_failed`, …), jamais un identifiant de profil,
 * une adresse de serveur ou un contenu de configuration.
 */
internal object CoreEventLogger {
    const val TAG: String = "DamsCoreSupervisor"

    fun sink(record: CoreEventRecord) {
        val line = record.format()
        val cause = record.throwable
        when (record.kind) {
            CoreEventKind.CORE_START_FAILED,
            CoreEventKind.CORE_EXIT_UNEXPECTED,
            CoreEventKind.RECONNECT_FAILED,
            CoreEventKind.RECONNECT_LIMIT_REACHED,
            CoreEventKind.STOP_NOT_CONFIRMED,
            -> if (cause != null) LogUtil.e(TAG, line, cause) else LogUtil.e(TAG, line)

            CoreEventKind.CORE_STARTED,
            CoreEventKind.CORE_STOPPED,
            CoreEventKind.RECONNECT_SUCCEEDED,
            -> LogUtil.i(TAG, line)

            else -> LogUtil.i(TAG, line)
        }
    }
}
