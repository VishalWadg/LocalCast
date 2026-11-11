package com.localcast.poc.model

/**
 * M5: High-level state of local Wi-Fi mDNS / NSD service discovery.
 */
enum class DiscoveryStatus {
    IDLE,
    PERMISSION_REQUIRED,
    WIFI_UNAVAILABLE,
    SCANNING,
    ERROR
}

/**
 * M5: Resolved LocalCast host discovered on the local Wi-Fi network via NsdManager (`_localcast._tcp`).
 *
 * Security note: Never contains the host's 6-digit `sessionCode`.
 */
data class DiscoveredHost(
    val serviceName: String,
    val displayName: String,
    val deviceId: String?,
    val hostAddress: String,
    val port: Int,
    val protocolVersion: Int = 1,
    val codeRequired: Boolean = false,
    val lastSeenAtMs: Long = System.currentTimeMillis()
)

/**
 * M5: Observable discovery & advertisement state exposed in [SessionState].
 */
data class DiscoveryState(
    val status: DiscoveryStatus = DiscoveryStatus.IDLE,
    val isBrowsing: Boolean = false,
    val isAdvertising: Boolean = false,
    val advertisedServiceName: String? = null,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
    val statusMessage: String = "Discovery idle",
    val lastError: String? = null
)
