package com.localcast.poc.signaling

import com.localcast.poc.network.LocalNetworkPermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCodecTest {

    @Test
    fun `encode and decode HELLO message preserves all envelope and payload fields`() {
        val original = SignalMessage(
            v = 1,
            messageId = "msg-001",
            sessionId = "sess-abc",
            type = SignalType.HELLO,
            fromDeviceId = "phone-b",
            toDeviceId = "*",
            sentAtMs = 1793780000000L,
            payload = mapOf(
                "deviceId" to "phone-b",
                "deviceName" to "Pixel 10 Pro",
                "sessionCode" to "482910"
            )
        )

        val wireJson = SignalCodec.encode(original)
        val decoded = SignalCodec.decodeAndValidate(wireJson, expectedSessionId = "sess-abc")

        assertTrue("Expected decode success, got $decoded", decoded is SignalDecodeResult.Success)
        val msg = (decoded as SignalDecodeResult.Success).message
        assertEquals(original.v, msg.v)
        assertEquals(original.messageId, msg.messageId)
        assertEquals(original.sessionId, msg.sessionId)
        assertEquals(SignalType.HELLO, msg.type)
        assertEquals("phone-b", msg.fromDeviceId)
        assertEquals("Pixel 10 Pro", msg.payload["deviceName"])
        assertEquals("482910", msg.payload["sessionCode"])
    }

    @Test
    fun `encode and decode multiline WebRTC SDP OFFER preserves CRLF line breaks`() {
        val sampleSdp = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1\r\n"
        val offer = SignalMessage(
            messageId = "offer-01",
            sessionId = "sess-rtc",
            type = SignalType.OFFER,
            fromDeviceId = "phone-a",
            toDeviceId = "phone-b",
            sentAtMs = 1793780050123L,
            payload = mapOf("sdp" to sampleSdp)
        )

        val encoded = SignalCodec.encode(offer)
        val result = SignalCodec.decodeAndValidate(encoded, expectedSessionId = "sess-rtc")
        assertTrue(result is SignalDecodeResult.Success)
        val parsed = (result as SignalDecodeResult.Success).message
        assertEquals(SignalType.OFFER, parsed.type)
        assertEquals(sampleSdp, parsed.payload["sdp"])
    }

    @Test
    fun `reject unsupported protocol version`() {
        val badJson = """
            {
              "v": 2,
              "messageId": "m1",
              "sessionId": "s1",
              "type": "PING",
              "fromDeviceId": "a",
              "toDeviceId": "b",
              "sentAtMs": 1793780000000,
              "payload": {}
            }
        """.trimIndent()

        val result = SignalCodec.decodeAndValidate(badJson)
        assertTrue(result is SignalDecodeResult.Invalid)
        assertEquals("UNSUPPORTED_VERSION", (result as SignalDecodeResult.Invalid).errorCode)
    }

    @Test
    fun `reject mismatched sessionId for non-HELLO and non-JOIN_REQUEST message`() {
        val msg = SignalMessage(
            sessionId = "wrong-session",
            type = SignalType.PING,
            fromDeviceId = "phone-b",
            toDeviceId = "phone-a"
        )
        val wire = SignalCodec.encode(msg)
        val result = SignalCodec.decodeAndValidate(wire, expectedSessionId = "active-session")
        assertTrue(result is SignalDecodeResult.Invalid)
        assertEquals("SESSION_MISMATCH", (result as SignalDecodeResult.Invalid).errorCode)
    }

    @Test
    fun `conditional ACCESS_LOCAL_NETWORK permission policy enforces only on API 37 and newer`() {
        assertFalse(LocalNetworkPermissionHelper.requiresRuntimeLocalNetworkPermission(sdkInt = 29))
        assertFalse(LocalNetworkPermissionHelper.requiresRuntimeLocalNetworkPermission(sdkInt = 35))
        assertFalse(LocalNetworkPermissionHelper.requiresRuntimeLocalNetworkPermission(sdkInt = 36))
        assertTrue(LocalNetworkPermissionHelper.requiresRuntimeLocalNetworkPermission(sdkInt = 37))
        assertTrue(LocalNetworkPermissionHelper.requiresRuntimeLocalNetworkPermission(sdkInt = 38))
    }
}
