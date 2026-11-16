package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Règles pures des sondes de connectivité.
 *
 * Ce sont les décisions qui, en production, autorisent ou interdisent un
 * rechargement du cœur : chaque cas ci-dessous correspond à une situation
 * réelle (Google filtré, téléphone sans réseau, sonde concurrente…). Aucune
 * dépendance Android ni native n'est nécessaire, donc tout tourne sur JVM.
 */
class ProbePolicyTest {

    private fun ok(provider: ProbeProvider, latency: Long) =
        ProbeAttempt("https://$provider.test/", provider, ProbeKind.OK, latency)

    private fun failed(provider: ProbeProvider, kind: ProbeKind = ProbeKind.TIMEOUT) =
        ProbeAttempt("https://$provider.test/", provider, kind, 8_000L)

    // ── Budgets (T1-T4) ──────────────────────────────────────────────────────

    @Test
    fun `T1 trois destinations recoivent chacune leur part du budget total`() {
        val budgets = ProbePolicy.planBudgets(
            totalMillis = 8_000L,
            count = 3,
            minPerUrl = 1_500L,
        )

        assertEquals(3, budgets.size)
        // L'ancien calcul (`timeout / 2`) laissait 4 000 ms à la première URL et
        // rien d'exploitable aux suivantes : ici la somme reste le budget total.
        assertEquals(8_000L, budgets.sum())
        budgets.forEach { assertTrue(it >= 1_500L, "budget trop faible : $it") }
        assertEquals(2_667L, budgets[0])
        assertEquals(2_667L, budgets[1])
        assertEquals(2_666L, budgets[2])
    }

    @Test
    fun `T1b le budget de production accorde quatre secondes par destination`() {
        val budgets = ProbePolicy.planBudgets(totalMillis = 12_000L, count = 3)

        // Réseau de référence : 2,2 à 2,5 s par voyage HTTPS complet. Quatre
        // secondes par URL laissent la marge nécessaire au tunnel.
        assertEquals(listOf(4_000L, 4_000L, 4_000L), budgets)
    }

    @Test
    fun `T2 deux destinations se partagent le budget a parts egales`() {
        val budgets = ProbePolicy.planBudgets(totalMillis = 12_000L, count = 2)

        assertEquals(listOf(6_000L, 6_000L), budgets)
    }

    @Test
    fun `T3 un budget serre est reparti sans depasser le total`() {
        val budgets = ProbePolicy.planBudgets(totalMillis = 4_000L, count = 3, minPerUrl = 1_500L)

        assertEquals(4_000L, budgets.sum())
        budgets.forEach { assertTrue(it >= 1_300L, "budget trop faible : $it") }
    }

    @Test
    fun `T4 aucune destination n obtient un budget nul ni negatif`() {
        assertTrue(ProbePolicy.planBudgets(totalMillis = 0L, count = 3).isEmpty())
        assertTrue(ProbePolicy.planBudgets(totalMillis = 8_000L, count = 0).isEmpty())
        // Plus de destinations que de millisecondes : mieux vaut ne rien tenter
        // qu'envoyer une sonde avec un délai nul.
        assertTrue(ProbePolicy.planBudgets(totalMillis = 10L, count = 100).isEmpty())
        val budgets = ProbePolicy.planBudgets(totalMillis = 8_000L, count = 3)
        assertTrue(budgets.all { it > 0L })
    }

    // ── Fournisseurs et destinations (T5, T17-T18) ───────────────────────────

    @Test
    fun `T5 chaque destination est rattachee a son fournisseur`() {
        assertEquals(ProbeProvider.GOOGLE, ProbePolicy.providerOf("https://www.gstatic.com/generate_204"))
        assertEquals(ProbeProvider.GOOGLE, ProbePolicy.providerOf("https://connectivitycheck.gstatic.com/generate_204"))
        assertEquals(ProbeProvider.CLOUDFLARE, ProbePolicy.providerOf("https://cp.cloudflare.com/generate_204"))
        assertEquals(ProbeProvider.CANONICAL, ProbePolicy.providerOf("https://connectivity-check.ubuntu.com/"))
        assertEquals(ProbeProvider.OTHER, ProbePolicy.providerOf("https://example.invalid/ping"))
    }

    @Test
    fun `T16 un succes lent reste une connexion valide`() {
        val slow = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(ok(ProbeProvider.CLOUDFLARE, 4_200L)),
        )

