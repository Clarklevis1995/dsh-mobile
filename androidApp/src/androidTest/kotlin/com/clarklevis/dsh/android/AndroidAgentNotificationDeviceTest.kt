package com.clarklevis.dsh.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAgentNotificationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val system = context.getSystemService(NotificationManager::class.java)

    @Test
    fun backgroundCompletionReachesSystemAndSurvivesManagerRestartWithoutDuplicate() = runBlocking {
        val gateway = "notification-test-${UUID.randomUUID()}"
        grantNotificationPermission()
        val manager = AndroidAgentNotificationManager(context, isInForeground = { false })
        val ended = requireNotNull(AndroidAgentExecutionNotifications().accept(
            GatewayWireDecoder.decode("""{"kind":"event","sessionId":"session","seq":42,"event":{"type":"turn/end"}}"""),
            emptySet()
        ))
        fun complete(notifier: AndroidAgentNotificationManager) = notifier.notifyExecutionEnded(
            gatewayId = gateway, sessionId = ended.sessionId, eventId = ended.eventId,
            sessionTitle = "Notification test", failed = ended.failed
        )
        try {
            complete(manager)
            awaitCount(gateway, 1)
            val notification = system.activeNotifications.single { it.tag?.contains(gateway) == true }.notification
            assertEquals(context.getString(R.string.agent_execution_completed_notification_title),
                notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.contentIntent != null)
            clear(gateway)
            complete(AndroidAgentNotificationManager(context, isInForeground = { false }))
            delay(300)
            assertEquals(0, count(gateway))
        } finally { clear(gateway) }
    }

    @Test
    fun foregroundQuestionsAndApprovalsCanStillNotifyAfterEnteringBackground() = runBlocking {
        val gateway = "notification-test-${UUID.randomUUID()}"
        grantNotificationPermission()
        var foreground = true
        val manager = AndroidAgentNotificationManager(context, isInForeground = { foreground })
        fun send() {
            manager.notifyQuestionRequired(gateway, "question", "session", "Notification test", "Choose a direction")
            manager.notifyApprovalRequired(gateway, "approval", "session", "Notification test", "Run command")
        }
        try {
            send()
            assertEquals(0, count(gateway))
            foreground = false
            send()
            awaitCount(gateway, 2)
        } finally { clear(gateway) }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun count(gateway: String): Int = system.activeNotifications.count { it.tag?.contains(gateway) == true }

    private suspend fun awaitCount(gateway: String, expected: Int) = withTimeout(5_000) {
        while (count(gateway) != expected) delay(25)
    }

    private fun clear(gateway: String) {
        system.activeNotifications.filter { it.tag?.contains(gateway) == true }.forEach { system.cancel(it.tag, it.id) }
    }
}
