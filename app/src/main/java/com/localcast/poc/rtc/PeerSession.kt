package com.localcast.poc.rtc

import com.localcast.poc.capture.CameraMicCapture
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Callback interface emitted by [PeerSession] back to the runtime coordinator.
 */
interface PeerSessionCallback {
    fun onLocalSdpReady(type: SessionDescription.Type, sdp: String)
    fun onLocalIceCandidateFound(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String, candidateType: String)
    fun onSignalingStateChanged(state: String)
    fun onIceConnectionStateChanged(state: String)
    fun onIceGatheringStateChanged(state: String)
    fun onPeerConnectionStateChanged(state: String)
    fun onQueuedCandidatesFlushed(flushedCount: Int)
    fun onRemoteVideoTrackAvailable()
    fun onRemoteAudioTrackAvailable()
    fun onPeerSessionError(reason: String)
}

/**
 * M2 + M3: Manages a single `PeerConnection` configured with `PeerConnection.RTCConfiguration(emptyList())`
 * (strictly zero STUN/TURN servers), SDP Offer/Answer negotiation, trickled ICE candidate queueing,
 * and local/remote audio/video tracks.
 */
class PeerSession(
    private val factory: PeerConnectionFactory,
    private val callback: PeerSessionCallback
) {
    private val lock = Any()
    private var peerConnection: PeerConnection? = null
    private val candidateQueue = IceCandidateQueue<IceCandidate>()
    private val remoteVideoSinks = CopyOnWriteArraySet<VideoSink>()

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    /**
     * Creates a local-only WebRTC PeerConnection (`RTCConfiguration(emptyList())`) with Unified Plan semantics.
     */
    fun createPeerConnection(
        localVideoTrack: VideoTrack? = null,
        localAudioTrack: AudioTrack? = null,
        isPublisher: Boolean
    ): Boolean {
        synchronized(lock) {
            close()
            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            val observer = RtcPeerConnectionObserver(
                onSignalingState = { callback.onSignalingStateChanged(it.name) },
                onIceConnectionState = { callback.onIceConnectionStateChanged(it.name) },
                onIceGatheringState = { callback.onIceGatheringStateChanged(it.name) },
                onPeerConnectionState = { callback.onPeerConnectionStateChanged(it.name) },
                onLocalIceCandidate = { candidate ->
                    val candidateType = extractCandidateType(candidate.sdp)
                    callback.onLocalIceCandidateFound(
                        sdpMid = candidate.sdpMid ?: "",
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidateSdp = candidate.sdp,
                        candidateType = candidateType
                    )
                },
                onRemoteTrackReceived = { transceiver ->
                    handleRemoteTransceiverTrack(transceiver)
                }
            )

            val pc = factory.createPeerConnection(rtcConfig, observer)
            if (pc == null) {
                callback.onPeerSessionError("PeerConnectionFactory.createPeerConnection returned null")
                return false
            }

            if (isPublisher) {
                val streamIds = listOf(CameraMicCapture.LOCAL_STREAM_ID)
                if (localVideoTrack != null) {
                    pc.addTrack(localVideoTrack, streamIds)
                }
                if (localAudioTrack != null) {
                    pc.addTrack(localAudioTrack, streamIds)
                }
            } else {
                // Receiver (Phone B) adds RECV_ONLY transceivers for video and audio
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
                pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
                )
            }

            peerConnection = pc
            return true
        }
    }

    /**
     * Phone A: Creates an SDP Offer, sets it as the local description, and emits `onLocalSdpReady`.
     */
    fun createAndSetOffer() {
        val pc = synchronized(lock) { peerConnection } ?: run {
            callback.onPeerSessionError("Cannot create offer — PeerConnection is null")
            return
        }
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        pc.createOffer(
            SimpleSdpObserver(
                onCreated = { sdp ->
                    pc.setLocalDescription(
                        SimpleSdpObserver(
                            onSet = {
                                callback.onLocalSdpReady(SessionDescription.Type.OFFER, sdp.description)
                            },
                            onError = { err -> callback.onPeerSessionError(err) }
                        ),
                        sdp
                    )
                },
                onError = { err -> callback.onPeerSessionError(err) }
            ),
            constraints
        )
    }

    /**
     * Phone B: Applies Phone A's remote SDP Offer, flushes queued remote ICE candidates,
     * creates an SDP Answer, sets it as local description, and emits `onLocalSdpReady`.
     */
    fun applyRemoteOfferAndCreateAnswer(offerSdp: String) {
        val pc = synchronized(lock) { peerConnection } ?: run {
            callback.onPeerSessionError("Cannot apply remote offer — PeerConnection is null")
            return
        }
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(
            SimpleSdpObserver(
                onSet = {
                    flushQueuedCandidates(pc)
                    val answerConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    }
                    pc.createAnswer(
                        SimpleSdpObserver(
                            onCreated = { answerDesc ->
                                pc.setLocalDescription(
                                    SimpleSdpObserver(
                                        onSet = {
                                            callback.onLocalSdpReady(
                                                SessionDescription.Type.ANSWER,
                                                answerDesc.description
                                            )
                                        },
                                        onError = { err -> callback.onPeerSessionError(err) }
                                    ),
                                    answerDesc
                                )
                            },
                            onError = { err -> callback.onPeerSessionError(err) }
                        ),
                        answerConstraints
                    )
                },
                onError = { err -> callback.onPeerSessionError(err) }
            ),
            remoteDesc
        )
    }

    /**
     * Phone A: Applies Phone B's remote SDP Answer and flushes any queued ICE candidates.
     */
    fun applyRemoteAnswer(answerSdp: String) {
        val pc = synchronized(lock) { peerConnection } ?: run {
            callback.onPeerSessionError("Cannot apply remote answer — PeerConnection is null")
            return
        }
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(
            SimpleSdpObserver(
                onSet = {
                    flushQueuedCandidates(pc)
                },
                onError = { err -> callback.onPeerSessionError(err) }
            ),
            remoteDesc
        )
    }

    /**
     * Adds a remote ICE candidate immediately if remote SDP is set, or queues it until
     * `setRemoteDescription` completes.
     */
    fun addOrQueueRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdp: String) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        val readyCandidate = candidateQueue.enqueueOrPassThrough(candidate)
        if (readyCandidate != null) {
            val pc = synchronized(lock) { peerConnection }
            pc?.addIceCandidate(readyCandidate)
        }
    }

    fun attachRemoteVideoSink(sink: VideoSink) {
        remoteVideoSinks.add(sink)
        remoteVideoTrack?.addSink(sink)
    }

    fun detachRemoteVideoSink(sink: VideoSink) {
        remoteVideoSinks.remove(sink)
        runCatching { remoteVideoTrack?.removeSink(sink) }
    }

    /**
     * Idempotent cleanup of the PeerConnection and remote track bindings.
     */
    fun close() {
        synchronized(lock) {
            candidateQueue.reset()
            val rVideo = remoteVideoTrack
            remoteVideoTrack = null
            if (rVideo != null) {
                for (sink in remoteVideoSinks) {
                    runCatching { rVideo.removeSink(sink) }
                }
            }
            remoteAudioTrack = null

            val pc = peerConnection
            peerConnection = null
            if (pc != null) {
                runCatching { pc.close() }
                runCatching { pc.dispose() }
            }
        }
    }

    private fun flushQueuedCandidates(pc: PeerConnection) {
        val drained = candidateQueue.onRemoteDescriptionApplied()
        for (candidate in drained) {
            pc.addIceCandidate(candidate)
        }
        if (drained.isNotEmpty()) {
            callback.onQueuedCandidatesFlushed(drained.size)
        }
    }

    private fun handleRemoteTransceiverTrack(transceiver: RtpTransceiver) {
        val track = transceiver.receiver?.track() ?: return
        when (track) {
            is VideoTrack -> {
                track.setEnabled(true)
                remoteVideoTrack = track
                for (sink in remoteVideoSinks) {
                    track.addSink(sink)
                }
                callback.onRemoteVideoTrackAvailable()
            }
            is AudioTrack -> {
                track.setEnabled(true)
                remoteAudioTrack = track
                callback.onRemoteAudioTrackAvailable()
            }
        }
    }

    companion object {
        fun extractCandidateType(candidateSdp: String): String {
            val tokens = candidateSdp.split(" ")
            val typIdx = tokens.indexOf("typ")
            return if (typIdx != -1 && typIdx + 1 < tokens.size) {
                tokens[typIdx + 1]
            } else {
                "unknown"
            }
        }
    }
}
