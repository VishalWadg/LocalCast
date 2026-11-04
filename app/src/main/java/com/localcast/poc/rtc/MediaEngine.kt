package com.localcast.poc.rtc

import android.content.Context
import com.localcast.poc.model.MediaEngineStatus
import org.webrtc.SurfaceViewRenderer

/**
 * Application-level MediaEngine abstraction (Requirement 2 & Complete Product Scope Section 11.1).
 *
 * Isolates `io.github.webrtc-sdk:android` behind a single interface so UI, ViewModel, and
 * `SessionRuntimeCoordinator` can orchestrate M0 initialization, M2 SDP/ICE negotiation, and
 * M3 Camera/Microphone capture and rendering without coupling to `org.webrtc.*` internals.
 */
interface MediaEngine {

    /** M0: Initializes the native WebRTC engine and validates local-only ICE config. */
    fun initialize(context: Context): MediaEngineStatus

    /** Returns current native initialization status. */
    fun currentStatus(): MediaEngineStatus

    /** M3: Starts local Camera2 (720p->480p fallback) and Microphone capture. */
    fun startLocalCapture(preferFrontCamera: Boolean = true, onLog: (String) -> Unit): Boolean

    /** M3: Stops local Camera2 and Microphone capture idempotently. */
    fun stopLocalCapture()

    /** M3: Switches between front and rear camera. */
    fun switchCamera(onSwitched: (Boolean) -> Unit = {})

    /** M3: Enables or disables local camera video track. */
    fun setCameraEnabled(enabled: Boolean)

    /** M3: Enables or disables local microphone audio track. */
    fun setMicrophoneEnabled(enabled: Boolean)

    /** Returns active capture resolution/FPS profile description. */
    fun activeCaptureProfile(): String

    /** M2: Starts a publisher PeerConnection on Phone A and creates an SDP Offer. */
    fun startPublisherPeerSession(callback: PeerSessionCallback): Boolean

    /** M2: Starts a receiver PeerConnection on Phone B, applies remote Offer, and creates SDP Answer. */
    fun startReceiverPeerSessionAndAnswer(offerSdp: String, callback: PeerSessionCallback): Boolean

    /** M2: Applies remote SDP Answer on Phone A. */
    fun applyRemoteAnswer(answerSdp: String)

    /** M2: Adds or queues a trickled remote ICE candidate. */
    fun addOrQueueRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String)

    /** M2: Closes the active PeerSession without tearing down the native factory. */
    fun closePeerSession()

    /** M3: Initializes and binds a Compose-hosted SurfaceViewRenderer to the local camera track. */
    fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer, mirror: Boolean = true)

    /** M3: Unbinds a SurfaceViewRenderer from the local camera track. */
    fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer)

    /** M3: Initializes and binds a Compose-hosted SurfaceViewRenderer to the remote video track. */
    fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer)

    /** M3: Unbinds a SurfaceViewRenderer from the remote video track. */
    fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer)

    /** Releases all native WebRTC resources idempotently. */
    fun release()
}
