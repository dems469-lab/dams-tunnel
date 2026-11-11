package com.v2ray.ang.dto

import java.io.Serializable

/**
 * Échantillon de latence de la session en cours.
 *
 * La mesure est produite par le cœur lui-même ([libv2ray.CoreController.measureDelay]),
 * c'est-à-dire par une requête HTTP qui traverse réellement le tunnel actif. Ce
 * n'est ni un ping ICMP vers l'adresse du serveur, ni une mesure directe sur le
 * réseau du téléphone.
 *
 * @property delayMillis aller-retour mesuré en millisecondes, négatif si la sonde
 * a échoué.
 * @property measuredAtMillis horodatage de la mesure (`System.currentTimeMillis`).
 * @property success vrai uniquement lorsque la sonde a obtenu une réponse.
 */
data class SessionRttSample(
    val delayMillis: Long,
    val measuredAtMillis: Long,
    val success: Boolean,
) : Serializable
