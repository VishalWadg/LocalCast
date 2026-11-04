package com.localcast.poc.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.localcast.poc.model.DeviceInfo
import com.localcast.poc.model.DiagnosticLogEntry
import com.localcast.poc.model.ForegroundServiceStatus
import com.localcast.poc.model.MediaStreamStatus
import com.localcast.poc.model.PendingJoinRequest
import com.localcast.poc.model.PingPongStats
import com.localcast.poc.model.SessionPhase
import com.localcast.poc.model.SessionRole
import com.localcast.poc.model.SessionState
import com.localcast.poc.model.WebRtcSessionStatus
import com.localcast.poc.network.LocalNetworkPermissionHelper
import com.localcast.poc.network.WifiNetworkController
import com.localcast.poc.rtc.MediaEngine
import com.localcast.poc.rtc.PeerSession
import com.localcast.poc.rtc.PeerSessionCallback
import com.localcast.poc.rtc.WebRtcFactory
import com.localcast.poc.signaling.LocalSignalingServer
import com.localcast.poc.signaling.SignalMessage
import com.localcast.poc.signaling.SignalType
import com.localcast.poc.signaling.SignalingClient
import com.localcast.poc.signaling.SignalingClientListener
import com.localcast.poc.signaling.SignalingClientTransport
import com.localcast.poc.signaling.SignalingServerListener
import com.localcast.poc.signaling.SignalingServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.UUID
import kotlin.random.Random

/**
 * Process-level runtime coordinator that owns the active M0–M4 session state,
 * [MediaEngine] instance, Wi-Fi network binding, Ktor WebSocket signaling, WebRTC
 * [PeerSession], Camera/Mic capture, and [StreamingForegroundService] synchronization.
 *
 * Critical ownership rule (Spec Section 3 & Section 5):
 * Active PeerConnection and capture objects never live inside `MainActivity` or a Composable.
 * The Activity can be minimized or recreated while `StreamingForegroundService` and this
 * coordinator keep the active stream alive.
 */
