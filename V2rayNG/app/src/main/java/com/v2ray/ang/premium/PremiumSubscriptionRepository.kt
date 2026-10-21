package com.v2ray.ang.premium

import android.content.Context
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

sealed interface PremiumActivationResult {
    data class Imported(val profileCount: Int, val info: PremiumSubscriptionInfo?) : PremiumActivationResult
    data object InvalidCode : PremiumActivationResult
    data object Expired : PremiumActivationResult
    data object EmptyOrInvalidResponse : PremiumActivationResult
    data object NetworkOrServerError : PremiumActivationResult
    data object ReentryRequired : PremiumActivationResult
    data object Disabled : PremiumActivationResult
}

sealed interface PremiumStoredTokenResult {
    data object Missing : PremiumStoredTokenResult
    data object ReentryRequired : PremiumStoredTokenResult
    data object Disabled : PremiumStoredTokenResult
    data object Present : PremiumStoredTokenResult
}

/** Étape de stockage/import. L'interface sera branchée à l'écran Premium à l'étape suivante. */
class PremiumSubscriptionRepository internal constructor(
    context: Context,
    private val endpointPolicy: PremiumEndpointPolicy = PremiumEndpointPolicy(configuredBaseUrls()),
    private val tokenStore: PremiumTokenStore = PremiumTokenStore(context),
    private val client: PremiumSubscriptionClient = PremiumSubscriptionClient(endpointPolicy),
    private val importProfiles: (String) -> Int = AngConfigManager::importPremiumSubscriptionContent,
    private val removeExpiredProfiles: () -> Int = MmkvManager::removePremiumGroupProfilesAfterConfirmedExpiration,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    suspend fun activate(codeOrUrl: String): PremiumActivationResult {
        if (!BuildConfig.PREMIUM_ENABLED) return PremiumActivationResult.Disabled
        val token = endpointPolicy.token(codeOrUrl) ?: return PremiumActivationResult.InvalidCode
        return applyFetchResult(token, persistTokenOnSuccess = true)
    }

    suspend fun refreshStoredToken(): PremiumActivationResult {
        if (!BuildConfig.PREMIUM_ENABLED) return PremiumActivationResult.Disabled
        return when (val stored = tokenStore.readToken()) {
            PremiumTokenReadResult.Missing -> PremiumActivationResult.InvalidCode
            PremiumTokenReadResult.ReentryRequired -> PremiumActivationResult.ReentryRequired
            is PremiumTokenReadResult.Available -> applyFetchResult(stored.token, persistTokenOnSuccess = false)
        }
    }

    fun readStoredTokenState(): PremiumStoredTokenResult {
        if (!BuildConfig.PREMIUM_ENABLED) return PremiumStoredTokenResult.Disabled
        return when (val stored = tokenStore.readToken()) {
            PremiumTokenReadResult.Missing -> PremiumStoredTokenResult.Missing
            PremiumTokenReadResult.ReentryRequired -> PremiumStoredTokenResult.ReentryRequired
            is PremiumTokenReadResult.Available -> PremiumStoredTokenResult.Present
        }
    }

    fun clear() = tokenStore.clear()

    private suspend fun applyFetchResult(
        token: String,
        persistTokenOnSuccess: Boolean,
    ): PremiumActivationResult = when (val fetched = client.fetch(token, nowEpochSeconds())) {
        is PremiumFetchResult.Content -> {
            val count = importProfiles(fetched.text)
            if (count <= 0) {
                PremiumActivationResult.EmptyOrInvalidResponse
            } else {
                if (persistTokenOnSuccess) tokenStore.saveToken(token)
                PremiumActivationResult.Imported(count, fetched.userInfo)
            }
        }
        PremiumFetchResult.ConfirmedExpired -> {
            removeExpiredProfiles()
            tokenStore.clear()
            PremiumActivationResult.Expired
        }
        PremiumFetchResult.EmptyResponse -> PremiumActivationResult.EmptyOrInvalidResponse
        PremiumFetchResult.Failed -> PremiumActivationResult.NetworkOrServerError
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
