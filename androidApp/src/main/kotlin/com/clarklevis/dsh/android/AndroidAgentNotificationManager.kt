package com.clarklevis.dsh.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

internal const val EXTRA_NOTIFICATION_GATEWAY_ID = "com.clarklevis.dsh.android.notification.GATEWAY_ID"
internal const val EXTRA_NOTIFICATION_SESSION_ID = "com.clarklevis.dsh.android.notification.SESSION_ID"

internal data class AndroidNotificationSessionRoute(val gatewayId: String, val sessionId: String)

/** 审批和执行结果使用独立的提醒渠道；保活服务的常驻通知不承担提醒职责。 */
internal class AndroidAgentNotificationManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val recentIdentifiers = mutableListOf<String>()
    private val recentIdentifierSet = mutableSetOf<String>()

    init {
        runCatching { JSONArray(preferences.getString(RECENT_IDENTIFIERS_KEY, "[]")) }
            .getOrNull()
            ?.let { saved ->
                for (index in 0 until saved.length()) {
                    saved.optString(index).takeIf(String::isNotEmpty)?.let { identifier ->
                        if (recentIdentifierSet.add(identifier)) recentIdentifiers += identifier
                    }
                }
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.agent_alert_notification_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = appContext.getString(R.string.agent_alert_notification_channel_description)
                }
            )
        }
    }

    fun notifyApprovalRequired(
        gatewayId: String,
        requestId: String,
        sessionId: String,
        sessionTitle: String,
        detail: String?
    ) {
        val body = detail?.trim()?.takeIf(String::isNotEmpty)?.let {
            appContext.getString(R.string.agent_approval_notification_detail, sessionTitle, it)
        } ?: appContext.getString(R.string.agent_approval_notification_generic, sessionTitle)
        notifyOnce(
            identifier = "approval:$gatewayId:$requestId",
            gatewayId = gatewayId,
            sessionId = sessionId,
            title = appContext.getString(R.string.agent_approval_notification_title),
            body = body,
            icon = R.drawable.ic_permission_ask
        )
    }

    fun notifyExecutionEnded(
        gatewayId: String,
        sessionId: String,
        sequence: Int,
        sessionTitle: String,
        failed: Boolean
    ) {
        notifyOnce(
            identifier = "execution-ended:$gatewayId:$sessionId:$sequence",
            gatewayId = gatewayId,
            sessionId = sessionId,
            title = appContext.getString(
                if (failed) R.string.agent_execution_failed_notification_title
                else R.string.agent_execution_completed_notification_title
            ),
            body = appContext.getString(
                if (failed) R.string.agent_execution_failed_notification_body
                else R.string.agent_execution_completed_notification_body,
                sessionTitle
            ),
            icon = if (failed) android.R.drawable.stat_notify_error else R.drawable.ic_check_circle
        )
    }

    @Synchronized
    private fun notifyOnce(
        identifier: String,
        gatewayId: String,
        sessionId: String,
        title: String,
        body: String,
        icon: Int
    ) {
        if (identifier in recentIdentifierSet) return
        if ((appContext as? DshAndroidApplication)?.isInForeground == true) {
            rememberIdentifier(identifier)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = PendingIntent.getActivity(
            appContext,
            identifier.hashCode(),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_NOTIFICATION_GATEWAY_ID, gatewayId)
                putExtra(EXTRA_NOTIFICATION_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup("agent.session.$gatewayId.$sessionId")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()
        try {
            manager.notify(identifier, 0, notification)
        } catch (_: SecurityException) {
            return
        }
        rememberIdentifier(identifier)
    }

    private fun rememberIdentifier(identifier: String) {
        recentIdentifierSet += identifier
        recentIdentifiers += identifier
        if (recentIdentifiers.size > MAXIMUM_RECENT_IDENTIFIERS) {
            val removed = recentIdentifiers.removeAt(0)
            recentIdentifierSet -= removed
        }
        preferences.edit()
            .putString(RECENT_IDENTIFIERS_KEY, JSONArray(recentIdentifiers).toString())
            .apply()
    }

    private companion object {
        const val CHANNEL_ID = "agent-alerts"
        const val PREFERENCES_NAME = "agent_user_notifications"
        const val RECENT_IDENTIFIERS_KEY = "recent_identifiers"
        const val MAXIMUM_RECENT_IDENTIFIERS = 256
    }
}
