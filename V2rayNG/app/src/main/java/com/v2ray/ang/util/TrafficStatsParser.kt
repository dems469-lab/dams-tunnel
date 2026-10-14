package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.OutboundTrafficStat

/** Parses the compact stats string returned by the libv2ray AAR. */
object TrafficStatsParser {
    fun parse(raw: String?): List<OutboundTrafficStat> = raw.orEmpty()
        .split(';', '\n')
        .mapNotNull { item ->
            val fields = item.trim().split(',')
            if (fields.size < 3) return@mapNotNull null
            val direction = fields[1].trim()
            if (direction != AppConfig.UPLINK && direction != AppConfig.DOWNLINK) return@mapNotNull null
            val value = fields[2].trim().toLongOrNull() ?: return@mapNotNull null
            OutboundTrafficStat(fields[0].trim(), direction, value)
        }
}
