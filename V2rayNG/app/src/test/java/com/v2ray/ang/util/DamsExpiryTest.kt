package com.v2ray.ang.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DamsExpiryTest {
    @Test
    fun `expiry is 23 59 59 on selected local date across time zones`() {
        val selectedDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, Calendar.OCTOBER, 20)
        }.timeInMillis

        listOf("GMT-08:00", "UTC", "GMT+13:00").forEach { zoneId ->
            val zone = TimeZone.getTimeZone(zoneId)
            val expiry = Calendar.getInstance(zone).apply {
                timeInMillis = damsExpiryAtLocalEndOfDay(selectedDate, zone)
            }

            assertEquals(2026, expiry.get(Calendar.YEAR), zoneId)
            assertEquals(Calendar.OCTOBER, expiry.get(Calendar.MONTH), zoneId)
            assertEquals(20, expiry.get(Calendar.DAY_OF_MONTH), zoneId)
            assertEquals(23, expiry.get(Calendar.HOUR_OF_DAY), zoneId)
            assertEquals(59, expiry.get(Calendar.MINUTE), zoneId)
            assertEquals(59, expiry.get(Calendar.SECOND), zoneId)
            assertEquals(0, expiry.get(Calendar.MILLISECOND), zoneId)
        }
    }
}
