package com.localcast.poc.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * - MediaEngine initialization & local-only ICE configuration
 * - Local camera/mic capture start and profile verification
 * - Video sink/renderer binding and registration
 * - Camera flip front/back lens state transitions
 * - Audio and video track mute/unmute toggling
 *
 * Tier 2 (Boundary & Corner Cases):
 * - Multiple initialization idempotency
 * - Unbinding an unattached renderer (safe no-op)
 * - Binding duplicate renderers (deduplication)
 * - Switching camera while capture is stopped
 * - Stopping capture when already stopped (idempotency)
 * - Muting camera/mic before capture starts
 * - Multiple release calls (cleanup idempotency)
 * - Peer session creation failure when engine is uninitialized
 *
 * Tier 3:
 * - Switching camera while video is muted preserves mute state
 * - Concurrent audio track and video track active streaming
 * - Video sink unbind and rebind during live stream
 * - Teardown and immediate restart on the same engine instance
 *
 * Tier 4:
 * - Scenario 2: Camera flip front/back during active call
 * - Scenario 3: Mute video / mute audio toggles without dropping stream
 */
class MediaEngineLifecycleTest {

    private lateinit var fakeEngine: FakeMediaEngine

    @Before
    fun setUp() {
        fakeEngine = FakeMediaEngine()
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
    fun `Tier1 - initialize sets localOnlyIceConfigured and hardware codec capabilities`() {
        assertFalse(fakeEngine.isInitialized)
        val status = fakeEngine.initialize()

        assertTrue("Engine must be initialized", status.initialized)
        assertTrue("Local-only ICE must be verified (no STUN/TURN)", status.localOnlyIceConfigured)
        assertTrue("Hardware video encoders must be reported", status.hardwareVideoEncoders >= 1)
        assertTrue("Hardware video decoders must be reported", status.hardwareVideoDecoders >= 1)
        assertEquals("FakeMediaEngine (Test Double)", status.engineName)
    }

    @Test
    fun `Tier1 - startLocalCapture initializes front camera and mic at 720p@30fps profile`() {
        fakeEngine.initialize()
        val logs = mutableListOf<String>()

        val started = fakeEngine.startLocalCapture(preferFrontCamera = true) { logs.add(it) }
        assertTrue("Capture must start successfully", started)
        assertTrue(fakeEngine.isCapturing)
        assertTrue(fakeEngine.isFrontCamera)
        assertTrue(fakeEngine.isCameraEnabled)
        assertTrue(fakeEngine.isMicrophoneEnabled)
        assertEquals("720p@30fps (Camera2)", fakeEngine.activeCaptureProfile())
        assertTrue(logs.any { it.contains("720p@30fps") })
    }

    @Test
    fun `Tier1 - bindLocalVideoRenderer and bindRemoteVideoRenderer register renderers`() {
        fakeEngine.initialize()
        val localRenderer = createDummyRenderer()
        val remoteRenderer = createDummyRenderer()

        fakeEngine.bindLocalVideoRenderer(localRenderer, mirror = true)
        fakeEngine.bindRemoteVideoRenderer(remoteRenderer)

        assertTrue(fakeEngine.boundLocalRenderers.contains(localRenderer))
        assertTrue(fakeEngine.boundRemoteRenderers.contains(remoteRenderer))

        fakeEngine.unbindLocalVideoRenderer(localRenderer)
        fakeEngine.unbindRemoteVideoRenderer(remoteRenderer)

        assertFalse(fakeEngine.boundLocalRenderers.contains(localRenderer))
        assertFalse(fakeEngine.boundRemoteRenderers.contains(remoteRenderer))
    }

    @Test
    fun `Tier1 - switchCamera toggles front and rear lens state during active capture`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}
        assertTrue(fakeEngine.isFrontCamera)

        var switchedResult: Boolean? = null
        fakeEngine.switchCamera { switchedResult = it }
        assertFalse("Camera must flip to rear lens", fakeEngine.isFrontCamera)
        assertEquals(false, switchedResult)

