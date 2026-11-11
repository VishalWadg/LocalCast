package com.localcast.poc.network

import com.localcast.poc.model.DiscoveredHost
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Pure-Kotlin protocol helpers, self-filtering rules, TXT record encoding/decoding,
 * serialized resolution queue, and host registry for M5 ZeroConf / mDNS discovery.
 */
object NsdDiscoveryProtocol {

    const val SERVICE_TYPE = "_localcast._tcp"
    const val MAX_SERVICE_NAME_UTF8_BYTES = 63
    const val MAX_TXT_NAME_UTF8_BYTES = 120

    // Safe DNS-SD TXT attribute keys (<= 9 US-ASCII chars per Android NsdServiceInfo guidelines).
    // Security rule: Never publish `sessionCode` or any join PIN over LAN multicast.
    const val TXT_KEY_DEVICE_ID = "deviceId"
    const val TXT_KEY_DISPLAY_NAME = "name"
    const val TXT_KEY_PROTOCOL_VERSION = "proto"
    const val TXT_KEY_CODE_REQUIRED = "codeReq"

    /**
     * Checks whether [serviceType] matches `_localcast._tcp`, tolerating leading/trailing DNS dots
     * returned across different Android API versions (e.g. `_localcast._tcp.` or `._localcast._tcp`).
     */
    fun isExpectedServiceType(serviceType: String?): Boolean {
        val normalized = serviceType?.trim()?.trim('.')?.lowercase() ?: return false
        return normalized == SERVICE_TYPE
    }

    /**
     * Builds a collision-resistant, DNS-SD-compliant service instance name within the 63-byte UTF-8 limit.
     * Never includes `sessionCode`.
     */
    fun buildAdvertisedServiceName(displayName: String, deviceId: String): String {
        val cleanName = sanitizeDisplayText(displayName)
        val cleanDeviceId = sanitizeDisplayText(deviceId)
        val shortId = truncateUtf8(takeLastCodePoints(cleanDeviceId, 4), 12).ifBlank { "host" }
        val suffix = " [$shortId]"
        val prefix = "LocalCast - "

        if (cleanName.isBlank()) {
            return truncateUtf8("LocalCast ($shortId)", MAX_SERVICE_NAME_UTF8_BYTES)
        }

        val fixedBytes = prefix.toByteArray(Charsets.UTF_8).size +
            suffix.toByteArray(Charsets.UTF_8).size
        val nameBudget = (MAX_SERVICE_NAME_UTF8_BYTES - fixedBytes).coerceAtLeast(0)
        val truncatedName = truncateUtf8(cleanName, nameBudget).trim()

        return if (truncatedName.isBlank()) {
            truncateUtf8("LocalCast ($shortId)", MAX_SERVICE_NAME_UTF8_BYTES)
        } else {
            "$prefix$truncatedName$suffix"
        }
    }

    /**
     * Builds safe, non-sensitive TXT record attributes for `_localcast._tcp`.
     * Never exposes the 6-digit `sessionCode`.
     */
    fun buildSafeTxtAttributes(
        deviceId: String,
        displayName: String,
        protocolVersion: Int,
        codeRequired: Boolean
    ): Map<String, String> {
        val safeDeviceId = truncateUtf8(sanitizeDisplayText(deviceId).ifBlank { "unknown" }, 64)
        val safeName = truncateUtf8(
            sanitizeDisplayText(displayName).ifBlank { "LocalCast Host" },
            MAX_TXT_NAME_UTF8_BYTES
        )
        val safeProto = protocolVersion.coerceAtLeast(1).toString()
        return linkedMapOf(
            TXT_KEY_DEVICE_ID to safeDeviceId,
            TXT_KEY_DISPLAY_NAME to safeName,
            TXT_KEY_PROTOCOL_VERSION to safeProto,
            TXT_KEY_CODE_REQUIRED to codeRequired.toString()
        )
    }

