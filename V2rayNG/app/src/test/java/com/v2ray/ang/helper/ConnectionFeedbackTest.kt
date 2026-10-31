package com.v2ray.ang.helper

import com.v2ray.ang.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectionFeedbackTest {
    @Test
    fun `stored values map to their mode`() {
        assertEquals(FeedbackMode.SILENT, FeedbackMode.fromValue("silent"))
        assertEquals(FeedbackMode.VIBRATE, FeedbackMode.fromValue("vibrate"))
        assertEquals(FeedbackMode.SOUND, FeedbackMode.fromValue("sound"))
    }

    @Test
    fun `unknown and missing values fall back to silent`() {
        assertEquals(FeedbackMode.SILENT, FeedbackMode.fromValue(null))
        assertEquals(FeedbackMode.SILENT, FeedbackMode.fromValue(""))
        assertEquals(FeedbackMode.SILENT, FeedbackMode.fromValue("loud"))
    }

    @Test
    fun `each event owns a distinct preference`() {
        val keys = listOf(
            ConnectionFeedback.EVENT_CONNECT,
            ConnectionFeedback.EVENT_DISCONNECT,
            ConnectionFeedback.EVENT_NOTIFICATION,
        ).map(ConnectionFeedback::prefKeyFor)

        assertEquals(
            listOf(
                AppConfig.PREF_FEEDBACK_CONNECT_MODE,
                AppConfig.PREF_FEEDBACK_DISCONNECT_MODE,
                AppConfig.PREF_FEEDBACK_NOTIFICATION_MODE,
            ),
            keys
        )
        assertEquals(keys.size, keys.toSet().size)
    }
}
