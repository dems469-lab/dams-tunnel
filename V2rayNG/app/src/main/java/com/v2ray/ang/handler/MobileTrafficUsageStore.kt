package com.v2ray.ang.handler

import java.time.LocalDate

/** Stocke uniquement le trafic du tunnel observé sur le réseau cellulaire. */
data class MobileTrafficUsage(
    val day: String,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
)

object MobileTrafficUsageStore {
    private const val DAY_KEY = "dams_mobile_usage_day"
    private const val UPLINK_KEY = "dams_mobile_usage_uplink"
    private const val DOWNLINK_KEY = "dams_mobile_usage_downlink"

    @Synchronized
    fun current(): MobileTrafficUsage {
        val today = LocalDate.now().toString()
        if (MmkvManager.decodeSettingsString(DAY_KEY) != today) {
            MmkvManager.encodeSettings(DAY_KEY, today)
            MmkvManager.encodeSettings(UPLINK_KEY, 0L)
            MmkvManager.encodeSettings(DOWNLINK_KEY, 0L)
        }
        return MobileTrafficUsage(
            day = today,
            uplinkBytes = MmkvManager.decodeSettingsLong(UPLINK_KEY, 0L).coerceAtLeast(0L),
            downlinkBytes = MmkvManager.decodeSettingsLong(DOWNLINK_KEY, 0L).coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun add(uplinkBytes: Long, downlinkBytes: Long): MobileTrafficUsage {
        val before = current()
        val uplink = before.uplinkBytes + uplinkBytes.coerceAtLeast(0L)
        val downlink = before.downlinkBytes + downlinkBytes.coerceAtLeast(0L)
        MmkvManager.encodeSettings(UPLINK_KEY, uplink)
        MmkvManager.encodeSettings(DOWNLINK_KEY, downlink)
        return before.copy(uplinkBytes = uplink, downlinkBytes = downlink)
    }
}
