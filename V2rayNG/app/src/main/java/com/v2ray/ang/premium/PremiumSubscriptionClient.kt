package com.v2ray.ang.premium

import com.v2ray.ang.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException

internal sealed interface PremiumFetchResult {
    data class Content(val text: String, val userInfo: PremiumSubscriptionInfo?) : PremiumFetchResult
    data object ConfirmedExpired : PremiumFetchResult
    data object InvalidCode : PremiumFetchResult
    data object EmptyResponse : PremiumFetchResult
    data object DomainUnreachable : PremiumFetchResult
    data object ServerFailure : PremiumFetchResult
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
        if (urls.isEmpty()) return@withContext PremiumFetchResult.InvalidCode

        val outcomes = mutableListOf<PremiumExpiryObservation>()
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
                        EndpointResult.Observed(observation)
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
                            PremiumExpiryObservation.HEADER_EXPIRED,
                            PremiumExpiryObservation.INVALID_CODE,
                            PremiumExpiryObservation.SERVER_FAILURE,
                            PremiumExpiryObservation.NETWORK_FAILURE,
                            PremiumExpiryObservation.EMPTY_RESPONSE,
                            PremiumExpiryObservation.INDETERMINATE -> EndpointResult.Observed(observation)
                            PremiumExpiryObservation.ACTIVE -> EndpointResult.Content(text!!, userInfo)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: UnknownHostException) {
                EndpointResult.Observed(PremiumExpiryObservation.NETWORK_FAILURE)
            } catch (_: SocketTimeoutException) {
                EndpointResult.Observed(PremiumExpiryObservation.NETWORK_FAILURE)
            } catch (_: IOException) {
                EndpointResult.Observed(PremiumExpiryObservation.NETWORK_FAILURE)
            } catch (_: Exception) {
                // Ne pas journaliser l'exception : certains clients incluent l'URL et le token.
                EndpointResult.Observed(PremiumExpiryObservation.INDETERMINATE)
            }

            when (response) {
                is EndpointResult.Content -> return@withContext PremiumFetchResult.Content(response.text, response.userInfo)
                is EndpointResult.Observed -> outcomes += response.observation
            }
        }
        when (PremiumExpiryPolicy.aggregate(outcomes, urls.size)) {
            PremiumExpiryObservation.HTTP_EXPIRED,
            PremiumExpiryObservation.HEADER_EXPIRED -> PremiumFetchResult.ConfirmedExpired
            PremiumExpiryObservation.INVALID_CODE -> PremiumFetchResult.InvalidCode
            PremiumExpiryObservation.ACTIVE -> PremiumFetchResult.Failed
            PremiumExpiryObservation.EMPTY_RESPONSE -> PremiumFetchResult.EmptyResponse
            PremiumExpiryObservation.NETWORK_FAILURE -> PremiumFetchResult.DomainUnreachable
            PremiumExpiryObservation.SERVER_FAILURE -> PremiumFetchResult.ServerFailure
            PremiumExpiryObservation.INDETERMINATE -> PremiumFetchResult.Failed
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
        data class Observed(val observation: PremiumExpiryObservation) : EndpointResult
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val BUFFER_SIZE = 8 * 1024
        const val SUBSCRIPTION_USER_INFO_HEADER = "subscription-userinfo"
    }
}
