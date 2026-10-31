package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GroupDisplayNameTest {
    @Test
    fun defaultGroupWithLegacyRemarksUsesLocalizedLabel() {
        assertTrue(
            usesDefaultGroupLabel(
                AppConfig.DEFAULT_SUBSCRIPTION_ID,
                AppConfig.DEFAULT_SUBSCRIPTION_REMARKS,
            ),
        )
    }

    @Test
    fun renamedDefaultGroupKeepsUserName() {
        assertFalse(usesDefaultGroupLabel(AppConfig.DEFAULT_SUBSCRIPTION_ID, "Travail"))
    }

    @Test
    fun subscriptionNamedDefaultKeepsItsOwnName() {
        assertFalse(usesDefaultGroupLabel("subscription-guid", AppConfig.DEFAULT_SUBSCRIPTION_REMARKS))
    }

    @Test
    fun emptyGroupIdIsNotTheDefaultGroup() {
        assertFalse(usesDefaultGroupLabel("", AppConfig.DEFAULT_SUBSCRIPTION_REMARKS))
    }
}