    /**
     * Selects the best IPv4 LAN address from [addresses] (and optional [fallbackHost]).
     * Prefers site-local non-loopback IPv4 (e.g. `192.168.x.x`, `10.x.x.x`, `172.16.x.x`),
     * then any non-link-local routable IPv4, then link-local IPv4. Rejects loopback (`127.0.0.1`),
     * wildcard (`0.0.0.0`), multicast, and IPv6 addresses.
     */
    fun selectPreferredIpv4(
        addresses: List<InetAddress>,
        fallbackHost: InetAddress? = null
    ): String? {
        val candidates = buildList {
            addAll(addresses)
            if (fallbackHost != null) add(fallbackHost)
        }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }

        val siteLocal = candidates.firstOrNull { it.isSiteLocalAddress }
        val nonLinkLocal = candidates.firstOrNull { !it.isLinkLocalAddress }
        return (siteLocal ?: nonLinkLocal ?: candidates.firstOrNull())?.hostAddress
    }

    /**
     * Parses a resolved NSD service into a [DiscoveredHost], or returns `null` if the
     * resolved info lacks a valid IPv4 host address or TCP port.
     */
    fun parseDiscoveredHost(
        serviceName: String,
        hostAddresses: List<InetAddress>,
        fallbackHost: InetAddress?,
        port: Int,
        attributes: Map<String, ByteArray?>,
        observedAtMs: Long = System.currentTimeMillis()
    ): DiscoveredHost? {
        val cleanServiceName = serviceName.trim()
        if (cleanServiceName.isBlank()) return null
        if (port !in 1..65535) return null

        val ipv4 = selectPreferredIpv4(hostAddresses, fallbackHost) ?: return null
        val deviceId = decodeTxtValue(attributes[TXT_KEY_DEVICE_ID])?.takeIf { it.isNotBlank() }
        val txtDisplayName = decodeTxtValue(attributes[TXT_KEY_DISPLAY_NAME])?.takeIf { it.isNotBlank() }
        val protocolVersion = decodeTxtValue(attributes[TXT_KEY_PROTOCOL_VERSION])
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
        val codeReqToken = decodeTxtValue(attributes[TXT_KEY_CODE_REQUIRED])?.lowercase()
        val codeRequired = codeReqToken == "true" || codeReqToken == "1"

        val displayName = txtDisplayName ?: cleanServiceName

        return DiscoveredHost(
            serviceName = cleanServiceName,
            displayName = displayName,
            deviceId = deviceId,
            hostAddress = ipv4,
            port = port,
            protocolVersion = protocolVersion,
            codeRequired = codeRequired,
            lastSeenAtMs = observedAtMs
        )
    }

    /**
     * Returns true when a discovered service belongs to the current device and should be hidden.
     */
    fun shouldFilterSelf(
        localDeviceId: String,
        activeAdvertisedServiceName: String?,
        candidateDeviceId: String?,
        candidateServiceName: String
    ): Boolean {
        if (!candidateDeviceId.isNullOrBlank() && candidateDeviceId == localDeviceId) {
            return true
        }
        if (!activeAdvertisedServiceName.isNullOrBlank() &&
            candidateServiceName.trim() == activeAdvertisedServiceName.trim()
        ) {
            return true
        }
        return false
    }

    /**
     * Maps Android NsdManager error codes to concise human-readable diagnostics.
     */
    fun describeNsdError(errorCode: Int): String = when (errorCode) {
        0 -> "INTERNAL_ERROR (0)"
        3 -> "FAILURE_ALREADY_ACTIVE (3)"
        4 -> "FAILURE_MAX_LIMIT (4)"
        5 -> "FAILURE_OPERATION_NOT_RUNNING (5)"
        6 -> "FAILURE_BAD_PARAMETERS (6)"
        7 -> "FAILURE_PERMISSION_DENIED (7)"
        else -> "NSD_ERROR ($errorCode)"
    }

    internal fun truncateUtf8(input: String, maxBytes: Int): String {
        if (maxBytes <= 0 || input.isEmpty()) return ""
        val builder = StringBuilder()
        var usedBytes = 0
        var index = 0
        while (index < input.length) {
            val codePoint = input.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val piece = input.substring(index, index + charCount)
            val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
            if (usedBytes + pieceBytes > maxBytes) break
            builder.append(piece)
            usedBytes += pieceBytes
            index += charCount
        }
        return builder.toString()
    }

    private fun takeLastCodePoints(input: String, maxCodePoints: Int): String {
        if (input.isEmpty() || maxCodePoints <= 0) return ""
        val totalCodePoints = input.codePointCount(0, input.length)
        if (totalCodePoints <= maxCodePoints) return input
        val startIndex = input.offsetByCodePoints(0, totalCodePoints - maxCodePoints)
        return input.substring(startIndex)
    }

    private fun sanitizeDisplayText(raw: String): String {
        return raw.map { ch -> if (ch.isISOControl()) ' ' else ch }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun decodeTxtValue(bytes: ByteArray?): String? {
        if (bytes == null) return null
        return bytes.toString(Charsets.UTF_8).trim()
    }
}

