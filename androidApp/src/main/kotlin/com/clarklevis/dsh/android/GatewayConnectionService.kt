package com.clarklevis.dsh.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.clarklevis.dsh.android.platform.GatewayLifecycleEvent
import com.clarklevis.dsh.android.ui.AppearanceSettings
import kotlinx.coroutines.launch

/**
 * 回合、内嵌隧道或用户开启后台常驻时运行，复用同一条常驻通知。
 */
class GatewayConnectionService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppearanceSettings.localizedContext(newBase))
    }

    private val graph: AndroidAppGraph
        get() = (application as DshAndroidApplication).graph

    override fun onCreate() {
        super.onCreate()
        graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_CREATED)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_DISCONNECT)
            graph.gatewayScope.launch { graph.gatewayRuntime.disconnect() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!(application as DshAndroidApplication).hosts.connectionKeepAliveRequested) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, makeNotification())
            graph.diagnostics.lifecycle(GatewayLifecycleEvent.SERVICE_STARTED)
        } catch (error: IllegalStateException) {
            startFailed(error)
        } catch (error: SecurityException) {
            startFailed(error)
        }
        return START_NOT_STICKY
    }

    private fun startFailed(error: RuntimeException) {
        com.clarklevis.dsh.android.platform.LocalAppLog.w("GatewayService", "foreground-failed reason=${error.javaClass.simpleName}")
        stopSelf()
    }

    override fun onDestroy() {
        startRequested = false
        com.clarklevis.dsh.android.platform.LocalAppLog.i("GatewayService", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun makeNotification(): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val disconnectIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, GatewayConnectionService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.dsh_orca_mark)
            .setContentTitle(getString(R.string.gateway_active_notification_title))
            .setContentText(getString(R.string.gateway_active_notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.gateway_disconnect_action), disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gateway_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.gateway_notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "gateway-active-turn"
        private const val NOTIFICATION_ID = 12_001
        private const val ACTION_DISCONNECT = "com.clarklevis.dsh.android.action.DISCONNECT_GATEWAY"

        private var startRequested = false

        fun start(context: Context) {
            if (startRequested) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, GatewayConnectionService::class.java))
                startRequested = true
                com.clarklevis.dsh.android.platform.LocalAppLog.i("GatewayService", "keep-alive-start")
            } catch (error: IllegalStateException) {
                logStartFailure(error)
            } catch (error: SecurityException) {
                logStartFailure(error)
            }
        }

        private fun logStartFailure(error: RuntimeException) {
            com.clarklevis.dsh.android.platform.LocalAppLog.w("GatewayService", "start-denied reason=${error.javaClass.simpleName}")
        }

        fun stop(context: Context) {
            if (startRequested) com.clarklevis.dsh.android.platform.LocalAppLog.i("GatewayService", "keep-alive-stop")
            startRequested = false
            context.stopService(Intent(context, GatewayConnectionService::class.java))
        }
    }
}
