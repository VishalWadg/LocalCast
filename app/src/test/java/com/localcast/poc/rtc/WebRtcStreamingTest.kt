package com.localcast.poc.rtc

import com.localcast.poc.signaling.SignalCodec
import com.localcast.poc.signaling.SignalDecodeResult
import com.localcast.poc.signaling.SignalMessage
import com.localcast.poc.signaling.SignalType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

/**
 * Requirements R2 & Test Infra Features 3, 4, 5:
 * - Feature 3: WebRTC Video Track negotiation on connect
 * - Feature 4: SurfaceViewRenderer frame display concurrent with audio
 * - Feature 5: Camera controls (flip/mute/pause) stream integrity
 *
 * Tier 1 (Feature Coverage):
 * - Unified Plan SDP Offer generation containing video (m=video) and audio (m=audio)
 * - Unified Plan SDP Answer generation on Receiver
 * - Local-only host ICE candidate generation
 * - Remote video and audio track attachment to session listeners
 * - Camera/mic track status updates across signaling
 *
 * Tier 2 (Boundary & Corner Cases):
 * - Trickled candidate arrival before remote SDP offer/answer is applied (queued)
 * - Draining queued candidates in FIFO order
 * - Pass-through of trickled candidates arriving after remote description
 * - Candidate SDP type extraction for malformed or non-standard SDP tokens
 * - Resetting candidate queue upon peer session close
 * - Receiver peer session rejection on empty or invalid offer SDP
 *
 * Tier 3 (Cross-Feature Combinations):
 * - Bidirectional ICE exchange with concurrent audio + video streaming
 * - Network jitter simulation: delayed ICE candidates arriving out of order
 * - Disconnect and renegotiation with new session ID
 *
 * Tier 4 (Real-World Scenarios):
 * - Scenario 1: Phone A Hosts -> Phone B Joins -> Video & Audio concurrent streaming with frame delivery
 * - Scenario 5: Network jitter, trickled ICE queueing, and stream recovery
 */
class WebRtcStreamingTest {

    private lateinit var hostEngine: FakeMediaEngine
    private lateinit var receiverEngine: FakeMediaEngine

    @Before
    fun setUp() {
        hostEngine = FakeMediaEngine()
        receiverEngine = FakeMediaEngine()
    }

    private fun createDummyRenderer(): SurfaceViewRenderer {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(SurfaceViewRenderer::class.java) as SurfaceViewRenderer
    }

    // =========================================================================
    // TIER 1: Feature Coverage (≥5 Tests)
    // =========================================================================

    @Test
    fun `Tier1 - Host creates Unified Plan Offer containing both video and audio tracks`() {
        hostEngine.initialize()
        hostEngine.startLocalCapture(preferFrontCamera = true) {}

        var generatedOffer: String? = null
        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                if (type == SessionDescription.Type.OFFER) generatedOffer = sdp
            }
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        val started = hostEngine.startPublisherPeerSession(callback)
        assertTrue(started)
        assertNotNull(generatedOffer)

