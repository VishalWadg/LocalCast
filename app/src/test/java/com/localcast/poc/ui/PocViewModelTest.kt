package com.localcast.poc.ui

import android.app.Application
import androidx.compose.runtime.saveable.SaverScope
import com.localcast.poc.model.DeviceInfo
import com.localcast.poc.model.DiscoveredHost
import com.localcast.poc.model.MediaStreamStatus
import com.localcast.poc.model.PendingJoinRequest
import com.localcast.poc.model.SessionPhase
import com.localcast.poc.model.SessionRole
import com.localcast.poc.model.SessionState
import com.localcast.poc.model.WebRtcSessionStatus
import com.localcast.poc.model.WifiBindingStatus
import com.localcast.poc.rtc.FakeMediaEngine
import com.localcast.poc.service.SessionRuntimeCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.SurfaceViewRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Requirements R3 & Test Infra Features 6 & 7:
 * - Feature 6: Streamlined Host/Join UI layout & clear viewports
 * - Feature 7: Diagnostic/telemetry clutter minimization
 *
 * Tier 1 (Feature Coverage):
 * - Initial state defaults for Host and Join flows
 * - Tab selection transitions (selectTab, selectMode, onTabSelected)
 * - Host IP, port, and join code updates
 * - Collapsible diagnostics accordion initial state (collapsed by default to prevent visual clutter)
 * - Diagnostics accordion expansion toggle (toggleDiagnostics, setDiagnosticsExpanded)
 * - Input error setting and clearing (setInputError, clearInputError)
 * - Visual feedback flag and auto-reset timeout for copied Host IP (copyHostIpToClipboard)
 * - Contract compatibility accessors (activeTab, targetIp, targetPort, isCopiedNotification)
 * - PocUiState and UiMode Serializable interface conformance and byte-stream roundtrip
 * - PocUiState Saver reflection-free roundtrip
 *
 * Tier 2 (Boundary & Corner Cases):
 * - Host IP validation: empty, whitespace-only, invalid format, out-of-range octets
 * - Signaling port validation: privileged (< 1024), negative, 0, > 65535, valid edges (1024 and 65535)
 * - Join code validation: empty (optional), whitespace, 5-digit (fail), 7-digit (fail), alphanumeric (fail), 6-digit (pass)
 * - Updating IP, port, or tab clears existing validation error automatically
 * - Rapid diagnostics accordion toggling stability
 * - PocUiState Saver restore handles empty or truncated list gracefully
 * - PocUiState Saver restore handles corrupted enum safely without throwing
 *
 * Tier 3 (Cross-Feature Combinations & FakeMediaEngine Integration):
 * - Mode switching preserves custom port and join code while resetting error
 * - Delegation of local and remote video renderer binding to FakeMediaEngine
 * - Delegation of camera controls (mic mute, camera mute, switch lens) to FakeMediaEngine
 * - Disconnect and teardown resets streaming overlay to IDLE
 *
 * Tier 4 (Real-World Scenarios):
 * - Scenario 1: Phone A complete Host flow with diagnostics minimization, IP copy, and renderer binding
 * - Scenario 2: Phone B complete Join flow with validation error recovery, join code entry, and stream reception
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PocViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApplication: Application
    private lateinit var viewModel: PocViewModel
    private lateinit var fakeEngine: FakeMediaEngine

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockApplication = createMockApplication()
        viewModel = PocViewModel(mockApplication)
        fakeEngine = FakeMediaEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createMockApplication(): Application {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Application::class.java) as Application
    }

    private fun createDummyRenderer(): SurfaceViewRenderer {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(SurfaceViewRenderer::class.java) as SurfaceViewRenderer
    }

    private fun createTestCoordinator(engine: FakeMediaEngine): SessionRuntimeCoordinator {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val coordinator = unsafe.allocateInstance(SessionRuntimeCoordinator::class.java) as SessionRuntimeCoordinator

        engine.initialize()
        engine.startLocalCapture(true) { }

        val engineField = SessionRuntimeCoordinator::class.java.getDeclaredField("mediaEngine")
        engineField.isAccessible = true
        engineField.set(coordinator, engine)

        val stateField = SessionRuntimeCoordinator::class.java.getDeclaredField("_state")
        stateField.isAccessible = true
        stateField.set(coordinator, MutableStateFlow(SessionState(
            localDevice = DeviceInfo("test-dev", "Test Device"),
            wifiStatus = WifiBindingStatus(isBoundToWifi = true, localIpv4Address = "192.168.1.100")
        )))

        val scopeField = SessionRuntimeCoordinator::class.java.getDeclaredField("scope")
        scopeField.isAccessible = true
        scopeField.set(coordinator, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

        val localDeviceField = SessionRuntimeCoordinator::class.java.getDeclaredField("localDevice")
        localDeviceField.isAccessible = true
        localDeviceField.set(coordinator, DeviceInfo("test-dev", "Test Device"))

        return coordinator
    }

    // =========================================================================
    // TIER 1: Feature Coverage
    // =========================================================================

    @Test
    fun `Tier1 - Initial PocViewModel uiState has Host mode, port 8765, empty IP, and collapsed diagnostics`() {
        val uiState = viewModel.uiState.value
        assertEquals("Default mode must be HOST", UiMode.HOST, uiState.selectedMode)
        assertEquals("activeTab contract alias must be HOST", UiMode.HOST, uiState.activeTab)
        assertEquals("Default port must be 8765", "8765", uiState.hostPortInput)
        assertEquals("targetPort contract alias must be 8765", "8765", uiState.targetPort)
        assertEquals("Default host IP must be empty", "", uiState.hostIpInput)
        assertEquals("targetIp contract alias must be empty", "", uiState.targetIp)
        assertEquals("Default join code must be empty", "", uiState.joinCodeInput)
        assertFalse("Diagnostics accordion must be collapsed by default to minimize visual clutter (R3)", uiState.isDiagnosticsExpanded)
        assertNull("Input error must be null initially", uiState.inputError)
        assertFalse("isIpCopied feedback must be false initially", uiState.isIpCopied)
        assertFalse("isCopiedNotification alias must be false initially", uiState.isCopiedNotification)
    }

    @Test
    fun `Tier1 - selectTab and selectMode transition between Host and Join tabs`() {
        viewModel.selectTab(SetupTab.JOIN)
        assertEquals(UiMode.JOIN, viewModel.uiState.value.selectedMode)
        assertEquals(UiMode.JOIN, viewModel.uiState.value.activeTab)

        viewModel.selectMode(UiMode.HOST)
        assertEquals(UiMode.HOST, viewModel.uiState.value.selectedMode)
        assertEquals(UiMode.HOST, viewModel.uiState.value.activeTab)

        viewModel.onTabSelected(UiMode.JOIN)
        assertEquals(UiMode.JOIN, viewModel.uiState.value.selectedMode)
    }

    @Test
    fun `Tier1 - updateHostIp updateTargetPort and updateJoinCode update state fields`() {
        viewModel.updateHostIp("192.168.1.50")
        assertEquals("192.168.1.50", viewModel.uiState.value.hostIpInput)
        assertEquals("192.168.1.50", viewModel.uiState.value.targetIp)

        viewModel.onTargetIpChanged("10.0.0.1")
        assertEquals("10.0.0.1", viewModel.uiState.value.hostIpInput)

        viewModel.updateTargetPort("9090")
        assertEquals("9090", viewModel.uiState.value.hostPortInput)
        assertEquals("9090", viewModel.uiState.value.targetPort)

        viewModel.onTargetPortChanged("8888")
        assertEquals("8888", viewModel.uiState.value.hostPortInput)

        viewModel.updateJoinCode("123456")
        assertEquals("123456", viewModel.uiState.value.joinCodeInput)

        viewModel.onJoinCodeChanged("654321")
        assertEquals("654321", viewModel.uiState.value.joinCodeInput)
    }

    @Test
    fun `Tier1 - toggleDiagnostics and setDiagnosticsExpanded control accordion expansion`() {
        assertFalse(viewModel.uiState.value.isDiagnosticsExpanded)

        viewModel.toggleDiagnostics()
        assertTrue("Accordion must expand when toggled", viewModel.uiState.value.isDiagnosticsExpanded)

        viewModel.onToggleDiagnostics()
        assertFalse("Accordion must collapse when toggled again", viewModel.uiState.value.isDiagnosticsExpanded)

        viewModel.setDiagnosticsExpanded(true)
        assertTrue(viewModel.uiState.value.isDiagnosticsExpanded)

        viewModel.setDiagnosticsExpanded(false)
        assertFalse(viewModel.uiState.value.isDiagnosticsExpanded)
    }

    @Test
    fun `Tier1 - setInputError and clearInputError manage error message banner`() {
        assertNull(viewModel.uiState.value.inputError)

        viewModel.setInputError("Invalid IPv4 address entered")
        assertEquals("Invalid IPv4 address entered", viewModel.uiState.value.inputError)

        viewModel.clearInputError()
        assertNull("clearInputError must reset inputError to null", viewModel.uiState.value.inputError)
    }

    @Test
    fun `Tier1 - copyHostIpToClipboard sets isIpCopied flag to true and auto-resets after delay`() = runTest {
        assertFalse(viewModel.uiState.value.isIpCopied)

        val success = viewModel.copyHostIpToClipboard("192.168.1.100")
        assertTrue("copyHostIpToClipboard must return true for valid IP", success)
        assertTrue("isIpCopied must be true immediately after copying", viewModel.uiState.value.isIpCopied)
        assertTrue("isCopiedNotification alias must be true", viewModel.uiState.value.isCopiedNotification)

        testDispatcher.scheduler.advanceTimeBy(2001)
        assertFalse("isIpCopied must auto-reset to false after 2000ms delay", viewModel.uiState.value.isIpCopied)
    }

    @Test
    fun `Tier1 - copyHostIpToClipboard with empty string returns false and does not set isIpCopied`() {
        val success = viewModel.copyHostIpToClipboard("")
        assertFalse("copyHostIpToClipboard must return false for empty IP", success)
        assertFalse("isIpCopied must remain false", viewModel.uiState.value.isIpCopied)
    }

    @Test
    fun `Tier1 - PocUiState implements Serializable for Activity recreation and orientation change safety`() {
        val state = PocUiState()
        assertTrue("PocUiState must implement java.io.Serializable to prevent runtime crash in rememberSaveable", state is Serializable)
        assertTrue("UiMode must implement java.io.Serializable", UiMode.HOST is Serializable)
    }

    @Test
    fun `Tier1 - PocUiState and UiMode serialize and deserialize cleanly via standard Java serialization`() {
        val original = PocUiState(
            selectedMode = UiMode.JOIN,
            hostIpInput = "192.168.1.150",
            hostPortInput = "9090",
            joinCodeInput = "123456",
            isDiagnosticsExpanded = true,
            inputError = "Sample error message",
            isIpCopied = true
        )

        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use { it.writeObject(original) }
        val bytes = baos.toByteArray()
        assertTrue("Serialized byte array must not be empty", bytes.isNotEmpty())

        val bais = ByteArrayInputStream(bytes)
        val restored = ObjectInputStream(bais).use { it.readObject() } as PocUiState

        assertEquals(original, restored)
        assertEquals(UiMode.JOIN, restored.selectedMode)
        assertEquals("192.168.1.150", restored.hostIpInput)
        assertEquals("9090", restored.hostPortInput)
        assertEquals("123456", restored.joinCodeInput)
        assertTrue(restored.isDiagnosticsExpanded)
        assertEquals("Sample error message", restored.inputError)
        assertTrue(restored.isIpCopied)
    }

    @Test
    fun `Tier1 - PocUiState Saver roundtrip preserves all fields without reflection`() {
        val original = PocUiState(
            selectedMode = UiMode.JOIN,
            hostIpInput = "10.0.0.42",
            hostPortInput = "8080",
            joinCodeInput = "654321",
            isDiagnosticsExpanded = true,
            inputError = "Invalid port range",
            isIpCopied = true
        )

        val dummyScope = SaverScope { true }
        val saved = with(PocUiState.Saver) { dummyScope.save(original) }
        assertNotNull("Saved state list must not be null", saved)
        assertTrue("Saved state must be a List", saved is List<*>)

        val restored = PocUiState.Saver.restore(saved as Any)

        assertNotNull("Restored state must not be null", restored)
        assertEquals(original, restored)
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases
    // =========================================================================

    @Test
    fun `Tier2 - validateJoinInputs fails when host IP is empty whitespace or invalid format`() {
        viewModel.updateHostIp("")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("Enter Phone A's") == true)

        viewModel.updateHostIp("   ")
        assertFalse(viewModel.validateJoinInputs())

        viewModel.updateHostIp("999.999.999.999")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("Invalid IPv4") == true)

        viewModel.updateHostIp("192.168.1")
        assertFalse(viewModel.validateJoinInputs())

        viewModel.updateHostIp("abc.def.ghi.jkl")
        assertFalse(viewModel.validateJoinInputs())
    }

    @Test
    fun `Tier2 - validateJoinInputs boundary checks on signaling port`() {
        viewModel.updateHostIp("192.168.1.10")

        // Privileged ports (< 1024)
        viewModel.updateTargetPort("1023")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("reserved") == true)

        viewModel.updateTargetPort("80")
        assertFalse(viewModel.validateJoinInputs())

        viewModel.updateTargetPort("0")
        assertFalse(viewModel.validateJoinInputs())

        viewModel.updateTargetPort("-1")
        assertFalse(viewModel.validateJoinInputs())

        // Above max port (> 65535)
        viewModel.updateTargetPort("65536")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("exceeds maximum") == true)

        // Non-integer string
        viewModel.updateTargetPort("xyz")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("valid integer") == true)

        // Valid boundaries: 1024 and 65535
        viewModel.updateTargetPort("1024")
        assertTrue(viewModel.validateJoinInputs())
        assertNull(viewModel.uiState.value.inputError)

        viewModel.updateTargetPort("65535")
        assertTrue(viewModel.validateJoinInputs())
        assertNull(viewModel.uiState.value.inputError)
    }

    @Test
    fun `Tier2 - validateJoinInputs enforces 6-digit numeric join code`() {
        viewModel.updateHostIp("192.168.1.10")
        viewModel.updateTargetPort("8765")

        // Optional empty or whitespace code is allowed
        viewModel.updateJoinCode("")
        assertTrue(viewModel.validateJoinInputs())
        assertNull(viewModel.uiState.value.inputError)

        viewModel.updateJoinCode("   ")
        assertTrue(viewModel.validateJoinInputs())

        // Valid 6-digit codes
        viewModel.updateJoinCode("123456")
        assertTrue(viewModel.validateJoinInputs())

        viewModel.updateJoinCode("000000")
        assertTrue(viewModel.validateJoinInputs())

        // Invalid codes: too short, too long, alphanumeric
        viewModel.updateJoinCode("12345")
        assertFalse(viewModel.validateJoinInputs())
        assertTrue(viewModel.uiState.value.inputError?.contains("6 digits") == true)

        viewModel.updateJoinCode("1234567")
        assertFalse(viewModel.validateJoinInputs())

        viewModel.updateJoinCode("12A456")
        assertFalse(viewModel.validateJoinInputs())
    }

    @Test
    fun `Tier2 - updating host IP port or mode automatically clears existing input error`() {
        viewModel.setInputError("Previous error message")
        assertEquals("Previous error message", viewModel.uiState.value.inputError)

        viewModel.updateHostIp("192.168.1.20")
        assertNull("Updating host IP must clear inputError", viewModel.uiState.value.inputError)

        viewModel.setInputError("Another error")
        viewModel.updateTargetPort("9000")
        assertNull("Updating port must clear inputError", viewModel.uiState.value.inputError)

        viewModel.setInputError("Third error")
        viewModel.selectTab(SetupTab.JOIN)
        assertNull("Switching tab must clear inputError", viewModel.uiState.value.inputError)
    }

    @Test
    fun `Tier2 - rapid accordion toggling remains stable and deterministic`() {
        assertFalse(viewModel.uiState.value.isDiagnosticsExpanded)
        for (i in 1..25) {
            viewModel.toggleDiagnostics()
        }
        assertTrue("Odd number of toggles must result in expanded state", viewModel.uiState.value.isDiagnosticsExpanded)

        viewModel.toggleDiagnostics()
        assertFalse("Even number of toggles must result in collapsed state", viewModel.uiState.value.isDiagnosticsExpanded)
    }

    @Test
    fun `Tier2 - PocUiState Saver restore handles empty or truncated list gracefully`() {
        val emptyList = emptyList<Any?>()
        val restored = PocUiState.Saver.restore(emptyList)

        assertNotNull(restored)
        assertEquals(UiMode.HOST, restored?.selectedMode)
        assertEquals("", restored?.hostIpInput)
        assertEquals("8765", restored?.hostPortInput)
        assertEquals("", restored?.joinCodeInput)
        assertFalse(restored?.isDiagnosticsExpanded ?: true)
        assertNull(restored?.inputError)
        assertFalse(restored?.isIpCopied ?: true)
    }

    @Test
    fun `Tier2 - PocUiState Saver restore handles corrupted enum name safely without throwing`() {
        val corruptedList = listOf("UNKNOWN_CORRUPTED_MODE", "192.168.1.1", "8765", "", false, null, false)
        val restored = PocUiState.Saver.restore(corruptedList)

        assertNotNull(restored)
        assertEquals("Should safely fallback to HOST mode", UiMode.HOST, restored?.selectedMode)
        assertEquals("192.168.1.1", restored?.hostIpInput)
    }

    // =========================================================================
    // TIER 3: Cross-Feature Combinations & FakeMediaEngine Integration
    // =========================================================================

    @Test
    fun `Tier3 - mode switching preserves custom port and join code while resetting error`() {
        viewModel.selectTab(SetupTab.JOIN)
        viewModel.updateTargetPort("9999")
        viewModel.updateJoinCode("888888")
        viewModel.setInputError("Validation failed")

        viewModel.selectTab(SetupTab.HOST)
        assertEquals(UiMode.HOST, viewModel.uiState.value.selectedMode)
        assertEquals("9999", viewModel.uiState.value.hostPortInput)
        assertEquals("888888", viewModel.uiState.value.joinCodeInput)
        assertNull(viewModel.uiState.value.inputError)
    }

    @Test
    fun `Tier3 - ViewModel delegates video renderer binding to FakeMediaEngine`() {
        val coordinator = createTestCoordinator(fakeEngine)
        val vmWithCoordinator = PocViewModel(mockApplication, coordinator)
        val localRenderer = createDummyRenderer()
        val remoteRenderer = createDummyRenderer()

        vmWithCoordinator.bindLocalVideoRenderer(localRenderer)
        assertTrue("FakeMediaEngine must register local renderer", fakeEngine.boundLocalRenderers.contains(localRenderer))

        vmWithCoordinator.bindRemoteVideoRenderer(remoteRenderer)
        assertTrue("FakeMediaEngine must register remote renderer", fakeEngine.boundRemoteRenderers.contains(remoteRenderer))

        vmWithCoordinator.unbindLocalVideoRenderer(localRenderer)
        assertFalse("FakeMediaEngine must unbind local renderer", fakeEngine.boundLocalRenderers.contains(localRenderer))

        vmWithCoordinator.unbindRemoteVideoRenderer(remoteRenderer)
        assertFalse("FakeMediaEngine must unbind remote renderer", fakeEngine.boundRemoteRenderers.contains(remoteRenderer))
    }

    @Test
    fun `Tier3 - ViewModel delegates camera controls and track muting to FakeMediaEngine`() {
        val coordinator = createTestCoordinator(fakeEngine)
        val vmWithCoordinator = PocViewModel(mockApplication, coordinator)

        assertTrue(fakeEngine.isCameraEnabled)
        assertTrue(fakeEngine.isMicrophoneEnabled)

        vmWithCoordinator.toggleLocalMic()
        assertFalse("FakeMediaEngine mic should be toggled to muted", fakeEngine.isMicrophoneEnabled)

        vmWithCoordinator.toggleLocalCamera()
        assertFalse("FakeMediaEngine camera should be toggled to muted", fakeEngine.isCameraEnabled)

        val initialLens = fakeEngine.isFrontCamera
        vmWithCoordinator.switchCamera()
        assertEquals(!initialLens, fakeEngine.isFrontCamera)
    }

    // =========================================================================
    // TIER 4: Real-World Scenarios
    // =========================================================================

    @Test
    fun `Tier4 - Phone A complete Host flow with diagnostics minimization and clipboard copy`() = runTest {
        // Step 1: Initial state verified
        assertEquals(UiMode.HOST, viewModel.uiState.value.selectedMode)
        assertFalse("Diagnostics collapsed to prevent visual clutter", viewModel.uiState.value.isDiagnosticsExpanded)

        // Step 2: Host copies IP for receiver
        val copied = viewModel.copyHostIpToClipboard("192.168.1.100")
        assertTrue(copied)
        assertTrue(viewModel.uiState.value.isIpCopied)

        // Step 3: Advance delay -> copy flag resets
        testDispatcher.scheduler.advanceTimeBy(2001)
        assertFalse(viewModel.uiState.value.isIpCopied)

        // Step 4: Video renderer bound for local preview
        val coordinator = createTestCoordinator(fakeEngine)
        val vmWithCoordinator = PocViewModel(mockApplication, coordinator)
        val previewRenderer = createDummyRenderer()
        vmWithCoordinator.bindLocalVideoRenderer(previewRenderer)
        assertTrue(fakeEngine.boundLocalRenderers.contains(previewRenderer))
    }

    @Test
    fun `Tier4 - Phone B complete Join flow with validation error recovery and renderer binding`() {
        // Step 1: User navigates to Join mode
        viewModel.selectTab(SetupTab.JOIN)
        assertEquals(UiMode.JOIN, viewModel.uiState.value.selectedMode)

        // Step 2: User attempts connection with invalid IP
        viewModel.updateHostIp("bad-ip")
        viewModel.updateTargetPort("8765")
        assertFalse(viewModel.validateJoinInputs())
        assertEquals("Invalid IPv4 address format: bad-ip", viewModel.uiState.value.inputError)

        // Step 3: User corrects IP address
        viewModel.updateHostIp("192.168.1.100")
        assertNull("Updating IP clears error", viewModel.uiState.value.inputError)

        // Step 4: User enters join code and connects
        viewModel.updateJoinCode("654321")
        assertTrue("Validation must pass with valid IP, port, and code", viewModel.validateJoinInputs())
        assertNull(viewModel.uiState.value.inputError)

        // Step 5: Remote video renderer bound
        val coordinator = createTestCoordinator(fakeEngine)
        val vmWithCoordinator = PocViewModel(mockApplication, coordinator)
        val remoteRenderer = createDummyRenderer()
        vmWithCoordinator.bindRemoteVideoRenderer(remoteRenderer)
        assertTrue(fakeEngine.boundRemoteRenderers.contains(remoteRenderer))
    }

    @Test
    fun `Tier1 - selectDiscoveredHost populates hostIpInput and hostPortInput and clears inputError`() {
        viewModel.selectMode(UiMode.JOIN)
        viewModel.setInputError("Invalid IPv4 address format: bad")

        val host = DiscoveredHost(
            serviceName = "LocalCast - Pixel 10 [ab12]",
            displayName = "Pixel 10",
            deviceId = "android-ab12",
            hostAddress = "192.168.1.77",
            port = 8766
        )

        viewModel.selectDiscoveredHost(host)
        assertEquals("192.168.1.77", viewModel.uiState.value.hostIpInput)
        assertEquals("8766", viewModel.uiState.value.hostPortInput)
        assertNull("Selecting a discovered host must clear inputError", viewModel.uiState.value.inputError)
        assertTrue(viewModel.validateJoinInputs())
    }

    @Test
    fun `Tier3 - PocViewModel startDiscovery refreshDiscovery and stopDiscovery handle test coordinator cleanly without crash`() {
        val coordinator = createTestCoordinator(fakeEngine)
        val vmWithCoordinator = PocViewModel(mockApplication, coordinator)
        vmWithCoordinator.startDiscovery()
        vmWithCoordinator.refreshDiscovery()
        vmWithCoordinator.stopDiscovery()
        assertEquals(UiMode.HOST, vmWithCoordinator.uiState.value.selectedMode)
    }
}
