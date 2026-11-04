package com.localcast.poc.signaling

import java.util.UUID

/**
 * Required signaling message types (POC Spec Section 6 & Product Scope Section 14.2).
 */
enum class SignalType {
    HELLO,
    JOIN_REQUEST,
    JOIN_ACCEPT,
    JOIN_REJECT,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    TRACK_STATE,
    PING,
    PONG,
    LEAVE,
    SESSION_END,
    ERROR;

    companion object {
        fun fromWireName(value: String): SignalType? {
            return entries.firstOrNull { it.name == value }
        }
    }
}

/**
 * Versioned signaling envelope over WebSocket text frames.
 */
data class SignalMessage(
    val v: Int = PROTOCOL_VERSION,
    val messageId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val type: SignalType,
    val fromDeviceId: String,
    val toDeviceId: String,
    val sentAtMs: Long = System.currentTimeMillis(),
    val payload: Map<String, String> = emptyMap()
) {
    companion object {
        const val PROTOCOL_VERSION = 1
        const val BROADCAST_TARGET = "*"
    }
}