        assertEquals(TunnelVerdict.SLOW, slow.verdict)
        assertEquals(4_200L, slow.latencyMillis)
        // Un tunnel lent transporte quand même : le déclarer déconnecté ferait
        // recharger un cœur sain et couperait le trafic en cours.
        assertTrue(slow.reachable)
        assertFalse(slow.confirmedDown)
    }

    @Test
    fun `T17 les destinations gardent l ordre voulu et les doublons d hote sautent`() {
        val endpoints = ProbePolicy.endpoints(
            listOf(
                "https://www.gstatic.com/generate_204",
                "  ",
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/other-path",
                "https://connectivity-check.ubuntu.com/",
            ),
        )

        assertEquals(3, endpoints.size)
        assertEquals(ProbeProvider.GOOGLE, endpoints[0].provider)
        assertEquals(ProbeProvider.CLOUDFLARE, endpoints[1].provider)
        assertEquals(ProbeProvider.CANONICAL, endpoints[2].provider)
        assertEquals("cp.cloudflare.com", ProbePolicy.hostOf("https://cp.cloudflare.com/generate_204"))
    }

    @Test
    fun `T18 l ordre par defaut couvre trois fournisseurs distincts`() {
        val endpoints = ProbePolicy.endpoints(
            listOf(
                "https://www.gstatic.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://connectivity-check.ubuntu.com/",
            ),
        )

        assertEquals(3, endpoints.map { it.provider }.distinct().size)
        // C'est exactement ce qui rend une panne « confirmée » possible même si
        // l'utilisateur a choisi une URL Google bloquée par son serveur.
        assertEquals(3, ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = endpoints.map { failed(it.provider) },
        ).providersAttempted)
    }

    // ── Verdicts (T6-T15) ─────────────────────────────────────────────────────

    @Test
    fun `T6 Google en echec et Cloudflare joignable donne un tunnel sain`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(
                failed(ProbeProvider.GOOGLE),
                ok(ProbeProvider.CLOUDFLARE, 120L),
            ),
        )

        assertEquals(TunnelVerdict.UP, report.verdict)
        assertEquals(120L, report.latencyMillis)
        assertTrue(report.reachable)
    }

    @Test
    fun `T7 deux fournisseurs en echec suffisent quand Internet fonctionne`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(
                failed(ProbeProvider.GOOGLE),
                failed(ProbeProvider.CLOUDFLARE),
            ),
        )

        assertEquals(TunnelVerdict.DOWN, report.verdict)
        assertTrue(report.confirmedDown)
        assertFalse(report.reachable)
        assertEquals(-1L, report.latencyMillis)
    }

    @Test
    fun `T8 trois fournisseurs en echec confirment aussi la panne`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(
                failed(ProbeProvider.GOOGLE),
                failed(ProbeProvider.CLOUDFLARE, ProbeKind.ERROR),
                failed(ProbeProvider.CANONICAL),
            ),
        )

        assertEquals(TunnelVerdict.DOWN, report.verdict)
        assertEquals(3, report.providersAttempted)
    }

    @Test
    fun `T9 une seule destination en echec ne condamne jamais le tunnel`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(failed(ProbeProvider.GOOGLE)),
        )

        assertEquals(TunnelVerdict.SUSPECT, report.verdict)
        assertFalse(report.confirmedDown)
        assertFalse(report.reachable)
    }

    @Test
    fun `T10 deux echecs du meme fournisseur ne font pas une preuve`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(
                ProbeAttempt("https://a.gstatic.com/", ProbeProvider.GOOGLE, ProbeKind.TIMEOUT, 8_000L),
                ProbeAttempt("https://b.gstatic.com/", ProbeProvider.GOOGLE, ProbeKind.ERROR, 8_000L),
            ),
        )

        assertEquals(TunnelVerdict.SUSPECT, report.verdict)
        assertEquals(1, report.providersAttempted)
    }

    @Test
    fun `T11 une sonde deja en cours ne prouve rien et ne compte pas comme echec`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = emptyList(),
            skipped = true,
        )

        assertEquals(TunnelVerdict.SKIPPED, report.verdict)
        assertTrue(report.skipped)
        assertFalse(report.reachable)
        assertFalse(report.confirmedDown)
    }

    @Test
    fun `T12 au dela du seuil un succes est lent mais reste joignable`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = true,
            attempts = listOf(ok(ProbeProvider.GOOGLE, ProbePolicy.SLOW_PROBE_MILLIS + 1L)),
        )

        assertEquals(TunnelVerdict.SLOW, report.verdict)
        assertTrue(report.reachable)
        // Juste en dessous du seuil : tunnel simplement sain.
        assertEquals(
            TunnelVerdict.UP,
            ProbePolicy.evaluate(
                coreRunning = true,
                relayRunning = true,
                internetValidated = true,
                attempts = listOf(ok(ProbeProvider.GOOGLE, ProbePolicy.SLOW_PROBE_MILLIS)),
            ).verdict,
        )
    }

    @Test
    fun `T13 un coeur arrete est distingue d une panne reseau`() {
        val report = ProbePolicy.evaluate(
            coreRunning = false,
            relayRunning = false,
            internetValidated = true,
            attempts = emptyList(),
        )

        assertEquals(TunnelVerdict.CORE_DOWN, report.verdict)
        assertFalse(report.confirmedDown)
    }

    @Test
    fun `T14 un relais mort rend le tunnel inutilisable meme si la sonde repond`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = false,
            internetValidated = true,
            attempts = listOf(ok(ProbeProvider.CLOUDFLARE, 100L)),
        )

        assertEquals(TunnelVerdict.RELAY_DOWN, report.verdict)
        assertFalse(report.reachable)
    }

    @Test
    fun `T15 sans Internet porteur l echec n est pas impute au tunnel`() {
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = false,
            attempts = listOf(
                failed(ProbeProvider.GOOGLE),
                failed(ProbeProvider.CLOUDFLARE),
            ),
        )

        assertEquals(TunnelVerdict.NO_INTERNET, report.verdict)
        assertFalse(report.confirmedDown)
        assertFalse(report.reachable)
    }

    @Test
    fun `T15b un succes reel prime sur l information systeme`() {
        // Le système peut annoncer « non validé » alors qu'une requête aboutit
        // (portail captif qui laisse passer la sonde) : la preuve réelle gagne.
        val report = ProbePolicy.evaluate(
            coreRunning = true,
            relayRunning = true,
            internetValidated = false,
            attempts = listOf(ok(ProbeProvider.CANONICAL, 300L)),
        )

        assertEquals(TunnelVerdict.UP, report.verdict)
        assertTrue(report.reachable)
    }

    @Test
    fun `T6b aucune tentative aboutie et aucune tentative du tout restent des doutes`() {
        assertEquals(
            TunnelVerdict.SUSPECT,
            ProbePolicy.evaluate(
                coreRunning = true,
                relayRunning = true,
                internetValidated = true,
                attempts = emptyList(),
            ).verdict,
        )
    }
}
