package com.localcast.poc.network

import android.net.Network
import com.localcast.poc.model.DiscoveredHost
import com.localcast.poc.model.DiscoveryState
import com.localcast.poc.model.DiscoveryStatus

/**
 * Lightweight Wi-Fi availability probe passed to [LocalDiscoveryCoordinator].
 */
data class WifiProbe(
    val isWifiAvailable: Boolean,
    val wifiNetwork: Network? = null,
    val localIpv4Address: String? = null
)

/**
 * Pure-Kotlin orchestrator managing M5 host advertisement and client browsing state transitions
 * on top of [LocalServiceDiscovery] and [DiscoveredHostRegistry].
 *
 * Isolates all NSD lifecycle, permission gating, self-filtering, and race protection so it can be
 * tested deterministically on the JVM without Android framework dependencies.
 */
class LocalDiscoveryCoordinator(
    private val localDeviceId: String,
    private val localDisplayName: String,
    private val serviceDiscovery: LocalServiceDiscovery,
    private val isDiscoveryAuthorized: () -> Boolean,
    private val wifiProbeProvider: () -> WifiProbe,
    private val onStateChanged: (DiscoveryState) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit = { _, _ -> }
) {
    private val operationLock = Any()
    private val stateLock = Any()
    private val hostRegistry = DiscoveredHostRegistry(localDeviceId = localDeviceId)
    private var state = DiscoveryState()
    private var advertisingSessionToken = 0L
    private var browsingSessionToken = 0L

    fun currentState(): DiscoveryState = synchronized(stateLock) { state }

    fun startHostAdvertisement(
        port: Int,
        protocolVersion: Int = 1,
        codeRequired: Boolean = false
    ) {
        synchronized(operationLock) {
            stopClientDiscovery()

            if (!isDiscoveryAuthorized()) {
                onLog("M5-NSD-PERM", "Skipping NSD advertisement: ACCESS_LOCAL_NETWORK not granted")
                updateState {
                    it.copy(
                        isAdvertising = false,
                        advertisedServiceName = null,
                        lastError = "Local network permission required for mDNS advertisement"
                    )
                }
                return
            }

            val wifi = wifiProbeProvider()
            val token = synchronized(stateLock) { ++advertisingSessionToken }

            val config = HostAdvertisementConfig(
                deviceId = localDeviceId,
                displayName = localDisplayName,
                port = port,
                protocolVersion = protocolVersion,
                codeRequired = codeRequired,
                wifiNetwork = wifi.wifiNetwork
            )

            serviceDiscovery.startAdvertising(
                config = config,
                listener = object : LocalServiceDiscoveryListener {
                    override fun onAdvertisingStarted(registeredServiceName: String) {
                        synchronized(stateLock) {
                            if (token != advertisingSessionToken) return
                            hostRegistry.configureIdentity(localDeviceId, registeredServiceName)
                        }
                        onLog("M5-NSD", "Advertising _localcast._tcp as \"$registeredServiceName\" on port $port")
                        updateState {
                            it.copy(
                                isAdvertising = true,
                                advertisedServiceName = registeredServiceName,
                                lastError = null
                            )
                        }
                    }

                    override fun onAdvertisingStopped() {
                        synchronized(stateLock) {
                            if (token != advertisingSessionToken) return
                            hostRegistry.configureIdentity(localDeviceId, null)
                        }
                        updateState {
                            it.copy(
                                isAdvertising = false,
                                advertisedServiceName = null
                            )
                        }
                    }

                    override fun onAdvertisingFailed(reason: String, errorCode: Int) {
                        synchronized(stateLock) {
                            if (token != advertisingSessionToken) return
                            hostRegistry.configureIdentity(localDeviceId, null)
                        }
                        onLog("M5-NSD-ERR", reason)
                        updateState {
                            it.copy(
                                isAdvertising = false,
                                advertisedServiceName = null,
                                lastError = reason
                            )
                        }
                    }
                }
            )
        }
    }

    fun stopHostAdvertisement() {
        synchronized(operationLock) {
            val wasAdvertising = synchronized(stateLock) {
                ++advertisingSessionToken
                hostRegistry.configureIdentity(localDeviceId, null)
                state.isAdvertising || state.advertisedServiceName != null
            }
            serviceDiscovery.stopAdvertising()
            if (wasAdvertising) {
                onLog("M5-NSD", "Stopped _localcast._tcp advertisement")
                updateState {
                    it.copy(
                        isAdvertising = false,
                        advertisedServiceName = null
                    )
                }
            }
        }
    }

    fun startClientDiscovery(forceRestart: Boolean = false) {
        synchronized(operationLock) {
            if (!isDiscoveryAuthorized()) {
                stopDiscoveryInternal(
                    nextStatus = DiscoveryStatus.PERMISSION_REQUIRED,
                    nextMessage = "Allow Local Network access to discover nearby hosts on Wi-Fi",
                    nextError = null
                )
                return
            }

            val wifi = wifiProbeProvider()
            if (!wifi.isWifiAvailable) {
                stopDiscoveryInternal(
                    nextStatus = DiscoveryStatus.WIFI_UNAVAILABLE,
                    nextMessage = "Connect to Wi-Fi to discover nearby LocalCast hosts",
                    nextError = null
                )
                return
            }

            val initialState: DiscoveryState
            val token = synchronized(stateLock) {
                if (!forceRestart && state.isBrowsing && state.status == DiscoveryStatus.SCANNING) {
                    return
                }
                hostRegistry.clear()
                val nextTok = ++browsingSessionToken
                state = state.copy(
                    status = DiscoveryStatus.SCANNING,
                    isBrowsing = true,
                    discoveredHosts = emptyList(),
                    statusMessage = "Scanning local Wi-Fi for LocalCast hosts...",
                    lastError = null
                )
                initialState = state
                nextTok
            }
            onStateChanged(initialState)

            onLog("M5-NSD", "Starting _localcast._tcp discovery on Wi-Fi")

            serviceDiscovery.startDiscovery(
                localDeviceId = localDeviceId,
                wifiNetwork = wifi.wifiNetwork,
                listener = object : LocalServiceDiscoveryListener {
                    override fun onDiscoveryStarted() {
                        updateIfBrowsingToken(token) {
                            it.copy(
                                status = DiscoveryStatus.SCANNING,
                                isBrowsing = true,
                                lastError = null
                            )
                        }
                    }

                    override fun onHostResolved(host: DiscoveredHost) {
                        val updatedHosts = synchronized(stateLock) {
                            if (token != browsingSessionToken || !state.isBrowsing) return
                            if (NsdDiscoveryProtocol.shouldFilterSelf(
                                    localDeviceId = localDeviceId,
                                    activeAdvertisedServiceName = state.advertisedServiceName,
                                    candidateDeviceId = host.deviceId,
                                    candidateServiceName = host.serviceName
                                )
                            ) {
                                return
                            }
                            hostRegistry.upsert(host)
                        }
                        onLog("M5-NSD", "Discovered host \"${host.displayName}\" at ${host.hostAddress}:${host.port}")
                        updateIfBrowsingToken(token) {
                            it.copy(
                                status = DiscoveryStatus.SCANNING,
                                discoveredHosts = updatedHosts,
                                statusMessage = if (updatedHosts.isEmpty()) {
                                    "Scanning local Wi-Fi for LocalCast hosts..."
                                } else {
                                    "Found ${updatedHosts.size} nearby host(s)"
                                },
                                lastError = null
                            )
                        }
                    }

                    override fun onHostLost(serviceName: String) {
                        val (wasRemoved, updatedHosts) = synchronized(stateLock) {
                            if (token != browsingSessionToken || !state.isBrowsing) return
                            hostRegistry.removeByServiceNameTracked(serviceName)
                        }
                        if (!wasRemoved) return
                        onLog("M5-NSD", "Host lost: $serviceName")
                        updateIfBrowsingToken(token) {
                            it.copy(
                                discoveredHosts = updatedHosts,
                                statusMessage = if (updatedHosts.isEmpty()) {
                                    "Scanning local Wi-Fi for LocalCast hosts..."
                                } else {
                                    "Found ${updatedHosts.size} nearby host(s)"
                                }
                            )
                        }
                    }

                    override fun onDiscoveryFailed(reason: String, errorCode: Int) {
                        onLog("M5-NSD-ERR", reason)
                        val mappedStatus = if (errorCode == 7) {
                            DiscoveryStatus.PERMISSION_REQUIRED
                        } else {
                            DiscoveryStatus.ERROR
                        }
                        val nextState = synchronized(stateLock) {
                            if (token != browsingSessionToken) null
                            else {
                                ++browsingSessionToken
                                state = state.copy(
                                    status = mappedStatus,
                                    isBrowsing = false,
                                    statusMessage = reason,
                                    lastError = reason
                                )
                                state
                            }
                        }
                        if (nextState != null) {
                            onStateChanged(nextState)
                        }
                    }

                    override fun onDiscoveryStopped() {
                        val nextState = synchronized(stateLock) {
                            if (token != browsingSessionToken) null
                            else if (state.status == DiscoveryStatus.SCANNING) {
                                state = state.copy(
                                    status = DiscoveryStatus.IDLE,
                                    isBrowsing = false,
                                    statusMessage = "Discovery paused"
                                )
                                state
                            } else {
                                null
                            }
                        }
                        if (nextState != null) {
                            onStateChanged(nextState)
                        }
                    }
                }
            )
        }
    }

    fun stopClientDiscovery() {
        synchronized(operationLock) {
            stopDiscoveryInternal(
                nextStatus = DiscoveryStatus.IDLE,
                nextMessage = "Discovery idle",
                nextError = null
            )
        }
    }

    fun onWifiLost() {
        synchronized(operationLock) {
            stopHostAdvertisement()
            val shouldTransitionWifiLost = synchronized(stateLock) {
                state.isBrowsing ||
                    state.status == DiscoveryStatus.SCANNING ||
                    state.discoveredHosts.isNotEmpty()
            }
            if (shouldTransitionWifiLost) {
                stopDiscoveryInternal(
                    nextStatus = DiscoveryStatus.WIFI_UNAVAILABLE,
                    nextMessage = "Wi-Fi disconnected — reconnect to scan for hosts",
                    nextError = null
                )
            }
        }
    }

    fun release() {
        synchronized(operationLock) {
            stopClientDiscovery()
            stopHostAdvertisement()
            serviceDiscovery.release()
        }
    }

    private fun stopDiscoveryInternal(
        nextStatus: DiscoveryStatus,
        nextMessage: String,
        nextError: String?
    ) {
        val hadActiveOrNonIdle = synchronized(stateLock) {
            val changed = state.isBrowsing ||
                state.status != nextStatus ||
                state.statusMessage != nextMessage ||
                state.discoveredHosts.isNotEmpty()
            ++browsingSessionToken
            hostRegistry.clear()
            if (changed) {
                state = state.copy(
                    status = nextStatus,
                    isBrowsing = false,
                    discoveredHosts = emptyList(),
                    statusMessage = nextMessage,
                    lastError = nextError
                )
            }
            changed
        }
        serviceDiscovery.stopDiscovery()
        if (hadActiveOrNonIdle) {
            onStateChanged(currentState())
        }
    }

    private fun updateState(transform: (DiscoveryState) -> DiscoveryState) {
        val next = synchronized(stateLock) {
            state = transform(state)
            state
        }
        onStateChanged(next)
    }

    private fun updateIfBrowsingToken(
        token: Long,
        transform: (DiscoveryState) -> DiscoveryState
    ) {
        val next = synchronized(stateLock) {
            if (token != browsingSessionToken) {
                null
            } else {
                state = transform(state)
                state
            }
        }
        if (next != null) {
            onStateChanged(next)
        }
    }
}
