package com.localcast.poc.rtc

import android.content.Context
import com.localcast.poc.model.MediaEngineStatus
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-fidelity test double implementing [MediaEngine].
 *
 * Simulates WebRTC session signaling, track attachment, video sink attachment, camera switching,
 * mute/unmute events, and frame delivery without requiring native C++ WebRTC binaries or device hardware.
 */
class FakeMediaEngine : MediaEngine {

    data class IceCandidateRecord(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidateSdp: String,
        val queued: Boolean
    )

    var shouldFailInit: Boolean = false
    var shouldFailCapture: Boolean = false
    var shouldFailPeerSession: Boolean = false

    var activeProfile: String = "720p@30fps (Camera2)"
    var fallbackProfile: String = "480p@30fps (Fallback)"

    @Volatile var isInitialized: Boolean = false
        private set

    @Volatile var isCapturing: Boolean = false
        private set

    @Volatile var isFrontCamera: Boolean = true
        private set

    @Volatile var isCameraEnabled: Boolean = true
        private set

    @Volatile var isMicrophoneEnabled: Boolean = true
        private set

    @Volatile var isPeerSessionActive: Boolean = false
        private set

    @Volatile var isReleased: Boolean = false
        private set

    val boundLocalRenderers = Collections.newSetFromMap(ConcurrentHashMap<SurfaceViewRenderer, Boolean>())
    val boundRemoteRenderers = Collections.newSetFromMap(ConcurrentHashMap<SurfaceViewRenderer, Boolean>())

    var activeCallback: PeerSessionCallback? = null
        private set

    var isPublisher: Boolean = false
        private set

    var lastGeneratedOfferSdp: String? = null
        private set

    var lastGeneratedAnswerSdp: String? = null
        private set

    var lastAppliedRemoteOfferSdp: String? = null
        private set

    var lastAppliedRemoteAnswerSdp: String? = null
        private set

    val remoteIceCandidates = CopyOnWriteArrayList<IceCandidateRecord>()
    val capturedLogs = CopyOnWriteArrayList<String>()

    var framesDeliveredCount: Int = 0
        private set

    private var currentStatusCache = MediaEngineStatus(
        initialized = false,
        engineName = "FakeMediaEngine (Test Double)",
        primaryAbi = "jvm-test",
        localOnlyIceConfigured = true
    )

    fun initialize(): MediaEngineStatus {
        return initializeInternal()
    }

    override fun initialize(context: Context): MediaEngineStatus {
        return initializeInternal()
    }