/**
 * Pure-Kotlin single-flight FIFO resolution queue for Android 10–13 (API 29–33),
 * preventing `NsdManager.FAILURE_ALREADY_ACTIVE` when multiple services are discovered concurrently.
 */
class SerialResolutionQueue<T> {

    data class ResolutionDispatch<T>(
        val token: Long,
        val key: String,
        val item: T
    )

    private data class PendingEntry<T>(
        val key: String,
        val item: T
    )

    private val lock = Any()
    private val pending = ArrayDeque<PendingEntry<T>>()
    private var inFlight = false
    private var activeKey: String? = null
    private var activeToken = 0L
    private var nextToken = 0L

    /**
     * Returns a [ResolutionDispatch] immediately if no resolution is in flight, or queues
     * [item] in FIFO order (deduplicating matching [key]s) and returns `null`.
     */
    fun enqueue(key: String, item: T): ResolutionDispatch<T>? {
        val cleanKey = key.trim()
        synchronized(lock) {
            if (!inFlight) {
                inFlight = true
                activeKey = cleanKey
                activeToken = ++nextToken
                return ResolutionDispatch(activeToken, cleanKey, item)
            }
            if (activeKey == cleanKey || pending.any { it.key == cleanKey }) {
                return null
            }
            pending.addLast(PendingEntry(cleanKey, item))
            return null
        }
    }

    /**
     * Completes the active resolution identified by [token] and dispatches the next queued item,
     * if any. Stale tokens (e.g., from an earlier cancelled discovery or expired timeout) are ignored.
     */
    fun completeAndNext(token: Long): ResolutionDispatch<T>? {
        synchronized(lock) {
            if (!inFlight || token != activeToken) {
                return null
            }
            if (pending.isEmpty()) {
                inFlight = false
                activeKey = null
                return null
            }
            val next = pending.removeFirst()
            activeKey = next.key
            activeToken = ++nextToken
            return ResolutionDispatch(activeToken, next.key, next.item)
        }
    }

    /**
     * Drops any queued (not yet started) entry matching [key] when a service disappears.
     */
    fun removePending(key: String) {
        val cleanKey = key.trim()
        synchronized(lock) {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().key == cleanKey) {
                    iterator.remove()
                }
            }
        }
    }

    fun isInFlight(): Boolean = synchronized(lock) { inFlight }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun reset() {
        synchronized(lock) {
            pending.clear()
            inFlight = false
            activeKey = null
            ++nextToken
        }
    }
}

/**
 * Thread-safe pure-Kotlin registry maintaining the live list of discovered LocalCast hosts,
 * applying self-filtering, deduplication, and deterministic sorting.
 */
