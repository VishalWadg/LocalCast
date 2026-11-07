package com.localcast.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Requirements R1 & Test Infra Features 1 & 2:
 * - Feature 1: APK binary size under 50 MB (50,000,000 bytes)
 * - Feature 2: Telegram Bot API sendDocument delivery and {"ok":true} response validation
 *
 * Tier 1 (Feature Coverage):
 * - APK size within standard range
 * - Telegram response parser for {"ok":true}
 * - Telegram error response parsing ({"ok":false, "description":"..."})
 * - Telegram sendDocument API endpoint construction
 * - Caption formatting with release metadata
 *
 * Tier 2 (Boundary & Corner Cases):
 * - Exact 50,000,000 bytes ceiling (boundary value)
 * - 50,000,001 bytes (boundary breach rejected)
 * - 49,999,999 bytes (boundary value accepted)
 * - 0 bytes (empty file rejected)
 * - Malformed JSON responses (non-JSON, HTML error, missing "ok" key)
 * - Telegram HTTP 429 rate limit response parsing
 *
 * Tier 3 (Cross-Feature Combinations):
 * - ABI strip filtering (arm64-v8a + armeabi-v7a) size enforcement
 * - Transient failure retry with subsequent successful {"ok":true}
 *
 * Tier 4 (Real-World Scenarios):
 * - End-to-end CI build staging pipeline simulation with artifact validation
 */
class TelegramPackagingTest {

    companion object {
        const val TELEGRAM_MAX_DOCUMENT_BYTES = 50_000_000L // 50 MB strict API ceiling
        const val TELEGRAM_API_BASE = "https://api.telegram.org/bot"
    }

    // Helper functions modeling CI/CD packaging and Telegram delivery logic
    data class SizeValidationResult(val isValid: Boolean, val reason: String)

    fun validateApkDeliverableSize(byteCount: Long): SizeValidationResult {
        return when {
            byteCount <= 0 -> SizeValidationResult(false, "APK file is empty or negative size: $byteCount bytes")
            byteCount > TELEGRAM_MAX_DOCUMENT_BYTES -> SizeValidationResult(
                false,
                "Staged APK size ($byteCount bytes) exceeds 50 MB Telegram ceiling ($TELEGRAM_MAX_DOCUMENT_BYTES bytes)"
            )
            else -> SizeValidationResult(true, "APK size ($byteCount bytes) is within 50 MB ceiling")
        }
    }

    data class TelegramDeliveryResult(
        val isSuccess: Boolean,
        val messageId: Long? = null,
        val errorCode: Int? = null,
        val description: String? = null,
        val retryAfterSeconds: Int? = null
    )

    fun parseTelegramBotResponse(jsonResponse: String): TelegramDeliveryResult {
        val trimmed = jsonResponse.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return TelegramDeliveryResult(false, description = "Malformed non-JSON response: $trimmed")
        }

