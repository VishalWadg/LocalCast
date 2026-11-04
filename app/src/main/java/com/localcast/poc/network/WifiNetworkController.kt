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

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var boundNetwork: Network? = null

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Locates an active Wi-Fi network, extracts its site-local IPv4 address, and binds
     * the current process to that Wi-Fi network.
     */
    @Synchronized
    fun bindToWifiNetwork(onWifiLost: (() -> Unit)? = null): WifiBindingStatus {
        val wifiNetwork = findWifiNetwork()
            ?: return WifiBindingStatus(
                isBoundToWifi = false,
                localIpv4Address = null,
                interfaceName = null,
                networkHandle = null,
                statusMessage = "No active Wi-Fi network found. Connect both phones to the same Wi-Fi LAN."
            )

        val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
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

        val bound = connectivityManager.bindProcessToNetwork(wifiNetwork)
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
     * Restores default process network routing and unregisters any network callback.
     * Safe and idempotent when called multiple times.
     */
    @Synchronized
    fun unbindProcessFromWifi(): WifiBindingStatus {
        unregisterDisconnectMonitor()
        boundNetwork = null
        runCatching {
            connectivityManager.bindProcessToNetwork(null)
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
        val currentBound = boundNetwork
        if (currentBound != null) {
            val props = connectivityManager.getLinkProperties(currentBound)
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
        val props = candidate?.let { connectivityManager.getLinkProperties(it) }
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
        val active = connectivityManager.activeNetwork
        if (active != null && isWifiTransport(active)) {
            return active
        }
        for (network in connectivityManager.allNetworks) {
            if (isWifiTransport(network)) {
                return network
            }
        }
        return null
    }

    private fun isWifiTransport(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
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
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        }
    }

    private fun unregisterDisconnectMonitor() {
        val existing = networkCallback ?: return
        networkCallback = null
        runCatching {
            connectivityManager.unregisterNetworkCallback(existing)
        }
    }
}
