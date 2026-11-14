package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.TrafficStatsParser
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Implémentation de [CoreEngine] sur l'AAR `libv2ray`
 * (AndroidLibXrayLite v26.11.9, Xray-core officiel v26.11.9).
 *
 * Avec [CoreNativeManager], c'est le seul fichier qui importe `libv2ray` :
 * une nouvelle version du moteur ne se propage pas au reste de l'application.
 */
internal class Libv2rayEngine(handler: CoreCallbackHandler) : CoreEngine {
    private val controller: CoreController = try {
        Libv2ray.newCoreController(handler)
    } catch (e: Exception) {
        LogUtil.e(AppConfig.TAG, "Failed to create core controller", e)
        throw e
    }

    override fun start(configContent: String, tunFd: Int) {
        controller.startLoop(configContent, tunFd)
    }

    override fun stop() {
        controller.stopLoop()
    }

    override val isRunning: Boolean
        get() = controller.isRunning

    override fun measureDelay(url: String): Long = controller.measureDelay(url)

    /**
     * L'API native ne renvoie que les compteurs non nuls et remet tous les
     * compteurs à zéro en un seul appel (une seule traversée JNI par seconde au
     * lieu de deux par tag).
     */
    override fun readOutboundTrafficStats(): List<OutboundTrafficStat> =
        TrafficStatsParser.parse(controller.queryAllOutboundTrafficStats())

    override fun version(): String = CoreNativeManager.getLibVersion()
}
