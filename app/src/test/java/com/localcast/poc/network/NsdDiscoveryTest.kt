package com.localcast.poc.network

import android.app.Application
import com.localcast.poc.model.DiscoveredHost
import com.localcast.poc.model.DiscoveryState
import com.localcast.poc.model.DiscoveryStatus
import com.localcast.poc.ui.PocViewModel
import com.localcast.poc.ui.UiMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Milestone M5 — ZeroConf / mDNS Auto-Discovery (`NsdManager`) Multi-Tier Unit Test Suite.
 *
 * Tier 1 (Feature Coverage):
 * - Separated discovery permissions (API 37+ ACCESS_LOCAL_NETWORK vs API 29–36 empty, no media/notification prompts)
 * - Safe TXT attribute encoding (excludes 6-digit `sessionCode` / PIN)
 * - Resolved NSD host parsing (IPv4, port, deviceId, displayName, protocolVersion, codeRequired)
 * - Self-filtering by deviceId and active advertised serviceName
 * - DiscoveredHostRegistry live upsert, update, and removal on host loss
 *
 * Tier 2 (Boundary & Corner Cases):
 * - Service instance name sanitization, blank fallback, and 63-byte UTF-8 boundary (including multi-byte emojis)
 * - TXT displayName truncation within DNS-SD 255-byte entry budget
 * - Service type normalization (`_localcast._tcp`, `_localcast._tcp.`, case, invalid types)
 * - IPv4 preference over loopback and IPv6 addresses
 * - Invalid ports (<=0, >65535), blank service names, and missing IPv4 rejection
 * - SerialResolutionQueue single-flight FIFO ordering, deduplication, stale token rejection, and service-loss removal
 * - Stale service-lost handling when a device re-registers under a colliding instance name
 *
 * Tier 3 (Lifecycle & Coordinator Integration):
 * - Permission gating transitioning to PERMISSION_REQUIRED without calling platform discovery
 * - Wi-Fi unavailability and onWifiLost teardown stopping discovery/advertising
 * - Host advertisement lifecycle (no sessionCode exposure, clean teardown on stop/error)
 * - Late callback protection after stopClientDiscovery()
 *
 * Tier 4 (Real-World End-to-End Scenarios):
 * - Phone A hosts & advertises -> Phone B discovers, filters self, taps host to populate PocViewModel,
 *   validates inputs -> Phone A stops hosting and disappears from Phone B's host list
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NsdDiscoveryTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress {
        return InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
    }

    private fun ipv6Loopback(): InetAddress {
        return InetAddress.getByAddress(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
    }

    private fun ipv6LinkLocal(): InetAddress {
        return InetAddress.getByAddress(
            byteArrayOf(0xfe.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        )
    }

    private fun createMockApplication(): Application {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Application::class.java) as Application
    }

    // =========================================================================
    // TIER 1: Feature Coverage
    // =========================================================================

    @Test
    fun `Tier1 - requiredDiscoveryPermissions requests only ACCESS_LOCAL_NETWORK on API 37+ and nothing on API 29-36`() {
        assertEquals(emptyList<String>(), LocalNetworkPermissionHelper.requiredDiscoveryPermissions(sdkInt = 29))
        assertEquals(emptyList<String>(), LocalNetworkPermissionHelper.requiredDiscoveryPermissions(sdkInt = 33))
        assertEquals(emptyList<String>(), LocalNetworkPermissionHelper.requiredDiscoveryPermissions(sdkInt = 36))

        val api37Perms = LocalNetworkPermissionHelper.requiredDiscoveryPermissions(sdkInt = 37)
        assertEquals(listOf(LocalNetworkPermissionHelper.PERMISSION_ACCESS_LOCAL_NETWORK), api37Perms)
        assertFalse(api37Perms.contains("android.permission.CAMERA"))
        assertFalse(api37Perms.contains("android.permission.RECORD_AUDIO"))
        assertFalse(api37Perms.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun `Tier1 - buildSafeTxtAttributes publishes safe metadata and never leaks sessionCode`() {
        val secretJoinCode = "482910"
        val txt = NsdDiscoveryProtocol.buildSafeTxtAttributes(
            deviceId = "android-a1b2c3d4",
            displayName = "Google Pixel 10 Pro",
            protocolVersion = 1,
            codeRequired = false
        )

        assertEquals("android-a1b2c3d4", txt[NsdDiscoveryProtocol.TXT_KEY_DEVICE_ID])
        assertEquals("Google Pixel 10 Pro", txt[NsdDiscoveryProtocol.TXT_KEY_DISPLAY_NAME])
        assertEquals("1", txt[NsdDiscoveryProtocol.TXT_KEY_PROTOCOL_VERSION])
        assertEquals("false", txt[NsdDiscoveryProtocol.TXT_KEY_CODE_REQUIRED])

        val serializedAll = txt.entries.joinToString { "${it.key}=${it.value}" }
        assertFalse("TXT attributes must never expose sessionCode", serializedAll.contains(secretJoinCode))
        assertFalse("TXT keys must not include sessionCode", txt.containsKey("sessionCode"))
    }

    @Test
    fun `Tier1 - parseDiscoveredHost parses valid IPv4 port and TXT attributes`() {
        val attrs = mapOf(
            "deviceId" to "android-host1".toByteArray(Charsets.UTF_8),
            "name" to "Pixel 10 Host".toByteArray(Charsets.UTF_8),
            "proto" to "1".toByteArray(Charsets.UTF_8),
            "codeReq" to "true".toByteArray(Charsets.UTF_8)
        )

        val host = NsdDiscoveryProtocol.parseDiscoveredHost(
            serviceName = "LocalCast - Pixel 10 Host [ost1]",
            hostAddresses = listOf(ipv4(192, 168, 1, 42)),
            fallbackHost = null,
            port = 8765,
            attributes = attrs,
            observedAtMs = 123456L
        )

        assertNotNull(host)
        assertEquals("LocalCast - Pixel 10 Host [ost1]", host?.serviceName)
        assertEquals("Pixel 10 Host", host?.displayName)
        assertEquals("android-host1", host?.deviceId)
        assertEquals("192.168.1.42", host?.hostAddress)
        assertEquals(8765, host?.port)
        assertEquals(1, host?.protocolVersion)
        assertTrue(host?.codeRequired == true)
        assertEquals(123456L, host?.lastSeenAtMs)
    }

    @Test
    fun `Tier1 - shouldFilterSelf filters local device by deviceId and advertised service name`() {
        assertTrue(
            NsdDiscoveryProtocol.shouldFilterSelf(
                localDeviceId = "android-self",
                activeAdvertisedServiceName = null,
                candidateDeviceId = "android-self",
                candidateServiceName = "LocalCast - My Phone"
            )
        )
        assertTrue(
            NsdDiscoveryProtocol.shouldFilterSelf(
                localDeviceId = "android-self",
                activeAdvertisedServiceName = "LocalCast - My Phone [self]",
                candidateDeviceId = null,
                candidateServiceName = "LocalCast - My Phone [self]"
            )
        )
        assertFalse(
            NsdDiscoveryProtocol.shouldFilterSelf(
                localDeviceId = "android-self",
                activeAdvertisedServiceName = "LocalCast - My Phone [self]",
                candidateDeviceId = "android-peer",
                candidateServiceName = "LocalCast - Peer [peer]"
            )
        )
    }

    @Test
    fun `Tier1 - DiscoveredHostRegistry upserts updates sorts and removes lost hosts`() {
        val registry = DiscoveredHostRegistry(localDeviceId = "android-self")

        // Self host must be ignored
        registry.upsert(
            DiscoveredHost("LocalCast - Self", "Self", "android-self", "192.168.1.10", 8765)
        )
        assertTrue(registry.snapshot().isEmpty())

        // Peer hosts added
        registry.upsert(
            DiscoveredHost("svc-b", "Zebra Phone", "dev-z", "192.168.1.30", 8765)
        )
        val afterTwo = registry.upsert(
            DiscoveredHost("svc-a", "Alpha Phone", "dev-a", "192.168.1.20", 8765)
        )
        assertEquals(listOf("Alpha Phone", "Zebra Phone"), afterTwo.map { it.displayName })

        // Update existing device with new IP/port
        val afterUpdate = registry.upsert(
            DiscoveredHost("svc-a", "Alpha Phone", "dev-a", "192.168.1.25", 9000)
        )
        assertEquals(2, afterUpdate.size)
        assertEquals("192.168.1.25", afterUpdate.first().hostAddress)
        assertEquals(9000, afterUpdate.first().port)

        // Remove on host lost
        val afterLoss = registry.removeByServiceName("svc-a")
        assertEquals(1, afterLoss.size)
        assertEquals("Zebra Phone", afterLoss.single().displayName)
    }

    // =========================================================================
    // TIER 2: Boundary & Corner Cases
    // =========================================================================

    @Test
    fun `Tier2 - buildAdvertisedServiceName sanitizes control chars handles blanks and caps UTF8 at 63 bytes`() {
        val blankName = NsdDiscoveryProtocol.buildAdvertisedServiceName("   \n\t  ", "dev-1234")
        assertEquals("LocalCast (1234)", blankName)
        assertTrue(blankName.toByteArray(Charsets.UTF_8).size <= 63)

        val emojiLongName = "Samsung Galaxy 🚀🔥📱 Ultra Mega Long Model Name Edition 2026 Pro Max"
        val built = NsdDiscoveryProtocol.buildAdvertisedServiceName(emojiLongName, "android-9999")
        val utf8Bytes = built.toByteArray(Charsets.UTF_8)
        assertTrue("Service name must stay <= 63 UTF-8 bytes, got ${utf8Bytes.size}", utf8Bytes.size <= 63)
        assertTrue("Short device tag should remain intact", built.endsWith("[9999]"))
        // Ensure valid UTF-8 roundtrip without broken surrogate chars
        assertEquals(built, String(utf8Bytes, Charsets.UTF_8))
    }

    @Test
    fun `Tier2 - buildSafeTxtAttributes keeps every key+value strictly under DNS-SD 255 byte limit`() {
        val veryLongDisplayName = "A".repeat(400)
        val txt = NsdDiscoveryProtocol.buildSafeTxtAttributes(
            deviceId = "android-12345678",
            displayName = veryLongDisplayName,
            protocolVersion = 1,
            codeRequired = true
        )

        txt.forEach { (key, value) ->
            assertFalse("TXT key must not contain '='", key.contains('='))
            val totalBytes = key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
            assertTrue("TXT key+value ($key) must be < 255 bytes, got $totalBytes", totalBytes < 255)
        }
    }

    @Test
    fun `Tier2 - isExpectedServiceType handles trailing dots mixed case and rejects unrelated types`() {
        assertTrue(NsdDiscoveryProtocol.isExpectedServiceType("_localcast._tcp"))
        assertTrue(NsdDiscoveryProtocol.isExpectedServiceType("_localcast._tcp."))
        assertTrue(NsdDiscoveryProtocol.isExpectedServiceType("._localcast._tcp."))
        assertTrue(NsdDiscoveryProtocol.isExpectedServiceType("_LOCALCAST._TCP."))

        assertFalse(NsdDiscoveryProtocol.isExpectedServiceType(null))
        assertFalse(NsdDiscoveryProtocol.isExpectedServiceType(""))
        assertFalse(NsdDiscoveryProtocol.isExpectedServiceType("_http._tcp"))
        assertFalse(NsdDiscoveryProtocol.isExpectedServiceType("_localcast._udp"))
    }

    @Test
    fun `Tier2 - selectPreferredIpv4 skips loopback wildcard multicast and IPv6 and prefers site-local IPv4`() {
        val addresses = listOf(
            ipv6Loopback(),
            ipv6LinkLocal(),
            ipv4(0, 0, 0, 0),
            ipv4(224, 0, 0, 251),
            ipv4(127, 0, 0, 1),
            ipv4(169, 254, 12, 34),
            ipv4(8, 8, 8, 8),
            ipv4(192, 168, 43, 15)
        )
        assertEquals("192.168.43.15", NsdDiscoveryProtocol.selectPreferredIpv4(addresses))

        val invalidOnly = listOf(ipv6Loopback(), ipv6LinkLocal(), ipv4(0, 0, 0, 0), ipv4(224, 0, 0, 251))
        assertNull(NsdDiscoveryProtocol.selectPreferredIpv4(invalidOnly, fallbackHost = ipv4(127, 0, 0, 1)))

        val linkLocalOnly = listOf(ipv4(169, 254, 12, 34))
        assertEquals("169.254.12.34", NsdDiscoveryProtocol.selectPreferredIpv4(linkLocalOnly))

        val fallbackOnly = NsdDiscoveryProtocol.selectPreferredIpv4(emptyList(), fallbackHost = ipv4(10, 0, 2, 15))
        assertEquals("10.0.2.15", fallbackOnly)
    }

    @Test
    fun `Tier2 - buildAdvertisedServiceName handles emoji deviceId without splitting surrogate pairs`() {
        val built = NsdDiscoveryProtocol.buildAdvertisedServiceName("Pixel 10", "dev-🚀🔥📱🎉")
        val bytes = built.toByteArray(Charsets.UTF_8)
        assertTrue("Service name must stay <= 63 UTF-8 bytes, got ${bytes.size}", bytes.size <= 63)
        assertEquals(built, String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `Tier2 - parseDiscoveredHost rejects invalid ports blank names and missing IPv4`() {
        val validIp = listOf(ipv4(192, 168, 1, 55))

        assertNull(NsdDiscoveryProtocol.parseDiscoveredHost("", validIp, null, 8765, emptyMap()))
        assertNull(NsdDiscoveryProtocol.parseDiscoveredHost("Host", validIp, null, 0, emptyMap()))
        assertNull(NsdDiscoveryProtocol.parseDiscoveredHost("Host", validIp, null, -1, emptyMap()))
        assertNull(NsdDiscoveryProtocol.parseDiscoveredHost("Host", validIp, null, 65536, emptyMap()))
        assertNull(NsdDiscoveryProtocol.parseDiscoveredHost("Host", listOf(ipv6LinkLocal()), null, 8765, emptyMap()))

        // Missing TXT falls back gracefully to serviceName + proto 1 + codeRequired false
        val minimal = NsdDiscoveryProtocol.parseDiscoveredHost("LocalCast - Fallback", validIp, null, 8765, emptyMap())
        assertNotNull(minimal)
        assertEquals("LocalCast - Fallback", minimal?.displayName)
        assertNull(minimal?.deviceId)
        assertEquals(1, minimal?.protocolVersion)
        assertFalse(minimal?.codeRequired == true)
    }

    @Test
    fun `Tier2 - SerialResolutionQueue serializes concurrent resolves deduplicates ignores stale tokens and handles lost`() {
        val queue = SerialResolutionQueue<String>()

        // Concurrent discoveries: only first dispatches immediately
        val d1 = queue.enqueue("Host-A", "info-A")
        val d2 = queue.enqueue("Host-B", "info-B")
        val dDup = queue.enqueue("Host-B", "info-B-dup")
        val d3 = queue.enqueue("Host-C", "info-C")

        assertNotNull(d1)
        assertEquals("Host-A", d1?.key)
        assertNull("Host-B must wait for single-flight lock", d2)
        assertNull("Duplicate Host-B must be deduplicated", dDup)
        assertNull("Host-C must wait in FIFO queue", d3)
        assertTrue(queue.isInFlight())
        assertEquals(2, queue.pendingCount())

        // Stale token (e.g., expired timeout) must NOT advance queue
        assertNull(queue.completeAndNext(token = 9999L))
        assertTrue(queue.isInFlight())
        assertEquals(2, queue.pendingCount())

        // Remove Host-B while still queued (service lost before resolution)
        queue.removePending("Host-B")
        assertEquals(1, queue.pendingCount())

        // Complete Host-A -> dispatches Host-C directly
        val next = queue.completeAndNext(d1!!.token)
        assertNotNull(next)
        assertEquals("Host-C", next?.key)
        assertEquals(0, queue.pendingCount())

        // Complete Host-C -> queue becomes idle
        assertNull(queue.completeAndNext(next!!.token))
        assertFalse(queue.isInFlight())

        // Reset invalidates in-flight tokens cleanly
        val d4 = queue.enqueue("Host-D", "info-D")!!
        queue.reset()
        assertFalse(queue.isInFlight())
        assertNull(queue.completeAndNext(d4.token))
    }

    @Test
    fun `Tier2 - DiscoveredHostRegistry ignores stale onHostLost from previous renamed service instance`() {
        val registry = DiscoveredHostRegistry(localDeviceId = "self")
        registry.upsert(
            DiscoveredHost("LocalCast - Host", "Host", "dev-1", "192.168.1.5", 8765)
        )
        // Same deviceId re-registered as "(1)"
        registry.upsert(
            DiscoveredHost("LocalCast - Host (1)", "Host", "dev-1", "192.168.1.5", 8765)
        )
        // Stale lost event for old name must NOT delete the updated "(1)" entry
        val afterStaleLoss = registry.removeByServiceName("LocalCast - Host")
        assertEquals(1, afterStaleLoss.size)
        assertEquals("LocalCast - Host (1)", afterStaleLoss.single().serviceName)
    }

    // =========================================================================
    // TIER 3: Coordinator Lifecycle & Integration
    // =========================================================================

    @Test
    fun `Tier3 - LocalDiscoveryCoordinator gates browsing on API 37 permission and transitions on Wi-Fi loss`() {
        val fakeDiscovery = FakeLocalServiceDiscovery()
        var authorized = false
        var hasWifi = true
        var latestState = DiscoveryState()

        val coordinator = LocalDiscoveryCoordinator(
            localDeviceId = "phone-b",
            localDisplayName = "Phone B",
            serviceDiscovery = fakeDiscovery,
            isDiscoveryAuthorized = { authorized },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = hasWifi, localIpv4Address = if (hasWifi) "192.168.1.50" else null) },
            onStateChanged = { latestState = it }
        )

        // Step 1: API 37 permission denied -> PERMISSION_REQUIRED, never calls platform NsdManager
        coordinator.startClientDiscovery()
        assertEquals(DiscoveryStatus.PERMISSION_REQUIRED, latestState.status)
        assertFalse(fakeDiscovery.isDiscoveryActive)
        assertEquals(0, fakeDiscovery.discoveryStartCount)

        // Step 2: Permission granted but Wi-Fi disconnected -> WIFI_UNAVAILABLE
        authorized = true
        hasWifi = false
        coordinator.startClientDiscovery()
        assertEquals(DiscoveryStatus.WIFI_UNAVAILABLE, latestState.status)
        assertFalse(fakeDiscovery.isDiscoveryActive)

        // Step 3: Wi-Fi connected -> starts scanning
        hasWifi = true
        coordinator.startClientDiscovery()
        assertEquals(DiscoveryStatus.SCANNING, latestState.status)
        assertTrue(latestState.isBrowsing)
        assertTrue(fakeDiscovery.isDiscoveryActive)
        assertEquals(1, fakeDiscovery.discoveryStartCount)

        // Duplicate startClientDiscovery without forceRestart is idempotent
        coordinator.startClientDiscovery()
        assertEquals(1, fakeDiscovery.discoveryStartCount)

        // Step 4: Wi-Fi drops mid-scan -> stops platform discovery and transitions to WIFI_UNAVAILABLE
        coordinator.onWifiLost()
        assertFalse(fakeDiscovery.isDiscoveryActive)
        assertEquals(DiscoveryStatus.WIFI_UNAVAILABLE, latestState.status)
        assertFalse(latestState.isBrowsing)
    }

    @Test
    fun `Tier3 - LocalDiscoveryCoordinator manages host advertisement lifecycle without exposing sessionCode`() {
        val fakeDiscovery = FakeLocalServiceDiscovery()
        var latestState = DiscoveryState()

        val coordinator = LocalDiscoveryCoordinator(
            localDeviceId = "phone-a",
            localDisplayName = "Phone A",
            serviceDiscovery = fakeDiscovery,
            isDiscoveryAuthorized = { true },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.10") },
            onStateChanged = { latestState = it }
        )

        // Start client discovery first, then switch to hosting -> must stop browsing cleanly
        coordinator.startClientDiscovery()
        assertTrue(fakeDiscovery.isDiscoveryActive)

        coordinator.startHostAdvertisement(port = 8765, protocolVersion = 1, codeRequired = false)
        assertFalse("Hosting must stop active client discovery", fakeDiscovery.isDiscoveryActive)
        assertTrue(fakeDiscovery.isAdvertisingActive)
        assertNotNull(fakeDiscovery.lastAdvertisedConfig)
        assertEquals(8765, fakeDiscovery.lastAdvertisedConfig?.port)

        fakeDiscovery.simulateAdvertisingStarted("LocalCast - Phone A [ne-a]")
        assertTrue(latestState.isAdvertising)
        assertEquals("LocalCast - Phone A [ne-a]", latestState.advertisedServiceName)

        // Stop advertisement tears down cleanly and is idempotent
        coordinator.stopHostAdvertisement()
        coordinator.stopHostAdvertisement()
        assertFalse(fakeDiscovery.isAdvertisingActive)
        assertFalse(latestState.isAdvertising)
        assertNull(latestState.advertisedServiceName)
    }

    @Test
    fun `Tier3 - LocalDiscoveryCoordinator maps permission error code 7 and ignores late callbacks after stop`() {
        val fakeDiscovery = FakeLocalServiceDiscovery()
        var latestState = DiscoveryState()

        val coordinator = LocalDiscoveryCoordinator(
            localDeviceId = "phone-b",
            localDisplayName = "Phone B",
            serviceDiscovery = fakeDiscovery,
            isDiscoveryAuthorized = { true },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.50") },
            onStateChanged = { latestState = it }
        )

        coordinator.startClientDiscovery()
        fakeDiscovery.simulateDiscoveryStarted()
        val capturedListener = fakeDiscovery.discoveryListener!!

        // Stop discovery, then simulate a late async callback arriving from NSD binder
        coordinator.stopClientDiscovery()
        assertEquals(DiscoveryStatus.IDLE, latestState.status)
        capturedListener.onHostResolved(
            DiscoveredHost("Late-Host", "Late Host", "late-1", "192.168.1.99", 8765)
        )
        assertTrue("Late host after stop must be ignored", latestState.discoveredHosts.isEmpty())
        assertEquals(DiscoveryStatus.IDLE, latestState.status)

        // Restart and simulate OS FAILURE_PERMISSION_DENIED (7)
        coordinator.startClientDiscovery()
        fakeDiscovery.simulateDiscoveryFailed("Local network permission denied", errorCode = 7)
        assertEquals(DiscoveryStatus.PERMISSION_REQUIRED, latestState.status)
        assertFalse(latestState.isBrowsing)
    }

    @Test
    fun `Tier3 - forceRestart clears stale hosts and advertising respects permission gating`() {
        val fakeDiscovery = FakeLocalServiceDiscovery()
        var authorized = true
        var latestState = DiscoveryState()

        val coordinator = LocalDiscoveryCoordinator(
            localDeviceId = "phone-x",
            localDisplayName = "Phone X",
            serviceDiscovery = fakeDiscovery,
            isDiscoveryAuthorized = { authorized },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.33") },
            onStateChanged = { latestState = it }
        )

        coordinator.startClientDiscovery()
        fakeDiscovery.simulateDiscoveryStarted()
        fakeDiscovery.simulateHostResolved(
            DiscoveredHost("Stale-Host", "Stale Host", "dev-stale", "192.168.1.91", 8765)
        )
        assertEquals(1, latestState.discoveredHosts.size)

        // Refresh scan clears stale entries and re-invokes discovery
        coordinator.startClientDiscovery(forceRestart = true)
        assertEquals(2, fakeDiscovery.discoveryStartCount)
        assertTrue(latestState.discoveredHosts.isEmpty())
        assertEquals(DiscoveryStatus.SCANNING, latestState.status)

        // When API 37 local network permission is revoked, host advertisement must not register NSD
        authorized = false
        coordinator.startHostAdvertisement(port = 8765)
        assertFalse(fakeDiscovery.isAdvertisingActive)
        assertFalse(latestState.isAdvertising)
        assertNotNull(latestState.lastError)
    }

    @Test
    fun `Tier3 - concurrent startClientDiscovery deduplicates safely and duplicate host loss is suppressed`() {
        val fakeDiscovery = FakeLocalServiceDiscovery()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stateUpdates = java.util.concurrent.atomic.AtomicInteger(0)
        val latestState = java.util.concurrent.atomic.AtomicReference(DiscoveryState())

        val coordinator = LocalDiscoveryCoordinator(
            localDeviceId = "phone-self",
            localDisplayName = "Self Phone",
            serviceDiscovery = fakeDiscovery,
            isDiscoveryAuthorized = { true },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.40") },
            onStateChanged = {
                stateUpdates.incrementAndGet()
                latestState.set(it)
            },
            onLog = { tag, msg -> logs.add("$tag:$msg") }
        )

        // Simulate simultaneous startClientDiscovery calls (e.g., onResume + LaunchedEffect + permission callback)
        val startGate = java.util.concurrent.CountDownLatch(1)
        val doneGate = java.util.concurrent.CountDownLatch(4)
        repeat(4) {
            kotlin.concurrent.thread {
                startGate.await()
                coordinator.startClientDiscovery()
                doneGate.countDown()
            }
        }
        startGate.countDown()
        assertTrue(doneGate.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Concurrent non-forced starts must invoke platform startDiscovery once", 1, fakeDiscovery.discoveryStartCount)

        fakeDiscovery.simulateDiscoveryStarted()

        // Self host resolution must neither add to discoveredHosts nor log a discovered host
        fakeDiscovery.simulateHostResolved(
            DiscoveredHost("LocalCast - Self Phone", "Self Phone", "phone-self", "192.168.1.40", 8765)
        )
        assertTrue(latestState.get().discoveredHosts.isEmpty())
        assertFalse(logs.any { it.contains("Discovered host \"Self Phone\"") })

        // Peer host resolved then lost twice (e.g., ServiceInfoCallback + DiscoveryListener) -> lost logged once
        fakeDiscovery.simulateHostResolved(
            DiscoveredHost("LocalCast - Peer [peer]", "Peer", "phone-peer", "192.168.1.41", 8765)
        )
        assertEquals(1, latestState.get().discoveredHosts.size)
        val updatesBeforeLoss = stateUpdates.get()
        fakeDiscovery.simulateHostLost("LocalCast - Peer [peer]")
        fakeDiscovery.simulateHostLost("LocalCast - Peer [peer]")

        assertTrue(latestState.get().discoveredHosts.isEmpty())
        assertEquals(1, logs.count { it.contains("Host lost: LocalCast - Peer [peer]") })
        assertEquals(updatesBeforeLoss + 1, stateUpdates.get())
    }

    // =========================================================================
    // TIER 4: Real-World Scenario
    // =========================================================================

    @Test
    fun `Tier4 - Phone A advertises Phone B discovers taps to populate PocViewModel and handles teardown`() {
        val hostDiscovery = FakeLocalServiceDiscovery()
        val clientDiscovery = FakeLocalServiceDiscovery()
        var hostDiscoveryState = DiscoveryState()
        var clientDiscoveryState = DiscoveryState()

        val hostCoordinator = LocalDiscoveryCoordinator(
            localDeviceId = "android-hostA",
            localDisplayName = "Pixel 10 Host",
            serviceDiscovery = hostDiscovery,
            isDiscoveryAuthorized = { true },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.10") },
            onStateChanged = { hostDiscoveryState = it }
        )
        val clientCoordinator = LocalDiscoveryCoordinator(
            localDeviceId = "android-joinB",
            localDisplayName = "Pixel 9 Join",
            serviceDiscovery = clientDiscovery,
            isDiscoveryAuthorized = { true },
            wifiProbeProvider = { WifiProbe(isWifiAvailable = true, localIpv4Address = "192.168.1.22") },
            onStateChanged = { clientDiscoveryState = it }
        )

        // 1. Phone A starts hosting & advertising
        hostCoordinator.startHostAdvertisement(port = 8765, protocolVersion = 1, codeRequired = false)
        val advConfig = hostDiscovery.lastAdvertisedConfig!!
        val safeName = NsdDiscoveryProtocol.buildAdvertisedServiceName(advConfig.displayName, advConfig.deviceId)
        val safeTxt = NsdDiscoveryProtocol.buildSafeTxtAttributes(
            deviceId = advConfig.deviceId,
            displayName = advConfig.displayName,
            protocolVersion = advConfig.protocolVersion,
            codeRequired = advConfig.codeRequired
        )
        hostDiscovery.simulateAdvertisingStarted(safeName)
        assertTrue(hostDiscoveryState.isAdvertising)

        // 2. Phone B opens Join tab and starts scanning
        val viewModel = PocViewModel(createMockApplication())
        viewModel.selectMode(UiMode.JOIN)
        viewModel.setInputError("Old manual validation error")

        clientCoordinator.startClientDiscovery()
        clientDiscovery.simulateDiscoveryStarted()

        // Simulate Phone B seeing both its own echo and Phone A's advertisement
        val phoneBSelfHost = DiscoveredHost(
            serviceName = "LocalCast - Pixel 9 Join [oinB]",
            displayName = "Pixel 9 Join",
            deviceId = "android-joinB",
            hostAddress = "192.168.1.22",
            port = 8765
        )
        val parsedPhoneA = NsdDiscoveryProtocol.parseDiscoveredHost(
            serviceName = safeName,
            hostAddresses = listOf(ipv4(192, 168, 1, 10)),
            fallbackHost = null,
            port = advConfig.port,
            attributes = safeTxt.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        )!!
        clientDiscovery.simulateHostResolved(phoneBSelfHost)
        clientDiscovery.simulateHostResolved(parsedPhoneA)

        // Self-filtered so only Phone A appears
        assertEquals(1, clientDiscoveryState.discoveredHosts.size)
        val discoveredHost = clientDiscoveryState.discoveredHosts.single()
        assertEquals("Pixel 10 Host", discoveredHost.displayName)
        assertEquals("192.168.1.10", discoveredHost.hostAddress)
        assertEquals(8765, discoveredHost.port)

        // 3. User taps discovered Phone A -> populates IP/port and clears old validation error
        viewModel.selectDiscoveredHost(discoveredHost)
        assertEquals("192.168.1.10", viewModel.uiState.value.hostIpInput)
        assertEquals("8765", viewModel.uiState.value.hostPortInput)
        assertNull(viewModel.uiState.value.inputError)
        assertTrue(viewModel.validateJoinInputs())

        // 4. Phone A stops hosting -> Phone B removes Phone A from discovered list
        hostCoordinator.stopHostAdvertisement()
        assertFalse(hostDiscoveryState.isAdvertising)

        clientDiscovery.simulateHostLost(safeName)
        assertTrue(clientDiscoveryState.discoveredHosts.isEmpty())
    }
}
