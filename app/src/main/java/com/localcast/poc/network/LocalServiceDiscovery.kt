package com.localcast.poc.network

import android.net.Network
import com.localcast.poc.model.DiscoveredHost

/**
 * Configuration for advertising a LocalCast host session over LAN mDNS (`_localcast._tcp`).
 *
 * Security invariant: This config intentionally excludes `sessionCode` so the 6-digit
 * join code can never be serialized into mDNS TXT records or service names.
 */
data class HostAdvertisementConfig(
    val deviceId: String,
    val displayName: String,
    val port: Int,
    val protocolVersion: Int = 1,
    val codeRequired: Boolean = false,
    val wifiNetwork: Network? = null
)

/**
 * Callback interface emitted by [LocalServiceDiscovery] implementations.
 */
interface LocalServiceDiscoveryListener {
    fun onAdvertisingStarted(registeredServiceName: String) {}
    fun onAdvertisingStopped() {}
    fun onAdvertisingFailed(reason: String, errorCode: Int = -1) {}

    fun onDiscoveryStarted() {}
    fun onDiscoveryStopped() {}
    fun onDiscoveryFailed(reason: String, errorCode: Int = -1) {}
    fun onHostResolved(host: DiscoveredHost) {}
    fun onHostLost(serviceName: String) {}
}

/**
 * Testable abstraction boundary isolating Android's `NsdManager` and `WifiManager.MulticastLock`
 * from session coordination and JVM unit tests.
 */
interface LocalServiceDiscovery {

    /**
     * Advertises this device as a `_localcast._tcp` host on the local Wi-Fi network.
     */
    fun startAdvertising(config: HostAdvertisementConfig, listener: LocalServiceDiscoveryListener)

    /**
     * Stops host advertisement and releases any associated registration listener and multicast lock.
     * Safe and idempotent when called repeatedly.
     */
    fun stopAdvertising()

    /**
     * Starts browsing for nearby `_localcast._tcp` hosts on the local Wi-Fi network.
     */
    fun startDiscovery(
        localDeviceId: String,
        wifiNetwork: Network?,
        listener: LocalServiceDiscoveryListener
    )

    /**
     * Stops active discovery, unregisters any active resolution callbacks, and releases locks.
     * Safe and idempotent when called repeatedly.
     */
    fun stopDiscovery()

    /**
     * Releases all discovery/advertising resources and executors cleanly.
     */
    fun release()
}
