package com.v2ray.ang.ui.premium

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.premium.PremiumActivationResult
import com.v2ray.ang.premium.PremiumExpiryState
import com.v2ray.ang.premium.PremiumStoredTokenResult
import com.v2ray.ang.premium.PremiumSubscriptionDataSource
import com.v2ray.ang.premium.PremiumSubscriptionInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PremiumSubscriptionUiState(
    val featureEnabled: Boolean,
    val isLoading: Boolean = false,
    val code: String = "",
    val hasSubscription: Boolean = false,
    val requiresCodeReentry: Boolean = false,
    val info: PremiumSubscriptionInfo? = null,
    val expiry: PremiumExpiryState = PremiumExpiryState(),
    @StringRes val messageRes: Int? = null,
    val messageIsError: Boolean = false,
    val showRemoveConfirmation: Boolean = false,
)

class PremiumSubscriptionViewModel(
    private val dataSource: PremiumSubscriptionDataSource,
    featureEnabled: Boolean,
    private val operationScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PremiumSubscriptionUiState(featureEnabled = featureEnabled))
    val uiState: StateFlow<PremiumSubscriptionUiState> = _uiState.asStateFlow()

    fun loadSavedState() {
        if (!uiState.value.featureEnabled || uiState.value.isLoading) return
        launchOperation {
            val loaded = withContext(ioDispatcher) {
                val token = dataSource.readStoredTokenState()
                val expiry = dataSource.expiryState()
                PremiumSubscriptionUiState(
                    featureEnabled = true,
                    hasSubscription = token == PremiumStoredTokenResult.Present || expiry.isExpired,
                    requiresCodeReentry = token == PremiumStoredTokenResult.ReentryRequired,
                    info = dataSource.subscriptionInfo(),
                    expiry = expiry,
                )
            }
            _uiState.value = loaded
        }
    }

    fun onCodeChanged(code: String) {
        _uiState.value = uiState.value.copy(code = code, messageRes = null, messageIsError = false)
    }

    fun activate() {
        val current = uiState.value
        if (!current.featureEnabled || current.isLoading) return
        if (current.code.isBlank()) {
            showError(R.string.premium_error_invalid_code)
            return
        }
        launchOperation {
            applyResult(dataSource.activate(current.code), R.string.premium_activation_success)
        }
    }

    fun refresh() {
        val current = uiState.value
        if (!current.featureEnabled || current.isLoading) return
        launchOperation { applyResult(dataSource.refreshStoredToken(), R.string.premium_refresh_success) }
    }

    fun requestRemove() {
        if (uiState.value.hasSubscription && !uiState.value.isLoading) {
            _uiState.value = uiState.value.copy(showRemoveConfirmation = true)
        }
    }

    fun dismissRemoveConfirmation() {
        _uiState.value = uiState.value.copy(showRemoveConfirmation = false)
    }

    fun confirmRemove() {
        val current = uiState.value
        if (!current.featureEnabled || current.isLoading || !current.showRemoveConfirmation) return
        launchOperation {
            withContext(ioDispatcher) { dataSource.removeSubscriptionManually() }
            _uiState.value = PremiumSubscriptionUiState(
                featureEnabled = true,
                messageRes = R.string.premium_remove_success,
            )
        }
    }

    private suspend fun applyResult(result: PremiumActivationResult, successMessageRes: Int) {
        val current = uiState.value
        when (result) {
            is PremiumActivationResult.Imported -> _uiState.value = current.copy(
                isLoading = false,
                code = "",
                hasSubscription = true,
                requiresCodeReentry = false,
                info = result.info,
                expiry = withContext(ioDispatcher) { dataSource.expiryState() },
                messageRes = successMessageRes,
                messageIsError = false,
            )
            PremiumActivationResult.InvalidCode -> showError(R.string.premium_error_invalid_code)
            PremiumActivationResult.Expired -> _uiState.value = current.copy(
                isLoading = false,
                hasSubscription = true,
                requiresCodeReentry = false,
                info = withContext(ioDispatcher) { dataSource.subscriptionInfo() },
                expiry = withContext(ioDispatcher) { dataSource.expiryState() },
                messageRes = R.string.premium_error_expired,
                messageIsError = true,
            )
            PremiumActivationResult.EmptyOrInvalidResponse -> showError(R.string.premium_error_invalid_response)
            PremiumActivationResult.NetworkUnavailable -> showError(R.string.premium_error_network_unavailable)
            PremiumActivationResult.DomainUnreachable -> showError(R.string.premium_error_domain_unreachable)
            PremiumActivationResult.ServerFailure -> showError(R.string.premium_error_server)
            PremiumActivationResult.ReentryRequired -> _uiState.value = current.copy(
                isLoading = false,
                requiresCodeReentry = true,
                messageRes = R.string.premium_error_reenter_code,
                messageIsError = true,
            )
            PremiumActivationResult.Disabled -> _uiState.value = current.copy(
                isLoading = false,
                featureEnabled = false,
            )
        }
    }

    private fun showError(@StringRes messageRes: Int) {
        _uiState.value = uiState.value.copy(
            isLoading = false,
            messageRes = messageRes,
            messageIsError = true,
        )
    }

    private fun launchOperation(block: suspend () -> Unit) {
        _uiState.value = uiState.value.copy(isLoading = true, messageRes = null, messageIsError = false)
        (operationScope ?: viewModelScope).launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(R.string.premium_error_server)
            } finally {
                if (uiState.value.isLoading) _uiState.value = uiState.value.copy(isLoading = false)
            }
        }
    }

    class Factory(
        private val dataSource: PremiumSubscriptionDataSource,
        private val featureEnabled: Boolean,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(PremiumSubscriptionViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class")
            }
            return PremiumSubscriptionViewModel(dataSource, featureEnabled) as T
        }
    }
}
