package com.v2ray.ang.util

import java.util.Calendar
import java.util.TimeZone

/** Convertit le jour UTC renvoyé par Material DatePicker en fin de journée locale. */
fun damsExpiryAtLocalEndOfDay(selectedDateMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
    val selectedUtcDate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = selectedDateMillis
    }
    return Calendar.getInstance(timeZone).apply {
        clear()
        set(
            selectedUtcDate.get(Calendar.YEAR),
            selectedUtcDate.get(Calendar.MONTH),
            selectedUtcDate.get(Calendar.DAY_OF_MONTH),
            23,
            59,
            59,
        )
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
