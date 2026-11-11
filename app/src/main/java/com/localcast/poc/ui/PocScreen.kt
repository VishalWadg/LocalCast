package com.localcast.poc.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.poc.model.DiscoveredHost
import com.localcast.poc.model.DiscoveryState
import com.localcast.poc.model.DiscoveryStatus
import com.localcast.poc.model.SessionPhase
import com.localcast.poc.model.SessionRole
import com.localcast.poc.model.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Streamlined, user-friendly UI for LocalCast POC v0.1 (Milestones M0–M5).
 *
 * Key features:
 * - Segmented tab selector switching between "Host" and "Join" modes.
 * - Prominent Host local IP display with one-tap clipboard copy and mDNS advertisement badge.
 * - ZeroConf / mDNS auto-discovery host list in Join mode with one-tap IP/port population
 *   plus manual IPv4 and port fallback fields.
 * - Responsive 16:9 unobstructed video viewport for remote and local preview.
 * - Floating circular media controls (Mic, Camera, Flip, End Session).
 * - Expandable "Advanced Diagnostics" accordion at the bottom (collapsed by default),
 *   preserving 100% of M0–M5 infrastructure telemetry and event logs without clutter.
 */
@Composable
fun PocScreen(
    state: SessionState,
    onRequestPermissionsAndStartHost: () -> Unit,
    onRequestPermissionsAndConnectClient: (hostIp: String, targetPort: Int, joinCode: String) -> Unit,
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
    onUnbindRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    onRequestDiscoveryPermissions: () -> Unit = {},
    onStartDiscovery: () -> Unit = {},
    onStopDiscovery: () -> Unit = {},
    onRefreshDiscovery: () -> Unit = {},
    viewModel: PocViewModel? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // State binding: observe from ViewModel if supplied, else fallback to rememberSaveable
    val vmUiState = viewModel?.uiState?.collectAsStateWithLifecycle()?.value
    var localUiState by rememberSaveable(stateSaver = PocUiState.Saver) { mutableStateOf(PocUiState()) }
    val uiState = vmUiState ?: localUiState
    var hasPromptedDiscoveryPermission by rememberSaveable { mutableStateOf(false) }

    val shouldDiscover = state.role == SessionRole.NONE && uiState.selectedMode == UiMode.JOIN
    LaunchedEffect(shouldDiscover, state.permissions.localNetworkGranted) {
        if (shouldDiscover) {
            if (state.permissions.localNetworkGranted) {
                onStartDiscovery()
            } else if (!hasPromptedDiscoveryPermission) {
                hasPromptedDiscoveryPermission = true
                onRequestDiscoveryPermissions()
            } else {
                // Ensure UI surfaces PERMISSION_REQUIRED cleanly without re-prompting in a loop
                onStartDiscovery()
            }
        } else if (state.discoveryState.isBrowsing || state.discoveryState.status != DiscoveryStatus.IDLE) {
            onStopDiscovery()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val isConfigChange = (context as? Activity)?.isChangingConfigurations == true
            if (!isConfigChange) {
                onStopDiscovery()
            }
        }
    }

    val onSelectMode: (UiMode) -> Unit = { mode ->
        if (viewModel != null) viewModel.selectMode(mode)
        else localUiState = localUiState.copy(selectedMode = mode, inputError = null)
    }

    val onIpChanged: (String) -> Unit = { ip ->
        if (viewModel != null) viewModel.updateHostIp(ip)
        else localUiState = localUiState.copy(hostIpInput = ip, inputError = null)
    }

    val onPortChanged: (String) -> Unit = { port ->
        if (viewModel != null) viewModel.updateHostPort(port)
        else localUiState = localUiState.copy(hostPortInput = port, inputError = null)
    }

    val onJoinCodeChanged: (String) -> Unit = { code ->
        if (viewModel != null) viewModel.updateJoinCode(code)
        else localUiState = localUiState.copy(joinCodeInput = code)
    }

    val onSelectDiscoveredHost: (DiscoveredHost) -> Unit = { host ->
        if (viewModel != null) {
            viewModel.selectDiscoveredHost(host)
        } else {
            localUiState = localUiState.copy(
                hostIpInput = host.hostAddress,
                hostPortInput = host.port.toString(),
                inputError = null
            )
        }
    }

    val onToggleDiagnostics: () -> Unit = {
        if (viewModel != null) viewModel.toggleDiagnostics()
        else localUiState = localUiState.copy(isDiagnosticsExpanded = !localUiState.isDiagnosticsExpanded)
    }

    val onCopyIp: (String) -> Unit = { ip ->
        clipboardManager.setText(AnnotatedString(ip))
        if (viewModel != null) {
            viewModel.setIpCopied(true)
            coroutineScope.launch {
                delay(2000)
                viewModel.setIpCopied(false)
            }
        } else {
            localUiState = localUiState.copy(isIpCopied = true)
            coroutineScope.launch {
                delay(2000)
                localUiState = localUiState.copy(isIpCopied = false)
            }
        }
    }

    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFF8FAFC)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: App Title, Subtitle, and Connection Status Badge
            HeaderSection(state = state)

            // Optional Error Banner
            if (!state.lastError.isNullOrBlank()) {
                ErrorBanner(errorMessage = state.lastError)
            }

            // Dual Mode Setup vs. Active Streaming View
            if (state.role == SessionRole.NONE) {
                // Segmented Tab Selector: "Host" vs "Join"
                SegmentedTabSelector(
                    selectedMode = uiState.selectedMode,
                    onModeSelected = onSelectMode
                )

                when (uiState.selectedMode) {
                    UiMode.HOST -> {
                        HostSetupCard(
                            localIp = state.wifiStatus.localIpv4Address,
                            isIpCopied = uiState.isIpCopied,
                            onCopyIp = onCopyIp,
                            onStartHosting = onRequestPermissionsAndStartHost
                        )
                    }
                    UiMode.JOIN -> {
                        JoinSetupCard(
                            discoveryState = state.discoveryState,
                            onSelectDiscoveredHost = onSelectDiscoveredHost,
                            onRequestDiscoveryPermissions = onRequestDiscoveryPermissions,
                            onRefreshDiscovery = onRefreshDiscovery,
                            targetIp = uiState.hostIpInput,
                            onTargetIpChanged = onIpChanged,
                            targetPort = uiState.hostPortInput,
                            onTargetPortChanged = onPortChanged,
                            joinCode = uiState.joinCodeInput,
                            onJoinCodeChanged = onJoinCodeChanged,
                            inputError = uiState.inputError,
                            onConnect = {
                                val (ipValid, ipErr) = UiValidators.validateHostIp(uiState.hostIpInput)
                                if (!ipValid) {
                                    viewModel?.setInputError(ipErr) ?: run { localUiState = localUiState.copy(inputError = ipErr) }
                                    return@JoinSetupCard
                                }
                                val (portValid, portErr) = UiValidators.validateSignalingPort(uiState.hostPortInput)
                                if (!portValid) {
                                    viewModel?.setInputError(portErr) ?: run { localUiState = localUiState.copy(inputError = portErr) }
                                    return@JoinSetupCard
                                }
                                val (codeValid, codeErr) = UiValidators.validateJoinCode(uiState.joinCodeInput)
                                if (!codeValid) {
                                    viewModel?.setInputError(codeErr) ?: run { localUiState = localUiState.copy(inputError = codeErr) }
                                    return@JoinSetupCard
                                }
                                viewModel?.clearInputError() ?: run { localUiState = localUiState.copy(inputError = null) }
                                val parsedPort = uiState.hostPortInput.trim().toIntOrNull() ?: 8765
                                onRequestPermissionsAndConnectClient(
                                    uiState.hostIpInput.trim(),
                                    parsedPort,
                                    uiState.joinCodeInput.trim()
                                )
                            }
                        )
                    }
                }
            } else {
                // Active Session Flow: Waiting, Approvals, or Live Streaming
                if (state.role == SessionRole.HOST_PUBLISHER && state.phase != SessionPhase.CONNECTED) {
                    HostWaitingBanner(
                        localIp = state.wifiStatus.localIpv4Address,
                        sessionCode = state.sessionCode,
                        isIpCopied = uiState.isIpCopied,
                        isAdvertising = state.discoveryState.isAdvertising,
                        advertisedServiceName = state.discoveryState.advertisedServiceName,
                        onCopyIp = onCopyIp
                    )
                }

                // Incoming Participant Join Request Dialog / Card
                val pending = state.pendingJoinRequest
                if (pending != null) {
                    PendingJoinApprovalCard(
                        deviceName = pending.remoteDeviceName,
                        deviceId = pending.remoteDeviceId,
                        joinCode = pending.providedSessionCode,
                        codeMatched = pending.codeMatched,
                        onApprove = onApproveJoin,
                        onReject = onRejectJoin
                    )
                }

                // Active Stream Viewport & Media Controls
                ActiveStreamView(
                    state = state,
                    onToggleMic = onToggleMic,
                    onToggleCamera = onToggleCamera,
                    onSwitchCamera = onSwitchCamera,
                    onEndSession = onStopSession,
                    onBindLocalRenderer = onBindLocalRenderer,
                    onUnbindLocalRenderer = onUnbindLocalRenderer,
                    onBindRemoteRenderer = onBindRemoteRenderer,
                    onUnbindRemoteRenderer = onUnbindRemoteRenderer
                )
            }

            // Advanced Diagnostics Accordion (Collapsed by Default)
            DiagnosticsAccordion(
                state = state,
                isExpanded = uiState.isDiagnosticsExpanded,
                onToggleExpand = onToggleDiagnostics,
                onSendPing = onSendPing,
                onInitWebRtc = onInitWebRtc,
                onBindWifi = onBindWifi
            )
        }
    }
}

