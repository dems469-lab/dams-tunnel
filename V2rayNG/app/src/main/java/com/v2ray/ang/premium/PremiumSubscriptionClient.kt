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

        val outcomes = mutableListOf<PremiumExpiryObservation>()
        var emptyResponseFound = false
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
                    if (!PremiumExpiryPolicy.responseMayContainSubscriptionContent(code)) {
                        val observation = PremiumExpiryPolicy.observeHttpResponse(code, userInfo, false, nowEpochSeconds)
                        if (observation == PremiumExpiryObservation.HTTP_EXPIRED ||
                            observation == PremiumExpiryObservation.HEADER_EXPIRED
                        ) EndpointResult.Expired(observation) else EndpointResult.Failed
                    } else {
                        val body = httpResponse.body
                        val text = body?.let { readBounded(it.byteStream()) }
                        when (val observation = PremiumExpiryPolicy.observeHttpResponse(
                            httpCode = code,
                            info = userInfo,
                            hasUsableContent = !text.isNullOrBlank(),
                            nowEpochSeconds = nowEpochSeconds,
                        )) {
                            PremiumExpiryObservation.HTTP_EXPIRED,
                            PremiumExpiryObservation.HEADER_EXPIRED -> EndpointResult.Expired(observation)
                            PremiumExpiryObservation.ACTIVE -> EndpointResult.Content(text!!, userInfo)
                            PremiumExpiryObservation.INDETERMINATE -> if (text.isNullOrBlank()) {
                                EndpointResult.Empty
                            } else {
                                EndpointResult.Failed
                            }
                        }
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
                is EndpointResult.Expired -> outcomes += response.observation
                EndpointResult.Empty -> {
                    emptyResponseFound = true
                    outcomes += PremiumExpiryObservation.INDETERMINATE
                }
                EndpointResult.Failed -> outcomes += PremiumExpiryObservation.INDETERMINATE
            }
        }
        when (PremiumExpiryPolicy.aggregate(outcomes, urls.size)) {
            PremiumExpiryObservation.HTTP_EXPIRED,
            PremiumExpiryObservation.HEADER_EXPIRED -> PremiumFetchResult.ConfirmedExpired
            PremiumExpiryObservation.ACTIVE -> PremiumFetchResult.Failed
            PremiumExpiryObservation.INDETERMINATE -> if (emptyResponseFound) PremiumFetchResult.EmptyResponse else PremiumFetchResult.Failed
        }
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
        data class Expired(val observation: PremiumExpiryObservation) : EndpointResult
        data object Empty : EndpointResult
        data object Failed : EndpointResult
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val BUFFER_SIZE = 8 * 1024
        const val SUBSCRIPTION_USER_INFO_HEADER = "subscription-userinfo"
    }
}
