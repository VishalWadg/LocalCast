package com.localcast.poc.rtc

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Pure-Kotlin queue for remote ICE candidates that arrive before `setRemoteDescription` completes.
 *
 * Per POC Spec Section 6 & Section 7:
 * "ICE candidates are exchanged asynchronously. We must queue remote candidates if they arrive
 * before the remote SDP description has been applied."
 */
class IceCandidateQueue<T> {
    private val lock = Any()
    private val pending = ArrayDeque<T>()
    private var remoteDescriptionReady = false

    /**
     * Marks remote description as applied and returns all queued candidates in FIFO order.
     */
    fun onRemoteDescriptionApplied(): List<T> {
        synchronized(lock) {
            remoteDescriptionReady = true
            val drained = pending.toList()
            pending.clear()
            return drained
        }
    }

    /**
     * Either returns the candidate immediately if `remoteDescriptionReady` is true,
     * or enqueues it and returns `null` so it will be flushed when `onRemoteDescriptionApplied()` runs.
     */
    fun enqueueOrPassThrough(candidate: T): T? {
        synchronized(lock) {
            return if (remoteDescriptionReady) {
                candidate
            } else {
                pending.addLast(candidate)
                null
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun reset() {
        synchronized(lock) {
            pending.clear()
            remoteDescriptionReady = false
        }
    }
}

/**
 * Clean adapter implementing [PeerConnection.Observer] for M2/M3 events.
 */
class RtcPeerConnectionObserver(
    private val onSignalingState: (PeerConnection.SignalingState) -> Unit,
    private val onIceConnectionState: (PeerConnection.IceConnectionState) -> Unit,
    private val onIceGatheringState: (PeerConnection.IceGatheringState) -> Unit,
    private val onPeerConnectionState: (PeerConnection.PeerConnectionState) -> Unit,
    private val onLocalIceCandidate: (IceCandidate) -> Unit,
    private val onRemoteTrackReceived: (RtpTransceiver) -> Unit
) : PeerConnection.Observer {

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
        if (newState != null) onSignalingState(newState)
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        if (newState != null) onIceConnectionState(newState)
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        if (newState != null) onIceGatheringState(newState)
    }

    override fun onIceCandidate(candidate: IceCandidate?) {
        if (candidate != null) onLocalIceCandidate(candidate)
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

    override fun onAddStream(stream: MediaStream?) = Unit

    override fun onRemoveStream(stream: MediaStream?) = Unit

    override fun onDataChannel(dataChannel: DataChannel?) = Unit

    override fun onRenegotiationNeeded() = Unit

    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit

    override fun onTrack(transceiver: RtpTransceiver?) {
        if (transceiver != null) onRemoteTrackReceived(transceiver)
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        if (newState != null) onPeerConnectionState(newState)
    }
}

/**
 * Clean lambda adapter for [SdpObserver].
 */
open class SimpleSdpObserver(
    private val onCreated: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : SdpObserver {

    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
        if (sessionDescription != null) {
            onCreated(sessionDescription)
        } else {
            onError("SDP onCreateSuccess returned null SessionDescription")
        }
    }

    override fun onSetSuccess() {
        onSet()
    }

    override fun onCreateFailure(error: String?) {
        onError("SDP create failure: ${error ?: "unknown"}")
    }

    override fun onSetFailure(error: String?) {
        onError("SDP set failure: ${error ?: "unknown"}")
    }
}
