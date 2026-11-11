package com.localcast.poc.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.localcast.poc.model.WifiBindingStatus
import java.net.Inet4Address

/**
 * Selects an active Wi-Fi network, resolves its local IPv4 address, and binds/unbinds
 * the application process to that Wi-Fi network so Ktor and WebRTC sockets never route
 * over cellular or public interfaces.
 */
class WifiNetworkController(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var boundNetwork: Network? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var discoveryWifiCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Locates an active Wi-Fi network, extracts its site-local IPv4 address, and binds
     * the current process to that Wi-Fi network.
     */
    @Synchronized
    fun bindToWifiNetwork(onWifiLost: (() -> Unit)? = null): WifiBindingStatus {
        val cm = connectivityManager
            ?: return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = null,
                networkHandle = null,
                statusMessage = "Connectivity service unavailable."
            )

        val wifiNetwork = findWifiNetwork()
            ?: return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = null,
                networkHandle = null,
                statusMessage = "No active Wi-Fi network found. Connect both phones to the same Wi-Fi LAN."
            )

        val linkProperties = cm.getLinkProperties(wifiNetwork)
        val ipv4 = extractSiteLocalIpv4(linkProperties)
        val iface = linkProperties?.interfaceName

        if (ipv4 == null) {
            return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = iface,
                networkHandle = wifiNetwork.networkHandle,
                statusMessage = "Wi-Fi connected (${iface ?: "unknown"}), but no IPv4 LAN address is assigned."
            )
        }

        val bound = cm.bindProcessToNetwork(wifiNetwork)
        if (!bound) {
            return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = ipv4,
                interfaceName = iface,
                networkHandle = wifiNetwork.networkHandle,
                statusMessage = "Failed to bind app process to Wi-Fi network ($ipv4)."
            )
        }

        boundNetwork = wifiNetwork
        registerDisconnectMonitor(wifiNetwork, onWifiLost)

        return WifiBindingStatus(
            isBoundToWifi = true,
            localIpv4Address = ipv4,
            interfaceName = iface,
            networkHandle = wifiNetwork.networkHandle,
            statusMessage = "Process bound to Wi-Fi ($iface · $ipv4)"
        )
    }

    /**
     * Returns the bound Wi-Fi [Network] if active, or locates an available Wi-Fi [Network]
     * for scoping mDNS discovery/advertisement to the local Wi-Fi interface.
     */
    @Synchronized
    fun activeWifiNetwork(): Network? = boundNetwork ?: findWifiNetwork()

    /**
     * Restores default process network routing and unregisters any network callback.
     * Safe and idempotent when called multiple times.
     */
    @Synchronized
    fun unbindProcessFromWifi(): WifiBindingStatus {
        unregisterDisconnectMonitor()
        boundNetwork = null
        runCatching {
            connectivityManager?.bindProcessToNetwork(null)
        }
        return WifiBindingStatus(
            isBoundToWifi = false,
            localIpv4Address = null,
            interfaceName = null,
            networkHandle = null,
            statusMessage = "Unbound process from Wi-Fi"
        )
    }

    /**
     * Inspects current Wi-Fi status without mutating process socket binding.
     */
    fun inspectCurrentWifiStatus(): WifiBindingStatus {
        val cm = connectivityManager
            ?: return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = null,
                networkHandle = null,
                statusMessage = "Wi-Fi unavailable — connect to a local Wi-Fi network"
            )

        val currentBound = boundNetwork
        if (currentBound != null) {
            val props = cm.getLinkProperties(currentBound)
            val ip = extractSiteLocalIpv4(props)
            if (ip != null) {
                return WifiBindingStatus(
                    isBoundToWifi = true,
                    localIpv4Address = ip,
                    interfaceName = props?.interfaceName,
                    networkHandle = currentBound.networkHandle,
                    statusMessage = "Process bound to Wi-Fi (${props?.interfaceName ?: "wlan"} · $ip)"
                )
            }
        }

        val candidate = findWifiNetwork()
        val props = candidate?.let { cm.getLinkProperties(it) }
        val ip = extractSiteLocalIpv4(props)
        return if (candidate != null && ip != null) {
            WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = ip,
                interfaceName = props?.interfaceName,
                networkHandle = candidate.networkHandle,
                statusMessage = "Wi-Fi available (${props?.interfaceName ?: "wlan"} · $ip) — tap Bind Wi-Fi"
            )
        } else {
            WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = null,
                networkHandle = null,
                statusMessage = "Wi-Fi unavailable — connect to a local Wi-Fi network"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun findWifiNetwork(): Network? {
        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork
        if (active != null && isWifiTransport(active)) {
            return active
        }
        for (network in cm.allNetworks) {
            if (isWifiTransport(network)) {
                return network
            }
        }
        return null
    }

    private fun isWifiTransport(network: Network): Boolean {
        val caps = connectivityManager?.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun extractSiteLocalIpv4(linkProperties: LinkProperties?): String? {
        if (linkProperties == null) return null
        val addresses = linkProperties.linkAddresses.mapNotNull { it.address as? Inet4Address }
        val siteLocal = addresses.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
        val fallback = addresses.firstOrNull { !it.isLoopbackAddress }
        return (siteLocal ?: fallback)?.hostAddress
    }

    private fun registerDisconnectMonitor(targetNetwork: Network, onWifiLost: (() -> Unit)?) {
        unregisterDisconnectMonitor()
        val cm = connectivityManager ?: return
        if (onWifiLost == null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                if (network == targetNetwork) {
                    onWifiLost.invoke()
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    /**
     * Registers a passive Wi-Fi callback (without process network binding) so Join-mode
     * auto-discovery reacts immediately when Wi-Fi disconnects or reconnects.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    fun startWifiMonitor(
        onWifiAvailable: () -> Unit,
        onWifiLost: () -> Unit
    ) {
        stopWifiMonitor()
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiAvailable()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (extractSiteLocalIpv4(linkProperties) != null) {
                    onWifiAvailable()
                }
            }

            override fun onLost(network: Network) {
                val anotherWifiExists = runCatching {
                    cm.allNetworks.any { it != network && isWifiTransport(it) }
                }.getOrDefault(false)
                if (!anotherWifiExists) {
                    onWifiLost()
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            discoveryWifiCallback = callback
        }
    }

    @Synchronized
    fun stopWifiMonitor() {
        val existing = discoveryWifiCallback ?: return
        discoveryWifiCallback = null
        runCatching {
            connectivityManager?.unregisterNetworkCallback(existing)
        }
    }

    @Synchronized
    fun isWifiMonitorActive(): Boolean = discoveryWifiCallback != null

    private fun unregisterDisconnectMonitor() {
        val existing = networkCallback ?: return
        networkCallback = null
        runCatching {
            connectivityManager?.unregisterNetworkCallback(existing)
        }
    }
}
