package com.v2ray.ang.core

import android.content.Context
import com.google.gson.JsonObject
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.File

/**
 * Pont minimal entre le laboratoire Multipath et le moteur Xray existant.
 *
 * Le laboratoire ne remplace ni le moteur ni le service VPN : il publie dans ce
 * fichier la configuration Xray qu'il a préparée. Au démarrage (ou au
 * rechargement) de [CoreServiceManager], le moteur lit ce fichier et l'utilise
 * *à la place* du profil sélectionné, uniquement lorsque :
 *
 * - le mode VPN est actif ;
 * - une session laboratoire valide existe ;
 * - la configuration contient au moins un outbound proxy exploitable.
 *
 * Le fichier vit dans le stockage privé de l'application, partagé par les
 * processus `:ui` et `:daemon`. Il est supprimé dès que le tunnel s'arrête :
 * une session laboratoire ne peut donc jamais « fuiter » dans le mode normal.
 *
 * Aucune donnée de production n'est modifiée : la sélection de serveur, les
 * Configs, les abonnements et les préférences classiques restent intacts.
 */
object LaboratoryTunnelBridge {

    private const val TAG = "LaboratoryTunnel"
    private const val DIRECTORY_NAME = "laboratory"
    private const val SESSION_FILE_NAME = "active_session.json"
    private const val TEMP_FILE_NAME = "active_session.tmp"
    private const val PERMIT_FILE_NAME = "active_start.permit"
    private const val STATUS_FILE_NAME = "tunnel_state.json"
    private const val SESSION_VERSION = 1
    private const val MAX_SESSION_BYTES = 1_048_576L

    /** Session expérimentale telle que consommée par le moteur. */
    data class Session(
        val primaryGuid: String,
        val configJson: String,
        val selector: List<String>,
        val createdAt: Long,
    )

