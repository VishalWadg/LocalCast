package com.localcast.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.poc.model.SessionRole
import com.localcast.poc.network.LocalNetworkPermissionHelper
import com.localcast.poc.ui.PocScreen
import com.localcast.poc.ui.PocViewModel
import com.localcast.poc.ui.UiMode

/**
 * Main entry-point Activity for LocalCast POC v0.1 (Milestones M0–M5).
 *
 * Responsibilities:
 * - Requests runtime permissions (`CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` on API 33+,
 *   and `ACCESS_LOCAL_NETWORK` conditionally ONLY on Android 17 / API 37+) from a visible state
 *   before starting capture or the foreground service, while keeping Join discovery permission
 *   readiness separated from streaming/notification prompts.
 * - Renders [PocScreen] and delegates all media/session/discovery actions to [PocViewModel] ->
 *   `SessionRuntimeCoordinator`, so minimizing the Activity does not interrupt an active stream.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PocViewModel by viewModels()

    private var pendingPostPermissionAction: (() -> Unit)? = null

    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onPermissionResult()
        val action = pendingPostPermissionAction
        pendingPostPermissionAction = null
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize M0 WebRTC native engine on launch
        viewModel.initializeWebRtcEngine()

        setContent {
            MaterialTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                PocScreen(
                    state = state,
                    onRequestPermissionsAndStartHost = {
                        val needed = LocalNetworkPermissionHelper.filterUngrantedPermissions(
                            this,
                            LocalNetworkPermissionHelper.requiredPublisherPermissions()
                        )
                        if (needed.isEmpty()) {
                            viewModel.startHostingAndShare()
                        } else {
                            pendingPostPermissionAction = { viewModel.startHostingAndShare() }
                            multiPermissionLauncher.launch(needed.toTypedArray())
                        }
                    },
                    onRequestPermissionsAndConnectClient = { ip, port, code ->
                        val needed = LocalNetworkPermissionHelper.filterUngrantedPermissions(
                            this,
                            LocalNetworkPermissionHelper.requiredReceiverPermissions()
                        )
                        if (needed.isEmpty()) {
                            viewModel.connectToHost(ip, port, code)
                        } else {
                            pendingPostPermissionAction = { viewModel.connectToHost(ip, port, code) }
                            multiPermissionLauncher.launch(needed.toTypedArray())
                        }
                    },
                    onRequestDiscoveryPermissions = {
                        val needed = LocalNetworkPermissionHelper.filterUngrantedPermissions(
                            this,
                            LocalNetworkPermissionHelper.requiredDiscoveryPermissions()
                        )
                        if (needed.isEmpty()) {
                            viewModel.startDiscovery()
                        } else {
                            pendingPostPermissionAction = { viewModel.startDiscovery() }
                            multiPermissionLauncher.launch(needed.toTypedArray())
                        }
                    },
                    onStartDiscovery = { viewModel.startDiscovery() },
                    onStopDiscovery = { viewModel.stopDiscovery() },
                    onRefreshDiscovery = { viewModel.refreshDiscovery() },
                    onInitWebRtc = { viewModel.initializeWebRtcEngine() },
                    onBindWifi = { viewModel.bindToWifi() },
                    onApproveJoin = { viewModel.approveJoinRequest() },
                    onRejectJoin = { viewModel.rejectJoinRequest() },
                    onToggleMic = { viewModel.toggleLocalMic() },
                    onToggleCamera = { viewModel.toggleLocalCamera() },
                    onSwitchCamera = { viewModel.switchCamera() },
                    onSendPing = { viewModel.sendPing() },
                    onStopSession = { viewModel.stopSession() },
                    onBindLocalRenderer = { viewModel.bindLocalVideoRenderer(it) },
                    onUnbindLocalRenderer = { viewModel.unbindLocalVideoRenderer(it) },
                    onBindRemoteRenderer = { viewModel.bindRemoteVideoRenderer(it) },
                    onUnbindRemoteRenderer = { viewModel.unbindRemoteVideoRenderer(it) },
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onPermissionResult()
        if (viewModel.uiState.value.selectedMode == UiMode.JOIN &&
            viewModel.state.value.role == SessionRole.NONE &&
            LocalNetworkPermissionHelper.isDiscoveryReady(this)
        ) {
            viewModel.startDiscovery()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.stopDiscovery()
        }
    }
}
