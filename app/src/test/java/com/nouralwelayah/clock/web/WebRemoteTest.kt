package com.nouralwelayah.clock.web

import android.content.ContextWrapper
import com.nouralwelayah.clock.data.AppSettings
import com.nouralwelayah.clock.data.Store
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WebRemoteTest {

    private lateinit var webRemote: WebRemote
    private lateinit var initialSettings: AppSettings

    private val noOpPlayer = object : WebPlayer {
        override fun webStatus(o: JSONObject) {}
        override fun webCommand(c: String, v: String): String = ""
        override fun webPlayFile(f: File): String = ""
        override fun webMedia(o: JSONObject, reply: (JSONObject) -> Unit) {
            reply(JSONObject().put("ok", true))
        }
    }

    @Before
    fun setUp() {
        initialSettings = Store.settings.value
        Store.saveSettings(
            AppSettings(
                city = "Makkah",
                voiceAssistantEnabled = true,
                voiceRecognitionMode = AppSettings.VOICE_MODE_AUTO,
                wakeWordEnabled = false,
                webPin = "",
            )
        )
        webRemote = WebRemote(ContextWrapper(null), noOpPlayer)
    }

    @After
    fun tearDown() {
        Store.saveSettings(initialSettings)
    }

    @Test
    fun `settingsGet outputs voice_assistant_enabled voice_recognition_mode and wake_word_enabled from Store`() {
        // Case 1: Defaults (true, "auto", false)
        val jsonDefault = webRemote.settingsGet(lists = false)
        assertTrue(jsonDefault.getBoolean("voice_assistant_enabled"))
        assertEquals("auto", jsonDefault.getString("voice_recognition_mode"))
        assertFalse(jsonDefault.getBoolean("wake_word_enabled"))

        // Case 2: Updated Store values (false, "ar", true)
        Store.saveSettings(
            Store.settings.value.copy(
                voiceAssistantEnabled = false,
                voiceRecognitionMode = AppSettings.VOICE_MODE_AR,
                wakeWordEnabled = true,
            )
        )
        val jsonAr = webRemote.settingsGet(lists = false)
        assertFalse(jsonAr.getBoolean("voice_assistant_enabled"))
        assertEquals("ar", jsonAr.getString("voice_recognition_mode"))
        assertTrue(jsonAr.getBoolean("wake_word_enabled"))

        // Case 3: Verify via GET /api/settings route
        Store.saveSettings(
            Store.settings.value.copy(
                voiceAssistantEnabled = true,
                voiceRecognitionMode = AppSettings.VOICE_MODE_EN,
                wakeWordEnabled = true,
            )
        )
        val apiResponse = webRemote.api("/api/settings", post = false, args = mapOf("lists" to "0"))
        assertNotNull(apiResponse)
        assertEquals(200, apiResponse!!.first)
        val payload = apiResponse.second as JSONObject
        assertTrue(payload.getBoolean("voice_assistant_enabled"))
        assertEquals("en", payload.getString("voice_recognition_mode"))
        assertTrue(payload.getBoolean("wake_word_enabled"))
    }

    @Test
    fun `settingsPost handles JSON booleans numeric flags string flags and valid modes`() {
        // 1. JSON booleans (false / true) and mode "ar"
        val resBool = webRemote.settingsPost(
            JSONObject()
                .put("voice_assistant_enabled", false)
                .put("wake_word_enabled", true)
                .put("voice_recognition_mode", "ar")
        )
        assertTrue(resBool.getBoolean("ok"))
        assertFalse(Store.settings.value.voiceAssistantEnabled)
        assertTrue(Store.settings.value.wakeWordEnabled)
        assertEquals("ar", Store.settings.value.voiceRecognitionMode)

        // 2. Numeric flags (1 / 0) and mode "en"
        val resNum = webRemote.settingsPost(
            JSONObject()
                .put("voice_assistant_enabled", 1)
                .put("wake_word_enabled", 0)
                .put("voice_recognition_mode", "  EN ")
        )
        assertTrue(resNum.getBoolean("ok"))
        assertTrue(Store.settings.value.voiceAssistantEnabled)
        assertFalse(Store.settings.value.wakeWordEnabled)
        assertEquals("en", Store.settings.value.voiceRecognitionMode)

        // 3. String flags ("0" / "1" and "true" / "false") and mode "auto"
        val resStr1 = webRemote.settingsPost(
            mapOf(
                "voice_assistant_enabled" to "0",
                "wake_word_enabled" to "1",
                "voice_recognition_mode" to "AUTO",
            )
        )
        assertTrue(resStr1.getBoolean("ok"))
        assertFalse(Store.settings.value.voiceAssistantEnabled)
        assertTrue(Store.settings.value.wakeWordEnabled)
        assertEquals("auto", Store.settings.value.voiceRecognitionMode)

        val resStr2 = webRemote.settingsPost(
            mapOf(
                "voice_assistant_enabled" to "true",
                "wake_word_enabled" to "false",
            )
        )
        assertTrue(resStr2.getBoolean("ok"))
        assertTrue(Store.settings.value.voiceAssistantEnabled)
        assertFalse(Store.settings.value.wakeWordEnabled)
        assertEquals("auto", Store.settings.value.voiceRecognitionMode)
    }

    @Test
    fun `settingsPost preserves untouched fields on partial updates and ignores invalid values without crashing`() {
        Store.saveSettings(
            Store.settings.value.copy(
                city = "Madinah",
                azanVolume = 18,
                voiceAssistantEnabled = true,
                voiceRecognitionMode = AppSettings.VOICE_MODE_AR,
                wakeWordEnabled = true,
            )
        )

        // Partial update 1: only change wake_word_enabled
        webRemote.settingsPost(JSONObject().put("wake_word_enabled", false))
        assertEquals("Madinah", Store.settings.value.city)
        assertEquals(18, Store.settings.value.azanVolume)
        assertTrue(Store.settings.value.voiceAssistantEnabled)
        assertEquals("ar", Store.settings.value.voiceRecognitionMode)
        assertFalse(Store.settings.value.wakeWordEnabled)

        // Partial update 2: only change voice_recognition_mode via POST /api/settings
        val apiRes = webRemote.api(
            path = "/api/settings",
            post = true,
            args = mapOf("voice_recognition_mode" to "en"),
        )
        assertNotNull(apiRes)
        assertEquals(200, apiRes!!.first)
        assertTrue(Store.settings.value.voiceAssistantEnabled)
        assertEquals("en", Store.settings.value.voiceRecognitionMode)
        assertFalse(Store.settings.value.wakeWordEnabled)
        assertEquals("Madinah", Store.settings.value.city)

        // Partial update 3: invalid values for flags and mode are ignored safely without crashing
        val invalidRes = webRemote.settingsPost(
            mapOf(
                "voice_assistant_enabled" to "not_a_bool",
                "wake_word_enabled" to "99",
                "voice_recognition_mode" to "fr",
            )
        )
        assertTrue(invalidRes.getBoolean("ok"))
        assertTrue(Store.settings.value.voiceAssistantEnabled)
        assertEquals("en", Store.settings.value.voiceRecognitionMode)
        assertFalse(Store.settings.value.wakeWordEnabled)
    }
}
