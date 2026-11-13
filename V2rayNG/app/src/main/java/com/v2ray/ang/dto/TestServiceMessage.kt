package com.v2ray.ang.dto

import java.io.Serializable

data class TestServiceMessage(
    val key: Int,
    val subscriptionId: String = "",
    val serverGuids: List<String> = emptyList(),
    val onlyTcp: Boolean = false,
    /** 0 = réglage global ; sinon concurrence imposée par l'écran demandeur. */
    val concurrency: Int = 0
) : Serializable

