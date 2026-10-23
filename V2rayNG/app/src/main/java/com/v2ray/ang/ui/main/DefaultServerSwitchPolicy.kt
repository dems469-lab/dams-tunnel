package com.v2ray.ang.ui.main

data class ServerPerformanceCandidate(
    val guid: String,
    val protocol: String,
    val delayMillis: Long,
)

fun chooseFasterDefaultServer(
    enabled: Boolean,
    isDefaultGroup: Boolean,
    selected: ServerPerformanceCandidate,
    candidates: List<ServerPerformanceCandidate>,
    slowThresholdMillis: Long = 800L,
): ServerPerformanceCandidate? {
    if (!enabled || !isDefaultGroup) return null
    if (selected.delayMillis in 0 until slowThresholdMillis) return null
    return candidates
        .asSequence()
        .filter { it.protocol == selected.protocol && it.guid != selected.guid && it.delayMillis >= 0L }
        .filter { it.delayMillis + 100L < selected.delayMillis || selected.delayMillis < 0L }
        .minByOrNull { it.delayMillis }
}
