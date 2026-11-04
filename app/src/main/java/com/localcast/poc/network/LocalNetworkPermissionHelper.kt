package com.localcast.poc.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.localcast.poc.model.PermissionSnapshot

/**
 * Handles conditional runtime permission checks for `android.permission.ACCESS_LOCAL_NETWORK`
 * (Android 17 / API 37+) alongside Camera, Microphone, and Notification permissions.
 *
 * Requirement 1:
 * - Android 17 (API 37+) enforces `ACCESS_LOCAL_NETWORK` at runtime for apps targeting SDK 37+.
 * - Android 10–16 (API 29–36) grants local LAN socket access implicitly via `INTERNET`;
 *   we must NOT request `ACCESS_LOCAL_NETWORK` on older OS versions even when targetSdk = 37.
 */
object LocalNetworkPermissionHelper {

    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    const val ANDROID_17_API_LEVEL = 37
    const val ANDROID_13_API_LEVEL = 33

    /**
     * Returns true ONLY when the device is running Android 17 (API 37) or newer.
     */
    fun requiresRuntimeLocalNetworkPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= ANDROID_17_API_LEVEL
    }

    /**
     * Returns true when `POST_NOTIFICATIONS` must be requested at runtime (Android 13 / API 33+).
     */
    fun requiresRuntimeNotificationPermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt >= ANDROID_13_API_LEVEL
    }

    /**
     * Checks whether local network communication is currently authorized by the OS.
     */
    fun isLocalNetworkAuthorized(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!requiresRuntimeLocalNetworkPermission(sdkInt)) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            PERMISSION_ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isCameraGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isMicrophoneGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationGranted(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): Boolean {
        if (!requiresRuntimeNotificationPermission(sdkInt)) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the list of runtime permissions needed before starting Publisher (Phone A) mode.
     */
    fun requiredPublisherPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (requiresRuntimeNotificationPermission(sdkInt)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requiresRuntimeLocalNetworkPermission(sdkInt)) {
            perms.add(PERMISSION_ACCESS_LOCAL_NETWORK)
        }
        return perms
    }

    /**
     * Returns the list of runtime permissions needed before starting Receiver (Phone B) mode.
     */
    fun requiredReceiverPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        val perms = mutableListOf<String>()
        if (requiresRuntimeNotificationPermission(sdkInt)) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (requiresRuntimeLocalNetworkPermission(sdkInt)) {
            perms.add(PERMISSION_ACCESS_LOCAL_NETWORK)
        }
        return perms
    }

    /**
     * Builds a diagnostic snapshot of all relevant runtime permissions.
     */
    fun evaluateStatus(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): PermissionSnapshot {
        val reqLocalNet = requiresRuntimeLocalNetworkPermission(sdkInt)
        val localNetOk = isLocalNetworkAuthorized(context, sdkInt)
        val camOk = isCameraGranted(context)
        val micOk = isMicrophoneGranted(context)
        val notifOk = isNotificationGranted(context, sdkInt)

        val lnpSummary = when {
            !reqLocalNet -> "API $sdkInt (<37): Implicit LAN via INTERNET"
            localNetOk -> "API $sdkInt (>=37): ACCESS_LOCAL_NETWORK Granted"
            else -> "API $sdkInt (>=37): ACCESS_LOCAL_NETWORK Required"
        }
        val mediaSummary = "Camera=${if (camOk) "OK" else "Prompt"} · Mic=${if (micOk) "OK" else "Prompt"}"

        return PermissionSnapshot(
            requiresLocalNetworkRuntimePermission = reqLocalNet,
            localNetworkGranted = localNetOk,
            cameraGranted = camOk,
            microphoneGranted = micOk,
            notificationsGranted = notifOk,
            sdkInt = sdkInt,
            policySummary = "$lnpSummary | $mediaSummary"
        )
    }
}
