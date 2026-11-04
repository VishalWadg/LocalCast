package com.localcast.poc.model

/**
 * Capabilities advertised by a LocalCast participant.
 * Aligned with LocalCast Complete Product Scope v1.0 (Section 6.1 & Section 14.1).
 */
enum class DeviceCapability {
    SIGNALING_V1,
    CAMERA_FRONT,
    CAMERA_REAR,
    MICROPHONE,
    SCREEN_CAPTURE,
    SYSTEM_AUDIO_CAPTURE,
    SFU_CLIENT
}

/**
 * Trust state of a discovered or connected peer device.
 */
enum class DeviceTrustState {
    EPHEMERAL_POC,
    PAIRING_PENDING,
    TRUSTED,
    REVOKED
}

/**
 * Routing topology mode for a LocalCast session (Complete Product Scope Section 8).
 */
enum class TopologyMode {
    DIRECT_ONE_TO_ONE,
    HOST_TO_VIEWERS,
    HOST_MONITORING,
    SELECTIVE_GROUP,
    FULL_MESH,
    SFU_ROUTED
}

/**
 * Participant role within a specific session (dynamic per session, not fixed per device).
 */
enum class SessionRole {
    NONE,
    HOST_PUBLISHER,
    CLIENT_RECEIVER
}

/**
 * Represents a LocalCast device identity and capabilities.
 * IP address is transient transport metadata, never the device identity.
 */
data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val platform: String = "Android",
    val protocolVersion: Int = 1,
    val publicKeyFingerprint: String = "poc-ephemeral-unverified",
    val capabilities: Set<DeviceCapability> = setOf(
        DeviceCapability.SIGNALING_V1,
        DeviceCapability.CAMERA_FRONT,
        DeviceCapability.CAMERA_REAR,
        DeviceCapability.MICROPHONE
    ),
    val trustState: DeviceTrustState = DeviceTrustState.EPHEMERAL_POC
)