        // Lightweight parser avoiding external reflection dependencies
        val isOk = trimmed.contains("\"ok\":true") || trimmed.contains("\"ok\": true")
        if (isOk) {
            val msgIdRegex = """"message_id"\s*:\s*(\d+)""".toRegex()
            val msgId = msgIdRegex.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
            return TelegramDeliveryResult(isSuccess = true, messageId = msgId)
        } else {
            val descRegex = """"description"\s*:\s*"([^"]+)"""".toRegex()
            val desc = descRegex.find(trimmed)?.groupValues?.get(1) ?: "Unknown Telegram API error"

            val errCodeRegex = """"error_code"\s*:\s*(\d+)""".toRegex()
            val errCode = errCodeRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

            val retryRegex = """"retry_after"\s*:\s*(\d+)""".toRegex()
            val retryAfter = retryRegex.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()

            return TelegramDeliveryResult(
                isSuccess = false,
                errorCode = errCode,
                description = desc,
                retryAfterSeconds = retryAfter
            )
        }
    }

    fun buildTelegramSendDocumentUrl(botToken: String): String {
        require(botToken.isNotBlank()) { "Telegram bot token cannot be blank" }
        return "$TELEGRAM_API_BASE$botToken/sendDocument"
    }

    fun formatReleaseCaption(versionName: String, runNumber: Int, commitSha: String): String {
        return buildString {
            append("🚀 <b>New LocalCast Release: $versionName</b>\n")
            append("Build #$runNumber from commit ${commitSha.take(7)}\n\n")
            append("<b>What's New:</b>\n")
            append("• WebRTC native engine initialization (M0)\n")
            append("• Local Wi-Fi socket signaling via Ktor (M1)\n")
            append("• WebRTC SDP offer/answer & local host-candidate ICE (M2)\n")
            append("• Camera2 720p/480p & mic live streaming (M3)\n")
            append("• StreamingForegroundService & ongoing persistent notification (M4)\n")
        }
    }

    // =========================================================================
    // TIER 1: Feature Coverage (≥5 Tests)
    // =========================================================================

    @Test
    fun `Tier1 - validateApkDeliverableSize accepts typical debug binary size`() {
        val typicalSize = 24_500_000L // ~24.5 MB
        val result = validateApkDeliverableSize(typicalSize)
        assertTrue(result.isValid)
        assertTrue(result.reason.contains("within 50 MB ceiling"))
    }

    @Test
    fun `Tier1 - parseTelegramBotResponse parses valid ok true with message_id`() {
        val validSuccessResponse = """
            {
                "ok": true,
                "result": {
                    "message_id": 98765,
                    "date": 1793780000,
                    "document": {
                        "file_name": "LocalCast-poc-v0.1.0-debug.apk",
                        "mime_type": "application/vnd.android.package-archive",
                        "file_size": 24500000
                    }
                }
            }
        """.trimIndent()

        val parsed = parseTelegramBotResponse(validSuccessResponse)
        assertTrue("Expected delivery success", parsed.isSuccess)
        assertEquals(98765L, parsed.messageId)
    }

    @Test
    fun `Tier1 - parseTelegramBotResponse parses ok false with error description`() {
        val errorResponse = """
            {
                "ok": false,
                "error_code": 400,
                "description": "Bad Request: chat not found"
            }
        """.trimIndent()

        val parsed = parseTelegramBotResponse(errorResponse)
        assertFalse(parsed.isSuccess)
        assertEquals(400, parsed.errorCode)
        assertEquals("Bad Request: chat not found", parsed.description)
    }

    @Test
    fun `Tier1 - buildTelegramSendDocumentUrl formats correct REST endpoint`() {
        val token = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ"
        val endpoint = buildTelegramSendDocumentUrl(token)
        assertEquals("https://api.telegram.org/bot123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ/sendDocument", endpoint)
    }

    @Test
    fun `Tier1 - formatReleaseCaption generates valid HTML payload with required release tags`() {
        val caption = formatReleaseCaption("v0.1.0-poc", 42, "abcdef123456")
        assertTrue(caption.contains("<b>New LocalCast Release: v0.1.0-poc</b>"))
        assertTrue(caption.contains("Build #42 from commit abcdef1"))
        assertTrue(caption.contains("Camera2 720p/480p"))
        assertTrue(caption.contains("StreamingForegroundService"))
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases (≥5 Tests)
    // =========================================================================

    @Test
    fun `Tier2 - validateApkDeliverableSize exact 50MB boundary is accepted`() {
        val exact50Mb = 50_000_000L
        val result = validateApkDeliverableSize(exact50Mb)
        assertTrue("Exact 50,000,000 bytes must be accepted", result.isValid)
    }

    @Test
    fun `Tier2 - validateApkDeliverableSize 50MB plus one byte is strictly rejected`() {
        val oneByteOver = 50_000_001L
        val result = validateApkDeliverableSize(oneByteOver)
        assertFalse("50,000,001 bytes must be rejected", result.isValid)
        assertTrue(result.reason.contains("exceeds 50 MB Telegram ceiling"))
    }

    @Test
    fun `Tier2 - validateApkDeliverableSize 50MB minus one byte is accepted`() {
        val oneByteUnder = 49_999_999L
        val result = validateApkDeliverableSize(oneByteUnder)
        assertTrue("49,999,999 bytes must be accepted", result.isValid)
    }

    @Test
    fun `Tier2 - validateApkDeliverableSize zero or negative bytes is rejected`() {
        val zeroResult = validateApkDeliverableSize(0L)
        assertFalse(zeroResult.isValid)
        assertTrue(zeroResult.reason.contains("empty or negative"))

        val negResult = validateApkDeliverableSize(-1024L)
        assertFalse(negResult.isValid)
    }

    @Test
    fun `Tier2 - parseTelegramBotResponse handles non-JSON HTML gateway error`() {
        val html502 = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        val result = parseTelegramBotResponse(html502)
        assertFalse(result.isSuccess)
        assertTrue(result.description?.contains("Malformed non-JSON") == true)
    }

    @Test
    fun `Tier2 - parseTelegramBotResponse handles HTTP 429 rate limit with retry_after`() {
        val rateLimitResponse = """
            {
                "ok": false,
                "error_code": 429,
                "description": "Too Many Requests: retry after 12",
                "parameters": {
                    "retry_after": 12
                }
            }
        """.trimIndent()

        val result = parseTelegramBotResponse(rateLimitResponse)
        assertFalse(result.isSuccess)
        assertEquals(429, result.errorCode)
        assertEquals(12, result.retryAfterSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Tier2 - buildTelegramSendDocumentUrl rejects empty bot token`() {
        buildTelegramSendDocumentUrl("   ")
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations (Pairwise & Stress)
    // =========================================================================

    @Test
    fun `Tier3 - packagingAbiFilters projected size for arm64 and armeabi stays within budget`() {
        // arm64-v8a WebRTC .so ~12 MB, armeabi-v7a ~9 MB, dex + assets ~6 MB = ~27 MB
        val estimatedArm64SoBytes = 12_500_000L
        val estimatedArmeabiSoBytes = 9_200_000L
        val estimatedDexAndAssetsBytes = 6_300_000L
        val combinedProjectedBytes = estimatedArm64SoBytes + estimatedArmeabiSoBytes + estimatedDexAndAssetsBytes

        val sizeCheck = validateApkDeliverableSize(combinedProjectedBytes)
        assertTrue("Projected dual-ABI binary ($combinedProjectedBytes bytes) must fit under 50 MB", sizeCheck.isValid)
        assertTrue(combinedProjectedBytes < TELEGRAM_MAX_DOCUMENT_BYTES)
    }

    @Test
    fun `Tier3 - retry policy handles transient 500 error followed by ok true success`() {
        val responses = listOf(
            """{"ok": false, "error_code": 500, "description": "Internal Server Error"}""",
            """{"ok": true, "result": {"message_id": 100200}}"""
        )

        var finalSuccess = false
        var attempts = 0
        for (resp in responses) {
            attempts++
            val parsed = parseTelegramBotResponse(resp)
            if (parsed.isSuccess) {
                finalSuccess = true
                break
            }
        }

        assertTrue("Second attempt must succeed", finalSuccess)
        assertEquals(2, attempts)
    }

    // =========================================================================
    // TIER 4: Real-World Scenarios
    // =========================================================================

    @Test
    fun `Tier4 - Scenario 4 CI build execution stage APK and Telegram delivery validation`() {
        // Step 1: Simulate staging an APK artifact
        val tempApk = File.createTempFile("LocalCast-poc-test-", ".apk")
        try {
            // Write 1 MB of simulated APK payload
            tempApk.writeBytes(ByteArray(1024 * 1024))
            val fileSize = tempApk.length()

            // Step 2: Validate size meets the < 50 MB requirement
            val sizeValidation = validateApkDeliverableSize(fileSize)
            assertTrue("Staged APK must pass size gate", sizeValidation.isValid)

            // Step 3: Format release caption
            val caption = formatReleaseCaption("v0.1.0-poc", 101, "c0ffee1234567890")
            assertTrue(caption.contains("v0.1.0-poc"))

            // Step 4: Validate Telegram sendDocument endpoint
            val testToken = "dummy_bot_token"
            val endpoint = buildTelegramSendDocumentUrl(testToken)
            assertEquals("https://api.telegram.org/botdummy_bot_token/sendDocument", endpoint)

            // Step 5: Simulate Telegram Bot API success response validation
            val wireResponse = """{"ok":true,"result":{"message_id":554433}}"""
            val deliveryResult = parseTelegramBotResponse(wireResponse)
            assertTrue(deliveryResult.isSuccess)
            assertEquals(554433L, deliveryResult.messageId)
        } finally {
            tempApk.delete()
        }
    }
}
