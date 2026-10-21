package com.v2ray.ang.dto

import java.io.Serializable

/** Snapshot of the traffic counters exposed by the Xray core. */
data class TrafficSnapshot(
    val uplinkBytesPerSecond: Long = 0L,
    val downlinkBytesPerSecond: Long = 0L,
    val uplinkTotalBytes: Long = 0L,
    val downlinkTotalBytes: Long = 0L,
    val available: Boolean = false,
    val mobileDailyUplinkBytes: Long = 0L,
    val mobileDailyDownlinkBytes: Long = 0L,
) : Serializable
