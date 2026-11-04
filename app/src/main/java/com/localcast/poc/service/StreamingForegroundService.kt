package com.localcast.poc.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.localcast.poc.network.LocalNetworkPermissionHelper

/**
 * M4: Owns the active background streaming lifecycle (Spec Section 3 & Section 8).
 *
 * Startup rules enforced:
 * 1. Must be started from a visible Activity after runtime permissions are checked.
 * 2. Promotes immediately via `startForeground` with an ongoing notification and a Stop action.
 * 3. Dynamically matches `FOREGROUND_SERVICE_TYPE_CAMERA` and `FOREGROUND_SERVICE_TYPE_MICROPHONE`
 *    to granted while-in-use permissions so Android 14–17 never throws a SecurityException.
 * 4. Stopping from the notification triggers `SessionRuntimeCoordinator.stopSession()` and
 *    releases all media and network resources.
 */
class StreamingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = SessionRuntimeCoordinator.getInstance(applicationContext)

        if (intent?.action == StreamingNotification.ACTION_STOP_STREAMING) {
            coordinator.onForegroundServiceStoppedFromNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "LocalCast Active Session"
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE)
            ?: "Sharing camera & microphone on local Wi-Fi"

        val notification = StreamingNotification.buildOngoingNotification(
            context = this,
            title = title,
            contentText = subtitle
        )

        val fgsType = resolveForegroundServiceType()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && fgsType != 0) {
                startForeground(StreamingNotification.NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(StreamingNotification.NOTIFICATION_ID, notification)
            }
            coordinator.onForegroundServiceStateChanged(
                isRunning = true,
                serviceTypesSummary = describeServiceTypes(fgsType),
                notificationText = subtitle
            )
        } catch (se: SecurityException) {
            coordinator.onForegroundServiceError(
                "Foreground service start rejected by OS (${se.message}). Start sharing from the visible app."
            )
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SessionRuntimeCoordinator.getInstance(applicationContext).onForegroundServiceStateChanged(
            isRunning = false,
            serviceTypesSummary = "none",
            notificationText = "Foreground service stopped"
        )
        super.onDestroy()
    }

    private fun resolveForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var mask = 0
        if (LocalNetworkPermissionHelper.isCameraGranted(this)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (LocalNetworkPermissionHelper.isMicrophoneGranted(this)) {
            mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return mask
    }

    private fun describeServiceTypes(mask: Int): String {
        val types = mutableListOf<String>()
        if ((mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0) types.add("camera")
        if ((mask and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) types.add("microphone")
        return if (types.isEmpty()) "standard" else types.joinToString("|")
    }

    companion object {
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_SUBTITLE = "extra_subtitle"

        fun startOrUpdate(context: Context, title: String, subtitle: String) {
            val appContext = context.applicationContext
            // Android 14+ requires CAMERA or RECORD_AUDIO permission before starting a camera|microphone FGS
            if (!LocalNetworkPermissionHelper.isCameraGranted(appContext) &&
                !LocalNetworkPermissionHelper.isMicrophoneGranted(appContext)
            ) {
                return
            }
            val intent = Intent(appContext, StreamingForegroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            runCatching {
                appContext.stopService(Intent(appContext, StreamingForegroundService::class.java))
            }
        }
    }
}
