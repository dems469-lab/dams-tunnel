package com.v2ray.ang.reconfig.engine

/**
 * Méthodes de reconfiguration proposées par le module.
 *
 * La transformation est purement textuelle et locale : elle ne contacte aucun
 * serveur et ne dépend d'aucune connexion réseau.
 *
 * @property storageKey valeur stable enregistrée en base (jamais un ordinal).
 * @property defaultTarget domaine proposé par défaut dans l'interface.
 * @property usesPrefix vrai lorsque la méthode préfixe l'adresse d'origine au
 * lieu de la remplacer. Un préfixe n'a de sens que sur un nom de domaine.
 */
internal enum class ReconfigMethod(
    val storageKey: String,
    val defaultTarget: String,
    val usesPrefix: Boolean,
) {
    MAXIT_MOBILE("maxit_mobile", "Maxit-mobile.orange.bf", usesPrefix = true),
    API_CONNECT("api_connect", "api-connect.orange.bf", usesPrefix = false),
    API_GATEWAY("api_gateway", "apigw-maxit2.orange.bf", usesPrefix = false),
    CUSTOM("custom", "", usesPrefix = false),
    ;

    companion object {
        fun fromStorageKey(key: String?): ReconfigMethod? =
            entries.firstOrNull { it.storageKey == key }
    }
}
