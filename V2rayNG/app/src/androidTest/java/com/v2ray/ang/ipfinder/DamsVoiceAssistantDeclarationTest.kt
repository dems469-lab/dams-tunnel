package com.v2ray.ang.ipfinder

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.service.voice.VoiceInteractionService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DamsVoiceAssistantDeclarationTest {
    @Test
    fun voiceInteractionServiceIsDeclaredResolvableAndAssistantEligible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, DamsVoiceInteractionService::class.java)
        val serviceInfo = context.packageManager.getServiceInfo(component, 0)

        assertTrue(serviceInfo.exported)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", serviceInfo.permission)
        val resolved = context.packageManager.resolveService(
            Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(context.packageName),
            0
        )?.serviceInfo
        assertNotNull(resolved)
        assertEquals(component.className, resolved?.name)

        val recognition = context.packageManager.resolveService(
            Intent(RecognitionService.SERVICE_INTERFACE).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.serviceInfo
        assertNotNull(recognition)
        assertEquals(DamsRecognitionService::class.java.name, recognition?.name)

        val metadata = serviceInfo.loadXmlMetaData(context.packageManager, VoiceInteractionService.SERVICE_META_DATA)
        assertNotNull(metadata)
        metadata!!.use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT
            ) Unit
            val namespace = "http://schemas.android.com/apk/res/android"
            assertEquals(
                "com.v2ray.ang.ipfinder.DamsVoiceInteractionSessionService",
                parser.getAttributeValue(namespace, "sessionService")
            )
            assertEquals(
                "com.v2ray.ang.ipfinder.DamsRecognitionService",
                parser.getAttributeValue(namespace, "recognitionService")
            )
            assertTrue(parser.getAttributeBooleanValue(namespace, "supportsAssist", false))
        }
    }
}
