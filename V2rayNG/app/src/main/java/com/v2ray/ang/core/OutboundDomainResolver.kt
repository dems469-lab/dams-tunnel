package com.v2ray.ang.core

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves outbound server domains on a bounded pool.
 *
 * [java.net.InetAddress.getAllByName] has no timeout and can block for seconds
 * on a slow resolver. Resolving every proxy outbound sequentially made startup
 * the sum of all lookups. This helper bounds the whole batch and returns only
 * the hosts resolved in time; unresolved hosts are left to Xray, which resolves
 * the selected outbound lazily.
 */
internal object OutboundDomainResolver {
    private const val MAX_CONCURRENCY = 4
    private const val DEFAULT_TIMEOUT_MILLIS = 4_000L

    private val executor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENCY) { runnable ->
        Thread(runnable, "dams-dns-resolve").apply { isDaemon = true }
    }

    fun resolve(
        domains: Collection<String>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        resolver: (String) -> List<String>?,
    ): Map<String, List<String>> {
        val uniqueDomains = domains.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (uniqueDomains.isEmpty()) return emptyMap()

        val tasks = uniqueDomains.map { domain ->
            Callable { domain to resolver(domain) }
        }
        val futures = try {
            executor.invokeAll(tasks, timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return emptyMap()
        }

        val resolved = LinkedHashMap<String, List<String>>()
        futures.forEach { future ->
            if (future.isCancelled) return@forEach
            val value = try {
                future.get()
            } catch (cancelled: CancellationException) {
                return@forEach
            } catch (failed: ExecutionException) {
                return@forEach
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return@forEach
            }
            val ips = value.second
            if (ips != null && ips.isNotEmpty()) {
                resolved[value.first] = ips
            }
        }
        return resolved
    }
}
