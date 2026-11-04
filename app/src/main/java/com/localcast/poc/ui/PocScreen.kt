package com.localcast.poc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.localcast.poc.model.SessionPhase
import com.localcast.poc.model.SessionRole
import com.localcast.poc.model.SessionState
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PocScreen(
    state: SessionState,
    onRequestPermissionsAndStartHost: () -> Unit,
    onRequestPermissionsAndConnectClient: (hostIp: String, joinCode: String) -> Unit,
    onInitWebRtc: () -> Unit,
    onBindWifi: () -> Unit,
    onApproveJoin: () -> Unit,
    onRejectJoin: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSendPing: () -> Unit,
    onStopSession: () -> Unit,
    onBindLocalRenderer: (SurfaceViewRenderer) -> Unit,
    onUnbindLocalRenderer: (SurfaceViewRenderer) -> Unit,
    onBindRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    onUnbindRemoteRenderer: (SurfaceViewRenderer) -> Unit
) {
    var remoteIpInput by rememberSaveable { mutableStateOf("") }
    var joinCodeInput by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF6F8FB)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Text(
                text = "LocalCast",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0D254C)
            )
            Text(
                text = "POC v0.1 (M0–M4) · Direct Wi-Fi WebRTC Camera/Mic + Foreground Service · SDK 37",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4A607A)
            )

            // Status Banner Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.phase) {
                        SessionPhase.CONNECTED -> Color(0xFFE6F4EA)
                        SessionPhase.FAILED -> Color(0xFFFCE8E6)
                        else -> Color(0xFFE8F0FE)
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Phase: ${state.phase.name} · Role: ${state.role.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0D254C)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.statusBanner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF1F3044)
                    )
                    if (!state.lastError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error: ${state.lastError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB3261E),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // M3 Video Viewport Card (shown when Phone A is capturing or Phone B is connected/receiving)
            if (state.mediaStatus.isCapturingLocal || state.role == SessionRole.CLIENT_RECEIVER) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.role == SessionRole.HOST_PUBLISHER) {
                            Text(
                                text = "Phone A — Local Camera Preview (${state.mediaStatus.captureProfile})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            WebRtcVideoRendererBox(
                                isFrontMirror = state.mediaStatus.isFrontCamera,
                                onBind = onBindLocalRenderer,
                                onUnbind = onUnbindLocalRenderer,
                                overlayLabel = if (state.mediaStatus.localCameraEnabled) {
                                    "Publishing (${if (state.mediaStatus.isFrontCamera) "Front" else "Rear"})"
                                } else {
                                    "Camera Paused"
                                }
                            )
                            // Publisher track controls
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onToggleMic,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (state.mediaStatus.localMicEnabled) "Mute Mic" else "Unmute Mic")
                                }
                                Button(
                                    onClick = onToggleCamera,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (state.mediaStatus.localCameraEnabled) "Pause Cam" else "Resume Cam")
                                }
                                OutlinedButton(
                                    onClick = onSwitchCamera,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Flip Cam")
                                }
                            }
                        } else if (state.role == SessionRole.CLIENT_RECEIVER) {
                            Text(
                                text = "Phone B — Incoming Remote Video & Audio",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            WebRtcVideoRendererBox(
                                isFrontMirror = false,
                                onBind = onBindRemoteRenderer,
                                onUnbind = onUnbindRemoteRenderer,
                                overlayLabel = when {
                                    !state.mediaStatus.remoteVideoAvailable -> "Waiting for remote video track..."
                                    !state.mediaStatus.remoteCameraEnabled -> "Host paused camera"
                                    else -> "LIVE · Host Mic: ${if (state.mediaStatus.remoteMicEnabled) "ON" else "MUTED"}"
                                }
                            )
                        }
                    }
                }
            }

            // M1 + M2 + M3 + M4 Session Controls Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Session Controls (Phone A Host / Phone B Receiver)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D254C)
                    )

                    // Phone A: Start Sharing / Hosting button
                    Button(
                        onClick = onRequestPermissionsAndStartHost,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.role == SessionRole.NONE
                    ) {
                        Text("Start Hosting & Share Camera/Mic (Phone A)")
                    }

                    if (state.role == SessionRole.HOST_PUBLISHER && state.hostEndpointUrl != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Enter this IP on Phone B: ${state.wifiStatus.localIpv4Address ?: "unknown"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0D254C)
                            )
                            Text(
                                text = "Endpoint: ${state.hostEndpointUrl} · Join Code: ${state.sessionCode}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF334155)
                            )
                            Text(
                                text = "M4 Foreground Service: ${if (state.foregroundServiceStatus.isRunning) "ACTIVE (${state.foregroundServiceStatus.activeServiceTypes})" else "Starting..."}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF137333)
                            )
                        }
                    }

                    // Pending Join Approval Card on Phone A
                    val pending = state.pendingJoinRequest
                    if (pending != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Incoming Join Request: ${pending.remoteDeviceName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Device ID: ${pending.remoteDeviceId} · Code: ${pending.providedSessionCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onApproveJoin,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Approve & Stream")
                                    }
                                    OutlinedButton(
                                        onClick = onRejectJoin,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Reject")
                                    }
                                }
                            }
                        }
                    }

                    // Phone B: Connect to Host controls
                    if (state.role == SessionRole.NONE) {
                        OutlinedTextField(
                            value = remoteIpInput,
                            onValueChange = { remoteIpInput = it },
                            label = { Text("Phone A Wi-Fi IP Address (e.g. 192.168.1.10)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = joinCodeInput,
                            onValueChange = { joinCodeInput = it },
                            label = { Text("Optional 6-Digit Join Code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = { onRequestPermissionsAndConnectClient(remoteIpInput, joinCodeInput) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Connect to Host (Phone B)")
                        }
                    }

                    // Active session actions (PING / Stop Session)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onSendPing,
                            modifier = Modifier.weight(1f),
                            enabled = state.role != SessionRole.NONE
                        ) {
                            Text("Send PING")
                        }
                        OutlinedButton(
                            onClick = onStopSession,
                            modifier = Modifier.weight(1f),
                            enabled = state.role != SessionRole.NONE
                        ) {
                            Text("Stop Session")
                        }
                    }
                }
            }

            // M0 + M1 + M2 + M4 Telemetry Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "M0–M4 Infrastructure Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D254C)
                    )
                    val engine = state.mediaEngineStatus
                    Text(
                        text = "M0 WebRTC: ${if (engine.initialized) "OK (${engine.primaryAbi}, LocalICE=${engine.localOnlyIceConfigured})" else "Uninitialized"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "M1 Perms: ${state.permissions.policySummary}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "M1 Wi-Fi: ${state.wifiStatus.statusMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    val rtc = state.rtcStatus
                    Text(
                        text = "M2 ICE/PC: ICE=${rtc.iceConnectionState} · PC=${rtc.peerConnectionState} · " +
                            "TxCand=${rtc.localCandidatesSent} · RxCand=${rtc.remoteCandidatesReceived} · " +
                            "QueuedFlushed=${rtc.queuedCandidatesFlushed} · Type=${rtc.lastCandidateType}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    val ping = state.pingPongStats
                    Text(
                        text = "M1 PING/PONG: Tx=${ping.pingsSent} · Rx=${ping.pongsReceived} · " +
                            "Last RTT=${ping.lastRttMs?.let { "${it}ms" } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onInitWebRtc,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Re-check M0")
                        }
                        OutlinedButton(
                            onClick = onBindWifi,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bind Wi-Fi (M1)")
                        }
                    }
                }
            }

            // Diagnostic Log Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Diagnostic Event Log (${state.diagnosticLogs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                    val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    for (entry in state.diagnosticLogs.take(25)) {
                        val ts = timeFmt.format(Date(entry.timestampMs))
                        Text(
                            text = "$ts [${entry.tag}] ${entry.message}",
                            color = Color(0xFFCBD5E1),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * M3: Hosts WebRTC's `SurfaceViewRenderer` inside Jetpack Compose `AndroidView` (Spec Section 7),
 * safely releasing the renderer when removed from composition.
 */
@Composable
private fun WebRtcVideoRendererBox(
    isFrontMirror: Boolean,
    onBind: (SurfaceViewRenderer) -> Unit,
    onUnbind: (SurfaceViewRenderer) -> Unit,
    overlayLabel: String
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context)
    }

    DisposableEffect(renderer) {
        onBind(renderer)
        onDispose {
            onUnbind(renderer)
            runCatching { renderer.release() }
        }
    }

    DisposableEffect(isFrontMirror) {
        runCatching { renderer.setMirror(isFrontMirror) }
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = overlayLabel,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
