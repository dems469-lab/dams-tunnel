package com.v2ray.ang.ui.premium

import com.v2ray.ang.R
import com.v2ray.ang.premium.PremiumActivationResult
import com.v2ray.ang.premium.PremiumDemoScenario
import com.v2ray.ang.premium.PremiumDemoSubscriptionDataSource
import com.v2ray.ang.premium.PremiumExpiryState
import com.v2ray.ang.premium.PremiumStoredTokenResult
import com.v2ray.ang.premium.PremiumSubscriptionDataSource
import com.v2ray.ang.premium.PremiumSubscriptionInfo
import com.v2ray.ang.premium.PremiumSubscriptionInfoParser
import com.v2ray.ang.premium.PremiumSubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PremiumSubscriptionViewModelTest {
    @Test
    fun `initial state reflects the feature gate and does not call repository`() {
        val data = FakeDataSource()
        val disabled = newViewModel(data, enabled = false)
        val enabled = newViewModel(data, enabled = true)

        assertFalse(disabled.uiState.value.featureEnabled)
        assertTrue(enabled.uiState.value.featureEnabled)
        assertFalse(enabled.uiState.value.hasSubscription)
        assertEquals(0, data.readTokenCalls)
    }

    @Test
    fun `load restores an active subscription and metadata`() {
        val data = FakeDataSource().apply {
            tokenState = PremiumStoredTokenResult.Present
            info = PremiumSubscriptionInfo(uploadBytes = 40, downloadBytes = 60, totalBytes = 500)
        }
        val viewModel = newViewModel(data)

        viewModel.loadSavedState()

        assertTrue(viewModel.uiState.value.hasSubscription)
        assertEquals(data.info, viewModel.uiState.value.info)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `load requests code again when saved token cannot be decrypted`() {
        val data = FakeDataSource().apply { tokenState = PremiumStoredTokenResult.ReentryRequired }
        val viewModel = newViewModel(data)

        viewModel.loadSavedState()

        assertTrue(viewModel.uiState.value.requiresCodeReentry)
        assertEquals(PremiumStoredTokenResult.ReentryRequired, data.tokenState)
    }

    @Test
    fun `activation validates blank code before repository call`() {
        val data = FakeDataSource()
        val viewModel = newViewModel(data)

        viewModel.activate()

        assertEquals(R.string.premium_error_invalid_code, viewModel.uiState.value.messageRes)
        assertEquals(0, data.activateCalls)
    }

    @Test
    fun `successful activation shows status and clears entered code`() {
        val info = PremiumSubscriptionInfo(uploadBytes = 100, downloadBytes = 200, totalBytes = 1000)
        val data = FakeDataSource().apply {
            activationResult = PremiumActivationResult.Imported(2, info)
        }
        val viewModel = newViewModel(data)
        viewModel.onCodeChanged("authorized-token")

        viewModel.activate()

        assertTrue(viewModel.uiState.value.hasSubscription)
        assertEquals(info, viewModel.uiState.value.info)
        assertEquals("", viewModel.uiState.value.code)
        assertEquals(R.string.premium_activation_success, viewModel.uiState.value.messageRes)
    }

    @Test
    fun `invalid code domain network expiry empty server and reentry have distinct messages`() {
        val cases = listOf(
            PremiumActivationResult.InvalidCode to R.string.premium_error_invalid_code,
            PremiumActivationResult.DomainUnreachable to R.string.premium_error_domain_unreachable,
            PremiumActivationResult.NetworkUnavailable to R.string.premium_error_network_unavailable,
            PremiumActivationResult.EmptyOrInvalidResponse to R.string.premium_error_invalid_response,
            PremiumActivationResult.ServerFailure to R.string.premium_error_server,
            PremiumActivationResult.ReentryRequired to R.string.premium_error_reenter_code,
        )
        cases.forEach { (result, expectedMessage) ->
            val data = FakeDataSource().apply { activationResult = result }
            val viewModel = newViewModel(data)
            viewModel.onCodeChanged("test-code")

            viewModel.activate()

            assertEquals(expectedMessage, viewModel.uiState.value.messageRes, "$result")
            assertTrue(viewModel.uiState.value.messageIsError, "$result")
        }
    }

    @Test
    fun `expiration is shown and keeps saved Premium state`() {
        val expiry = PremiumExpiryState(firstConfirmedAtMillis = 1_800_000_000_000L, confirmationCount = 1)
        val data = FakeDataSource().apply {
            refreshResult = PremiumActivationResult.Expired
            savedExpiry = expiry
            tokenState = PremiumStoredTokenResult.Present
        }
        val viewModel = newViewModel(data)

        viewModel.refresh()

        assertTrue(viewModel.uiState.value.hasSubscription)
        assertTrue(viewModel.uiState.value.expiry.isExpired)
        assertEquals(R.string.premium_error_expired, viewModel.uiState.value.messageRes)
    }

    @Test
    fun `refresh success updates information and reports refreshed state`() {
        val info = PremiumSubscriptionInfo(totalBytes = 0)
        val data = FakeDataSource().apply { refreshResult = PremiumActivationResult.Imported(1, info) }
        val viewModel = newViewModel(data)

        viewModel.refresh()

        assertEquals(R.string.premium_refresh_success, viewModel.uiState.value.messageRes)
        assertEquals(info, viewModel.uiState.value.info)
    }

    @Test
    fun `disabled result hides the feature state`() {
        val data = FakeDataSource().apply { activationResult = PremiumActivationResult.Disabled }
        val viewModel = newViewModel(data)
        viewModel.onCodeChanged("token")

        viewModel.activate()

        assertFalse(viewModel.uiState.value.featureEnabled)
    }

    @Test
    fun `manual deletion requires confirmation and resets only the subscription state`() {
        val data = FakeDataSource().apply { tokenState = PremiumStoredTokenResult.Present }
        val viewModel = newViewModel(data)
        viewModel.loadSavedState()

        viewModel.requestRemove()
        assertTrue(viewModel.uiState.value.showRemoveConfirmation)
        assertEquals(0, data.removeCalls)
        viewModel.dismissRemoveConfirmation()
        assertFalse(viewModel.uiState.value.showRemoveConfirmation)
        viewModel.requestRemove()
        viewModel.confirmRemove()

        assertEquals(1, data.removeCalls)
        assertFalse(viewModel.uiState.value.hasSubscription)
        assertEquals(R.string.premium_remove_success, viewModel.uiState.value.messageRes)
    }

    @Test
    fun `loading remains visible while activation is pending`() {
        val pending = CompletableDeferred<PremiumActivationResult>()
        val data = FakeDataSource().apply { activationBlock = { pending.await() } }
        val viewModel = newViewModel(data)
        viewModel.onCodeChanged("token")

        viewModel.activate()

        assertTrue(viewModel.uiState.value.isLoading)
        pending.complete(PremiumActivationResult.InvalidCode)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `unexpected repository exception maps to localized server error`() {
        val data = FakeDataSource().apply { activationBlock = { error("network") } }
        val viewModel = newViewModel(data)
        viewModel.onCodeChanged("token")

        viewModel.activate()

        assertEquals(R.string.premium_error_server, viewModel.uiState.value.messageRes)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `test build demo source presents active expired and exhausted quota states without networking`() {
        val source = PremiumDemoSubscriptionDataSource(nowEpochSeconds = { 1_800_000_000L })
        val viewModel = PremiumSubscriptionViewModel(
            dataSource = source,
            featureEnabled = true,
            operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.loadSavedState()
        assertEquals(PremiumDemoScenario.ACTIVE, viewModel.uiState.value.demoScenario)
        assertEquals(
            PremiumSubscriptionStatus.ACTIVE,
            PremiumSubscriptionInfoParser.status(viewModel.uiState.value.info, 1_800_000_000L),
        )

        viewModel.selectDemoScenario(PremiumDemoScenario.EXPIRED)
        assertTrue(viewModel.uiState.value.expiry.isExpired)
        assertEquals(
            PremiumSubscriptionStatus.EXPIRED,
            PremiumSubscriptionInfoParser.status(viewModel.uiState.value.info, 1_800_000_000L),
        )

        viewModel.selectDemoScenario(PremiumDemoScenario.QUOTA_EXHAUSTED)
        assertEquals(
            PremiumSubscriptionStatus.QUOTA_EXHAUSTED,
            PremiumSubscriptionInfoParser.status(viewModel.uiState.value.info, 1_800_000_000L),
        )
    }

    private fun newViewModel(data: FakeDataSource, enabled: Boolean = true) = PremiumSubscriptionViewModel(
        dataSource = data,
        featureEnabled = enabled,
        operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class FakeDataSource : PremiumSubscriptionDataSource {
        var activationResult: PremiumActivationResult = PremiumActivationResult.InvalidCode
        var refreshResult: PremiumActivationResult = PremiumActivationResult.InvalidCode
        var activationBlock: suspend () -> PremiumActivationResult = { activationResult }
        var tokenState: PremiumStoredTokenResult = PremiumStoredTokenResult.Missing
        var savedExpiry: PremiumExpiryState = PremiumExpiryState()
        var info: PremiumSubscriptionInfo? = null
        var activateCalls = 0
        var removeCalls = 0
        var readTokenCalls = 0

        override suspend fun activate(codeOrUrl: String): PremiumActivationResult {
            activateCalls++
            return activationBlock()
        }

        override suspend fun refreshStoredToken(): PremiumActivationResult = refreshResult
        override fun readStoredTokenState(): PremiumStoredTokenResult {
            readTokenCalls++
            return tokenState
        }
        override fun expiryState(): PremiumExpiryState = savedExpiry
        override fun subscriptionInfo(): PremiumSubscriptionInfo? = info
        override fun removeSubscriptionManually(): Int {
            removeCalls++
            return 0
        }
    }
}
