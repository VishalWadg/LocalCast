package com.localcast.poc.model

/**
 * High-level session lifecycle state machine (POC Spec Section 8 + Product Scope Section 14).
 */
enum class SessionPhase {
    IDLE,
    CONNECTING_SIGNALING,
    WAITING_FOR_APPROVAL,
    NEGOTIATING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    STOPPING,
    ENDED,
    FAILED
}

/**
 * M0: Status of the WebRTC / MediaEngine native initialization.
 */
data class MediaEngineStatus(
    val initialized: Boolean = false,
    val engineName: String = "Uninitialized",
    val deviceModel: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val primaryAbi: String = "",
    val supportedAbis: List<String> = emptyList(),
    val hardwareVideoEncoders: Int = 0,
    val hardwareVideoDecoders: Int = 0,
    val localOnlyIceConfigured: Boolean = false,
    val errorMessage: String? = null
)

/**
 * M1: Status of the local Wi-Fi network selection and process socket binding.
 */
data class WifiBindingStatus(
    val isBoundToWifi: Boolean = false,
    val localIpv4Address: String? = null,
    val interfaceName: String? = null,
    val networkHandle: Long? = null,
    val statusMessage: String = "Not bound to Wi-Fi"
)

/**
 * M1: Status of Android 17 (API 37+) conditional ACCESS_LOCAL_NETWORK permission,
 * plus M3/M4 CAMERA, RECORD_AUDIO, and POST_NOTIFICATIONS runtime permissions.
 */
data class PermissionSnapshot(
    val requiresLocalNetworkRuntimePermission: Boolean = false,
    val localNetworkGranted: Boolean = true,
    val cameraGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val sdkInt: Int = 0,
    val policySummary: String = ""
)

/**
 * M1: Pending join request received by Phone A (Host/Publisher) from Phone B (Receiver).
 */
data class PendingJoinRequest(
    val sessionId: String,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    val providedSessionCode: String,
    val codeMatched: Boolean,
    val receivedAtMs: Long
)

/**
 * M1: PING / PONG verification metrics.
 */
data class PingPongStats(
    val pingsSent: Int = 0,
    val pongsReceived: Int = 0,
    val pingsReceived: Int = 0,
    val pongsSent: Int = 0,
    val lastRttMs: Long? = null,
    val lastExchangeSummary: String = "No PING/PONG exchanged yet"
)

/**
 * M2: WebRTC SDP Offer/Answer and local-only ICE connection state.
 */
data class WebRtcSessionStatus(
    val signalingState: String = "CLOSED",
    val iceConnectionState: String = "CLOSED",
    val iceGatheringState: String = "NEW",
    val peerConnectionState: String = "CLOSED",
    val localCandidatesSent: Int = 0,
    val remoteCandidatesReceived: Int = 0,
    val queuedCandidatesFlushed: Int = 0,
    val lastCandidateType: String = "none",
    val isMediaConnected: Boolean = false
)

/**
 * M3: Camera and Microphone capture / remote playback status.
 */
data class MediaStreamStatus(
    val isCapturingLocal: Boolean = false,
    val captureProfile: String = "Stopped",
    val isFrontCamera: Boolean = true,
    val localCameraEnabled: Boolean = true,
    val localMicEnabled: Boolean = true,
    val remoteVideoAvailable: Boolean = false,
    val remoteAudioAvailable: Boolean = false,
    val remoteCameraEnabled: Boolean = true,
    val remoteMicEnabled: Boolean = true
)

/**
 * M4: Background Foreground Service status.
 */
data class ForegroundServiceStatus(
    val isRunning: Boolean = false,
    val activeServiceTypes: String = "none",
    val notificationText: String = "Foreground service stopped"
)

/**
 * Structured diagnostic log entry (redacting sensitive payloads by design).
 */
data class DiagnosticLogEntry(
    val timestampMs: Long,
    val tag: String,
    val message: String
)

/**
 * Complete observable state exposed by SessionRuntimeCoordinator to PocViewModel and Compose UI.
 */
data class SessionState(
    val localDevice: DeviceInfo,
    val phase: SessionPhase = SessionPhase.IDLE,
    val role: SessionRole = SessionRole.NONE,
    val topologyMode: TopologyMode = TopologyMode.DIRECT_ONE_TO_ONE,
    val sessionId: String? = null,
    val sessionCode: String? = null,
    val signalingPort: Int = 8765,
    val hostEndpointUrl: String? = null,
    val connectedPeer: DeviceInfo? = null,
    val pendingJoinRequest: PendingJoinRequest? = null,
    val mediaEngineStatus: MediaEngineStatus = MediaEngineStatus(),
    val wifiStatus: WifiBindingStatus = WifiBindingStatus(),
    val permissions: PermissionSnapshot = PermissionSnapshot(),
    val pingPongStats: PingPongStats = PingPongStats(),
    val rtcStatus: WebRtcSessionStatus = WebRtcSessionStatus(),
    val mediaStatus: MediaStreamStatus = MediaStreamStatus(),
    val foregroundServiceStatus: ForegroundServiceStatus = ForegroundServiceStatus(),
    val discoveryState: DiscoveryState = DiscoveryState(),
    val statusBanner: String = "Ready — Host on Phone A or Connect from Phone B",
    val lastError: String? = null,
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList()
)
