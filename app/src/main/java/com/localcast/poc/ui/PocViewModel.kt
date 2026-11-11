package com.localcast.poc.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localcast.poc.model.DiscoveredHost
import com.localcast.poc.model.SessionState
import com.localcast.poc.service.SessionRuntimeCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * Observable UI state holder for `PocScreen` across Milestones M0–M5.
 *
 * Manages UI mode selection (Host vs Join), input validation, discovered host selection,
 * collapsible diagnostics, and clipboard copy operations, delegating WebRTC, Camera/Mic,
 * NSD discovery/advertisement, ForegroundService, and Ktor WebSocket operations to
 * [SessionRuntimeCoordinator].
 */
class PocViewModel @JvmOverloads constructor(
    application: Application,
    coordinator: SessionRuntimeCoordinator? = null
) : AndroidViewModel(application) {

    private val actualCoordinator: SessionRuntimeCoordinator by lazy {
        coordinator ?: SessionRuntimeCoordinator.getInstance(application)
    }

    val state: StateFlow<SessionState> by lazy {
        actualCoordinator.state
    }

    private val _uiState = MutableStateFlow(PocUiState())
    val uiState: StateFlow<PocUiState> = _uiState.asStateFlow()

    // --- UI State Handling (Requirement R3 + M5) ---

    fun selectMode(mode: UiMode) {
        _uiState.update { it.copy(selectedMode = mode, inputError = null) }
    }

    fun selectTab(tab: SetupTab) = selectMode(tab)

    fun onTabSelected(tab: UiMode) = selectMode(tab)

    fun updateHostIp(ip: String) {
        _uiState.update { it.copy(hostIpInput = ip, inputError = null) }
    }

    fun onTargetIpChanged(ip: String) = updateHostIp(ip)

    fun updateHostPort(port: String) {
        _uiState.update { it.copy(hostPortInput = port, inputError = null) }
    }

    fun updateTargetPort(port: String) = updateHostPort(port)

    fun onTargetPortChanged(port: String) = updateHostPort(port)

    fun updateJoinCode(code: String) {
        _uiState.update { it.copy(joinCodeInput = code) }
    }

    fun onJoinCodeChanged(code: String) = updateJoinCode(code)

    /**
     * M5: Populates target IPv4 address and signaling port from a tapped NSD-discovered host.
     */
    fun selectDiscoveredHost(host: DiscoveredHost) {
        _uiState.update {
            it.copy(
                hostIpInput = host.hostAddress,
                hostPortInput = host.port.toString(),
                inputError = null
            )
        }
    }

    fun toggleDiagnostics() {
        _uiState.update { it.copy(isDiagnosticsExpanded = !it.isDiagnosticsExpanded) }
    }

    fun onToggleDiagnostics() = toggleDiagnostics()

    fun setDiagnosticsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isDiagnosticsExpanded = expanded) }
    }

    fun setInputError(error: String?) {
        _uiState.update { it.copy(inputError = error) }
    }

    fun clearInputError() {
        _uiState.update { it.copy(inputError = null) }
    }

    fun setIpCopied(copied: Boolean) {
        _uiState.update { it.copy(isIpCopied = copied) }
    }

    fun copyHostIpToClipboard(customIp: String? = null): Boolean {
        val ipToCopy = customIp?.takeIf { it.isNotBlank() }
            ?: runCatching { actualCoordinator.state.value.wifiStatus.localIpv4Address }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return false

        runCatching {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("LocalCast Host IP", ipToCopy)
            clipboard?.setPrimaryClip(clip)
        }

        _uiState.update { it.copy(isIpCopied = true) }
        viewModelScope.launch {
            delay(2000)
            _uiState.update { it.copy(isIpCopied = false) }
        }
        return true
    }

    fun onCopyIpClicked() {
        copyHostIpToClipboard()
    }

    fun validateJoinInputs(): Boolean {
        val current = _uiState.value
        val (ipValid, ipError) = UiValidators.validateHostIp(current.hostIpInput)
        if (!ipValid) {
            _uiState.update { it.copy(inputError = ipError) }
            return false
        }
        val (portValid, portError) = UiValidators.validateSignalingPort(current.hostPortInput)
        if (!portValid) {
            _uiState.update { it.copy(inputError = portError) }
            return false
        }
        val (codeValid, codeError) = UiValidators.validateJoinCode(current.joinCodeInput)
        if (!codeValid) {
            _uiState.update { it.copy(inputError = codeError) }
            return false
        }
        _uiState.update { it.copy(inputError = null) }
        return true
    }

    // --- Delegation to SessionRuntimeCoordinator ---

    fun onPermissionResult() {
        actualCoordinator.refreshEnvironmentStatus()
    }

    fun initializeWebRtcEngine() {
        actualCoordinator.initializeMediaEngine()
    }

    fun bindToWifi() {
        actualCoordinator.bindWifiNetwork()
    }

    fun startDiscovery(forceRefresh: Boolean = false) {
        actualCoordinator.startDiscovery(forceRefresh = forceRefresh)
    }

    fun stopDiscovery() {
        actualCoordinator.stopDiscovery()
    }

    fun refreshDiscovery() {
        actualCoordinator.startDiscovery(forceRefresh = true)
    }

    fun startHostingAndShare(port: Int = 8765) {
        actualCoordinator.startHostingAndPreview(port)
    }

    fun connectToHost(hostIp: String, port: Int = 8765, joinCode: String = "") {
        actualCoordinator.connectToHost(hostIp = hostIp, port = port, joinCode = joinCode)
    }

    fun approveJoinRequest() {
        actualCoordinator.approvePendingJoinRequest()
    }

    fun rejectJoinRequest() {
        actualCoordinator.rejectPendingJoinRequest()
    }

    fun toggleLocalMic() {
        actualCoordinator.toggleLocalMicrophone()
    }

    fun toggleLocalCamera() {
        actualCoordinator.toggleLocalCamera()
    }

    fun switchCamera() {
        actualCoordinator.switchCamera()
    }

    fun sendPing() {
        actualCoordinator.sendPing()
    }

    fun stopSession() {
        actualCoordinator.stopSession()
    }

    fun bindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        actualCoordinator.bindLocalVideoRenderer(renderer)
    }

    fun unbindLocalVideoRenderer(renderer: SurfaceViewRenderer) {
        actualCoordinator.unbindLocalVideoRenderer(renderer)
    }

    fun bindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        actualCoordinator.bindRemoteVideoRenderer(renderer)
    }

    fun unbindRemoteVideoRenderer(renderer: SurfaceViewRenderer) {
        actualCoordinator.unbindRemoteVideoRenderer(renderer)
    }
}