        fakeEngine.switchCamera { switchedResult = it }
        assertTrue("Camera must flip back to front lens", fakeEngine.isFrontCamera)
        assertEquals(true, switchedResult)
    }

    @Test
    fun `Tier1 - setCameraEnabled and setMicrophoneEnabled toggle mute state independently`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        // Mute video
        fakeEngine.setCameraEnabled(false)
        assertFalse(fakeEngine.isCameraEnabled)
        assertTrue("Audio must remain enabled when video is muted", fakeEngine.isMicrophoneEnabled)

        // Mute audio
        fakeEngine.setMicrophoneEnabled(false)
        assertFalse(fakeEngine.isCameraEnabled)
        assertFalse(fakeEngine.isMicrophoneEnabled)

        // Unmute video
        fakeEngine.setCameraEnabled(true)
        assertTrue(fakeEngine.isCameraEnabled)
        assertFalse(fakeEngine.isMicrophoneEnabled)

        // Unmute audio
        fakeEngine.setMicrophoneEnabled(true)
        assertTrue(fakeEngine.isCameraEnabled)
        assertTrue(fakeEngine.isMicrophoneEnabled)
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases (≥5 Tests)
    // =========================================================================

    @Test
    fun `Tier2 - repeated initialize calls are idempotent and do not corrupt state`() {
        val first = fakeEngine.initialize()
        assertTrue(first.initialized)

        val second = fakeEngine.initialize()
        assertTrue(second.initialized)
        assertEquals(first.engineName, second.engineName)
        assertEquals(first.localOnlyIceConfigured, second.localOnlyIceConfigured)
    }

    @Test
    fun `Tier2 - unbind renderer not previously bound is safe no-op`() {
        fakeEngine.initialize()
        val unboundRenderer = createDummyRenderer()

        // Should not throw or corrupt internal sets
        fakeEngine.unbindLocalVideoRenderer(unboundRenderer)
        fakeEngine.unbindRemoteVideoRenderer(unboundRenderer)

        assertTrue(fakeEngine.boundLocalRenderers.isEmpty())
        assertTrue(fakeEngine.boundRemoteRenderers.isEmpty())
    }

    @Test
    fun `Tier2 - duplicate bind calls for same renderer are deduplicated`() {
        fakeEngine.initialize()
        val renderer = createDummyRenderer()

        fakeEngine.bindLocalVideoRenderer(renderer)
        fakeEngine.bindLocalVideoRenderer(renderer)
        assertEquals(1, fakeEngine.boundLocalRenderers.size)

        fakeEngine.bindRemoteVideoRenderer(renderer)
        fakeEngine.bindRemoteVideoRenderer(renderer)
        assertEquals(1, fakeEngine.boundRemoteRenderers.size)
    }

    @Test
    fun `Tier2 - switchCamera when capture is stopped does not crash and preserves state`() {
        fakeEngine.initialize()
        assertFalse(fakeEngine.isCapturing)

        var switched = false
        fakeEngine.switchCamera { isFront ->
            switched = true
            assertTrue("Expected initial front camera preference", isFront)
        }
        assertTrue(switched)
        assertTrue(fakeEngine.isFrontCamera)
    }

    @Test
    fun `Tier2 - stopLocalCapture when already stopped is idempotent`() {
        fakeEngine.initialize()
        assertFalse(fakeEngine.isCapturing)

        fakeEngine.stopLocalCapture()
        assertFalse(fakeEngine.isCapturing)
        assertEquals("Stopped", fakeEngine.activeCaptureProfile())
    }

    @Test
    fun `Tier2 - multiple release calls perform safe idempotent cleanup`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}
        val renderer = createDummyRenderer()
        fakeEngine.bindLocalVideoRenderer(renderer)

        fakeEngine.release()
        assertTrue(fakeEngine.isReleased)
        assertFalse(fakeEngine.isInitialized)
        assertFalse(fakeEngine.isCapturing)
        assertTrue(fakeEngine.boundLocalRenderers.isEmpty())

        // Second release must not crash
        fakeEngine.release()
        assertTrue(fakeEngine.isReleased)
    }

    @Test
    fun `Tier2 - startLocalCapture without initialization fails gracefully`() {
        var logOutput: String? = null
        val started = fakeEngine.startLocalCapture(preferFrontCamera = true) { logOutput = it }

        assertFalse("Capture must fail if engine is uninitialized", started)
        assertFalse(fakeEngine.isCapturing)
        assertNotNull(logOutput)
        assertTrue(logOutput?.contains("initialized=false") == true)
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations (Pairwise & Stress)
    // =========================================================================

    @Test
    fun `Tier3 - switching camera while video track is muted maintains mute state`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        // Step 1: Mute video
        fakeEngine.setCameraEnabled(false)
        assertFalse(fakeEngine.isCameraEnabled)

        // Step 2: Switch camera to rear
        fakeEngine.switchCamera {}
        assertFalse("Camera must flip to rear", fakeEngine.isFrontCamera)

        // Step 3: Verify video remains muted across lens switch
        assertFalse("Video track must remain disabled after switching lenses", fakeEngine.isCameraEnabled)

        // Step 4: Switch back to front and verify mute still holds
        fakeEngine.switchCamera {}
        assertTrue(fakeEngine.isFrontCamera)
        assertFalse("Video track must still remain disabled", fakeEngine.isCameraEnabled)
    }

    @Test
    fun `Tier3 - concurrent audio and video track streaming lifecycle`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        var offerReceived: String? = null
        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {
                if (type == SessionDescription.Type.OFFER) offerReceived = sdp
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

        val started = fakeEngine.startPublisherPeerSession(callback)
        assertTrue("Publisher session started", started)
        assertNotNull("Offer SDP generated", offerReceived)

        // Unified plan SDP must declare both video (m=video) and audio (m=audio)
        val sdp = offerReceived!!
        assertTrue("SDP must contain m=video track", sdp.contains("m=video"))
        assertTrue("SDP must contain m=audio track", sdp.contains("m=audio"))
        assertTrue("SDP must declare BUNDLE for audio and video", sdp.contains("a=group:BUNDLE 0 1"))
    }

    @Test
    fun `Tier3 - video sink unbind and rebind during active streaming does not crash`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        val rendererA = createDummyRenderer()
        val rendererB = createDummyRenderer()

        fakeEngine.bindRemoteVideoRenderer(rendererA)
        assertEquals(1, fakeEngine.boundRemoteRenderers.size)

        // Simulate frame delivery
        fakeEngine.simulateFrameDelivery()
        assertEquals(1, fakeEngine.framesDeliveredCount)

        // Unbind A, bind B mid-stream
        fakeEngine.unbindRemoteVideoRenderer(rendererA)
        fakeEngine.bindRemoteVideoRenderer(rendererB)

        assertEquals(1, fakeEngine.boundRemoteRenderers.size)
        assertTrue(fakeEngine.boundRemoteRenderers.contains(rendererB))
        assertFalse(fakeEngine.boundRemoteRenderers.contains(rendererA))

        fakeEngine.simulateFrameDelivery()
        assertEquals(2, fakeEngine.framesDeliveredCount)
    }

    @Test
    fun `Tier3 - disconnect and restart peer session on same engine instance succeeds`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

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

        // Session 1
        fakeEngine.startPublisherPeerSession(dummyCallback)
        assertTrue(fakeEngine.isPeerSessionActive)
        fakeEngine.closePeerSession()
        assertFalse(fakeEngine.isPeerSessionActive)

        // Session 2 immediately following
        val restarted = fakeEngine.startPublisherPeerSession(dummyCallback)
        assertTrue("Restarted peer session must succeed", restarted)
        assertTrue(fakeEngine.isPeerSessionActive)
        assertNotNull(fakeEngine.lastGeneratedOfferSdp)
    }

    // =========================================================================
    // TIER 4: Real-World Scenarios
    // =========================================================================

    @Test
    fun `Tier4 - Scenario 2 camera flip front and back during active call`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        var remoteVideoAvailable = false
        var remoteAudioAvailable = false
        var iceState: String? = null

        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) { iceState = state }
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() { remoteVideoAvailable = true }
            override fun onRemoteAudioTrackAvailable() { remoteAudioAvailable = true }
            override fun onPeerSessionError(reason: String) {}
        }

        fakeEngine.startPublisherPeerSession(callback)
        fakeEngine.simulateIceConnectionState("CONNECTED")
        fakeEngine.simulateRemoteVideoTrackAvailable()
        fakeEngine.simulateRemoteAudioTrackAvailable()

        assertEquals("CONNECTED", iceState)
        assertTrue(remoteVideoAvailable)
        assertTrue(remoteAudioAvailable)

        // Flip to rear camera during live streaming
        fakeEngine.switchCamera { isFront -> assertFalse(isFront) }
        assertFalse(fakeEngine.isFrontCamera)
        // Stream must remain connected
        assertEquals("CONNECTED", iceState)

        // Flip back to front camera
        fakeEngine.switchCamera { isFront -> assertTrue(isFront) }
        assertTrue(fakeEngine.isFrontCamera)
        assertEquals("CONNECTED", iceState)
    }

    @Test
    fun `Tier4 - Scenario 3 mute video and mute audio toggles without dropping stream`() {
        fakeEngine.initialize()
        fakeEngine.startLocalCapture(preferFrontCamera = true) {}

        var iceConnected = false
        val callback = object : PeerSessionCallback {
            override fun onLocalSdpReady(type: SessionDescription.Type, sdp: String) {}
            override fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String) {}
            override fun onSignalingStateChanged(state: String) {}
            override fun onIceConnectionStateChanged(state: String) {
                iceConnected = (state == "CONNECTED")
            }
            override fun onIceGatheringStateChanged(state: String) {}
            override fun onPeerConnectionStateChanged(state: String) {}
            override fun onQueuedCandidatesFlushed(flushedCount: Int) {}
            override fun onRemoteVideoTrackAvailable() {}
            override fun onRemoteAudioTrackAvailable() {}
            override fun onPeerSessionError(reason: String) {}
        }

        fakeEngine.startPublisherPeerSession(callback)
        fakeEngine.simulateIceConnectionState("CONNECTED")
        assertTrue(iceConnected)

        // Toggle video mute off
        fakeEngine.setCameraEnabled(false)
        assertFalse(fakeEngine.isCameraEnabled)
        assertTrue("Stream must remain connected while camera is paused", iceConnected)

        // Toggle audio mute off
        fakeEngine.setMicrophoneEnabled(false)
        assertFalse(fakeEngine.isMicrophoneEnabled)
        assertTrue("Stream must remain connected while mic is muted", iceConnected)

        // Toggle both back on
        fakeEngine.setCameraEnabled(true)
        fakeEngine.setMicrophoneEnabled(true)
        assertTrue(fakeEngine.isCameraEnabled)
        assertTrue(fakeEngine.isMicrophoneEnabled)
        assertTrue("Stream must remain uninterrupted throughout toggling", iceConnected)
    }
}
