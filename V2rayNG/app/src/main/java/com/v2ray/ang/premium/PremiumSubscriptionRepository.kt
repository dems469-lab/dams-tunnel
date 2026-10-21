package com.v2ray.ang.premium

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PremiumActivationResult {
    data class Imported(val profileCount: Int, val info: PremiumSubscriptionInfo?) : PremiumActivationResult
    data object InvalidCode : PremiumActivationResult
    data object Expired : PremiumActivationResult
    data object EmptyOrInvalidResponse : PremiumActivationResult
    data object NetworkUnavailable : PremiumActivationResult
    data object DomainUnreachable : PremiumActivationResult
    data object ServerFailure : PremiumActivationResult
    data object ReentryRequired : PremiumActivationResult
    data object Disabled : PremiumActivationResult
}

sealed interface PremiumStoredTokenResult {
    data object Missing : PremiumStoredTokenResult
    data object ReentryRequired : PremiumStoredTokenResult
    data object Disabled : PremiumStoredTokenResult
    data object Present : PremiumStoredTokenResult
}

/** Accès isolé au stockage et à l'import de l'abonnement Premium. */
class PremiumSubscriptionRepository internal constructor(
    context: Context,
    private val endpointPolicy: PremiumEndpointPolicy = PremiumEndpointPolicy(configuredBaseUrls()),
    private val tokenStore: PremiumTokenStore = PremiumTokenStore(context),
    private val client: PremiumSubscriptionClient = PremiumSubscriptionClient(endpointPolicy),
    private val importProfiles: (String) -> Int = AngConfigManager::importPremiumSubscriptionContent,
    private val removeExpiredProfiles: () -> Int = MmkvManager::removePremiumGroupProfiles,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val expiryStateStore: PremiumExpiryStateStore = PremiumExpiryStateStore(),
    private val infoStore: PremiumSubscriptionInfoStore = PremiumSubscriptionInfoStore(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val networkAvailable: () -> Boolean = { hasInternetCapability(context) },
) : PremiumSubscriptionDataSource {
    override suspend fun activate(codeOrUrl: String): PremiumActivationResult {
        if (!PremiumFeatureGate.isEnabled()) return PremiumActivationResult.Disabled
        val token = endpointPolicy.token(codeOrUrl) ?: return PremiumActivationResult.InvalidCode
        return applyFetchResult(token, persistTokenOnSuccess = true)
    }

    override suspend fun refreshStoredToken(): PremiumActivationResult {
        if (!PremiumFeatureGate.isEnabled()) return PremiumActivationResult.Disabled
        return when (val stored = tokenStore.readToken()) {
            PremiumTokenReadResult.Missing -> PremiumActivationResult.InvalidCode
            PremiumTokenReadResult.ReentryRequired -> PremiumActivationResult.ReentryRequired
            is PremiumTokenReadResult.Available -> applyFetchResult(stored.token, persistTokenOnSuccess = false)
        }
    }

    override fun readStoredTokenState(): PremiumStoredTokenResult {
        if (!PremiumFeatureGate.isEnabled()) return PremiumStoredTokenResult.Disabled
        return when (val stored = tokenStore.readToken()) {
            PremiumTokenReadResult.Missing -> PremiumStoredTokenResult.Missing
            PremiumTokenReadResult.ReentryRequired -> PremiumStoredTokenResult.ReentryRequired
            is PremiumTokenReadResult.Available -> PremiumStoredTokenResult.Present
        }
    }

    fun clear() = tokenStore.clear()

    override fun expiryState(): PremiumExpiryState = expiryStateStore.load()

    override fun subscriptionInfo(): PremiumSubscriptionInfo? = infoStore.load()

    /** Suppression explicitement demandée : ne touche qu'aux profils du groupe Premium. */
    override fun removeSubscriptionManually(): Int {
        val removedProfiles = removeExpiredProfiles()
        tokenStore.clear()
        expiryStateStore.clear()
        infoStore.clear()
        return removedProfiles
    }

    private suspend fun applyFetchResult(
        token: String,
        persistTokenOnSuccess: Boolean,
    ): PremiumActivationResult {
        val connected = withContext(Dispatchers.IO) { networkAvailable() }
        if (!connected) return PremiumActivationResult.NetworkUnavailable
        return when (val fetched = client.fetch(token, nowEpochSeconds())) {
            is PremiumFetchResult.Content -> {
                val count = withContext(Dispatchers.IO) { importProfiles(fetched.text) }
                if (count <= 0) {
                    PremiumActivationResult.EmptyOrInvalidResponse
                } else {
                    if (persistTokenOnSuccess) withContext(Dispatchers.IO) { tokenStore.saveToken(token) }
                    withContext(Dispatchers.IO) {
                        expiryStateStore.save(PremiumExpiryPolicy.recordActive(expiryStateStore.load()).state)
                        infoStore.save(fetched.userInfo)
                    }
                    PremiumActivationResult.Imported(count, fetched.userInfo)
                }
            }
            PremiumFetchResult.ConfirmedExpired -> withContext(Dispatchers.IO) {
                val evaluation = PremiumExpiryPolicy.recordExpiration(expiryStateStore.load(), nowMillis())
                if (persistTokenOnSuccess && !evaluation.state.profilesDeleted) tokenStore.saveToken(token)
                if (evaluation.action == PremiumExpiryAction.DELETE_PROFILES) removeExpiredProfiles()
                expiryStateStore.save(evaluation.state)
                PremiumActivationResult.Expired
            }
            PremiumFetchResult.EmptyResponse -> PremiumActivationResult.EmptyOrInvalidResponse
            PremiumFetchResult.InvalidCode -> PremiumActivationResult.InvalidCode
            PremiumFetchResult.DomainUnreachable -> PremiumActivationResult.DomainUnreachable
            PremiumFetchResult.ServerFailure -> PremiumActivationResult.ServerFailure
            PremiumFetchResult.Failed -> PremiumActivationResult.ServerFailure
        }
    }

    private fun hasInternetCapability(context: Context): Boolean {
        return runCatching {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            connectivityManager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }

    private companion object {
        fun configuredBaseUrls(): String = buildString {
            append(BuildConfig.PREMIUM_BASE_URLS)
            if (BuildConfig.DEBUG && BuildConfig.PREMIUM_DEBUG_BASE_URL.isNotBlank()) {
                if (isNotEmpty()) append(',')
                append(BuildConfig.PREMIUM_DEBUG_BASE_URL)
            }
        }
    }
}
