package com.localcast.poc.network

import android.net.Network
import com.localcast.poc.model.DiscoveredHost

/**
 * High-fidelity JVM test double for [LocalServiceDiscovery].
 *
 * Tracks advertisement and discovery lifecycle calls, enforces single active
 * session semantics, and lets unit tests simulate NSD callbacks deterministically.
 */
class FakeLocalServiceDiscovery : LocalServiceDiscovery {

    var isAdvertisingActive: Boolean = false
        private set

    var isDiscoveryActive: Boolean = false
        private set

    var isReleased: Boolean = false
        private set

    var advertisingStartCount: Int = 0
        private set

    var advertisingStopCount: Int = 0
        private set

    var discoveryStartCount: Int = 0
        private set

    var discoveryStopCount: Int = 0
        private set

    var releaseCount: Int = 0
        private set

    var lastAdvertisedConfig: HostAdvertisementConfig? = null
        private set

    var lastDiscoveryLocalDeviceId: String? = null
        private set

    var lastDiscoveryWifiNetwork: Network? = null
        private set

    var advertisingListener: LocalServiceDiscoveryListener? = null
        private set

    var discoveryListener: LocalServiceDiscoveryListener? = null
        private set

    override fun startAdvertising(
        config: HostAdvertisementConfig,
        listener: LocalServiceDiscoveryListener
    ) {
        advertisingStartCount++
        isAdvertisingActive = true
        isReleased = false
        lastAdvertisedConfig = config
        advertisingListener = listener
    }

    override fun stopAdvertising() {
        advertisingStopCount++
        val listener = advertisingListener
        val wasActive = isAdvertisingActive
        isAdvertisingActive = false
        advertisingListener = null
        if (wasActive) {
            listener?.onAdvertisingStopped()
        }
    }

    override fun startDiscovery(
        localDeviceId: String,
        wifiNetwork: Network?,
        listener: LocalServiceDiscoveryListener
    ) {
        discoveryStartCount++
        isDiscoveryActive = true
        isReleased = false
        lastDiscoveryLocalDeviceId = localDeviceId
        lastDiscoveryWifiNetwork = wifiNetwork
        discoveryListener = listener
    }

    override fun stopDiscovery() {
        discoveryStopCount++
        val listener = discoveryListener
        val wasActive = isDiscoveryActive
        isDiscoveryActive = false
        discoveryListener = null
        if (wasActive) {
            listener?.onDiscoveryStopped()
        }
    }

    override fun release() {
        releaseCount++
        stopDiscovery()
        stopAdvertising()
        isReleased = true
    }

    // --- Simulation Helpers ---

    fun simulateAdvertisingStarted(registeredServiceName: String) {
        advertisingListener?.onAdvertisingStarted(registeredServiceName)
    }

    fun simulateAdvertisingFailed(reason: String, errorCode: Int = -1) {
        isAdvertisingActive = false
        val listener = advertisingListener
        advertisingListener = null
        listener?.onAdvertisingFailed(reason, errorCode)
    }

    fun simulateDiscoveryStarted() {
        discoveryListener?.onDiscoveryStarted()
    }

    fun simulateDiscoveryFailed(reason: String, errorCode: Int = -1) {
        isDiscoveryActive = false
        val listener = discoveryListener
        discoveryListener = null
        listener?.onDiscoveryFailed(reason, errorCode)
    }

    fun simulateHostResolved(host: DiscoveredHost) {
        discoveryListener?.onHostResolved(host)
    }

    fun simulateHostLost(serviceName: String) {
        discoveryListener?.onHostLost(serviceName)
    }
}
