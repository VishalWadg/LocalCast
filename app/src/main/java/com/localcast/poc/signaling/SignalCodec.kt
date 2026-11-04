package com.localcast.poc.signaling

/**
 * Result of decoding and validating an incoming signaling frame.
 */
sealed class SignalDecodeResult {
    data class Success(val message: SignalMessage) : SignalDecodeResult()
    data class Invalid(val errorCode: String, val reason: String) : SignalDecodeResult()
}

/**
 * Pure-Kotlin JSON codec and validator for [SignalMessage].
 *
 * Handles multiline SDP strings (`\r\n`), ICE candidate strings, and control payloads
 * with zero external reflection dependencies.
 */
object SignalCodec {

    const val MAX_FRAME_BYTES = 64 * 1024

    fun encode(message: SignalMessage): String {
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"v\":").append(message.v).append(',')
        sb.append("\"messageId\":\"").append(escapeJson(message.messageId)).append("\",")
        sb.append("\"sessionId\":\"").append(escapeJson(message.sessionId)).append("\",")
        sb.append("\"type\":\"").append(message.type.name).append("\",")
        sb.append("\"fromDeviceId\":\"").append(escapeJson(message.fromDeviceId)).append("\",")
        sb.append("\"toDeviceId\":\"").append(escapeJson(message.toDeviceId)).append("\",")
        sb.append("\"sentAtMs\":").append(message.sentAtMs).append(',')
        sb.append("\"payload\":{")
        var first = true
        for ((k, v) in message.payload) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJson(k)).append("\":\"")
                .append(escapeJson(v)).append('"')
        }
        sb.append("}}")
        return sb.toString()
    }

    fun decodeAndValidate(
        rawJson: String,
        expectedSessionId: String? = null
    ): SignalDecodeResult {
        val byteLen = rawJson.toByteArray(Charsets.UTF_8).size
        if (byteLen > MAX_FRAME_BYTES) {
            return SignalDecodeResult.Invalid(
                errorCode = "FRAME_TOO_LARGE",
                reason = "Signaling frame ($byteLen bytes) exceeds limit of $MAX_FRAME_BYTES bytes"
            )
        }
        if (rawJson.isBlank()) {
            return SignalDecodeResult.Invalid(
                errorCode = "EMPTY_FRAME",
                reason = "Signaling frame is empty"
            )
        }

        val parsed = try {
            SimpleJsonParser(rawJson).parseRootObject()
        } catch (e: IllegalArgumentException) {
            return SignalDecodeResult.Invalid(
                errorCode = "MALFORMED_JSON",
                reason = e.message ?: "Malformed JSON signaling frame"
            )
        }

        val versionAny = parsed["v"]
            ?: return SignalDecodeResult.Invalid("MISSING_VERSION", "Missing required field 'v'")
        val version = (versionAny as? Long)?.toInt()
            ?: return SignalDecodeResult.Invalid("INVALID_VERSION", "Field 'v' must be an integer")
        if (version != SignalMessage.PROTOCOL_VERSION) {
            return SignalDecodeResult.Invalid(
                errorCode = "UNSUPPORTED_VERSION",
                reason = "Unsupported protocol version: $version (expected ${SignalMessage.PROTOCOL_VERSION})"
            )
        }

        val messageId = (parsed["messageId"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SignalDecodeResult.Invalid("MISSING_MESSAGE_ID", "Missing required field 'messageId'")

        val sessionId = (parsed["sessionId"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SignalDecodeResult.Invalid("MISSING_SESSION_ID", "Missing required field 'sessionId'")

        val typeName = (parsed["type"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SignalDecodeResult.Invalid("MISSING_TYPE", "Missing required field 'type'")
        val signalType = SignalType.fromWireName(typeName)
            ?: return SignalDecodeResult.Invalid("UNKNOWN_TYPE", "Unsupported message type '$typeName'")

        if (expectedSessionId != null &&
            sessionId != expectedSessionId &&
            signalType != SignalType.HELLO &&
            signalType != SignalType.JOIN_REQUEST
        ) {
            return SignalDecodeResult.Invalid(
                errorCode = "SESSION_MISMATCH",
                reason = "Message sessionId '$sessionId' does not match active session '$expectedSessionId'"
            )
        }

        val fromDeviceId = (parsed["fromDeviceId"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SignalDecodeResult.Invalid("MISSING_FROM_DEVICE", "Missing required field 'fromDeviceId'")

        val toDeviceId = (parsed["toDeviceId"] as? String)?.takeIf { it.isNotBlank() }
            ?: return SignalDecodeResult.Invalid("MISSING_TO_DEVICE", "Missing required field 'toDeviceId'")

        val sentAtMs = (parsed["sentAtMs"] as? Long)
            ?: return SignalDecodeResult.Invalid("MISSING_TIMESTAMP", "Missing or invalid numeric 'sentAtMs'")

        val rawPayload = parsed["payload"]
            ?: return SignalDecodeResult.Invalid("MISSING_PAYLOAD", "Missing required object 'payload'")
        @Suppress("UNCHECKED_CAST")
        val payloadObj = rawPayload as? Map<String, Any?>
            ?: return SignalDecodeResult.Invalid("INVALID_PAYLOAD", "Field 'payload' must be a JSON object")

        val cleanPayload = linkedMapOf<String, String>()
        for ((k, v) in payloadObj) {
            if (v != null) {
                cleanPayload[k] = v.toString()
            }
        }

        return SignalDecodeResult.Success(
            SignalMessage(
                v = version,
                messageId = messageId,
                sessionId = sessionId,
                type = signalType,
                fromDeviceId = fromDeviceId,
                toDeviceId = toDeviceId,
                sentAtMs = sentAtMs,
                payload = cleanPayload
            )
        )
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    private class SimpleJsonParser(private val input: String) {
        private var pos = 0

        fun parseRootObject(): Map<String, Any?> {
            skipWhitespace()
            val obj = parseObject()
            skipWhitespace()
            if (pos != input.length) {
                throw IllegalArgumentException("Unexpected trailing characters at index $pos")
            }
            return obj
        }

        private fun parseObject(): Map<String, Any?> {
            expectChar('{')
            skipWhitespace()
            val map = linkedMapOf<String, Any?>()
            if (peekChar() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                skipWhitespace()
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (val ch = nextChar()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw IllegalArgumentException("Expected ',' or '}' at index ${pos - 1}, got '$ch'")
                }
            }
            return map
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            return when (val ch = peekChar()) {
                '"' -> parseString()
                '{' -> parseObject()
                '-', in '0'..'9' -> parseNumber()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> throw IllegalArgumentException("Unexpected token '$ch' at index $pos")
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                if (ch == '"') {
                    return sb.toString()
                }
                if (ch == '\\') {
                    if (pos >= input.length) {
                        throw IllegalArgumentException("Unterminated escape sequence")
                    }
                    when (val esc = input[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > input.length) {
                                throw IllegalArgumentException("Invalid unicode escape at $pos")
                            }
                            val hex = input.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Invalid escape char '$esc'")
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated string literal")
        }

        private fun parseNumber(): Long {
            val start = pos
            if (peekChar() == '-') pos++
            while (pos < input.length && input[pos] in '0'..'9') {
                pos++
            }
            val token = input.substring(start, pos)
            return token.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid integer value '$token'")
        }

        private fun parseBoolean(): Boolean {
            return if (input.startsWith("true", pos)) {
                pos += 4
                true
            } else if (input.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean token at index $pos")
            }
        }

        private fun parseNull(): Any? {
            if (input.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null token at index $pos")
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) {
                pos++
            }
        }

        private fun peekChar(): Char {
            if (pos >= input.length) {
                throw IllegalArgumentException("Unexpected end of JSON input")
            }
            return input[pos]
        }

        private fun nextChar(): Char {
            val ch = peekChar()
            pos++
            return ch
        }

        private fun expectChar(expected: Char) {
            val actual = nextChar()
            if (actual != expected) {
                throw IllegalArgumentException("Expected '$expected' at index ${pos - 1}, got '$actual'")
            }
        }
    }
}