class DiscoveredHostRegistry(
    private var localDeviceId: String = ""
) {
    private val lock = Any()
    private var activeAdvertisedServiceName: String? = null
    private val hostsByKey = LinkedHashMap<String, DiscoveredHost>()
    private val serviceNameToKey = HashMap<String, String>()

    fun configureIdentity(localDeviceId: String, advertisedServiceName: String? = null) {
        synchronized(lock) {
            this.localDeviceId = localDeviceId
            this.activeAdvertisedServiceName = advertisedServiceName
            // Purge any cached self entry if identity changed
            val toRemove = hostsByKey.entries
                .filter { (_, host) ->
                    NsdDiscoveryProtocol.shouldFilterSelf(
                        localDeviceId = this.localDeviceId,
                        activeAdvertisedServiceName = this.activeAdvertisedServiceName,
                        candidateDeviceId = host.deviceId,
                        candidateServiceName = host.serviceName
                    )
                }
                .map { it.key }
            toRemove.forEach { key ->
                val removed = hostsByKey.remove(key)
                if (removed != null) {
                    serviceNameToKey.remove(normalizeServiceName(removed.serviceName))
                }
            }
        }
    }

    /**
     * Inserts or updates [host] unless it matches the local device.
     * Returns the updated sorted snapshot.
     */
    fun upsert(host: DiscoveredHost): List<DiscoveredHost> {
        synchronized(lock) {
            if (NsdDiscoveryProtocol.shouldFilterSelf(
                    localDeviceId = localDeviceId,
                    activeAdvertisedServiceName = activeAdvertisedServiceName,
                    candidateDeviceId = host.deviceId,
                    candidateServiceName = host.serviceName
                )
            ) {
                return snapshotLocked()
            }

            val svcKey = normalizeServiceName(host.serviceName)
            val registryKey = host.deviceId?.takeIf { it.isNotBlank() }?.let { "dev:$it" }
                ?: "svc:$svcKey"

            // If this service name previously mapped to a different key, clean up old mapping
            val previousKeyForName = serviceNameToKey[svcKey]
            if (previousKeyForName != null && previousKeyForName != registryKey) {
                hostsByKey.remove(previousKeyForName)
            }

            // If same deviceId previously had another serviceName, clean up stale name mapping
            val previousHost = hostsByKey[registryKey]
            if (previousHost != null) {
                val prevSvcKey = normalizeServiceName(previousHost.serviceName)
                if (prevSvcKey != svcKey) {
                    serviceNameToKey.remove(prevSvcKey)
                }
            }

            hostsByKey[registryKey] = host
            serviceNameToKey[svcKey] = registryKey
            return snapshotLocked()
        }
    }

    /**
     * Removes a host matching [serviceName] when NSD reports service loss.
     * Returns the updated sorted snapshot.
     */
    fun removeByServiceName(serviceName: String): List<DiscoveredHost> {
        return removeByServiceNameTracked(serviceName).second
    }

    /**
     * Removes a host matching [serviceName] and returns `Pair(wasRemoved, updatedSnapshot)`.
     */
    fun removeByServiceNameTracked(serviceName: String): Pair<Boolean, List<DiscoveredHost>> {
        val svcKey = normalizeServiceName(serviceName)
        synchronized(lock) {
            var removed = false
            val mappedKey = serviceNameToKey[svcKey]
            if (mappedKey != null) {
                val existing = hostsByKey[mappedKey]
                if (existing != null && normalizeServiceName(existing.serviceName) == svcKey) {
                    hostsByKey.remove(mappedKey)
                    removed = true
                }
                serviceNameToKey.remove(svcKey)
            }
            return removed to snapshotLocked()
        }
    }

    fun snapshot(): List<DiscoveredHost> = synchronized(lock) { snapshotLocked() }

    fun clear() {
        synchronized(lock) {
            hostsByKey.clear()
            serviceNameToKey.clear()
        }
    }

    private fun snapshotLocked(): List<DiscoveredHost> {
        return hostsByKey.values.sortedWith(
            compareBy<DiscoveredHost>(
                { it.displayName.lowercase() },
                { it.hostAddress },
                { it.port }
            )
        )
    }

    private fun normalizeServiceName(serviceName: String): String = serviceName.trim()
}
