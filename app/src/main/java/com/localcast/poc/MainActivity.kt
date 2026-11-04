package com.localcast.poc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.poc.network.LocalNetworkPermissionHelper
import com.localcast.poc.ui.PocScreen
import com.localcast.poc.ui.PocViewModel

/**
 * Main entry-point Activity for LocalCast POC v0.1 (Milestones M0–M4).
 *
 * Responsibilities:
 * - Requests runtime permissions (`CAMERA`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` on API 33+,
 *   and `ACCESS_LOCAL_NETWORK` conditionally ONLY on Android 17 / API 37+) from a visible state
 *   before starting capture or the foreground service.
 * - Renders [PocScreen] and delegates all media/session actions to [PocViewModel] ->
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
                        val needed = LocalNetworkPermissionHelper.requiredPublisherPermissions()
                        pendingPostPermissionAction = { viewModel.startHostingAndShare() }
                        multiPermissionLauncher.launch(needed.toTypedArray())
                    },
                    onRequestPermissionsAndConnectClient = { ip, code ->
                        val needed = LocalNetworkPermissionHelper.requiredReceiverPermissions()
                        if (needed.isEmpty()) {
                            viewModel.connectToHost(ip, 8765, code)
                        } else {
                            pendingPostPermissionAction = { viewModel.connectToHost(ip, 8765, code) }
                            multiPermissionLauncher.launch(needed.toTypedArray())
                        }
                    },
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
                    onUnbindRemoteRenderer = { viewModel.unbindRemoteVideoRenderer(it) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onPermissionResult()
    }
}
