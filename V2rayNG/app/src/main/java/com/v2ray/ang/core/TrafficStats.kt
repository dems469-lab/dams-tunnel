package com.v2ray.ang.core

import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.TrafficSnapshot

internal fun outboundTagsFromConfig(configContent: String): Set<String> = runCatching {
    JsonParser.parseString(configContent)
        .asJsonObject
        .getAsJsonArray("outbounds")
        .mapNotNull { it.asJsonObject.get("tag")?.takeUnless { tag -> tag.isJsonNull }?.asString }
        .filter(String::isNotBlank)
        .toSet()
}.getOrDefault(emptySet())

/**
 * Large policy groups expose one outbound per member. Polling every two seconds
 * then costs two JNI reads per member; slow the cadence once the tag count is
 * high so the stats loop does not compete with the data path.
 */
internal fun trafficStatsPollIntervalMillis(outboundTagCount: Int): Long =
    if (outboundTagCount > 24) 5_000L else 2_000L

internal fun trafficSnapshotFromStats(
    stats: List<OutboundTrafficStat>,
    elapsedMillis: Long,
    previous: TrafficSnapshot,
): TrafficSnapshot? {
    if (stats.isEmpty()) return null
    val seconds = (elapsedMillis.coerceAtLeast(1L)) / 1000.0
    val uplink = stats.filter { it.direction == AppConfig.UPLINK }.sumOf { it.value.coerceAtLeast(0L) }
    val downlink = stats.filter { it.direction == AppConfig.DOWNLINK }.sumOf { it.value.coerceAtLeast(0L) }
    return TrafficSnapshot(
        uplinkBytesPerSecond = (uplink / seconds).toLong(),
        downlinkBytesPerSecond = (downlink / seconds).toLong(),
        uplinkTotalBytes = previous.uplinkTotalBytes + uplink,
        downlinkTotalBytes = previous.downlinkTotalBytes + downlink,
        available = true,
    )
}