/**
 * Top application header displaying brand title and a status badge.
 */
@Composable
private fun HeaderSection(state: SessionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "LocalCast",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Text(
                text = "Direct Local Streaming",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B)
            )
        }

        // Status Badge Pill
        val (badgeBg, badgeText, badgeTextColor) = when (state.phase) {
            SessionPhase.CONNECTED -> Triple(Color(0xFFDCFCE7), "● LIVE", Color(0xFF166534))
            SessionPhase.WAITING_FOR_APPROVAL,
            SessionPhase.CONNECTING_SIGNALING,
            SessionPhase.NEGOTIATING -> Triple(Color(0xFFDBEAFE), "● Connecting", Color(0xFF1E40AF))
            SessionPhase.FAILED -> Triple(Color(0xFFFEE2E2), "● Error", Color(0xFF991B1B))
            else -> {
                val ip = state.wifiStatus.localIpv4Address
                if (ip != null) Triple(Color(0xFFF1F5F9), "● Ready", Color(0xFF334155))
                else Triple(Color(0xFFF1F5F9), "● Offline", Color(0xFF64748B))
            }
        }

        Box(
            modifier = Modifier
                .background(badgeBg, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = badgeText,
                color = badgeTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Visual error banner displayed when session errors occur.
 */
@Composable
private fun ErrorBanner(errorMessage: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFCA5A5)))
    ) {
        Text(
            text = "Error: $errorMessage",
            modifier = Modifier.padding(12.dp),
            color = Color(0xFF991B1B),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Segmented Tab Selector for "Host" vs "Join" modes.
 */
@Composable
private fun SegmentedTabSelector(
    selectedMode: UiMode,
    onModeSelected: (UiMode) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE2E8F0))
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Host Tab Button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selectedMode == UiMode.HOST) Color(0xFF0F172A) else Color.Transparent)
                    .clickable { onModeSelected(UiMode.HOST) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Host (Share Camera)",
                    color = if (selectedMode == UiMode.HOST) Color.White else Color(0xFF475569),
                    fontWeight = if (selectedMode == UiMode.HOST) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            }

            // Join Tab Button
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selectedMode == UiMode.JOIN) Color(0xFF0F172A) else Color.Transparent)
                    .clickable { onModeSelected(UiMode.JOIN) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Join (Watch Stream)",
                    color = if (selectedMode == UiMode.JOIN) Color.White else Color(0xFF475569),
                    fontWeight = if (selectedMode == UiMode.JOIN) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Host Mode Setup Card: Prominent Local IP, One-Tap Copy, and "Start Hosting" Action.
 */
@Composable
private fun HostSetupCard(
    localIp: String?,
    isIpCopied: Boolean,
    onCopyIp: (String) -> Unit,
    onStartHosting: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Host a Broadcast",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Text(
                text = "Stream your camera and microphone to another device on the same local Wi-Fi without internet.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF475569)
            )

            // Prominent Local IP Section with One-Tap Copy
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Your Local Wi-Fi IP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF64748B)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = localIp ?: "Detecting IP...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )

                    Button(
                        onClick = { localIp?.let(onCopyIp) },
                        enabled = localIp != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isIpCopied) Color(0xFF16A34A) else Color(0xFF0F172A),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isIpCopied) "✓ Copied!" else "Copy IP",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Primary Action Button
            Button(
                onClick = onStartHosting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
            ) {
                Text(
                    text = "Start Hosting & Stream",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Join Mode Setup Card: M5 Auto-Discovery Host List + Manual Target IP & Port Fallback.
 */
@Composable
private fun JoinSetupCard(
    discoveryState: DiscoveryState,
    onSelectDiscoveredHost: (DiscoveredHost) -> Unit,
    onRequestDiscoveryPermissions: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    targetIp: String,
    onTargetIpChanged: (String) -> Unit,
    targetPort: String,
    onTargetPortChanged: (String) -> Unit,
    joinCode: String,
    onJoinCodeChanged: (String) -> Unit,
    inputError: String?,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Connect to Host",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Text(
                text = "Tap a nearby LocalCast host discovered on your Wi-Fi, or enter Phone A's IP address manually.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF475569)
            )

            // M5: Auto-Discovery Nearby Hosts Panel
            NearbyHostsDiscoverySection(
                discoveryState = discoveryState,
                selectedIp = targetIp,
                selectedPort = targetPort,
                onSelectHost = onSelectDiscoveredHost,
                onRequestPermission = onRequestDiscoveryPermissions,
                onRefresh = onRefreshDiscovery
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFE2E8F0)
                )
                Text(
                    text = "Or enter IP manually",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.Medium
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFE2E8F0)
                )
            }

            // Target IP Field
            OutlinedTextField(
                value = targetIp,
                onValueChange = onTargetIpChanged,
                label = { Text("Host IP Address (e.g. 192.168.1.10)") },
                placeholder = { Text("192.168.1.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Target Port & Optional Join Code Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = targetPort,
                    onValueChange = onTargetPortChanged,
                    label = { Text("Port") },
                    placeholder = { Text("8765") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = joinCode,
                    onValueChange = onJoinCodeChanged,
                    label = { Text("Join Code (Optional)") },
                    placeholder = { Text("6 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.4f)
                )
            }

            // Validation Error Display
            if (inputError != null) {
                Text(
                    text = "⚠ $inputError",
                    color = Color(0xFFDC2626),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Primary Action Button
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
            ) {
                Text(
                    text = "Connect to Host",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * M5: Displays nearby `_localcast._tcp` hosts, discovery status, permission prompt, and manual fallback cue.
 */
@Composable
private fun NearbyHostsDiscoverySection(
    discoveryState: DiscoveryState,
    selectedIp: String,
    selectedPort: String,
    onSelectHost: (DiscoveredHost) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Nearby Hosts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                val (pillBg, pillText, pillFg) = when (discoveryState.status) {
                    DiscoveryStatus.SCANNING -> {
                        if (discoveryState.discoveredHosts.isEmpty()) {
                            Triple(Color(0xFFDBEAFE), "Scanning", Color(0xFF1E40AF))
                        } else {
                            Triple(Color(0xFFDCFCE7), "${discoveryState.discoveredHosts.size} found", Color(0xFF166534))
                        }
                    }
                    DiscoveryStatus.PERMISSION_REQUIRED -> Triple(Color(0xFFFEF3C7), "Permission", Color(0xFF92400E))
                    DiscoveryStatus.WIFI_UNAVAILABLE -> Triple(Color(0xFFF1F5F9), "No Wi-Fi", Color(0xFF475569))
                    DiscoveryStatus.ERROR -> Triple(Color(0xFFFEE2E2), "Error", Color(0xFF991B1B))
                    DiscoveryStatus.IDLE -> Triple(Color(0xFFF1F5F9), "Idle", Color(0xFF64748B))
                }
                Box(
                    modifier = Modifier
                        .background(pillBg, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = pillText,
                        color = pillFg,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (discoveryState.status != DiscoveryStatus.PERMISSION_REQUIRED) {
                TextButton(
                    onClick = onRefresh,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Refresh",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF2563EB)
                    )
                }
            }
        }

        when (discoveryState.status) {
            DiscoveryStatus.PERMISSION_REQUIRED -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Allow Local Network access (or enable it in App Settings if previously denied) to auto-discover nearby hosts on Wi-Fi, or enter the host IP manually below.",
                        fontSize = 12.sp,
                        color = Color(0xFF475569)
                    )
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E40AF)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Allow Local Network Discovery",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            DiscoveryStatus.WIFI_UNAVAILABLE -> {
                Text(
                    text = "Connect both devices to the same Wi-Fi network to auto-discover hosts.",
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )
            }
            DiscoveryStatus.ERROR -> {
                Text(
                    text = discoveryState.lastError ?: discoveryState.statusMessage,
                    fontSize = 12.sp,
                    color = Color(0xFFB91C1C)
                )
            }
            DiscoveryStatus.SCANNING, DiscoveryStatus.IDLE -> {
                if (discoveryState.discoveredHosts.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (discoveryState.isBrowsing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF2563EB)
                            )
                        }
                        Text(
                            text = if (discoveryState.isBrowsing) {
                                "Listening on local Wi-Fi… Start hosting on Phone A to appear here."
                            } else {
                                discoveryState.statusMessage
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF475569)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (host in discoveryState.discoveredHosts) {
                            val isSelected = selectedIp.trim() == host.hostAddress &&
                                selectedPort.trim() == host.port.toString()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFEFF6FF) else Color.White)
                                    .border(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFFE2E8F0),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onSelectHost(host) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = host.displayName,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF0F172A),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${host.hostAddress}:${host.port}",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                    if (host.codeRequired) {
                                        Text(
                                            text = "Join code required",
                                            fontSize = 10.sp,
                                            color = Color(0xFFB45309),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFFF1F5F9),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (isSelected) "✓ Selected" else "Select",
                                        color = if (isSelected) Color.White else Color(0xFF334155),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Host Waiting Banner: Shown on Phone A while waiting for a receiver to connect.
 */
@Composable
private fun HostWaitingBanner(
    localIp: String?,
    sessionCode: String? = null,
    isIpCopied: Boolean,
    isAdvertising: Boolean = false,
    advertisedServiceName: String? = null,
    onCopyIp: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Waiting for Receiver to Connect",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E3A8A)
            )
            if (isAdvertising) {
                Text(
                    text = "● Auto-discoverable on Wi-Fi (${advertisedServiceName ?: "_localcast._tcp"})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF15803D)
                )
            }
            Text(
                text = "Or enter this IP on Phone B:",
                fontSize = 12.sp,
                color = Color(0xFF3B82F6)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = localIp ?: "unknown",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )

                Button(
                    onClick = { localIp?.let(onCopyIp) },
                    enabled = localIp != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isIpCopied) Color(0xFF16A34A) else Color(0xFF1E40AF),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isIpCopied) "✓ Copied!" else "Copy IP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (!sessionCode.isNullOrBlank()) {
                Text(
                    text = "Join Code: $sessionCode",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF475569)
                )
            }
        }
    }
}