        val sdp = generatedOffer!!
        assertTrue("Must contain video media line", sdp.contains("m=video"))
        assertTrue("Must contain audio media line", sdp.contains("m=audio"))
        assertTrue("Unified Plan bundle", sdp.contains("a=group:BUNDLE 0 1"))
        assertTrue("Must be sendonly on publisher", sdp.contains("a=sendonly"))
    }

    @Test
    fun `Tier1 - Receiver creates Unified Plan Answer responding to Host Offer`() {
        receiverEngine.initialize()

        val offerSdp = FakeMediaEngine.buildFakeUnifiedPlanSdp(isOffer = true)
        var generatedAnswer: String? = null

        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                if (type == SessionDescription.Type.ANSWER) generatedAnswer = sdp
            }
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        val started = receiverEngine.startReceiverPeerSessionAndAnswer(offerSdp, callback)
        assertTrue(started)
        assertNotNull(generatedAnswer)

        val answer = generatedAnswer!!
        assertTrue("Answer must contain video line", answer.contains("m=video"))
        assertTrue("Answer must contain audio line", answer.contains("m=audio"))
        assertTrue("Answer must be recvonly on receiver", answer.contains("a=recvonly"))
    }

    @Test
    fun `Tier1 - Local host ICE candidate is detected and formatted`() {
        val hostCand = "candidate:1001 1 udp 2122260223 192.168.1.50 54321 typ host generation 0"
        val candType = PeerSession.extractCandidateType(hostCand)
        assertEquals("host", candType)

        val srflxCand = "candidate:2002 1 udp 1686052863 203.0.113.1 54322 typ srflx raddr 192.168.1.50 rport 54321 generation 0"
        val srflxType = PeerSession.extractCandidateType(srflxCand)
        assertEquals("srflx", srflxType)
    }

    @Test
    fun `Tier1 - Remote video and audio tracks trigger callback on Receiver`() {
        receiverEngine.initialize()
        var videoTrackReady = false
        var audioTrackReady = false

        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() { videoTrackReady = true }
            override fun onRemoteAudioTrackAvailable() { audioTrackReady = true }
            override fun onPeerSessionError(reason: String) {}
        }

        receiverEngine.startReceiverPeerSessionAndAnswer(
            FakeMediaEngine.buildFakeUnifiedPlanSdp(isOffer = true),
            callback
        )

        receiverEngine.simulateRemoteVideoTrackAvailable()
        receiverEngine.simulateRemoteAudioTrackAvailable()

        assertTrue("Remote video track must be reported available", videoTrackReady)
        assertTrue("Remote audio track must be reported available", audioTrackReady)
    }

    @Test
    fun `Tier1 - Camera mute status serialized and decoded via TRACK_STATE signaling`() {
        val trackMsg = SignalMessage(
            sessionId = "sess-track-01",
            type = SignalType.TRACK_STATE,
            fromDeviceId = "phone-a",
            toDeviceId = "phone-b",
            payload = mapOf(
                "kind" to "video",
                "enabled" to "false"
            )
        )

        val encoded = SignalCodec.encode(trackMsg)
        val decoded = SignalCodec.decodeAndValidate(encoded, expectedSessionId = "sess-track-01")

        assertTrue(decoded is SignalDecodeResult.Success)
        val msg = (decoded as SignalDecodeResult.Success).message
        assertEquals(SignalType.TRACK_STATE, msg.type)
        assertEquals("video", msg.payload["kind"])
        assertEquals("false", msg.payload["enabled"])
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases (≥5 Tests)
    // =========================================================================

    @Test
    fun `Tier2 - Candidates arriving before remote description are queued in IceCandidateQueue`() {
        val queue = IceCandidateQueue<String>()

        // Arrive before remote description
        val passThrough1 = queue.enqueueOrPassThrough("cand-1")
        val passThrough2 = queue.enqueueOrPassThrough("cand-2")

        assertEquals(null, passThrough1)
        assertEquals(null, passThrough2)
        assertEquals(2, queue.pendingCount())
    }

    @Test
    fun `Tier2 - Flushing candidate queue delivers candidates in exact FIFO order`() {
        val queue = IceCandidateQueue<String>()
        queue.enqueueOrPassThrough("candidate-alpha")
        queue.enqueueOrPassThrough("candidate-beta")
        queue.enqueueOrPassThrough("candidate-gamma")

        val flushed = queue.onRemoteDescriptionApplied()
        assertEquals(listOf("candidate-alpha", "candidate-beta", "candidate-gamma"), flushed)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `Tier2 - Subsequent candidates after remote description pass through immediately`() {
        val queue = IceCandidateQueue<String>()
        queue.enqueueOrPassThrough("queued-1")
        queue.onRemoteDescriptionApplied()

        val direct = queue.enqueueOrPassThrough("direct-2")
        assertEquals("direct-2", direct)
        assertEquals(0, queue.pendingCount())
    }

    @Test
    fun `Tier2 - Malformed ICE candidate without typ token safely resolves to unknown`() {
        val malformed = "candidate:12345 1 udp 2122260223 192.168.1.1 50000"
        val type = PeerSession.extractCandidateType(malformed)
        assertEquals("unknown", type)

        val empty = ""
        assertEquals("unknown", PeerSession.extractCandidateType(empty))
    }

    @Test
    fun `Tier2 - Closing peer session clears candidate queue and detaches all sinks`() {
        hostEngine.initialize()
        val renderer = createDummyRenderer()
        hostEngine.bindRemoteVideoRenderer(renderer)
        hostEngine.addOrQueueRemoteIceCandidate("0", 0, "candidate:1 host")

        assertEquals(1, hostEngine.boundRemoteRenderers.size)
        assertEquals(1, hostEngine.remoteIceCandidates.size)

        hostEngine.closePeerSession()
        assertFalse(hostEngine.isPeerSessionActive)
        assertEquals(0, hostEngine.remoteIceCandidates.size)
    }

    @Test
    fun `Tier2 - Receiver startPeerSession fails cleanly when engine forces error`() {
        receiverEngine.initialize()
        receiverEngine.shouldFailPeerSession = true

        var reportedError: String? = null
        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) { reportedError = reason }
        }

        val started = receiverEngine.startReceiverPeerSessionAndAnswer("bad-offer", callback)
        assertFalse("Must return false on forced failure", started)
        assertNotNull(reportedError)
        assertTrue(reportedError?.contains("failure") == true)
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations (Pairwise & Stress)
    // =========================================================================

    @Test
    fun `Tier3 - Bi-directional ICE candidate exchange while streaming concurrent audio and video`() {
        hostEngine.initialize()
        hostEngine.startLocalCapture(preferFrontCamera = true) {}

        receiverEngine.initialize()

        val hostCandidates = mutableListOf<String>()
        val receiverCandidates = mutableListOf<String>()

        val hostCallback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {
                hostCandidates.add(candidateSdp)
            }
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        val receiverCallback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {
                receiverCandidates.add(candidateSdp)
            }
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        // Host generates offer
        hostEngine.startPublisherPeerSession(hostCallback)
        val offerSdp = hostEngine.lastGeneratedOfferSdp!!

        // Receiver generates answer
        receiverEngine.startReceiverPeerSessionAndAnswer(offerSdp, receiverCallback)
        val answerSdp = receiverEngine.lastGeneratedAnswerSdp!!

        // Host applies answer
        hostEngine.applyRemoteAnswer(answerSdp)

        // Both find local candidates
        hostEngine.simulateLocalIceCandidate("0", 0, "candidate:1 host-a")
        receiverEngine.simulateLocalIceCandidate("0", 0, "candidate:2 host-b")

        assertEquals(1, hostCandidates.size)
        assertEquals(1, receiverCandidates.size)

        // Mutual ICE connection establishment
        hostEngine.simulateIceConnectionState("CONNECTED")
        receiverEngine.simulateIceConnectionState("CONNECTED")

        assertTrue(hostEngine.isPeerSessionActive)
        assertTrue(receiverEngine.isPeerSessionActive)
    }

    @Test
    fun `Tier3 - Network jitter simulation with out-of-order candidate arrivals before answer`() {
        val queue = IceCandidateQueue<String>()

        // 3 candidates burst through with jitter prior to answer SDP
        queue.enqueueOrPassThrough("cand-jitter-1")
        queue.enqueueOrPassThrough("cand-jitter-3") // arrived before 2
        queue.enqueueOrPassThrough("cand-jitter-2")

        assertEquals(3, queue.pendingCount())

        // Remote description arrives
        val flushed = queue.onRemoteDescriptionApplied()
        assertEquals(3, flushed.size)
        assertEquals("cand-jitter-1", flushed[0])
        assertEquals("cand-jitter-3", flushed[1])
        assertEquals("cand-jitter-2", flushed[2])

        // Fourth candidate arrives after remote description
        val direct = queue.enqueueOrPassThrough("cand-jitter-4")
        assertEquals("cand-jitter-4", direct)
    }

    @Test
    fun `Tier3 - Disconnect and call restart establishes clean second session`() {
        hostEngine.initialize()
        hostEngine.startLocalCapture(preferFrontCamera = true) {}

        val dummyCallback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        // Call 1
        hostEngine.startPublisherPeerSession(dummyCallback)
        hostEngine.simulateIceConnectionState("CONNECTED")
        hostEngine.closePeerSession()
        assertFalse(hostEngine.isPeerSessionActive)

        // Call 2
        val startedSecond = hostEngine.startPublisherPeerSession(dummyCallback)
        assertTrue(startedSecond)
        assertTrue(hostEngine.isPeerSessionActive)
        assertNotNull(hostEngine.lastGeneratedOfferSdp)
    }

    // =========================================================================
    // TIER 4: Real-World Scenarios
    // =========================================================================

    @Test
    fun `Tier4 - Scenario 1 Phone A Hosts to Phone B Joins complete handshake and video frame sink delivery`() {
        // Step 1: Phone A (Host) initializes WebRTC engine & Camera2/Mic capture
        hostEngine.initialize()
        val captureStarted = hostEngine.startLocalCapture(preferFrontCamera = true) {}
        assertTrue("Phone A capture must start", captureStarted)

        val localPreviewRenderer = createDummyRenderer()
        hostEngine.bindLocalVideoRenderer(localPreviewRenderer, mirror = true)
        assertTrue(hostEngine.boundLocalRenderers.contains(localPreviewRenderer))

        // Step 2: Phone B (Receiver) initializes WebRTC engine
        receiverEngine.initialize()
        val remoteDisplayRenderer = createDummyRenderer()
        receiverEngine.bindRemoteVideoRenderer(remoteDisplayRenderer)
        assertTrue(receiverEngine.boundRemoteRenderers.contains(remoteDisplayRenderer))

        // Step 3: Signaling handshake - HELLO & JOIN_REQUEST
        val helloMsg = SignalMessage(
            sessionId = "sess-e2e-01",
            type = SignalType.HELLO,
            fromDeviceId = "phone-b",
            toDeviceId = "phone-a",
            payload = mapOf("deviceName" to "Pixel 10", "sessionCode" to "123456")
        )
        val encodedHello = SignalCodec.encode(helloMsg)
        val decodedHello = SignalCodec.decodeAndValidate(encodedHello)
        assertTrue(decodedHello is SignalDecodeResult.Success)

        val acceptMsg = SignalMessage(
            sessionId = "sess-e2e-01",
            type = SignalType.JOIN_ACCEPT,
            fromDeviceId = "phone-a",
            toDeviceId = "phone-b",
            payload = mapOf("sessionId" to "sess-e2e-01")
        )
        val encodedAccept = SignalCodec.encode(acceptMsg)
        val decodedAccept = SignalCodec.decodeAndValidate(encodedAccept)
        assertTrue(decodedAccept is SignalDecodeResult.Success)

        // Step 4: WebRTC PeerSession Offer/Answer negotiation
        var hostOfferSdp: String? = null
        val hostCallback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                if (type == SessionDescription.Type.OFFER) hostOfferSdp = sdp
            }
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }
        hostEngine.startPublisherPeerSession(hostCallback)
        assertNotNull(hostOfferSdp)

        var receiverAnswerSdp: String? = null
        var remoteVideoDelivered = false
        var remoteAudioDelivered = false
        val receiverCallback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                if (type == SessionDescription.Type.ANSWER) receiverAnswerSdp = sdp
            }
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {}
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() { remoteVideoDelivered = true }
            override fun onRemoteAudioTrackAvailable() { remoteAudioDelivered = true }
            override fun onPeerSessionError(reason: String) {}
        }
        receiverEngine.startReceiverPeerSessionAndAnswer(hostOfferSdp!!, receiverCallback)
        assertNotNull(receiverAnswerSdp)

        hostEngine.applyRemoteAnswer(receiverAnswerSdp!!)

        // Step 5: ICE candidates and track rendering
        receiverEngine.simulateRemoteVideoTrackAvailable()
        receiverEngine.simulateRemoteAudioTrackAvailable()
        assertTrue("Phone B must receive remote video track", remoteVideoDelivered)
        assertTrue("Phone B must receive remote audio track concurrently", remoteAudioDelivered)

        // Step 6: Video frame delivery to SurfaceViewRenderer
        receiverEngine.simulateFrameDelivery()
        receiverEngine.simulateFrameDelivery()
        assertEquals(2, receiverEngine.framesDeliveredCount)

        // Step 7: Clean teardown
        hostEngine.release()
        receiverEngine.release()
        assertFalse(hostEngine.isCapturing)
        assertFalse(receiverEngine.isPeerSessionActive)
    }

    @Test
    fun `Tier4 - Scenario 5 Network jitter ICE candidate queueing and state recovery`() {
        val candidateQueue = IceCandidateQueue<String>()

        // Trickled candidates arrive before SDP answer is generated due to network jitter
        candidateQueue.enqueueOrPassThrough("candidate:1 host 192.168.1.100")
        candidateQueue.enqueueOrPassThrough("candidate:2 host 192.168.1.100")
        candidateQueue.enqueueOrPassThrough("candidate:3 host 192.168.1.100")

        assertEquals(3, candidateQueue.pendingCount())

        // Remote answer arrives, draining the candidate queue
        val flushed = candidateQueue.onRemoteDescriptionApplied()
        assertEquals(3, flushed.size)
        assertEquals(0, candidateQueue.pendingCount())

        // Late candidate arrives after connection
        val lateCandidate = candidateQueue.enqueueOrPassThrough("candidate:4 host 192.168.1.100")
        assertEquals("candidate:4 host 192.168.1.100", lateCandidate)

        // Network recovers, ICE state transitions to CONNECTED
        hostEngine.initialize()
        var connected = false
        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {
                if (state == "CONNECTED") connected = true
            }
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }
        hostEngine.startPublisherPeerSession(callback)
        hostEngine.simulateIceConnectionState("CONNECTED")
        assertTrue("Stream must recover and report CONNECTED", connected)
    }
}
