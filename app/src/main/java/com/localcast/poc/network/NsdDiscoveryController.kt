package com.localcast.poc.network

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * M5: Platform implementation of [LocalServiceDiscovery] backed by Android's built-in
 * [NsdManager] and [WifiManager.MulticastLock].
 *
 * Cross-API compatibility (minSdk 29 to targetSdk 37):
 * - API 29–32: Uses `WifiManager.MulticastLock` when needed (`SDK_INT < 30` or T Ext < 7),
 *   3-arg `discoverServices` / `registerService`, and a [SerialResolutionQueue] around
 *   `resolveService` so concurrent discoveries never trigger `FAILURE_ALREADY_ACTIVE`.
 * - API 33: Scopes registration (`NsdServiceInfo.setNetwork`) and discovery (`Network` /
 *   `NetworkRequest`) to the active Wi-Fi transport and runs callbacks on a dedicated executor.
 * - API 34–37: Uses `NsdManager.registerServiceInfoCallback` (`ServiceInfoCallback`) and
 *   `NsdServiceInfo.getHostAddresses()` for continuous live host/IP/TXT tracking and clean loss
 *   detection without deprecated `resolveService` or `getHost()`.
 */
class NsdDiscoveryController(
    context: Context
) : LocalServiceDiscovery {

    private val appContext = context.applicationContext ?: context
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val lock = Any()
    private val resolutionQueue = SerialResolutionQueue<NsdServiceInfo>()

    private var executor: ScheduledExecutorService? = null

    // Advertising state
    private var advertisingEpoch = 0L
    private var activeRegistrationListener: NsdManager.RegistrationListener? = null
    private var activeAdvertisingListener: LocalServiceDiscoveryListener? = null
    @Volatile
    private var activeRegisteredServiceName: String? = null
    private var advertiseMulticastLock: WifiManager.MulticastLock? = null

    // Discovery state
    private var discoveryEpoch = 0L
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var activeDiscoveryCallback: LocalServiceDiscoveryListener? = null
    private var activeDiscoveryLocalDeviceId: String = ""
    private var activeDiscoveryWifiNetwork: Network? = null
    private var discoveryMulticastLock: WifiManager.MulticastLock? = null
    private var activeResolveTimeoutFuture: ScheduledFuture<*>? = null
    private val activeServiceCallbacks = HashMap<String, Any>()
    private val discoveredServiceNames = HashSet<String>()
    private val legacyResolveRetries = HashMap<String, Int>()

    override fun startAdvertising(
        config: HostAdvertisementConfig,
        listener: LocalServiceDiscoveryListener
    ) {
        val manager = nsdManager
        if (manager == null) {
            listener.onAdvertisingFailed("System NsdManager service unavailable")
            return
        }
        if (config.port !in 1..65535) {
            listener.onAdvertisingFailed("Invalid signaling port: ${config.port}")
            return
        }

        val previousReg: NsdManager.RegistrationListener?
        val previousLock: WifiManager.MulticastLock?
        val epoch: Long
        val newLock: WifiManager.MulticastLock?
        val exec: ScheduledExecutorService

        synchronized(lock) {
            previousReg = activeRegistrationListener
            activeRegistrationListener = null
            previousLock = advertiseMulticastLock
            advertiseMulticastLock = null
            activeRegisteredServiceName = null

            epoch = ++advertisingEpoch
            activeAdvertisingListener = listener
            newLock = acquireMulticastLockIfNeeded("LocalCastNsdAdv")
            advertiseMulticastLock = newLock
            exec = ensureExecutorLocked()
        }

        if (previousReg != null) {
            runCatching { manager.unregisterService(previousReg) }
        }
        releaseMulticastLock(previousLock)

        val advertisedName = NsdDiscoveryProtocol.buildAdvertisedServiceName(
            displayName = config.displayName,
            deviceId = config.deviceId
        )
        val safeTxt = NsdDiscoveryProtocol.buildSafeTxtAttributes(
            deviceId = config.deviceId,
            displayName = config.displayName,
            protocolVersion = config.protocolVersion,
            codeRequired = config.codeRequired
        )

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = advertisedName
            serviceType = NsdDiscoveryProtocol.SERVICE_TYPE
            port = config.port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && config.wifiNetwork != null) {
                setNetwork(config.wifiNetwork)
            }
            safeTxt.forEach { (key, value) -> setAttribute(key, value) }
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                val registeredName = registeredInfo.serviceName.orEmpty()
                val callbackToNotify: LocalServiceDiscoveryListener?
                var staleToUnregister = false
                synchronized(lock) {
                    if (epoch != advertisingEpoch) {
                        staleToUnregister = true
                        callbackToNotify = null
                    } else {
                        activeRegisteredServiceName = registeredName
                        callbackToNotify = activeAdvertisingListener
                    }
                }
                if (staleToUnregister) {
                    runCatching { manager.unregisterService(this) }
                    return
                }
                callbackToNotify?.onAdvertisingStarted(registeredName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val callbackToNotify: LocalServiceDiscoveryListener?
                val lockToRelease: WifiManager.MulticastLock?
                synchronized(lock) {
                    if (epoch != advertisingEpoch) return
                    activeRegistrationListener = null
                    activeRegisteredServiceName = null
                    callbackToNotify = activeAdvertisingListener
                    activeAdvertisingListener = null
                    lockToRelease = advertiseMulticastLock
                    advertiseMulticastLock = null
                }
                releaseMulticastLock(lockToRelease)
                callbackToNotify?.onAdvertisingFailed(
                    reason = "NSD registration failed: ${NsdDiscoveryProtocol.describeNsdError(errorCode)}",
                    errorCode = errorCode
                )
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                val callbackToNotify: LocalServiceDiscoveryListener?
                val lockToRelease: WifiManager.MulticastLock?
                synchronized(lock) {
                    if (epoch != advertisingEpoch) return
                    activeRegistrationListener = null
                    activeRegisteredServiceName = null
                    callbackToNotify = activeAdvertisingListener
                    activeAdvertisingListener = null
                    lockToRelease = advertiseMulticastLock
                    advertiseMulticastLock = null
                }
                releaseMulticastLock(lockToRelease)
                callbackToNotify?.onAdvertisingStopped()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val lockToRelease: WifiManager.MulticastLock?
                synchronized(lock) {
                    if (epoch != advertisingEpoch) return
                    lockToRelease = advertiseMulticastLock
                    advertiseMulticastLock = null
                }
                releaseMulticastLock(lockToRelease)
            }
        }

        synchronized(lock) {
            if (epoch != advertisingEpoch) return
            activeRegistrationListener = registrationListener
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    exec,
                    registrationListener
                )
            } else {
                manager.registerService(
                    serviceInfo,
                    NsdManager.PROTOCOL_DNS_SD,
                    registrationListener
                )
            }
        } catch (se: SecurityException) {
            cleanupFailedAdvertising(epoch, "Local network permission denied (${se.message})", 7)
        } catch (e: Exception) {
            cleanupFailedAdvertising(epoch, "NSD registration error: ${e.message ?: "unknown"}", -1)
        }
    }

    override fun stopAdvertising() {
        val manager = nsdManager
        val regToUnregister: NsdManager.RegistrationListener?
        val lockToRelease: WifiManager.MulticastLock?
        val callbackToNotify: LocalServiceDiscoveryListener?

        synchronized(lock) {
            ++advertisingEpoch
            regToUnregister = activeRegistrationListener
            activeRegistrationListener = null
            activeRegisteredServiceName = null
            callbackToNotify = activeAdvertisingListener
            activeAdvertisingListener = null
            lockToRelease = advertiseMulticastLock
            advertiseMulticastLock = null
        }

        if (regToUnregister != null) {
            runCatching { manager?.unregisterService(regToUnregister) }
        }
        releaseMulticastLock(lockToRelease)
        if (regToUnregister != null) {
            callbackToNotify?.onAdvertisingStopped()
        }
    }

    override fun startDiscovery(
        localDeviceId: String,
        wifiNetwork: Network?,
        listener: LocalServiceDiscoveryListener
    ) {
        val manager = nsdManager
        if (manager == null) {
            listener.onDiscoveryFailed("System NsdManager service unavailable")
            return
        }

        // Tear down any previous discovery cleanly before starting a new scan
        stopDiscoveryInternal(notifyStopped = false)

        val epoch: Long
        val exec: ScheduledExecutorService
        synchronized(lock) {
            epoch = ++discoveryEpoch
            activeDiscoveryCallback = listener
            activeDiscoveryLocalDeviceId = localDeviceId
            activeDiscoveryWifiNetwork = wifiNetwork
            discoveryMulticastLock = acquireMulticastLockIfNeeded("LocalCastNsdDisc")
            exec = ensureExecutorLocked()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                val cb: LocalServiceDiscoveryListener?
                var staleToStop = false
                synchronized(lock) {
                    if (epoch == discoveryEpoch) {
                        cb = activeDiscoveryCallback
                    } else {
                        cb = null
                        staleToStop = true
                    }
                }
                if (staleToStop) {
                    runCatching { manager.stopServiceDiscovery(this) }
                    return
                }
                cb?.onDiscoveryStarted()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                val cb = cleanupFailedDiscovery(epoch)
                cb?.onDiscoveryFailed(
                    reason = "NSD discovery failed to start: ${NsdDiscoveryProtocol.describeNsdError(errorCode)}",
                    errorCode = errorCode
                )
            }

            override fun onDiscoveryStopped(serviceType: String) {
                // Stop notification is emitted synchronously by stopDiscoveryInternal()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Best-effort teardown; locks and callbacks already cleaned up on stop
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val foundName = serviceInfo.serviceName?.trim()?.takeIf { it.isNotBlank() } ?: return
                val shouldProcess = synchronized(lock) {
                    val active = epoch == discoveryEpoch &&
                        NsdDiscoveryProtocol.isExpectedServiceType(serviceInfo.serviceType)
                    if (active) {
                        discoveredServiceNames.add(foundName)
                    }
                    active
                }
                if (!shouldProcess) return

                // Quick self-filter on exact registered service name before resolving
                val currentAdvName = activeRegisteredServiceName
                if (!currentAdvName.isNullOrBlank() &&
                    foundName == currentAdvName.trim()
                ) {
                    synchronized(lock) {
                        if (epoch == discoveryEpoch) {
                            discoveredServiceNames.remove(foundName)
                        }
                    }
                    return
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    registerContinuousServiceInfoCallback(
                        manager = manager,
                        epoch = epoch,
                        foundInfo = serviceInfo,
                        wifiNetwork = wifiNetwork,
                        exec = exec
                    )
                } else {
                    enqueueSerialLegacyResolution(
                        manager = manager,
                        epoch = epoch,
                        foundInfo = serviceInfo,
                        exec = exec
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName?.trim()?.takeIf { it.isNotBlank() } ?: return
                val cbToUnregister: Any?
                val listenerToNotify: LocalServiceDiscoveryListener?

                synchronized(lock) {
                    if (epoch != discoveryEpoch) return
                    discoveredServiceNames.remove(lostName)
                    legacyResolveRetries.remove(lostName)
                    resolutionQueue.removePending(lostName)
                    cbToUnregister = activeServiceCallbacks.remove(lostName)
                    listenerToNotify = activeDiscoveryCallback
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cbToUnregister != null) {
                    Api34Helper.unregister(manager, cbToUnregister)
                }
                listenerToNotify?.onHostLost(lostName)
            }
        }

        synchronized(lock) {
            if (epoch != discoveryEpoch) return
            activeDiscoveryListener = discoveryListener
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (wifiNetwork != null) {
                    manager.discoverServices(
                        NsdDiscoveryProtocol.SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        wifiNetwork,
                        exec,
                        discoveryListener
                    )
                } else {
                    val wifiRequest = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()
                    manager.discoverServices(
                        NsdDiscoveryProtocol.SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        wifiRequest,
                        exec,
                        discoveryListener
                    )
                }
            } else {
                manager.discoverServices(
                    NsdDiscoveryProtocol.SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            }
        } catch (se: SecurityException) {
            val cb = cleanupFailedDiscovery(epoch)
            cb?.onDiscoveryFailed(
                reason = "Local network permission denied (${se.message})",
                errorCode = 7
            )
        } catch (e: Exception) {
            val cb = cleanupFailedDiscovery(epoch)
            cb?.onDiscoveryFailed(
                reason = "NSD discovery error: ${e.message ?: "unknown"}",
                errorCode = -1
            )
        }
    }

    override fun stopDiscovery() {
        stopDiscoveryInternal(notifyStopped = true)
    }

    override fun release() {
        stopDiscoveryInternal(notifyStopped = false)
        stopAdvertising()
        val execToShutdown: ScheduledExecutorService?
        synchronized(lock) {
            execToShutdown = executor
            executor = null
        }
        runCatching { execToShutdown?.shutdownNow() }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerContinuousServiceInfoCallback(
        manager: NsdManager,
        epoch: Long,
        foundInfo: NsdServiceInfo,
        wifiNetwork: Network?,
        exec: ScheduledExecutorService
    ) {
        val serviceKey = foundInfo.serviceName?.trim()?.takeIf { it.isNotBlank() } ?: return
        val targetInfo = NsdServiceInfo().apply {
            serviceName = serviceKey
            serviceType = NsdDiscoveryProtocol.SERVICE_TYPE
            val scopedNet = foundInfo.network ?: wifiNetwork
            if (scopedNet != null) {
                setNetwork(scopedNet)
            }
        }

        val infoCallback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val cb: LocalServiceDiscoveryListener?
                val localId: String
                synchronized(lock) {
                    if (epoch != discoveryEpoch || !discoveredServiceNames.contains(serviceKey)) return
                    cb = activeDiscoveryCallback
                    localId = activeDiscoveryLocalDeviceId
                }
                val host = extractHostFromServiceInfo(serviceInfo) ?: return
                if (NsdDiscoveryProtocol.shouldFilterSelf(
                        localDeviceId = localId,
                        activeAdvertisedServiceName = activeRegisteredServiceName,
                        candidateDeviceId = host.deviceId,
                        candidateServiceName = host.serviceName
                    )
                ) {
                    return
                }
                cb?.onHostResolved(host)
            }

            override fun onServiceLost() {
                val cb = synchronized(lock) {
                    if (epoch != discoveryEpoch) return
                    activeDiscoveryCallback
                }
                // Keep callback registered per Android NsdManager docs so onServiceUpdated can
                // recover if the host reappears; DiscoveryListener.onServiceLost unregisters it.
                cb?.onHostLost(serviceKey)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                synchronized(lock) {
                    if (activeServiceCallbacks[serviceKey] === this) {
                        activeServiceCallbacks.remove(serviceKey)
                    }
                }
                if (errorCode == NsdManager.FAILURE_PERMISSION_DENIED) {
                    val cb = cleanupFailedDiscovery(epoch, stopDiscoveryListener = true)
                    cb?.onDiscoveryFailed(
                        reason = "Local network permission required: ${NsdDiscoveryProtocol.describeNsdError(errorCode)}",
                        errorCode = errorCode
                    )
                }
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

        val registeredNew = synchronized(lock) {
            if (epoch != discoveryEpoch ||
                !discoveredServiceNames.contains(serviceKey) ||
                activeServiceCallbacks.containsKey(serviceKey)
            ) {
                false
            } else {
                activeServiceCallbacks[serviceKey] = infoCallback
                true
            }
        }
        if (!registeredNew) return

        try {
            manager.registerServiceInfoCallback(targetInfo, exec, infoCallback)
        } catch (se: SecurityException) {
            synchronized(lock) {
                if (activeServiceCallbacks[serviceKey] === infoCallback) {
                    activeServiceCallbacks.remove(serviceKey)
                }
            }
            val cb = cleanupFailedDiscovery(epoch, stopDiscoveryListener = true)
            cb?.onDiscoveryFailed("Local network permission denied (${se.message})", 7)
        } catch (_: Exception) {
            synchronized(lock) {
                if (activeServiceCallbacks[serviceKey] === infoCallback) {
                    activeServiceCallbacks.remove(serviceKey)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enqueueSerialLegacyResolution(
        manager: NsdManager,
        epoch: Long,
        foundInfo: NsdServiceInfo,
        exec: ScheduledExecutorService
    ) {
        val serviceKey = foundInfo.serviceName?.trim()?.takeIf { it.isNotBlank() } ?: return
        val dispatch = synchronized(lock) {
            if (epoch != discoveryEpoch || !discoveredServiceNames.contains(serviceKey)) return
            resolutionQueue.enqueue(serviceKey, foundInfo)
        } ?: return

        dispatchLegacyResolve(manager, epoch, dispatch, exec)
    }

    @Suppress("DEPRECATION")
    private fun dispatchLegacyResolve(
        manager: NsdManager,
        epoch: Long,
        dispatch: SerialResolutionQueue.ResolutionDispatch<NsdServiceInfo>,
        exec: ScheduledExecutorService
    ) {
        val stillValid = synchronized(lock) { epoch == discoveryEpoch }
        if (!stillValid) return

        val timeoutFuture = runCatching {
            exec.schedule({
                val next = synchronized(lock) {
                    if (epoch != discoveryEpoch) null
                    else resolutionQueue.completeAndNext(dispatch.token)
                }
                if (next != null) {
                    dispatchLegacyResolve(manager, epoch, next, exec)
                }
            }, LEGACY_RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()

        synchronized(lock) {
            if (epoch != discoveryEpoch) {
                timeoutFuture?.cancel(false)
                return
            }
            activeResolveTimeoutFuture?.cancel(false)
            activeResolveTimeoutFuture = timeoutFuture
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                val cb: LocalServiceDiscoveryListener?
                val localId: String
                val stillPresent: Boolean
                val next: SerialResolutionQueue.ResolutionDispatch<NsdServiceInfo>?

                synchronized(lock) {
                    timeoutFuture?.cancel(false)
                    if (epoch != discoveryEpoch) return
                    stillPresent = discoveredServiceNames.contains(dispatch.key)
                    legacyResolveRetries.remove(dispatch.key)
                    cb = activeDiscoveryCallback
                    localId = activeDiscoveryLocalDeviceId
                    next = resolutionQueue.completeAndNext(dispatch.token)
                }

                if (stillPresent) {
                    val host = extractHostFromServiceInfo(resolvedInfo)
                    if (host != null && !NsdDiscoveryProtocol.shouldFilterSelf(
                            localDeviceId = localId,
                            activeAdvertisedServiceName = activeRegisteredServiceName,
                            candidateDeviceId = host.deviceId,
                            candidateServiceName = host.serviceName
                        )
                    ) {
                        cb?.onHostResolved(host)
                    }
                }

                if (next != null) {
                    dispatchLegacyResolve(manager, epoch, next, exec)
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                val next: SerialResolutionQueue.ResolutionDispatch<NsdServiceInfo>?
                var shouldRetry = false
                synchronized(lock) {
                    timeoutFuture?.cancel(false)
                    if (epoch != discoveryEpoch) return
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE &&
                        discoveredServiceNames.contains(dispatch.key)
                    ) {
                        val retries = legacyResolveRetries[dispatch.key] ?: 0
                        if (retries < MAX_LEGACY_RESOLVE_RETRIES) {
                            legacyResolveRetries[dispatch.key] = retries + 1
                            shouldRetry = true
                        }
                    }
                    next = resolutionQueue.completeAndNext(dispatch.token)
                }
                if (shouldRetry) {
                    runCatching {
                        exec.schedule({
                            enqueueSerialLegacyResolution(manager, epoch, dispatch.item, exec)
                        }, LEGACY_RESOLVE_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                    }
                }
                if (next != null) {
                    dispatchLegacyResolve(manager, epoch, next, exec)
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.resolveService(dispatch.item, exec, resolveListener)
            } else {
                manager.resolveService(dispatch.item, resolveListener)
            }
        } catch (_: Exception) {
            val next = synchronized(lock) {
                timeoutFuture?.cancel(false)
                if (epoch != discoveryEpoch) null
                else resolutionQueue.completeAndNext(dispatch.token)
            }
            if (next != null) {
                dispatchLegacyResolve(manager, epoch, next, exec)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractHostFromServiceInfo(serviceInfo: NsdServiceInfo): com.localcast.poc.model.DiscoveredHost? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { serviceInfo.hostAddresses }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val fallbackHost = runCatching { serviceInfo.host }.getOrNull()
        val attrs = runCatching { serviceInfo.attributes }.getOrDefault(emptyMap())
        return NsdDiscoveryProtocol.parseDiscoveredHost(
            serviceName = serviceInfo.serviceName.orEmpty(),
            hostAddresses = addresses,
            fallbackHost = fallbackHost,
            port = serviceInfo.port,
            attributes = attrs
        )
    }

    private fun stopDiscoveryInternal(notifyStopped: Boolean) {
        val manager = nsdManager
        val discToStop: NsdManager.DiscoveryListener?
        val callbacksToUnregister: List<Any>
        val lockToRelease: WifiManager.MulticastLock?
        val callbackToNotify: LocalServiceDiscoveryListener?
        val timeoutToCancel: ScheduledFuture<*>?

        synchronized(lock) {
            ++discoveryEpoch
            resolutionQueue.reset()
            discoveredServiceNames.clear()
            legacyResolveRetries.clear()
            timeoutToCancel = activeResolveTimeoutFuture
            activeResolveTimeoutFuture = null
            discToStop = activeDiscoveryListener
            activeDiscoveryListener = null
            callbacksToUnregister = activeServiceCallbacks.values.toList()
            activeServiceCallbacks.clear()
            callbackToNotify = activeDiscoveryCallback
            activeDiscoveryCallback = null
            activeDiscoveryWifiNetwork = null
            lockToRelease = discoveryMulticastLock
            discoveryMulticastLock = null
        }

        timeoutToCancel?.cancel(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && manager != null) {
            callbacksToUnregister.forEach { cb ->
                Api34Helper.unregister(manager, cb)
            }
        }
        if (discToStop != null) {
            runCatching { manager?.stopServiceDiscovery(discToStop) }
        }
        releaseMulticastLock(lockToRelease)
        if (notifyStopped && discToStop != null) {
            callbackToNotify?.onDiscoveryStopped()
        }
    }

    private fun cleanupFailedDiscovery(
        epoch: Long,
        stopDiscoveryListener: Boolean = false
    ): LocalServiceDiscoveryListener? {
        val manager = nsdManager
        val discToStop: NsdManager.DiscoveryListener?
        val callbacksToUnregister: List<Any>
        val lockToRelease: WifiManager.MulticastLock?
        val callbackToNotify: LocalServiceDiscoveryListener?
        val timeoutToCancel: ScheduledFuture<*>?

        synchronized(lock) {
            if (epoch != discoveryEpoch) return null
            ++discoveryEpoch
            resolutionQueue.reset()
            discoveredServiceNames.clear()
            legacyResolveRetries.clear()
            timeoutToCancel = activeResolveTimeoutFuture
            activeResolveTimeoutFuture = null
            discToStop = activeDiscoveryListener
            activeDiscoveryListener = null
            callbacksToUnregister = activeServiceCallbacks.values.toList()
            activeServiceCallbacks.clear()
            callbackToNotify = activeDiscoveryCallback
            activeDiscoveryCallback = null
            activeDiscoveryWifiNetwork = null
            lockToRelease = discoveryMulticastLock
            discoveryMulticastLock = null
        }

        timeoutToCancel?.cancel(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && manager != null) {
            callbacksToUnregister.forEach { cb ->
                Api34Helper.unregister(manager, cb)
            }
        }
        if (stopDiscoveryListener && discToStop != null) {
            runCatching { manager?.stopServiceDiscovery(discToStop) }
        }
        releaseMulticastLock(lockToRelease)
        return callbackToNotify
    }

    private fun cleanupFailedAdvertising(epoch: Long, reason: String, errorCode: Int) {
        val callbackToNotify: LocalServiceDiscoveryListener?
        val lockToRelease: WifiManager.MulticastLock?
        synchronized(lock) {
            if (epoch != advertisingEpoch) return
            activeRegistrationListener = null
            activeRegisteredServiceName = null
            callbackToNotify = activeAdvertisingListener
            activeAdvertisingListener = null
            lockToRelease = advertiseMulticastLock
            advertiseMulticastLock = null
        }
        releaseMulticastLock(lockToRelease)
        callbackToNotify?.onAdvertisingFailed(reason, errorCode)
    }

    private fun ensureExecutorLocked(): ScheduledExecutorService {
        val existing = executor
        if (existing != null && !existing.isShutdown) {
            return existing
        }
        return Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LocalCast-Nsd").apply { isDaemon = true }
        }.also { executor = it }
    }

    private fun needsMulticastLock(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
    }

    private fun acquireMulticastLockIfNeeded(tag: String): WifiManager.MulticastLock? {
        if (!needsMulticastLock()) return null
        val wm = wifiManager ?: return null
        return runCatching {
            wm.createMulticastLock(tag).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock(lockToRelease: WifiManager.MulticastLock?) {
        if (lockToRelease == null) return
        runCatching {
            if (lockToRelease.isHeld) {
                lockToRelease.release()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34Helper {
        fun unregister(manager: NsdManager, callback: Any) {
            val typed = callback as? NsdManager.ServiceInfoCallback ?: return
            runCatching { manager.unregisterServiceInfoCallback(typed) }
        }
    }

    private companion object {
        const val LEGACY_RESOLVE_TIMEOUT_MS = 5000L
        const val LEGACY_RESOLVE_RETRY_DELAY_MS = 350L
        const val MAX_LEGACY_RESOLVE_RETRIES = 2
    }
}