/**
 * Approval Card for Incoming Join Requests on Phone A.
 */
@Composable
private fun PendingJoinApprovalCard(
    deviceName: String,
    deviceId: String,
    joinCode: String,
    codeMatched: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFDE68A)))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Incoming Connection Request",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF92400E)
            )
            Text(
                text = "Device: $deviceName ($deviceId)",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF78350F)
            )
            Text(
                text = "Join Code: $joinCode ${if (codeMatched) "✓ (Matched)" else "⚠ (Mismatch)"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (codeMatched) Color(0xFF166534) else Color(0xFFB45309)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Approve & Stream")
                }
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reject")
                }
            }
        }
    }
}

/**
 * Active Stream View: Responsive 16:9 Video Viewport with Floating Circular Action Buttons.
 */
@Composable
private fun ActiveStreamView(
    state: SessionState,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndSession: () -> Unit,
    onBindLocalRenderer: (SurfaceViewRenderer) -> Unit,
    onUnbindLocalRenderer: (SurfaceViewRenderer) -> Unit,
    onBindRemoteRenderer: (SurfaceViewRenderer) -> Unit,
    onUnbindRemoteRenderer: (SurfaceViewRenderer) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Unobstructed Responsive 16:9 Video Viewport
            if (state.role == SessionRole.HOST_PUBLISHER) {
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
            } else if (state.role == SessionRole.CLIENT_RECEIVER) {
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

            // Floating Circular Controls Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Mute Toggle
                val isMicOn = state.mediaStatus.localMicEnabled
                CircularActionButton(
                    iconText = if (isMicOn) "MIC" else "MUTED",
                    labelText = if (isMicOn) "Mute" else "Unmute",
                    isActive = isMicOn,
                    activeContainerColor = Color(0xFF334155),
                    inactiveContainerColor = Color(0xFFDC2626),
                    onClick = onToggleMic
                )

                // Camera Pause Toggle
                val isCamOn = state.mediaStatus.localCameraEnabled
                CircularActionButton(
                    iconText = if (isCamOn) "CAM" else "PAUSED",
                    labelText = if (isCamOn) "Pause" else "Resume",
                    isActive = isCamOn,
                    activeContainerColor = Color(0xFF334155),
                    inactiveContainerColor = Color(0xFFD97706),
                    onClick = onToggleCamera
                )

                // Flip Camera (Host Only)
                CircularActionButton(
                    iconText = "FLIP",
                    labelText = if (state.mediaStatus.isFrontCamera) "Rear" else "Front",
                    isActive = true,
                    activeContainerColor = Color(0xFF334155),
                    inactiveContainerColor = Color(0xFF334155),
                    enabled = state.role == SessionRole.HOST_PUBLISHER,
                    onClick = onSwitchCamera
                )

                // End Session Disconnect Button (Prominently Styled)
                CircularActionButton(
                    iconText = "END",
                    labelText = "Disconnect",
                    isActive = false,
                    activeContainerColor = Color(0xFFDC2626),
                    inactiveContainerColor = Color(0xFFDC2626),
                    contentColor = Color.White,
                    onClick = onEndSession
                )
            }
        }
    }
}

