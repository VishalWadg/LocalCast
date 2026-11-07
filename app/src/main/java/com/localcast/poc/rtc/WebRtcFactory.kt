package com.localcast.poc.rtc

import android.content.Context
import android.os.Build
import com.localcast.poc.capture.CameraMicCapture
import com.localcast.poc.model.MediaEngineStatus
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Default [MediaEngine] adapter backed by `io.github.webrtc-sdk:android:150.7871.04`.
 *
 * Coordinates:
 * - M0: Native library initialization (`PeerConnectionFactory`, `EglBase`, `JavaAudioDeviceModule`)
 * - M2: [PeerSession] creation, SDP Offer/Answer, and queued ICE candidates
 * - M3: [CameraMicCapture] lifecycle and [SurfaceViewRenderer] binding for Jetpack Compose
 */
class WebRtcFactory : MediaEngine {

    private val lock = Any()
    private var appContext: Context? = null
    private var eglBase: EglBase? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    private var cameraMicCapture: CameraMicCapture? = null
    private var peerSession: PeerSession? = null

    private val initializedRenderers = Collections.newSetFromMap(IdentityHashMap<SurfaceViewRenderer, Boolean>())
    private val boundRemoteVideoSinks = CopyOnWriteArraySet<VideoSink>()

    @Volatile
    private var status: MediaEngineStatus = MediaEngineStatus(
        initialized = false,
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        supportedAbis = Build.SUPPORTED_ABIS.toList()
    )

