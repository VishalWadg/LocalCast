package com.localcast.poc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.localcast.poc.model.SessionState
import com.localcast.poc.service.SessionRuntimeCoordinator
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.SurfaceViewRenderer

/**
 * Observable UI state holder for `PocScreen` across Milestones M0–M4.
 *
 * Delegates all WebRTC, Camera/Mic, ForegroundService, and Ktor WebSocket operations to
 * [SessionRuntimeCoordinator] so active streams survive Activity minimization or recreation.
 */
class PocViewModel(application: Application) : AndroidViewModel(application) {

    private val coordinator = SessionRuntimeCoordinator.getInstance(application)

    val state: StateFlow<SessionState> = coordinator.state

    fun onPermissionResult() {
        coordinator.refreshEnvironmentStatus()
    }

    fun initializeWebRtcEngine() {
        coordinator.initializeMediaEngine()
    }

    fun bindToWifi() {
        coordinator.bindWifiNetwork()
    }

    fun startHostingAndShare(port: Int = 8765) {
        coordinator.startHostingAndPreview(port)
    }

    fun connectToHost(hostIp: String, port: Int = 8765, joinCode: String = "") {
        coordinator.connectToHost(hostIp = hostIp, port = port, joinCode = joinCode)
    }

    fun approveJoinRequest() {
        coordinator.approvePendingJoinRequest()
    }

    fun rejectJoinRequest() {
        coordinator.rejectPendingJoinRequest()
    }

    fun toggleLocalMic() {
        coordinator.toggleLocalMicrophone()
    }

    fun toggleLocalCamera() {
        coordinator.toggleLocalCamera()
    }

    fun switchCamera() {
        coordinator.switchCamera()
    }

    fun sendPing() {
        coordinator.sendPing()
    }

    fun stopSession() {
        coordinator.stopSession()
    }

    fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        coordinator.bindLocalVideoRenderer(renderer)
    }

    fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        coordinator.unbindLocalVideoRenderer(renderer)
    }

    fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        coordinator.bindRemoteVideoRenderer(renderer)
    }

    fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        coordinator.unbindRemoteVideoRenderer(renderer)
    }
}