class SessionRuntimeCoordinator private constructor(
    context: Context,
    val mediaEngine: MediaEngine = WebRtcFactory()
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiController = WifiNetworkController(appContext)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val localDevice = DeviceInfo(
        deviceId = "android-${UUID.randomUUID().toString().take(8)}",
        displayName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    )

    private val _state = MutableStateFlow(
        SessionState(
            localDevice = localDevice,
            mediaEngineStatus = mediaEngine.currentStatus(),
            wifiStatus = wifiController.inspectCurrentWifiStatus(),
            permissions = LocalNetworkPermissionHelper.evaluateStatus(appContext)
        )
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var serverTransport: SignalingServerTransport? = null
    private var clientTransport: SignalingClientTransport? = null
    private var clientJob: Job? = null

    init {
        appendLog("INIT", "LocalCast runtime initialized as ${localDevice.displayName} (${localDevice.deviceId})")
    }

    fun refreshEnvironmentStatus() {
        val perms = LocalNetworkPermissionHelper.evaluateStatus(appContext)
        val wifi = wifiController.inspectCurrentWifiStatus()
        _state.update {
            it.copy(
                permissions = perms,
                wifiStatus = wifi
            )
        }
    }

    /**
     * M0: Initializes the native WebRTC engine via the [MediaEngine] adapter interface.
     */
    fun initializeMediaEngine() {
        scope.launch {
            val result = mediaEngine.initialize(appContext)
            if (result.initialized) {
                appendLog(
                    "M0-WEBRTC",
                    "WebRTC initialized (${result.engineName}) · ABI=${result.primaryAbi} · " +
                        "HW Enc=${result.hardwareVideoEncoders} · HW Dec=${result.hardwareVideoDecoders} · " +
                        "LocalOnlyICE=${result.localOnlyIceConfigured}"
                )
                _state.update {
                    it.copy(
                        mediaEngineStatus = result,
                        statusBanner = if (it.phase == SessionPhase.IDLE) {
                            "M0 Ready: WebRTC initialized (${result.primaryAbi})"
                        } else {
                            it.statusBanner
                        },
                        lastError = null
                    )
                }
            } else {
                appendLog("M0-ERROR", "WebRTC init failed: ${result.errorMessage}")
                _state.update {
                    it.copy(
                        mediaEngineStatus = result,
                        statusBanner = "M0 Failed: ${result.errorMessage}",
                        lastError = result.errorMessage
                    )
                }
            }
        }
    }

    /**
     * M1: Selects the active Wi-Fi network and binds the app process to it.
     */
    fun bindWifiNetwork(): Boolean {
        val perms = LocalNetworkPermissionHelper.evaluateStatus(appContext)
        if (!perms.localNetworkGranted) {
            val msg = "ACCESS_LOCAL_NETWORK runtime permission is required on Android 17 (API 37+)"
            appendLog("M1-PERM", msg)
            _state.update {
                it.copy(
                    permissions = perms,
                    statusBanner = msg,
                    lastError = msg
                )
            }
            return false
        }

        val wifi = wifiController.bindToWifiNetwork(
            onWifiLost = {
                appendLog("M1-WIFI", "Wi-Fi network lost during active session")
                _state.update { current ->
                    current.copy(
                        phase = if (current.phase == SessionPhase.IDLE) SessionPhase.IDLE else SessionPhase.DISCONNECTED,
                        wifiStatus = wifiController.inspectCurrentWifiStatus(),
                        statusBanner = "Wi-Fi connection lost",
                        lastError = "Wi-Fi network disconnected"
                    )
                }
            }
        )
        appendLog("M1-WIFI", wifi.statusMessage)
        _state.update {
            it.copy(
                permissions = perms,
                wifiStatus = wifi,
                statusBanner = wifi.statusMessage,
                lastError = if (wifi.isBoundToWifi) null else wifi.statusMessage
            )
        }
        return wifi.isBoundToWifi
    }

    /**
     * M1 + M3 + M4 (Phone A — Host / Publisher):
     * 1. Verifies WebRTC initialization & Camera/Microphone permissions.
     * 2. Binds process to Wi-Fi.
     * 3. Starts StreamingForegroundService (M4) from visible state.
     * 4. Starts Camera2 (720p@30fps -> 480p fallback) + Microphone capture (M3).
     * 5. Starts Ktor CIO WebSocket signaling server on `<wifi-ip>:8765/signal` (M1).
     */
    fun startHostingAndPreview(port: Int = 8765) {
        scope.launch {
            val perms = LocalNetworkPermissionHelper.evaluateStatus(appContext)
            _state.update { it.copy(permissions = perms) }

            if (!perms.cameraGranted || !perms.microphoneGranted) {
                val msg = "Camera and Microphone permissions are required on Phone A to publish media."
                appendLog("M3-PERM", msg)
                _state.update { it.copy(lastError = msg, statusBanner = msg) }
                return@launch
            }

            if (!mediaEngine.currentStatus().initialized) {
                val initStatus = mediaEngine.initialize(appContext)
                _state.update { it.copy(mediaEngineStatus = initStatus) }
                if (!initStatus.initialized) return@launch
            }

            if (!_state.value.wifiStatus.isBoundToWifi) {
                val bound = bindWifiNetwork()
                if (!bound) return@launch
            }
            val bindIp = _state.value.wifiStatus.localIpv4Address
            if (bindIp.isNullOrBlank()) {
                _state.update {
                    it.copy(
                        phase = SessionPhase.FAILED,
                        lastError = "Cannot host without a valid Wi-Fi IPv4 address"
                    )
                }
                return@launch
            }

            stopActiveSignalingAndRtcInternal()

            // M4: Start foreground service while app is visible and permissions are granted
            StreamingForegroundService.startOrUpdate(
                context = appContext,
                title = "LocalCast Hosting ($bindIp:$port)",
                subtitle = "Camera + Microphone ready · Waiting for receiver"
            )

            // M3: Start local camera + microphone capture
            val captureStarted = mediaEngine.startLocalCapture(
                preferFrontCamera = true,
                onLog = { logMsg -> appendLog("M3-CAPTURE", logMsg) }
            )
            if (!captureStarted) {
                _state.update {
                    it.copy(
                        phase = SessionPhase.FAILED,
                        lastError = "Failed to initialize Camera2 / Microphone capture"
                    )
                }
                return@launch
            }

            configureVoiceCommunicationAudioRoute(enable = true)

            val newSessionId = "sess-${UUID.randomUUID().toString().take(8)}"
            val joinCode = String.format("%06d", Random.nextInt(100000, 999999))

            _state.update {
                it.copy(
                    phase = SessionPhase.CONNECTING_SIGNALING,
                    role = SessionRole.HOST_PUBLISHER,
                    sessionId = newSessionId,
                    sessionCode = joinCode,
                    signalingPort = port,
                    hostEndpointUrl = "ws://$bindIp:$port/signal",
                    connectedPeer = null,
                    pendingJoinRequest = null,
                    pingPongStats = PingPongStats(),
                    rtcStatus = WebRtcSessionStatus(),
                    mediaStatus = MediaStreamStatus(
                        isCapturingLocal = true,
                        captureProfile = mediaEngine.activeCaptureProfile(),
                        isFrontCamera = true,
                        localCameraEnabled = true,
                        localMicEnabled = true
                    ),
                    statusBanner = "Starting signaling server on $bindIp:$port...",
                    lastError = null
                )
            }

            val server = LocalSignalingServer(createServerListener())
            serverTransport = server
            server.start(bindIp = bindIp, port = port, expectedSessionId = newSessionId)
        }
    }

    /**
     * M1 + M2 + M3 (Phone A — Host):
     * Approves Phone B's join request, sends `JOIN_ACCEPT`, initializes the local-only
     * WebRTC `PeerSession`, attaches local Camera/Mic tracks, and sends the SDP `OFFER`.
     */
    fun approvePendingJoinRequest() {
        scope.launch {
            val current = _state.value
            val pending = current.pendingJoinRequest ?: return@launch
            val sessionId = current.sessionId ?: return@launch

            val acceptMsg = SignalMessage(
                sessionId = sessionId,
                type = SignalType.JOIN_ACCEPT,
                fromDeviceId = localDevice.deviceId,
                toDeviceId = pending.remoteDeviceId,
                payload = mapOf(
                    "sessionId" to sessionId,
                    "role" to "RECEIVER",
                    "hostName" to localDevice.displayName
                )
            )
            val sent = serverTransport?.send(acceptMsg) == true
            if (!sent) return@launch

            appendLog("M1-SIGNAL", "Sent JOIN_ACCEPT to ${pending.remoteDeviceName} (${pending.remoteDeviceId})")
            _state.update {
                it.copy(
                    phase = SessionPhase.NEGOTIATING,
                    pendingJoinRequest = null,
                    connectedPeer = DeviceInfo(
                        deviceId = pending.remoteDeviceId,
                        displayName = pending.remoteDeviceName
                    ),
                    statusBanner = "Approved ${pending.remoteDeviceName} — creating WebRTC SDP Offer..."
                )
            }

            StreamingForegroundService.startOrUpdate(
                context = appContext,
                title = "LocalCast Streaming to ${pending.remoteDeviceName}",
                subtitle = "Camera + Mic active (${mediaEngine.activeCaptureProfile()})"
            )

            // M2: Create Publisher PeerConnection and send SDP Offer
            val started = mediaEngine.startPublisherPeerSession(
                createPeerSessionCallback(remotePeerId = pending.remoteDeviceId)
            )
            if (!started) {
                _state.update {
                    it.copy(
                        phase = SessionPhase.FAILED,
                        lastError = "Failed to start WebRTC PeerSession on Host"
                    )
                }
            }
        }
    }

    /**
     * M1 (Phone A): Rejects a pending join request from Phone B and sends `JOIN_REJECT`.
     */
    fun rejectPendingJoinRequest(reasonCode: String = "HOST_DECLINED") {
        scope.launch {
            val current = _state.value
            val pending = current.pendingJoinRequest ?: return@launch
            val sessionId = current.sessionId ?: return@launch
            val rejectMsg = SignalMessage(
                sessionId = sessionId,
                type = SignalType.JOIN_REJECT,
                fromDeviceId = localDevice.deviceId,
                toDeviceId = pending.remoteDeviceId,
                payload = mapOf("reasonCode" to reasonCode)
            )
            serverTransport?.send(rejectMsg)
            appendLog("M1-SIGNAL", "Rejected join request from ${pending.remoteDeviceName} ($reasonCode)")
            _state.update {
                it.copy(
                    phase = SessionPhase.WAITING_FOR_APPROVAL,
                    pendingJoinRequest = null,
                    statusBanner = "Rejected join request ($reasonCode). Waiting for participant..."
                )
            }
        }
    }

    /**
     * M1 + M2 + M3 (Phone B — Receiver / Client):
     * Connects to Phone A's IP address, sends `HELLO` + `JOIN_REQUEST`, and prepares to
     * receive Phone A's WebRTC `OFFER` and remote video/audio tracks.
     */
    fun connectToHost(hostIp: String, port: Int = 8765, joinCode: String = "") {
        val cleanIp = hostIp.trim()
        if (cleanIp.isBlank()) {
            _state.update {
                it.copy(
                    lastError = "Enter Phone A's Wi-Fi IPv4 address (e.g. 192.168.1.10)",
                    statusBanner = "Host IP address is required"
                )
            }
            return
        }

        scope.launch {
            if (!mediaEngine.currentStatus().initialized) {
                val initStatus = mediaEngine.initialize(appContext)
                _state.update { it.copy(mediaEngineStatus = initStatus) }
                if (!initStatus.initialized) return@launch
            }

            if (!_state.value.wifiStatus.isBoundToWifi) {
                val bound = bindWifiNetwork()
                if (!bound) return@launch
            }

            stopActiveSignalingAndRtcInternal()
            configureVoiceCommunicationAudioRoute(enable = true)

            val provisionalSessionId = "join-${UUID.randomUUID().toString().take(8)}"
            _state.update {
                it.copy(
                    phase = SessionPhase.CONNECTING_SIGNALING,
                    role = SessionRole.CLIENT_RECEIVER,
                    sessionId = provisionalSessionId,
                    sessionCode = joinCode.trim(),
                    signalingPort = port,
                    hostEndpointUrl = "ws://$cleanIp:$port/signal",
                    connectedPeer = null,
                    pendingJoinRequest = null,
                    pingPongStats = PingPongStats(),
                    rtcStatus = WebRtcSessionStatus(),
                    mediaStatus = MediaStreamStatus(),
                    statusBanner = "Connecting to ws://$cleanIp:$port/signal...",
                    lastError = null
                )
            }

            val client = SignalingClient(createClientListener())
            clientTransport = client
            clientJob = scope.launch {
                client.connect(
                    hostIp = cleanIp,
                    port = port,
                    onReadyToHandshake = {
                        val hello = SignalMessage(
                            sessionId = provisionalSessionId,
                            type = SignalType.HELLO,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = SignalMessage.BROADCAST_TARGET,
                            payload = mapOf(
                                "deviceId" to localDevice.deviceId,
                                "deviceName" to localDevice.displayName,
                                "protocolVersion" to SignalMessage.PROTOCOL_VERSION.toString(),
                                "sessionCode" to joinCode.trim()
                            )
                        )
                        client.send(hello)
                        val joinReq = SignalMessage(
                            sessionId = provisionalSessionId,
                            type = SignalType.JOIN_REQUEST,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = SignalMessage.BROADCAST_TARGET,
                            payload = mapOf(
                                "deviceName" to localDevice.displayName,
                                "sessionCode" to joinCode.trim()
                            )
                        )
                        client.send(joinReq)
                        appendLog("M1-SIGNAL", "Sent HELLO + JOIN_REQUEST to $cleanIp:$port")
                    }
                )
            }
        }
    }

    /**
     * M3 (Phone A): Toggles local microphone mute/unmute and notifies Phone B via `TRACK_STATE`.
     */
    fun toggleLocalMicrophone() {
        val nextEnabled = !_state.value.mediaStatus.localMicEnabled
        mediaEngine.setMicrophoneEnabled(nextEnabled)
        _state.update {
            it.copy(mediaStatus = it.mediaStatus.copy(localMicEnabled = nextEnabled))
        }
        sendTrackStateUpdate(kind = "audio", enabled = nextEnabled)
    }

    /**
     * M3 (Phone A): Toggles local camera video track enable/disable and notifies Phone B via `TRACK_STATE`.
     */
    fun toggleLocalCamera() {
        val nextEnabled = !_state.value.mediaStatus.localCameraEnabled
        mediaEngine.setCameraEnabled(nextEnabled)
        _state.update {
            it.copy(mediaStatus = it.mediaStatus.copy(localCameraEnabled = nextEnabled))
        }
        sendTrackStateUpdate(kind = "video", enabled = nextEnabled)
    }

    /**
     * M3 (Phone A): Switches between front and rear camera.
     */
    fun switchCamera() {
        mediaEngine.switchCamera { isFront ->
            _state.update {
                it.copy(mediaStatus = it.mediaStatus.copy(isFrontCamera = isFront))
            }
        }
    }

    private fun sendTrackStateUpdate(kind: String, enabled: Boolean) {
        scope.launch {
            val current = _state.value
            val sessionId = current.sessionId ?: return@launch
            val peerId = current.connectedPeer?.deviceId ?: SignalMessage.BROADCAST_TARGET
            val msg = SignalMessage(
                sessionId = sessionId,
                type = SignalType.TRACK_STATE,
                fromDeviceId = localDevice.deviceId,
                toDeviceId = peerId,
                payload = mapOf(
                    "kind" to kind,
                    "enabled" to enabled.toString()
                )
            )
            when (current.role) {
                SessionRole.HOST_PUBLISHER -> serverTransport?.send(msg)
                SessionRole.CLIENT_RECEIVER -> clientTransport?.send(msg)
                SessionRole.NONE -> Unit
            }
        }
    }

    /**
     * M1: Sends a PING frame over the active WebSocket connection to measure signaling RTT.
     */
    fun sendPing() {
        scope.launch {
            val current = _state.value
            val sessionId = current.sessionId ?: "poc-session"
            val targetId = current.connectedPeer?.deviceId ?: SignalMessage.BROADCAST_TARGET
            val now = System.currentTimeMillis()
            val pingMessage = SignalMessage(
                sessionId = sessionId,
                type = SignalType.PING,
                fromDeviceId = localDevice.deviceId,
                toDeviceId = targetId,
                sentAtMs = now,
                payload = mapOf(
                    "pingSentAtMs" to now.toString(),
                    "senderName" to localDevice.displayName
                )
            )

            val sent = when (current.role) {
                SessionRole.HOST_PUBLISHER -> serverTransport?.send(pingMessage) == true
                SessionRole.CLIENT_RECEIVER -> clientTransport?.send(pingMessage) == true
                SessionRole.NONE -> false
            }

            if (sent) {
                appendLog("M1-PING", "Sent PING (seq=${current.pingPongStats.pingsSent + 1}) to $targetId")
                _state.update {
                    val updatedStats = it.pingPongStats.copy(
                        pingsSent = it.pingPongStats.pingsSent + 1,
                        lastExchangeSummary = "PING sent — awaiting PONG..."
                    )
                    it.copy(pingPongStats = updatedStats)
                }
            }
        }
    }

    fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        mediaEngine.bindLocalVideoRenderer(renderer, mirror = _state.value.mediaStatus.isFrontCamera)
    }

    fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        mediaEngine.unbindLocalVideoRenderer(renderer)
    }

    fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        mediaEngine.bindRemoteVideoRenderer(renderer)
    }

    fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        mediaEngine.unbindRemoteVideoRenderer(renderer)
    }

    /**
     * Called when the user taps "Stop Sharing" in the M4 ongoing notification.
     */
    fun onForegroundServiceStoppedFromNotification() {
        appendLog("M4-FGS", "Stop Sharing triggered from ongoing notification")
        stopSession()
    }

    fun onForegroundServiceStateChanged(
        isRunning: Boolean,
        serviceTypesSummary: String,
        notificationText: String
    ) {
        _state.update {
            it.copy(
                foregroundServiceStatus = ForegroundServiceStatus(
                    isRunning = isRunning,
                    activeServiceTypes = serviceTypesSummary,
                    notificationText = notificationText
                )
            )
        }
    }

    fun onForegroundServiceError(error: String) {
        appendLog("M4-FGS-ERR", error)
        _state.update { it.copy(lastError = error) }
    }

    /**
     * Gracefully stops the active streaming session, releases Camera/Mic, closes WebRTC
     * PeerConnection and Ktor signaling sockets, stops the Foreground Service, and unbinds Wi-Fi.
     * Idempotent so calling stop multiple times never crashes or leaks resources.
     */
    fun stopSession() {
        scope.launch {
            _state.update { it.copy(phase = SessionPhase.STOPPING, statusBanner = "Stopping session...") }
            val current = _state.value
            val sessionId = current.sessionId ?: "poc-session"
            val targetId = current.connectedPeer?.deviceId ?: SignalMessage.BROADCAST_TARGET
            when (current.role) {
                SessionRole.HOST_PUBLISHER -> {
                    serverTransport?.send(
                        SignalMessage(
                            sessionId = sessionId,
                            type = SignalType.SESSION_END,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = targetId,
                            payload = mapOf("reason" to "Host stopped sharing")
                        )
                    )
                }
                SessionRole.CLIENT_RECEIVER -> {
                    clientTransport?.send(
                        SignalMessage(
                            sessionId = sessionId,
                            type = SignalType.LEAVE,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = targetId,
                            payload = mapOf("reason" to "Receiver left session")
                        )
                    )
                }
                SessionRole.NONE -> Unit
            }

            stopActiveSignalingAndRtcInternal()
            StreamingForegroundService.stop(appContext)
            configureVoiceCommunicationAudioRoute(enable = false)
            val wifiStatus = wifiController.unbindProcessFromWifi()
            appendLog("SESSION", "Session stopped · Camera/Mic, WebRTC, FGS, and Wi-Fi binding released")

            _state.update {
                it.copy(
                    phase = SessionPhase.IDLE,
                    role = SessionRole.NONE,
                    sessionId = null,
                    sessionCode = null,
                    hostEndpointUrl = null,
                    connectedPeer = null,
                    pendingJoinRequest = null,
                    wifiStatus = wifiStatus,
                    rtcStatus = WebRtcSessionStatus(),
                    mediaStatus = MediaStreamStatus(),
                    foregroundServiceStatus = ForegroundServiceStatus(),
                    statusBanner = "Session stopped — all media & network resources released",
                    lastError = null
                )
            }
        }
    }

    private suspend fun stopActiveSignalingAndRtcInternal() {
        mediaEngine.closePeerSession()
        mediaEngine.stopLocalCapture()
        clientJob?.cancel()
        clientJob = null
        clientTransport?.disconnect()
        clientTransport = null
        serverTransport?.stop()
        serverTransport = null
    }

    private fun createPeerSessionCallback(remotePeerId: String): PeerSessionCallback {
        return object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                scope.launch {
                    val sessionId = _state.value.sessionId ?: return@launch
                    val signalType = if (type == SessionDescription.Type.OFFER) {
                        SignalType.OFFER
                    } else {
                        SignalType.ANSWER
                    }
                    val message = SignalMessage(
                        sessionId = sessionId,
                        type = signalType,
                        fromDeviceId = localDevice.deviceId,
                        toDeviceId = remotePeerId,
                        payload = mapOf("sdp" to sdp)
                    )
                    val sent = when (_state.value.role) {
                        SessionRole.HOST_PUBLISHER -> serverTransport?.send(message) == true
                        SessionRole.CLIENT_RECEIVER -> clientTransport?.send(message) == true
                        SessionRole.NONE -> false
                    }
                    appendLog("M2-SDP", "Sent local SDP ${signalType.name} (sent=$sent)")
                }
            }

            override fun onLocalIceCandidateFound(
                sdpMid: String,
                sdpMLineIndex: Int,
                candidateSdp: String,
                candidateType: String
            ) {
                scope.launch {
                    val sessionId = _state.value.sessionId ?: return@launch
                    val message = SignalMessage(
                        sessionId = sessionId,
                        type = SignalType.ICE_CANDIDATE,
                        fromDeviceId = localDevice.deviceId,
                        toDeviceId = remotePeerId,
                        payload = mapOf(
                            "sdpMid" to sdpMid,
                            "sdpMLineIndex" to sdpMLineIndex.toString(),
                            "candidate" to candidateSdp,
                            "candidateType" to candidateType
                        )
                    )
                    when (_state.value.role) {
                        SessionRole.HOST_PUBLISHER -> serverTransport?.send(message)
                        SessionRole.CLIENT_RECEIVER -> clientTransport?.send(message)
                        SessionRole.NONE -> Unit
                    }
                    appendLog("M2-ICE", "Sent local ICE candidate (type=$candidateType, mid=$sdpMid)")
                    _state.update {
                        it.copy(
                            rtcStatus = it.rtcStatus.copy(
                                localCandidatesSent = it.rtcStatus.localCandidatesSent + 1,
                                lastCandidateType = candidateType
                            )
                        )
                    }
                }
            }

            override fun onSignalingStateChanged(state: String) {
                appendLog("M2-RTC", "SignalingState -> $state")
                _state.update { it.copy(rtcStatus = it.rtcStatus.copy(signalingState = state)) }
            }

            override fun onIceConnectionStateChanged(state: String) {
                appendLog("M2-ICE", "IceConnectionState -> $state")
                val isConnected = state == "CONNECTED" || state == "COMPLETED"
                _state.update { current ->
                    val nextPhase = when {
                        isConnected -> SessionPhase.CONNECTED
                        state == "DISCONNECTED" -> SessionPhase.DISCONNECTED
                        state == "FAILED" -> SessionPhase.FAILED
                        else -> current.phase
                    }
                    val banner = when {
                        isConnected && current.role == SessionRole.HOST_PUBLISHER ->
                            "Streaming live Camera & Mic to ${current.connectedPeer?.displayName ?: "Receiver"}"
                        isConnected && current.role == SessionRole.CLIENT_RECEIVER ->
                            "Receiving live Camera & Mic from ${current.connectedPeer?.displayName ?: "Host"}"
                        else -> current.statusBanner
                    }
                    current.copy(
                        phase = nextPhase,
                        rtcStatus = current.rtcStatus.copy(
                            iceConnectionState = state,
                            isMediaConnected = isConnected
                        ),
                        statusBanner = banner
                    )
                }
            }

            override fun onIceGatheringStateChanged(state: String) {
                _state.update { it.copy(rtcStatus = it.rtcStatus.copy(iceGatheringState = state)) }
            }

            override fun onPeerConnectionStateChanged(state: String) {
                appendLog("M2-RTC", "PeerConnectionState -> $state")
                _state.update { it.copy(rtcStatus = it.rtcStatus.copy(peerConnectionState = state)) }
            }

            override fun onQueuedCandidatesFlushed(flushedCount: Int) {
                appendLog("M2-ICE-QUEUE", "Flushed $flushedCount queued remote ICE candidate(s) after setRemoteDescription")
                _state.update {
                    it.copy(
                        rtcStatus = it.rtcStatus.copy(
                            queuedCandidatesFlushed = it.rtcStatus.queuedCandidatesFlushed + flushedCount
                        )
                    )
                }
            }

            override fun onRemoteVideoTrackAvailable() {
                appendLog("M3-TRACK", "Remote VideoTrack attached to renderer")
                _state.update {
                    it.copy(mediaStatus = it.mediaStatus.copy(remoteVideoAvailable = true))
                }
            }

            override fun onRemoteAudioTrackAvailable() {
                appendLog("M3-TRACK", "Remote AudioTrack playing through speaker")
                _state.update {
                    it.copy(mediaStatus = it.mediaStatus.copy(remoteAudioAvailable = true))
                }
            }

            override fun onPeerSessionError(reason: String) {
                appendLog("M2-RTC-ERR", reason)
                _state.update {
                    it.copy(
                        phase = SessionPhase.FAILED,
                        statusBanner = reason,
                        lastError = reason
                    )
                }
            }
        }
    }

    private fun createServerListener(): SignalingServerListener = object : SignalingServerListener {
        override fun onServerStarted(bindIp: String, port: Int, endpointUrl: String) {
            appendLog("M1-HOST", "Ktor CIO WebSocket server listening at $endpointUrl")
            _state.update {
                it.copy(
                    phase = SessionPhase.WAITING_FOR_APPROVAL,
                    hostEndpointUrl = endpointUrl,
                    statusBanner = "Hosting at $endpointUrl (Join Code: ${it.sessionCode})",
                    lastError = null
                )
            }
        }

        override fun onClientSocketConnected(remoteAddress: String) {
            appendLog("M1-HOST", "Incoming WebSocket connection from $remoteAddress")
        }

        override fun onMessageReceived(message: SignalMessage) {
            handleIncomingServerMessage(message)
        }

        override fun onProtocolError(errorCode: String, reason: String) {
            appendLog("M1-PROTO-ERR", "Rejected frame [$errorCode]: $reason")
            scope.launch {
                val sessionId = _state.value.sessionId ?: "poc-session"
                serverTransport?.send(
                    SignalMessage(
                        sessionId = sessionId,
                        type = SignalType.ERROR,
                        fromDeviceId = localDevice.deviceId,
                        toDeviceId = SignalMessage.BROADCAST_TARGET,
                        payload = mapOf("code" to errorCode, "message" to reason)
                    )
                )
            }
        }

        override fun onClientSocketDisconnected(reason: String) {
            appendLog("M1-HOST", "Client WebSocket disconnected: $reason")
            mediaEngine.closePeerSession()
            _state.update { current ->
                if (current.role == SessionRole.HOST_PUBLISHER && current.phase != SessionPhase.IDLE) {
                    current.copy(
                        phase = SessionPhase.WAITING_FOR_APPROVAL,
                        connectedPeer = null,
                        pendingJoinRequest = null,
                        rtcStatus = WebRtcSessionStatus(),
                        statusBanner = "Receiver disconnected. Still hosting at ${current.hostEndpointUrl}"
                    )
                } else {
                    current
                }
            }
        }

        override fun onServerError(reason: String) {
            appendLog("M1-HOST-ERR", reason)
            _state.update {
                it.copy(
                    phase = SessionPhase.FAILED,
                    statusBanner = reason,
                    lastError = reason
                )
            }
        }
    }

    private fun handleIncomingServerMessage(message: SignalMessage) {
        val currentSessionId = _state.value.sessionId ?: message.sessionId
        when (message.type) {
            SignalType.HELLO, SignalType.JOIN_REQUEST -> {
                val remoteName = message.payload["deviceName"]
                    ?: _state.value.pendingJoinRequest?.remoteDeviceName
                    ?: message.fromDeviceId
                val providedCode = message.payload["sessionCode"].orEmpty()
                val expectedCode = _state.value.sessionCode.orEmpty()
                val codeMatched = expectedCode.isEmpty() || providedCode == expectedCode || providedCode.isEmpty()
                appendLog("M1-SIGNAL", "Received ${message.type} from $remoteName (${message.fromDeviceId})")

                _state.update {
                    it.copy(
                        phase = SessionPhase.WAITING_FOR_APPROVAL,
                        pendingJoinRequest = PendingJoinRequest(
                            sessionId = currentSessionId,
                            remoteDeviceId = message.fromDeviceId,
                            remoteDeviceName = remoteName,
                            providedSessionCode = providedCode.ifBlank { "(none)" },
                            codeMatched = codeMatched,
                            receivedAtMs = System.currentTimeMillis()
                        ),
                        statusBanner = "Join request from $remoteName — Tap Approve to start streaming"
                    )
                }
            }

            SignalType.ANSWER -> {
                val answerSdp = message.payload["sdp"].orEmpty()
                if (answerSdp.isNotBlank()) {
                    appendLog("M2-SDP", "Received SDP ANSWER from ${message.fromDeviceId}")
                    mediaEngine.applyRemoteAnswer(answerSdp)
                }
            }

            SignalType.ICE_CANDIDATE -> {
                val sdpMid = message.payload["sdpMid"].orEmpty()
                val sdpMLineIndex = message.payload["sdpMLineIndex"]?.toIntOrNull() ?: 0
                val candidateSdp = message.payload["candidate"].orEmpty()
                val candType = message.payload["candidateType"]
                    ?: PeerSession.extractCandidateType(candidateSdp)
                if (candidateSdp.isNotBlank()) {
                    appendLog("M2-ICE", "Received remote ICE candidate (type=$candType, mid=$sdpMid)")
                    mediaEngine.addOrQueueRemoteIceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                    _state.update {
                        it.copy(
                            rtcStatus = it.rtcStatus.copy(
                                remoteCandidatesReceived = it.rtcStatus.remoteCandidatesReceived + 1,
                                lastCandidateType = candType
                            )
                        )
                    }
                }
            }

            SignalType.PING -> {
                val pingOriginMs = message.payload["pingSentAtMs"]?.toLongOrNull() ?: message.sentAtMs
                scope.launch {
                    serverTransport?.send(
                        SignalMessage(
                            sessionId = currentSessionId,
                            type = SignalType.PONG,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = message.fromDeviceId,
                            payload = mapOf(
                                "pingSentAtMs" to pingOriginMs.toString(),
                                "pongSentAtMs" to System.currentTimeMillis().toString()
                            )
                        )
                    )
                }
                _state.update {
                    val stats = it.pingPongStats.copy(
                        pingsReceived = it.pingPongStats.pingsReceived + 1,
                        pongsSent = it.pingPongStats.pongsSent + 1,
                        lastExchangeSummary = "Rx PING -> Sent PONG"
                    )
                    it.copy(pingPongStats = stats)
                }
            }

            SignalType.PONG -> {
                val originMs = message.payload["pingSentAtMs"]?.toLongOrNull() ?: message.sentAtMs
                val rtt = (System.currentTimeMillis() - originMs).coerceAtLeast(0L)
                appendLog("M1-PONG", "Received PONG · RTT = ${rtt}ms")
                _state.update {
                    val stats = it.pingPongStats.copy(
                        pongsReceived = it.pingPongStats.pongsReceived + 1,
                        lastRttMs = rtt,
                        lastExchangeSummary = "PONG #${it.pingPongStats.pongsReceived + 1} · RTT ${rtt} ms"
                    )
                    it.copy(pingPongStats = stats)
                }
            }

            SignalType.LEAVE -> {
                appendLog("M1-SIGNAL", "Receiver ${message.fromDeviceId} left the session")
                mediaEngine.closePeerSession()
                _state.update {
                    it.copy(
                        phase = SessionPhase.WAITING_FOR_APPROVAL,
                        connectedPeer = null,
                        pendingJoinRequest = null,
                        rtcStatus = WebRtcSessionStatus(),
                        statusBanner = "Receiver left session. Waiting for new receiver..."
                    )
                }
            }

            else -> {
                appendLog("M1-SIGNAL", "Received ${message.type} from ${message.fromDeviceId}")
            }
        }
    }

    private fun createClientListener(): SignalingClientListener = object : SignalingClientListener {
        override fun onConnectedToHost(endpointUrl: String) {
            appendLog("M1-CLIENT", "Connected WebSocket to $endpointUrl")
            _state.update {
                it.copy(
                    phase = SessionPhase.WAITING_FOR_APPROVAL,
                    statusBanner = "Connected to $endpointUrl — waiting for Host approval...",
                    lastError = null
                )
            }
        }

        override fun onMessageReceived(message: SignalMessage) {
            handleIncomingClientMessage(message)
        }

        override fun onProtocolError(errorCode: String, reason: String) {
            appendLog("M1-PROTO-ERR", "Protocol error [$errorCode]: $reason")
        }

        override fun onDisconnected(reason: String) {
            appendLog("M1-CLIENT", "Disconnected from host: $reason")
            mediaEngine.closePeerSession()
            _state.update { current ->
                if (current.phase != SessionPhase.IDLE && current.phase != SessionPhase.STOPPING) {
                    current.copy(
                        phase = SessionPhase.ENDED,
                        connectedPeer = null,
                        rtcStatus = WebRtcSessionStatus(),
                        mediaStatus = MediaStreamStatus(),
                        statusBanner = "Disconnected: $reason"
                    )
                } else {
                    current
                }
            }
        }

        override fun onConnectionFailed(reason: String) {
            appendLog("M1-CLIENT-ERR", reason)
            _state.update {
                it.copy(
                    phase = SessionPhase.FAILED,
                    statusBanner = reason,
                    lastError = reason
                )
            }
        }
    }

    private fun handleIncomingClientMessage(message: SignalMessage) {
        when (message.type) {
            SignalType.JOIN_ACCEPT -> {
                val officialSessionId = message.payload["sessionId"] ?: message.sessionId
                val hostName = message.payload["hostName"] ?: message.fromDeviceId
                appendLog("M1-SIGNAL", "Host $hostName approved session join (sessionId=$officialSessionId)")
                _state.update {
                    it.copy(
                        phase = SessionPhase.NEGOTIATING,
                        sessionId = officialSessionId,
                        connectedPeer = DeviceInfo(
                            deviceId = message.fromDeviceId,
                            displayName = hostName
                        ),
                        statusBanner = "Approved by $hostName — awaiting WebRTC Offer..."
                    )
                }
            }

            SignalType.JOIN_REJECT -> {
                val reason = message.payload["reasonCode"] ?: "REJECTED"
                appendLog("M1-SIGNAL", "Host rejected join request: $reason")
                _state.update {
                    it.copy(
                        phase = SessionPhase.FAILED,
                        statusBanner = "Join rejected by host ($reason)",
                        lastError = "Host rejected join request: $reason"
                    )
                }
            }

            SignalType.OFFER -> {
                val offerSdp = message.payload["sdp"].orEmpty()
                if (offerSdp.isNotBlank()) {
                    appendLog("M2-SDP", "Received SDP OFFER from ${message.fromDeviceId} — creating Answer")
                    mediaEngine.startReceiverPeerSessionAndAnswer(
                        offerSdp = offerSdp,
                        callback = createPeerSessionCallback(remotePeerId = message.fromDeviceId)
                    )
                }
            }

            SignalType.ICE_CANDIDATE -> {
                val sdpMid = message.payload["sdpMid"].orEmpty()
                val sdpMLineIndex = message.payload["sdpMLineIndex"]?.toIntOrNull() ?: 0
                val candidateSdp = message.payload["candidate"].orEmpty()
                val candType = message.payload["candidateType"]
                    ?: PeerSession.extractCandidateType(candidateSdp)
                if (candidateSdp.isNotBlank()) {
                    appendLog("M2-ICE", "Received remote ICE candidate (type=$candType, mid=$sdpMid)")
                    mediaEngine.addOrQueueRemoteIceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
                    _state.update {
                        it.copy(
                            rtcStatus = it.rtcStatus.copy(
                                remoteCandidatesReceived = it.rtcStatus.remoteCandidatesReceived + 1,
                                lastCandidateType = candType
                            )
                        )
                    }
                }
            }

            SignalType.TRACK_STATE -> {
                val kind = message.payload["kind"].orEmpty()
                val enabled = message.payload["enabled"]?.toBooleanStrictOrNull() ?: true
                appendLog("M3-TRACK", "Remote track state update: $kind enabled=$enabled")
                _state.update { current ->
                    val nextMedia = when (kind) {
                        "video" -> current.mediaStatus.copy(remoteCameraEnabled = enabled)
                        "audio" -> current.mediaStatus.copy(remoteMicEnabled = enabled)
                        else -> current.mediaStatus
                    }
                    current.copy(mediaStatus = nextMedia)
                }
            }

            SignalType.PING -> {
                val pingOriginMs = message.payload["pingSentAtMs"]?.toLongOrNull() ?: message.sentAtMs
                val activeSessionId = _state.value.sessionId ?: message.sessionId
                scope.launch {
                    clientTransport?.send(
                        SignalMessage(
                            sessionId = activeSessionId,
                            type = SignalType.PONG,
                            fromDeviceId = localDevice.deviceId,
                            toDeviceId = message.fromDeviceId,
                            payload = mapOf(
                                "pingSentAtMs" to pingOriginMs.toString(),
                                "pongSentAtMs" to System.currentTimeMillis().toString()
                            )
                        )
                    )
                }
                _state.update {
                    val stats = it.pingPongStats.copy(
                        pingsReceived = it.pingPongStats.pingsReceived + 1,
                        pongsSent = it.pingPongStats.pongsSent + 1,
                        lastExchangeSummary = "Rx PING -> Sent PONG"
                    )
                    it.copy(pingPongStats = stats)
                }
            }

            SignalType.PONG -> {
                val originMs = message.payload["pingSentAtMs"]?.toLongOrNull() ?: message.sentAtMs
                val rtt = (System.currentTimeMillis() - originMs).coerceAtLeast(0L)
                appendLog("M1-PONG", "Received PONG · RTT = ${rtt}ms")
                _state.update {
                    val stats = it.pingPongStats.copy(
                        pongsReceived = it.pingPongStats.pongsReceived + 1,
                        lastRttMs = rtt,
                        lastExchangeSummary = "PONG #${it.pingPongStats.pongsReceived + 1} · RTT ${rtt} ms"
                    )
                    it.copy(pingPongStats = stats)
                }
            }

            SignalType.SESSION_END -> {
                val reason = message.payload["reason"] ?: "Host ended session"
                appendLog("M1-SIGNAL", "Host ended session: $reason")
                mediaEngine.closePeerSession()
                _state.update {
                    it.copy(
                        phase = SessionPhase.ENDED,
                        connectedPeer = null,
                        rtcStatus = WebRtcSessionStatus(),
                        mediaStatus = MediaStreamStatus(),
                        statusBanner = "Session ended by host"
                    )
                }
            }

            SignalType.ERROR -> {
                val code = message.payload["code"] ?: "ERROR"
                val errText = message.payload["message"] ?: "Remote protocol error"
                appendLog("M1-REMOTE-ERR", "Remote error [$code]: $errText")
                _state.update { it.copy(lastError = "[$code] $errText") }
            }

            else -> {
                appendLog("M1-SIGNAL", "Received ${message.type} from ${message.fromDeviceId}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureVoiceCommunicationAudioRoute(enable: Boolean) {
        runCatching {
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                    }
                } else {
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    audioManager.isSpeakerphoneOn = false
                }
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    private fun appendLog(tag: String, message: String) {
        val entry = DiagnosticLogEntry(
            timestampMs = System.currentTimeMillis(),
            tag = tag,
            message = message
        )
        _state.update { current ->
            val updated = (listOf(entry) + current.diagnosticLogs).take(80)
            current.copy(diagnosticLogs = updated)
        }
    }

    companion object {
        @Volatile
        private var instance: SessionRuntimeCoordinator? = null

        fun getInstance(context: Context): SessionRuntimeCoordinator {
            return instance ?: synchronized(this) {
                instance ?: SessionRuntimeCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
