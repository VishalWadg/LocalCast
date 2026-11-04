package com.localcast.poc.capture

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArraySet

/**
 * M3: Manages Camera2 video capture and Microphone audio capture tracks on Phone A.
 *
 * Target profile (Spec Section 7):
 * - Primary: 1280x720 @ 30 FPS
 * - Fallback: 640x480 @ 30 FPS if 720p fails on the device camera
 */
class CameraMicCapture(
    context: Context,
    private val factory: PeerConnectionFactory,
    private val eglBaseContext: EglBase.Context,
    private val onDiagnosticLog: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val localSinks = CopyOnWriteArraySet<VideoSink>()

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    var localVideoTrack: VideoTrack? = null
        private set

    private var audioSource: AudioSource? = null
    var localAudioTrack: AudioTrack? = null
        private set

    @Volatile
    var isFrontCamera: Boolean = true
        private set

    @Volatile
    var activeProfileDescription: String = "Stopped"
        private set

    @Volatile
    var isCameraEnabled: Boolean = true
        private set

    @Volatile
    var isMicEnabled: Boolean = true
        private set

    /**
     * Starts local Camera2 video capture (with 720p -> 480p fallback) and microphone audio track.
     */
    @Synchronized
    fun startCapture(preferFrontCamera: Boolean = true): Boolean {
        stopCapture()
        return try {
            // 1. Setup Camera2 video capturer
            val enumerator = Camera2Enumerator(appContext)
            val deviceNames = enumerator.deviceNames
            val chosenCameraName = selectCameraName(enumerator, deviceNames, preferFrontCamera)
                ?: throw IllegalStateException("No available camera found on this device")

            isFrontCamera = enumerator.isFrontFacing(chosenCameraName)

            val capturer = enumerator.createCapturer(
                chosenCameraName,
                object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(errorDescription: String?) {
                        onDiagnosticLog("Camera error: ${errorDescription ?: "unknown"}")
                    }

                    override fun onCameraDisconnected() {
                        onDiagnosticLog("Camera disconnected")
                    }

                    override fun onCameraFreezed(errorDescription: String?) {
                        onDiagnosticLog("Camera freezed: ${errorDescription ?: "unknown"}")
                    }

                    override fun onCameraOpening(cameraName: String?) {
                        onDiagnosticLog("Opening camera: $cameraName")
                    }

                    override fun onFirstFrameAvailable() {
                        onDiagnosticLog("First camera frame available ($activeProfileDescription)")
                    }

                    override fun onCameraClosed() {
                        onDiagnosticLog("Camera closed")
                    }
                }
            ) ?: throw IllegalStateException("Failed to create Camera2 capturer for $chosenCameraName")

            val textureHelper = SurfaceTextureHelper.create("LocalCastCaptureThread", eglBaseContext)
                ?: throw IllegalStateException("Failed to create SurfaceTextureHelper")

            val vSource = factory.createVideoSource(capturer.isScreencast)
            capturer.initialize(textureHelper, appContext, vSource.capturerObserver)

            // Start at 720p@30fps, fallback to 480p@30fps per Spec Section 7
            activeProfileDescription = try {
                capturer.startCapture(WIDTH_720P, HEIGHT_720P, FPS_DEFAULT)
                "720p @ 30fps"
            } catch (e: Exception) {
                onDiagnosticLog("720p capture failed (${e.message}), falling back to 480p@30fps")
                capturer.startCapture(WIDTH_480P, HEIGHT_480P, FPS_DEFAULT)
                "480p @ 30fps (fallback)"
            }

            val vTrack = factory.createVideoTrack(LOCAL_VIDEO_TRACK_ID, vSource).apply {
                setEnabled(true)
            }
            for (sink in localSinks) {
                vTrack.addSink(sink)
            }

            // 2. Setup Microphone audio source & track with AEC / NS / AGC constraints
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val aSource = factory.createAudioSource(audioConstraints)
            val aTrack = factory.createAudioTrack(LOCAL_AUDIO_TRACK_ID, aSource).apply {
                setEnabled(true)
            }

            surfaceTextureHelper = textureHelper
            videoCapturer = capturer
            videoSource = vSource
            localVideoTrack = vTrack
            audioSource = aSource
            localAudioTrack = aTrack
            isCameraEnabled = true
            isMicEnabled = true

            onDiagnosticLog("Started local capture ($activeProfileDescription, front=$isFrontCamera)")
            true
        } catch (t: Throwable) {
            onDiagnosticLog("Capture startup failed: ${t.message}")
            stopCapture()
            false
        }
    }

    /**
     * Switches between front and rear cameras without recreating the WebRTC video track.
     */
    fun switchCamera(onSwitched: (Boolean) -> Unit = {}) {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                onDiagnosticLog("Switched camera (isFrontCamera=$isFront)")
                onSwitched(isFront)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                onDiagnosticLog("Camera switch failed: $errorDescription")
            }
        })
    }

    fun setCameraEnabled(enabled: Boolean) {
        isCameraEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
        onDiagnosticLog("Local video track enabled=$enabled")
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        isMicEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
        onDiagnosticLog("Local microphone track enabled=$enabled")
    }

    fun attachLocalSink(sink: VideoSink) {
        localSinks.add(sink)
        localVideoTrack?.addSink(sink)
    }

    fun detachLocalSink(sink: VideoSink) {
        localSinks.remove(sink)
        runCatching { localVideoTrack?.removeSink(sink) }
    }

    /**
     * Idempotent teardown of camera and microphone capture resources.
     */
    @Synchronized
    fun stopCapture() {
        val vTrack = localVideoTrack
        localVideoTrack = null
        if (vTrack != null) {
            for (sink in localSinks) {
                runCatching { vTrack.removeSink(sink) }
            }
            runCatching { vTrack.dispose() }
        }

        val capturer = videoCapturer
        videoCapturer = null
        if (capturer != null) {
            runCatching { capturer.stopCapture() }
            runCatching { capturer.dispose() }
        }

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceTextureHelper?.dispose() }
        surfaceTextureHelper = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null

        runCatching { audioSource?.dispose() }
        audioSource = null

        activeProfileDescription = "Stopped"
    }

    private fun selectCameraName(
        enumerator: Camera2Enumerator,
        names: Array<String>,
        preferFront: Boolean
    ): String? {
        val preferred = names.firstOrNull { name ->
            if (preferFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        }
        return preferred ?: names.firstOrNull()
    }

    companion object {
        const val LOCAL_VIDEO_TRACK_ID = "localcast_video_0"
        const val LOCAL_AUDIO_TRACK_ID = "localcast_audio_0"
        const val LOCAL_STREAM_ID = "localcast_stream_0"

        const val WIDTH_720P = 1280
        const val HEIGHT_720P = 720
        const val WIDTH_480P = 640
        const val HEIGHT_480P = 480
        const val FPS_DEFAULT = 30
    }
}