    private fun initializeInternal(): MediaEngineStatus {
        if (shouldFailInit) {
            isInitialized = false
            currentStatusCache = MediaEngineStatus(
                initialized = false,
                engineName = "FakeMediaEngine",
                primaryAbi = "jvm-test",
                localOnlyIceConfigured = false,
                errorMessage = "Simulated initialization failure in FakeMediaEngine"
            )
            return currentStatusCache
        }
        isInitialized = true
        isReleased = false
        currentStatusCache = MediaEngineStatus(
            initialized = true,
            engineName = "FakeMediaEngine (Test Double)",
            deviceModel = "JVM Test Host",
            androidRelease = "17",
            sdkInt = 37,
            primaryAbi = "jvm-test",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "jvm-test"),
            hardwareVideoEncoders = 2,
            hardwareVideoDecoders = 2,
            localOnlyIceConfigured = true,
            errorMessage = null
        )
        return currentStatusCache
    }

    override fun currentStatus(): MediaEngineStatus = currentStatusCache

    override fun startLocalCapture(preferFrontCamera: Boolean, onLog: (String) -> Unit): Boolean {
        if (!isInitialized || shouldFailCapture) {
            onLog("Failed to start local capture (initialized=$isInitialized, forcedFail=$shouldFailCapture)")
            return false
        }
        isCapturing = true
        isFrontCamera = preferFrontCamera
        isCameraEnabled = true
        isMicrophoneEnabled = true
        val logMsg = "Local capture started ($activeProfile, frontCamera=$preferFrontCamera)"
        capturedLogs.add(logMsg)
        onLog(logMsg)
        return true
    }

    override fun stopLocalCapture() {
        isCapturing = false
        capturedLogs.add("Local capture stopped")
    }

    override fun switchCamera(onSwitched: (Boolean) -> Unit) {
        if (!isCapturing) {
            onSwitched(isFrontCamera)
            return
        }
        isFrontCamera = !isFrontCamera
        val logMsg = "Switched camera lens to isFrontCamera=$isFrontCamera"
        capturedLogs.add(logMsg)
        onSwitched(isFrontCamera)
    }

    override fun setCameraEnabled(enabled: Boolean) {
        isCameraEnabled = enabled
        capturedLogs.add("Camera track enabled=$enabled")
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        isMicrophoneEnabled = enabled
        capturedLogs.add("Microphone track enabled=$enabled")
    }

    override fun activeCaptureProfile(): String {
        return if (isCapturing) activeProfile else "Stopped"
    }

    override fun startPublisherPeerSession(callback: PeerSessionCallback): Boolean {
        if (shouldFailPeerSession) {
            callback.onPeerSessionError("Simulated peer session creation failure on Host")
            return false
        }
        activeCallback = callback
        isPublisher = true
        isPeerSessionActive = true

        val offerSdp = buildFakeUnifiedPlanSdp(isOffer = true)
        lastGeneratedOfferSdp = offerSdp

        callback.onSignalingStateChanged("HAVE_LOCAL_OFFER")
        callback.onLocalSdpReady(SessionDescription.Type.OFFER, offerSdp)
        return true
    }

    override fun startReceiverPeerSessionAndAnswer(
        offerSdp: String,
        callback: PeerSessionCallback
    ): Boolean {
        if (shouldFailPeerSession) {
            callback.onPeerSessionError("Simulated receiver peer session failure")
            return false
        }
        activeCallback = callback
        isPublisher = false
        isPeerSessionActive = true
        lastAppliedRemoteOfferSdp = offerSdp

        callback.onSignalingStateChanged("HAVE_REMOTE_OFFER")

        // Flush any trickled candidates received prior to SDP answer
        val queuedCount = remoteIceCandidates.count { it.queued }
        if (queuedCount > 0) {
            callback.onQueuedCandidatesFlushed(queuedCount)
        }

        val answerSdp = buildFakeUnifiedPlanSdp(isOffer = false)
        lastGeneratedAnswerSdp = answerSdp

        callback.onSignalingStateChanged("STABLE")
        callback.onLocalSdpReady(SessionDescription.Type.ANSWER, answerSdp)
        return true
    }

    override fun applyRemoteAnswer(answerSdp: String) {
        lastAppliedRemoteAnswerSdp = answerSdp
        activeCallback?.onSignalingStateChanged("STABLE")
        val queuedCount = remoteIceCandidates.count { it.queued }
        if (queuedCount > 0) {
            activeCallback?.onQueuedCandidatesFlushed(queuedCount)
        }
    }

    override fun addOrQueueRemoteIceCandidate(
        sdpMid: String,
        sdpMLineIndex: Int,
        candidateSdp: String
    ) {
        val hasRemoteDesc = if (isPublisher) {
            lastAppliedRemoteAnswerSdp != null
        } else {
            lastAppliedRemoteOfferSdp != null
        }
        val record = IceCandidateRecord(
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex,
            candidateSdp = candidateSdp,
            queued = !hasRemoteDesc
        )
        remoteIceCandidates.add(record)
    }

    override fun closePeerSession() {
        isPeerSessionActive = false
        activeCallback = null
        remoteIceCandidates.clear()
        capturedLogs.add("PeerSession closed")
    }

    override fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer, mirror: Boolean) {
        boundLocalRenderers.add(renderer)
        capturedLogs.add("Bound local video renderer: $renderer (mirror=$mirror)")
    }

    override fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        boundLocalRenderers.remove(renderer)
        capturedLogs.add("Unbound local video renderer: $renderer")
    }

    override fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        boundRemoteRenderers.add(renderer)
        capturedLogs.add("Bound remote video renderer: $renderer")
    }

    override fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        boundRemoteRenderers.remove(renderer)
        capturedLogs.add("Unbound remote video renderer: $renderer")
    }

    override fun release() {
        closePeerSession()
        stopLocalCapture()
        boundLocalRenderers.clear()
        boundRemoteRenderers.clear()
        isReleased = true
        isInitialized = false
        currentStatusCache = currentStatusCache.copy(initialized = false)
        capturedLogs.add("FakeMediaEngine released")
    }

    // --- Simulation and Verification Helpers for Unit/Integration Tests ---

    fun simulateRemoteVideoTrackAvailable() {
        activeCallback?.onRemoteVideoTrackAvailable()
    }

    fun simulateRemoteAudioTrackAvailable() {
        activeCallback?.onRemoteAudioTrackAvailable()
    }

    fun simulateIceConnectionState(state: String) {
        activeCallback?.onIceConnectionStateChanged(state)
    }

    fun simulatePeerConnectionState(state: String) {
        activeCallback?.onPeerConnectionStateChanged(state)
    }

    fun simulateSignalingState(state: String) {
        activeCallback?.onSignalingStateChanged(state)
    }

    fun simulateLocalIceCandidate(
        sdpMid: String = "0",
        sdpMLineIndex: Int = 0,
        candidateSdp: String = "candidate:1 1 udp 2122260223 192.168.1.100 50000 typ host generation 0",
        candidateType: String = "host"
    ) {
        activeCallback?.onLocalIceCandidateFound(sdpMid, sdpMLineIndex, candidateSdp, candidateType)
    }

    fun simulatePeerSessionError(reason: String) {
        activeCallback?.onPeerSessionError(reason)
    }

    fun simulateFrameDelivery() {
        framesDeliveredCount++
    }

    fun reset() {
        release()
        shouldFailInit = false
        shouldFailCapture = false
        shouldFailPeerSession = false
        activeProfile = "720p@30fps (Camera2)"
        isFrontCamera = true
        isCameraEnabled = true
        isMicrophoneEnabled = true
        lastGeneratedOfferSdp = null
        lastGeneratedAnswerSdp = null
        lastAppliedRemoteOfferSdp = null
        lastAppliedRemoteAnswerSdp = null
        remoteIceCandidates.clear()
        capturedLogs.clear()
        framesDeliveredCount = 0
    }

    companion object {
        fun buildFakeUnifiedPlanSdp(isOffer: Boolean): String {
            val type = if (isOffer) "offer" else "answer"
            return """
                v=0
                o=- 4611731400430051336 2 IN IP4 127.0.0.1
                s=-
                t=0 0
                a=group:BUNDLE 0 1
                a=msid-semantic: WMS
                m=video 9 UDP/TLS/RTP/SAVPF 96
                c=IN IP4 0.0.0.0
                a=mid:0
                a=${if (isOffer) "sendonly" else "recvonly"}
                a=rtpmap:96 VP8/90000
                m=audio 9 UDP/TLS/RTP/SAVPF 111
                c=IN IP4 0.0.0.0
                a=mid:1
                a=${if (isOffer) "sendonly" else "recvonly"}
                a=rtpmap:111 opus/48000/2
            """.trimIndent()
        }
    }
}
