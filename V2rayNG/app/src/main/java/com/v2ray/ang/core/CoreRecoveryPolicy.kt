package com.v2ray.ang.core

/** Verdict d'une sonde de santé du tunnel. */
internal enum class ProbeVerdict {
    /** La requête réelle a abouti : le tunnel transporte du trafic. */
    TUNNEL_UP,

    /** Échec isolé : le tunnel n'est pas déclaré en panne pour autant. */
    TUNNEL_SUSPECT,

    /** Coupure confirmée par plusieurs échecs consécutifs. */
    TUNNEL_DOWN,
}

/** Chemin de récupération à emprunter selon l'état réel du cœur et du relais. */
internal enum class RecoveryPath {
    /** Le cœur et le relais sont vivants : recharger le cœur seul. */
    RELOAD_CORE,

    /** Le cœur tourne mais plus aucun paquet ne passe : reconstruire le TUN. */
    REBUILD_TUNNEL,

    /** Plus aucun cœur : relancer le service (une seule instance). */
    RESTART_SERVICE,
}

/** Ce qu'un nouveau démarrage doit faire du tunnel existant. */
internal enum class StartDisposition {
    /** Cœur vivant et chemin des paquets valide : ne rien détruire. */
    KEEP_EXISTING,

    /** Chemin cassé ou cœur arrêté : reconstruire après un arrêt réel. */
    REBUILD,
}

/**
 * Décisions pures du cycle de vie réseau, sans Android ni moteur natif.
 *
 * Elles sont extraites ici pour être testées sur JVM : ce sont elles qui
 * décident si un tunnel sain est conservé, si le cœur est rechargé ou si
 * l'interface TUN est reconstruite. Une erreur ici coupe le trafic de
 * l'utilisateur, elle ne doit donc jamais dépendre d'une relecture humaine.
 */
internal object CoreRecoveryPolicy {

    /**
     * Une seule sonde perdue ne prouve rien : congestion, résolveur lent ou
     * serveur de test indisponible produisent le même symptôme qu'une coupure.
     * Un rechargement du cœur coupe toutes les connexions en cours, il exige
     * donc [failuresBeforeRecovery] échecs **consécutifs**.
     */
    fun probeVerdict(
        reachable: Boolean,
        consecutiveFailures: Int,
        failuresBeforeRecovery: Int,
    ): ProbeVerdict = when {
        reachable -> ProbeVerdict.TUNNEL_UP
        consecutiveFailures >= failuresBeforeRecovery.coerceAtLeast(1) -> ProbeVerdict.TUNNEL_DOWN
        else -> ProbeVerdict.TUNNEL_SUSPECT
    }

    /**
     * Le relais est le maillon qui reçoit réellement les paquets du TUN.
     * Un cœur vivant derrière un relais mort ne transporte rien : recharger le
     * cœur ne servirait à rien, il faut reconstruire l'interface et le relais.
     */
    fun recoveryPath(coreRunning: Boolean, relayRunning: Boolean): RecoveryPath = when {
        coreRunning && relayRunning -> RecoveryPath.RELOAD_CORE
        coreRunning -> RecoveryPath.REBUILD_TUNNEL
        else -> RecoveryPath.RESTART_SERVICE
    }

    /**
     * Un état « connecté » n'est publié que si la requête réelle a abouti,
     * que le cœur tourne **et** que le relais vit : la sonde seule ne prouve
     * que le trajet moteur → serveur, jamais le TUN.
     */
    fun canPublishConnected(
        probeReachable: Boolean,
        coreRunning: Boolean,
        relayRunning: Boolean,
    ): Boolean = probeReachable && coreRunning && relayRunning

    /**
     * Un second démarrage ne détruit un tunnel que si celui-ci ne peut plus
     * transporter de paquets. Un doute sur le relais est traité en amont comme
     * « vivant » : on ne casse jamais une connexion qui fonctionne.
     */
    fun startDisposition(coreRunning: Boolean, pathUsable: Boolean): StartDisposition =
        if (coreRunning && pathUsable) StartDisposition.KEEP_EXISTING else StartDisposition.REBUILD
}
