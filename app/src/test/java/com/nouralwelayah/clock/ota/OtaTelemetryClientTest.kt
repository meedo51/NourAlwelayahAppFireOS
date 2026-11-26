package com.nouralwelayah.clock.ota

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaTelemetryClientTest {

    @Test
    fun `formatDeviceNumber produces deterministic NW-XXXXXXXX identifier`() {
        assertEquals("NW-1A2B3C4D", DeviceIdProvider.formatDeviceNumber("998877661a2b3c4d"))
        assertEquals("NW-0000A1B2", DeviceIdProvider.formatDeviceNumber("a1b2"))
        assertEquals("NW-00000000", DeviceIdProvider.formatDeviceNumber(""))
        assertTrue(DeviceIdProvider.isValidDeviceNumber("NW-1A2B3C4D"))
        assertFalse(DeviceIdProvider.isValidDeviceNumber("NW-123"))
    }

    @Test
    fun `computeHmacSha256Signature signs timestamp dot rawJsonBody with HMAC-SHA256`() {
        val secret = "test-nour-telemetry-secret-2026"
        val timestamp = "1732550000"
        val rawBody = """{"deviceId":"NW-1A2B3C4D","event":"HEARTBEAT","versionCode":10011}"""

        val sig1 = OtaTelemetryClient.computeHmacSha256Signature(
            secret = secret,
            timestamp = timestamp,
            rawJsonBody = rawBody,
        )
        val sig2 = OtaTelemetryClient.computeHmacSha256Signature(
            secret = secret,
            timestamp = timestamp,
            rawJsonBody = rawBody,
        )

        assertEquals(64, sig1.length)
        assertEquals(sig1, sig2)
        assertTrue(sig1.matches(Regex("^[0-9a-f]{64}$")))

        // Changing timestamp or body must change the signature
        val sigDiffTimestamp = OtaTelemetryClient.computeHmacSha256Signature(
            secret = secret,
            timestamp = "1732550001",
            rawJsonBody = rawBody,
        )
        assertFalse(sig1 == sigDiffTimestamp)
    }

    @Test
    fun `buildPayloadJson includes HEARTBEAT kbVersion and upgrade fields`() {
        val jsonStr = OtaTelemetryClient.buildPayloadJson(
            deviceId = "NW-ABCDEF12",
            event = OtaTelemetryEvent.INSTALL_SUCCEEDED,
            versionCode = 10011L,
            versionName = "1.1.1",
            channel = "stable",
            kbVersion = 4L,
            targetVersionCode = 10011L,
            targetVersionName = "1.1.1",
            previousVersionCode = 10010L,
            previousVersionName = "1.1.0",
            isDeviceOwner = true,
            timestampSec = 1732550000L,
        )

        val parsed = JSONObject(jsonStr)
        assertEquals("NW-ABCDEF12", parsed.optString("deviceId"))
        assertEquals("INSTALL_SUCCEEDED", parsed.optString("event"))
        assertEquals(10011L, parsed.optLong("versionCode"))
        assertEquals("1.1.1", parsed.optString("versionName"))
        assertEquals("stable", parsed.optString("channel"))
        assertEquals(4L, parsed.optLong("kbVersion"))
        assertEquals(10011L, parsed.optLong("targetVersionCode"))
        assertEquals(10010L, parsed.optLong("previousVersionCode"))
        assertEquals(true, parsed.optBoolean("isDeviceOwner"))
    }

    @Test
    fun `sendSignedTelemetrySync skips cleanly when secret is blank`() {
        val result = OtaTelemetryClient.sendSignedTelemetrySync(
            rawJsonBody = """{"event":"HEARTBEAT"}""",
            timestamp = "1732550000",
            secret = "   ",
        )
        assertTrue(result is TelemetrySendResult.SkippedNoSecret)
    }
}