/**
 * Circular Action Button with icon indicator and sub-label for active calls.
 */
@Composable
private fun CircularActionButton(
    iconText: String,
    labelText: String,
    isActive: Boolean,
    activeContainerColor: Color,
    inactiveContainerColor: Color,
    contentColor: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(if (isActive) activeContainerColor else inactiveContainerColor)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = iconText,
                color = if (enabled) contentColor else Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            text = labelText,
            color = if (enabled) Color(0xFFCBD5E1) else Color(0xFF64748B),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Advanced Diagnostics Accordion: Collapsed by default at screen bottom,
 * preserving 100% of M0–M4 infrastructure telemetry, debug buttons, and raw event log.
 */
@Composable
private fun DiagnosticsAccordion(
    state: SessionState,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSendPing: () -> Unit,
    onInitWebRtc: () -> Unit,
    onBindWifi: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Expandable Accordion Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Advanced Diagnostics (M0–M5)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D254C)
                    )
                    Text(
                        text = "Low-level WebRTC, mDNS/NSD, network telemetry, and log events",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                        fontSize = 11.sp
                    )
                }

                Text(
                    text = if (isExpanded) "▲ Collapse" else "▼ Expand",
                    color = Color(0xFF2563EB),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = Color(0xFFE2E8F0))

                    // M0–M5 Telemetry Rows
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
                    val disc = state.discoveryState
                    Text(
                        text = "M5 NSD: ${disc.status.name} · Adv=${disc.isAdvertising} · Hosts=${disc.discoveredHosts.size} (${disc.statusMessage})",
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

                    // Diagnostic Actions Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSendPing,
                            modifier = Modifier.weight(1f),
                            enabled = state.role != SessionRole.NONE
                        ) {
                            Text("Send PING", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onInitWebRtc,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Re-check M0", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onBindWifi,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Bind Wi-Fi", fontSize = 11.sp)
                        }
                    }

                    // Diagnostic Event Log (Last 25 Entries)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
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
    }
}

/**
 * Hosts WebRTC's `SurfaceViewRenderer` inside Jetpack Compose `AndroidView`,
 * maintaining 16:9 aspect ratio and safely releasing the renderer on dispose.
 */
@Composable
private fun WebRtcVideoRendererBox(
    isFrontMirror: Boolean,
    onBind: (SurfaceViewRenderer) -> Unit,
    onUnbind: (SurfaceViewRenderer) -> Unit,
    overlayLabel: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            runCatching {
                setZOrderMediaOverlay(true)
            }
        }
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
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
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
