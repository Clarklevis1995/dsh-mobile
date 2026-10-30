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
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import org.json.JSONArray

internal const val EXTRA_NOTIFICATION_GATEWAY_ID = "com.clarklevis.dsh.android.notification.GATEWAY_ID"
internal const val EXTRA_NOTIFICATION_SESSION_ID = "com.clarklevis.dsh.android.notification.SESSION_ID"

internal data class AndroidNotificationSessionRoute(val gatewayId: String, val sessionId: String)

/** 复用实时请求的通知入口，后台切换时只补发仍需用户处理的请求。 */
internal fun pendingAgentNotificationFrames(snapshot: SharedMobileSnapshot): List<GatewayFrame> =
    snapshot.pendingApprovals.filter {
        snapshot.approvalRequestStatuses[it.rpcId]?.kind !in setOf("submitting", "accepted")
    }.map {
        GatewayFrame(kind = "approval-requested", rpcId = it.rpcId, sessionId = it.sessionId)
    } + snapshot.pendingQuestions.map {
        GatewayFrame(kind = "question-requested", rpcId = it.rpcId, sessionId = it.sessionId)
    }

/** 用户选择、审批和执行结果使用提醒渠道；保活服务的常驻通知不承担提醒职责。 */
internal class AndroidAgentNotificationManager(
    context: Context,
    private val isInForeground: () -> Boolean = {
        (context.applicationContext as? DshAndroidApplication)?.isInForeground == true
    }
) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val history = AgentNotificationHistory(
        runCatching {
            val saved = JSONArray(preferences.getString(RECENT_IDENTIFIERS_KEY, "[]"))
            (0 until saved.length()).map { saved.optString(it) }
        }.getOrDefault(emptyList())
    )

    init {
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
            icon = R.drawable.ic_permission_ask,
            requiresResponse = true
        )
    }

    fun notifyExecutionEnded(
        gatewayId: String,
        sessionId: String,
        eventId: String,
        sessionTitle: String,
        failed: Boolean
    ) {
        notifyOnce(
            identifier = "execution-ended:$gatewayId:$sessionId:$eventId",
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

    fun notifyQuestionRequired(
        gatewayId: String,
        requestId: String,
        sessionId: String,
        sessionTitle: String,
        prompt: String?
    ) {
        val body = prompt?.trim()?.takeIf(String::isNotEmpty)?.let {
            appContext.getString(R.string.agent_question_notification_detail, sessionTitle, it)
        } ?: appContext.getString(R.string.agent_question_notification_generic, sessionTitle)
        notifyOnce(
            identifier = "question:$gatewayId:$requestId",
            gatewayId = gatewayId,
            sessionId = sessionId,
            title = appContext.getString(R.string.agent_question_notification_title),
            body = body,
            icon = R.drawable.ic_question_bubble,
            requiresResponse = true
        )
    }

    @Synchronized
    private fun notifyOnce(
        identifier: String,
        gatewayId: String,
        sessionId: String,
        title: String,
        body: String,
        icon: Int,
        requiresResponse: Boolean = false
    ) {
        val inForeground = isInForeground()
        if (history.contains(identifier)) return
        if (inForeground) {
            // 待处理请求离开前台后仍需提醒；执行结果保留前台已读语义。
            if (!requiresResponse) rememberIdentifier(identifier)
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
        history.remember(identifier)
        preferences.edit()
            .putString(RECENT_IDENTIFIERS_KEY, JSONArray(history.identifiers).toString())
            .apply()
    }

    private companion object {
        const val CHANNEL_ID = "agent-alerts"
        const val PREFERENCES_NAME = "agent_user_notifications"
        const val RECENT_IDENTIFIERS_KEY = "recent_identifiers"
    }
}

/** 有界、可持久化的通知去重记录。仅在已提醒或执行结果在前台已读时写入。 */
internal class AgentNotificationHistory(saved: List<String>, private val maximumCount: Int = 256) {
    private val recent = saved.filter(String::isNotBlank).distinct().takeLast(maximumCount).toMutableList()
    private val recentSet = recent.toMutableSet()
    val identifiers: List<String> get() = recent.toList()

    fun contains(identifier: String): Boolean = identifier in recentSet

    fun remember(identifier: String) {
        if (!recentSet.add(identifier)) return
        recent += identifier
        if (recent.size > maximumCount) recentSet -= recent.removeAt(0)
    }
}