    override fun initialize(context: Context): MediaEngineStatus {
        synchronized(lock) {
            val ctx = context.applicationContext
            appContext = ctx
            if (status.initialized && peerConnectionFactory != null && eglBase != null) {
                for (sink in boundRemoteVideoSinks) {
                    if (sink is SurfaceViewRenderer) {
                        ensureRendererInitialized(sink, mirror = false)
                    }
                }
                return status
            }
            val abis = Build.SUPPORTED_ABIS.toList()
            val primaryAbi = abis.firstOrNull() ?: "unknown"
            val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val release = Build.VERSION.RELEASE ?: "unknown"
            val sdk = Build.VERSION.SDK_INT

            return try {
                val initOptions = PeerConnectionFactory.InitializationOptions
                    .builder(ctx)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initOptions)

                val createdEgl = EglBase.create()
                val adm = JavaAudioDeviceModule.builder(ctx)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()

                val encoderFactory = DefaultVideoEncoderFactory(
                    createdEgl.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true
                )
                val decoderFactory = DefaultVideoDecoderFactory(createdEgl.eglBaseContext)

                val factory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(adm)
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()

                val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }
                val localOnlyVerified = rtcConfig.iceServers.isEmpty()

                val supportedEncoders = encoderFactory.supportedCodecs.size
                val supportedDecoders = decoderFactory.supportedCodecs.size

                eglBase = createdEgl
                audioDeviceModule = adm
                peerConnectionFactory = factory

                for (sink in boundRemoteVideoSinks) {
                    if (sink is SurfaceViewRenderer) {
                        ensureRendererInitialized(sink, mirror = false)
                    }
                }

                status = MediaEngineStatus(
                    initialized = true,
                    engineName = "webrtc-sdk 150.7871.04",
                    deviceModel = model,
                    androidRelease = release,
                    sdkInt = sdk,
                    primaryAbi = primaryAbi,
                    supportedAbis = abis,
                    hardwareVideoEncoders = supportedEncoders,
                    hardwareVideoDecoders = supportedDecoders,
                    localOnlyIceConfigured = localOnlyVerified,
                    errorMessage = null
                )
                status
            } catch (t: Throwable) {
                releaseInternal()
                status = MediaEngineStatus(
                    initialized = false,
                    engineName = "webrtc-sdk 150.7871.04",
                    deviceModel = model,
                    androidRelease = release,
                    sdkInt = sdk,
                    primaryAbi = primaryAbi,
                    supportedAbis = abis,
                    hardwareVideoEncoders = 0,
                    hardwareVideoDecoders = 0,
                    localOnlyIceConfigured = false,
                    errorMessage = "${t.javaClass.simpleName}: ${t.message ?: "Native WebRTC initialization failed"}"
                )
                status
            }
        }
    }

    override fun currentStatus(): MediaEngineStatus = status

    override fun startLocalCapture(preferFrontCamera: Boolean, onLog: (String) -> Unit): Boolean {
        synchronized(lock) {
            val ctx = appContext ?: return false
            val factory = peerConnectionFactory ?: return false
            val eglContext = eglBase?.eglBaseContext ?: return false

            val capture = cameraMicCapture ?: CameraMicCapture(ctx, factory, eglContext, onLog).also {
                cameraMicCapture = it
            }
            return capture.startCapture(preferFrontCamera)
        }
    }

    override fun stopLocalCapture() {
        synchronized(lock) {
            cameraMicCapture?.stopCapture()
        }
    }

    override fun switchCamera(onSwitched: (Boolean) -> Unit) {
        synchronized(lock) {
            cameraMicCapture?.switchCamera(onSwitched)
        }
    }

    override fun setCameraEnabled(enabled: Boolean) {
        synchronized(lock) {
            cameraMicCapture?.setCameraEnabled(enabled)
        }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        synchronized(lock) {
            cameraMicCapture?.setMicrophoneEnabled(enabled)
        }
    }

    override fun activeCaptureProfile(): String {
        return synchronized(lock) {
            cameraMicCapture?.activeProfileDescription ?: "Stopped"
        }
    }

    override fun startPublisherPeerSession(callback: PeerSessionCallback): Boolean {
        synchronized(lock) {
            val factory = peerConnectionFactory ?: return false
            peerSession?.close()
            val session = PeerSession(factory, callback)
            val created = session.createPeerConnection(
                localVideoTrack = cameraMicCapture?.localVideoTrack,
                localAudioTrack = cameraMicCapture?.localAudioTrack,
                isPublisher = true
            )
            if (!created) return false
            for (sink in boundRemoteVideoSinks) {
                session.attachRemoteVideoSink(sink)
            }
            peerSession = session
            session.createAndSetOffer()
            return true
        }
    }

    override fun startReceiverPeerSessionAndAnswer(
        offerSdp: String,
        callback: PeerSessionCallback
    ): Boolean {
        synchronized(lock) {
            val factory = peerConnectionFactory ?: return false
            peerSession?.close()
            val session = PeerSession(factory, callback)
            val created = session.createPeerConnection(
                localVideoTrack = null,
                localAudioTrack = null,
                isPublisher = false
            )
            if (!created) return false
            for (sink in boundRemoteVideoSinks) {
                session.attachRemoteVideoSink(sink)
            }
            peerSession = session
            session.applyRemoteOfferAndCreateAnswer(offerSdp)
            return true
        }
    }

    override fun applyRemoteAnswer(answerSdp: String) {
        synchronized(lock) {
            peerSession?.applyRemoteAnswer(answerSdp)
        }
    }

    override fun addOrQueueRemoteIceCandidate(
        sdpMid: String,
        sdpMLineIndex: Int,
        candidateSdp: String
    ) {
        synchronized(lock) {
            peerSession?.addOrQueueRemoteIceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
        }
    }

    override fun closePeerSession() {
        synchronized(lock) {
            peerSession?.close()
            peerSession = null
        }
    }

    override fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer, mirror: Boolean) {
        synchronized(lock) {
            ensureRendererInitialized(renderer, mirror)
            cameraMicCapture?.attachLocalSink(renderer)
        }
    }

    override fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        synchronized(lock) {
            cameraMicCapture?.detachLocalSink(renderer)
            initializedRenderers.remove(renderer)
        }
    }

    override fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        synchronized(lock) {
            boundRemoteVideoSinks.add(renderer)
            ensureRendererInitialized(renderer, mirror = false)
            peerSession?.attachRemoteVideoSink(renderer)
        }
    }

    override fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        synchronized(lock) {
            peerSession?.detachRemoteVideoSink(renderer)
            boundRemoteVideoSinks.remove(renderer)
            initializedRenderers.remove(renderer)
        }
    }

    private fun ensureRendererInitialized(renderer: SurfaceViewRenderer, mirror: Boolean) {
        if (eglBase == null) {
            val ctx = appContext ?: runCatching { renderer.context?.applicationContext }.getOrNull()
            if (ctx != null) {
                runCatching { initialize(ctx) }
            }
        }
        val eglContext = eglBase?.eglBaseContext ?: return
        if (!initializedRenderers.contains(renderer)) {
            runCatching {
                renderer.init(eglContext, null)
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer.setEnableHardwareScaler(true)
                initializedRenderers.add(renderer)
            }
        }
        runCatching {
            renderer.setMirror(mirror)
        }
    }

    override fun release() {
        synchronized(lock) {
            releaseInternal()
            status = status.copy(initialized = false)
        }
    }

    private fun releaseInternal() {
        runCatching { peerSession?.close() }
        peerSession = null
        runCatching { cameraMicCapture?.stopCapture() }
        cameraMicCapture = null
        initializedRenderers.clear()
        boundRemoteVideoSinks.clear()
        runCatching { peerConnectionFactory?.dispose() }
        peerConnectionFactory = null
        runCatching { audioDeviceModule?.release() }
        audioDeviceModule = null
        runCatching { eglBase?.release() }
        eglBase = null
    }
}
