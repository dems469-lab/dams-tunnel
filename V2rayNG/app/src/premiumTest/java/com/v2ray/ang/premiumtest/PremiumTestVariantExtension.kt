package com.v2ray.ang.premiumtest

import android.content.Context
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.premium.PremiumBuildVariantExtension
import com.v2ray.ang.premium.PremiumDemoScenario
import com.v2ray.ang.premium.PremiumDemoSubscriptionDataSource
import com.v2ray.ang.premium.PremiumSubscriptionDataSource
import com.v2ray.ang.premium.PremiumSubscriptionRepository

class PremiumTestVariantExtension(context: Context) : PremiumBuildVariantExtension {
    private val demoSource = if (BuildConfig.DAMS_PREMIUM_DEMO_MODE) {
        PremiumDemoSubscriptionDataSource()
    } else {
        null
    }

    override val dataSource: PremiumSubscriptionDataSource =
        demoSource ?: PremiumSubscriptionRepository(context)

    override val hasOptions: Boolean = demoSource != null

    @Composable
    override fun Options(onOptionSelected: (String) -> Unit) {
        if (demoSource == null) return
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.premium_demo_build_notice))
            Text(stringResource(R.string.premium_demo_choose_state))
            ScenarioButton(R.string.premium_demo_active, "active", onOptionSelected)
            ScenarioButton(R.string.premium_demo_expired, "expired", onOptionSelected)
            ScenarioButton(R.string.premium_demo_quota, "quota", onOptionSelected)
        }
    }

    override fun selectOption(option: String) {
        val scenario = when (option) {
            "active" -> PremiumDemoScenario.ACTIVE
            "expired" -> PremiumDemoScenario.EXPIRED
            "quota" -> PremiumDemoScenario.QUOTA_EXHAUSTED
            else -> return
        }
        demoSource?.selectScenario(scenario)
    }

    @Composable
    private fun ScenarioButton(labelRes: Int, option: String, onOptionSelected: (String) -> Unit) {
        OutlinedButton(
            onClick = { onOptionSelected(option) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(labelRes)) }
    }
}
