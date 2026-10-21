package com.v2ray.ang.premium

import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal sealed interface PremiumFetchResult {
    data class Content(val text: String, val userInfo: PremiumSubscriptionInfo?) : PremiumFetchResult
    data object ConfirmedExpired : PremiumFetchResult
    data object EmptyResponse : PremiumFetchResult
    data object Failed : PremiumFetchResult
}

internal enum class PremiumEndpointOutcome { EXPIRED, EMPTY, FAILED }

internal object PremiumFetchOutcomePolicy {
    fun afterAllEndpoints(outcomes: List<PremiumEndpointOutcome>, endpointCount: Int): PremiumFetchResult = when {
        endpointCount > 0 && outcomes.size == endpointCount && outcomes.all { it == PremiumEndpointOutcome.EXPIRED } ->
            PremiumFetchResult.ConfirmedExpired
        outcomes.any { it == PremiumEndpointOutcome.EMPTY } -> PremiumFetchResult.EmptyResponse
        else -> PremiumFetchResult.Failed
    }
}

/** Client sans redirection automatique : le token ne peut pas être transmis à un autre hôte. */
internal class PremiumSubscriptionClient(
    private val endpointPolicy: PremiumEndpointPolicy,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    suspend fun fetch(token: String, nowEpochSeconds: Long): PremiumFetchResult = withContext(Dispatchers.IO) {
        val urls = endpointPolicy.subscriptionUrlsForToken(token)
        if (urls.isEmpty()) return@withContext PremiumFetchResult.Failed

        val outcomes = mutableListOf<PremiumEndpointOutcome>()
        urls.forEach { url ->
            val response = try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "v2rayNG/${BuildConfig.VERSION_NAME}")
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { httpResponse ->
                    val code = httpResponse.code
                    val userInfo = PremiumSubscriptionInfoParser.parse(
                        httpResponse.header(SUBSCRIPTION_USER_INFO_HEADER),
                    )
                    if (code in EXPIRED_HTTP_CODES) {
                        EndpointResult.Expired
                    } else if (!httpResponse.isSuccessful) {
                        EndpointResult.Failed
                    } else if (PremiumSubscriptionInfoParser.status(userInfo, nowEpochSeconds) == PremiumSubscriptionStatus.EXPIRED) {
                        EndpointResult.Expired
                    } else {
                        val body = httpResponse.body ?: return@use EndpointResult.Empty
                        val text = readBounded(body.byteStream()) ?: return@use EndpointResult.Empty
                        if (text.isBlank()) EndpointResult.Empty else EndpointResult.Content(text, userInfo)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Ne pas journaliser l'exception : certains clients incluent l'URL et le token.
                EndpointResult.Failed
            }

            when (response) {
                is EndpointResult.Content -> return@withContext PremiumFetchResult.Content(response.text, response.userInfo)
                EndpointResult.Expired -> outcomes += PremiumEndpointOutcome.EXPIRED
                EndpointResult.Empty -> outcomes += PremiumEndpointOutcome.EMPTY
                EndpointResult.Failed -> outcomes += PremiumEndpointOutcome.FAILED
            }
        }
        PremiumFetchOutcomePolicy.afterAllEndpoints(outcomes, urls.size)
    }

    private fun readBounded(input: java.io.InputStream): String? {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private sealed interface EndpointResult {
        data class Content(val text: String, val userInfo: PremiumSubscriptionInfo?) : EndpointResult
        data object Expired : EndpointResult
        data object Empty : EndpointResult
        data object Failed : EndpointResult
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val BUFFER_SIZE = 8 * 1024
        const val SUBSCRIPTION_USER_INFO_HEADER = "subscription-userinfo"
        val EXPIRED_HTTP_CODES = setOf(403, 404)
    }
}