    /**
     * Publie la session. L'écriture est atomique : le moteur ne lit jamais un
     * fichier partiellement écrit.
     */
    @Synchronized
    fun write(context: Context, session: Session): Boolean {
        if (session.primaryGuid.isBlank() || session.configJson.isBlank()) return false
        return try {
            val parsed = JsonUtil.parseString(session.configJson)
                ?.takeIf { it.isJsonObject }
                ?: return false
            val root = JsonObject().apply {
                addProperty("version", SESSION_VERSION)
                addProperty("primaryGuid", session.primaryGuid)
                addProperty("createdAt", session.createdAt)
                add("config", parsed)
                add("selector", com.google.gson.JsonArray().apply {
                    session.selector.filter { it.isNotBlank() }.forEach { add(it) }
                })
            }
            val directory = sessionDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) return false
            val target = sessionFile(context)
            val temp = File(directory, TEMP_FILE_NAME)
            temp.writeText(JsonUtil.toJson(root))
            if (target.exists() && !target.delete()) {
                LogUtil.w(TAG, "Impossible de remplacer la session laboratoire existante")
            }
            val moved = temp.renameTo(target)
            if (!moved) {
                LogUtil.e(TAG, "Impossible de publier la session laboratoire")
                temp.delete()
            } else {
                // Autorise un unique démarrage/rechargement du moteur. Le
                // moteur consomme ce jeton : une session oubliée après un
                // incident ne peut donc jamais détourner un démarrage normal.
                permitFile(context).writeText(session.createdAt.toString())
            }
            moved
        } catch (e: Exception) {
            // Ne journalise jamais la configuration : elle contient des identifiants.
            LogUtil.e(TAG, "Échec de publication de la session laboratoire (${e.javaClass.simpleName})")
            false
        }
    }

    /** Lit la session courante, ou null si elle est absente, vide ou invalide. */
    @Synchronized
    fun read(context: Context): Session? {        val file = sessionFile(context)
        if (!file.isFile) return null
        if (file.length() <= 0L || file.length() > MAX_SESSION_BYTES) {
            LogUtil.w(TAG, "Session laboratoire ignorée : taille invalide")
            clear(context)
            return null
        }
        return try {
            val root = JsonUtil.parseString(file.readText())
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: run {
                    LogUtil.w(TAG, "Session laboratoire illisible, nettoyage")
                    clear(context)
                    return null
                }
            val version = root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (version != SESSION_VERSION) {
                LogUtil.w(TAG, "Session laboratoire d'une version inconnue, nettoyage")
                clear(context)
                return null
            }
            val primaryGuid = root.get("primaryGuid")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                .orEmpty()
            val config = root.getAsJsonObject("config")
            if (primaryGuid.isBlank() || config == null) {
                clear(context)
                return null
            }
            val selector = root.getAsJsonArray("selector")
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
                }
                .orEmpty()
            Session(
                primaryGuid = primaryGuid,
                configJson = JsonUtil.toJson(config),
                selector = selector,
                createdAt = root.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        } catch (e: Exception) {
            LogUtil.e(TAG, "Échec de lecture de la session laboratoire (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Lecture réservée au moteur, au moment d'un démarrage ou d'un rechargement.
     *
     * Le démarrage n'est autorisé que si [write] a publié un jeton juste avant.
     * Le jeton est consommé ici, ce qui garantit qu'une session laissée par un
     * incident ne détourne jamais un démarrage classique ultérieur : dans ce
     * cas la session périmée est supprimée et le mode normal reprend la main.
     */
    @Synchronized
    fun readForStart(context: Context): Session? {
        val permit = permitFile(context)
        if (!permit.isFile) {
            if (sessionFile(context).isFile) {
                LogUtil.w(TAG, "Session laboratoire périmée ignorée")
                clear(context)
            }
            return null
        }
        val session = read(context)
        runCatching { permit.delete() }
        if (session == null) clear(context)
        return session
    }

    fun isActive(context: Context): Boolean = sessionFile(context).isFile

    /** Supprime la session. Idempotent. */
    @Synchronized
    fun clear(context: Context) {
        runCatching {
            sessionFile(context).delete()
            permitFile(context).delete()
            File(sessionDirectory(context), TEMP_FILE_NAME).delete()
        }.onFailure {
            LogUtil.w(TAG, "Échec du nettoyage de la session laboratoire")
        }
    }

    private fun sessionDirectory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun sessionFile(context: Context): File =
        File(sessionDirectory(context), SESSION_FILE_NAME)

    private fun permitFile(context: Context): File =
        File(sessionDirectory(context), PERMIT_FILE_NAME)

    /** Nom affiché par la notification et la console Xray pendant le laboratoire. */
    fun laboratoryRemarks(remarks: String): String =
        if (remarks.startsWith(LAB_PREFIX)) remarks else "$LAB_PREFIX$remarks"

    // ── État autoritaire du tunnel ───────────────────────────────────────────

    /**
     * État publié par le processus `:daemon` après chaque transition réelle.
     *
     * Il sert de seconde source de vérité (indépendante des broadcasts) pour que
     * l'interface puisse distinguer « service absent », « arrêt confirmé » et
     * « arrêt échoué », sans jamais deviner.
     */
    data class ServiceStatus(
        val state: String,
        val mode: String,
        val reason: String?,
        val atMillis: Long,
        val pid: Int,
    )

    const val STATE_RUNNING = "RUNNING"
    const val STATE_STOPPING = "STOPPING"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_STOP_FAILED = "STOP_FAILED"
    const val STATE_START_FAILED = "START_FAILED"

    const val MODE_CLASSIC = "CLASSIC"
    const val MODE_LABORATORY = "LABORATORY"

    /**
     * Publie l'état réel du tunnel. Écriture atomique, jamais bloquante pour
     * l'appelant (le service ne doit pas attendre le disque pour arrêter Xray).
     */
    @Synchronized
    fun writeStatus(
        context: Context,
        state: String,
        mode: String,
        reason: String? = null,
    ) {
        runCatching {
            val directory = sessionDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) return
            val root = JsonObject().apply {
                addProperty("state", state)
                addProperty("mode", mode)
                reason?.takeIf { it.isNotBlank() }?.let { addProperty("reason", it.take(160)) }
                addProperty("atMillis", System.currentTimeMillis())
                addProperty("pid", android.os.Process.myPid())
            }
            val temp = File(directory, "$STATUS_FILE_NAME.tmp")
            temp.writeText(JsonUtil.toJson(root))
            val target = statusFile(context)
            if (target.exists() && !target.delete()) {
                LogUtil.w(TAG, "Impossible de remplacer l'état du tunnel")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                LogUtil.w(TAG, "Impossible de publier l'état du tunnel")
            }
        }.onFailure {
            LogUtil.w(TAG, "Échec d'écriture de l'état du tunnel (${it.javaClass.simpleName})")
        }
    }

    /** Dernier état publié par le démon, ou null si aucun démon ne l'a écrit. */
    fun readStatus(context: Context): ServiceStatus? {
        val file = statusFile(context)
        if (!file.isFile) return null
        return runCatching {
            val root = JsonUtil.parseString(file.readText())
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: return null
            ServiceStatus(
                state = root.get("state")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                mode = root.get("mode")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                reason = root.get("reason")?.takeIf { it.isJsonPrimitive }?.asString,
                atMillis = root.get("atMillis")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                pid = root.get("pid")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearStatus(context: Context) {
        runCatching {
            statusFile(context).delete()
            File(sessionDirectory(context), "$STATUS_FILE_NAME.tmp").delete()
        }
    }

    private fun statusFile(context: Context): File =
        File(sessionDirectory(context), STATUS_FILE_NAME)

    private const val LAB_PREFIX = "LAB · "
}
