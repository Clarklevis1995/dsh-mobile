package com.clarklevis.dsh.android.platform.devicetools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clarklevis.dsh.android.MainActivity
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Android 没有通用的系统待办资料库；只管理本应用、当前主机创建的提醒。 */
internal class AndroidReminderConnector(private val context: Context, private val storage: AndroidConnectorStorage, private val hostId: String) {
    suspend fun list(args: JSONObject): JSONObject {
        val (start, end) = args.range()
        return storage.read { data ->
            val rows = data.optJSONObject("reminders") ?: JSONObject()
            val matches = rows.keys().asSequence().map { rows.getJSONObject(it) }.filter { row ->
                !row.optBoolean("completed") && (row.isNull("dueDate") || Instant.parse(row.getString("dueDate")).let { it >= start && it < end })
            }.sortedBy { it.optString("dueDate") }.toList()
            JSONObject().put("items", JSONArray(matches.take(100).map { row -> JSONObject().put("title", row.getString("title"))
                .put("completed", row.optBoolean("completed")).put("dueDate", row.opt("dueDate")) }))
                .put("truncated", matches.size > 100).put("timeZone", args.zone().id).put("source", "whaleness-host-reminders")
                .put("coverage", "this-host-whaleness-reminders").put("collectedAt", Instant.now().toString())
        }
    }

    suspend fun create(request: JSONObject): JSONObject {
        val args = request.getJSONObject("arguments")
        val title = args.text("title", 500); val zone = args.zone(); val operation = args.uuid("operationId")
        val due = if (args.has("dueDate")) args.instant("dueDate") else null
        val notify = if (args.has("notify")) args.getBoolean("notify") else due != null
        require(!notify || (due != null && due > Instant.now()))
        val priority = args.integer("priority", 0, 0..9).also { require(it in listOf(0, 1, 5, 9)) }
        if (args.has("notes")) args.text("notes", 2000, true)
        if (notify && !notificationsEnabled(context)) throw ConnectorFailure("notification_permission_required")
        return storage.write(request) { data ->
            val id = UUID.randomUUID().toString()
            val rows = data.optJSONObject("reminders") ?: JSONObject().also { data.put("reminders", it) }
            if (rows.length() >= 2000) throw ConnectorFailure("reminder_limit_reached")
            val row = JSONObject().put("id", id).put("title", title).put("dueDate", due?.toString() ?: JSONObject.NULL)
                .put("notes", args.optString("notes")).put("priority", priority).put("notify", notify).put("completed", false)
            rows.put(id, row)
            // 调度内容另外原子持久化，接收器不依赖 WebSocket 或 Activity 存活。
            if (notify) AndroidReminderReceiver.schedule(context, hostId, row)
            JSONObject().put("id", id).put("title", title).put("dueDate", due?.toString() ?: JSONObject.NULL)
                .put("timeZone", zone.id).put("notify", notify).put("priority", priority).put("operationId", operation).put("status", "created")
        }
    }
}

internal fun notificationsEnabled(context: Context): Boolean = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/** 普通待办通知使用系统非精确调度，不伪装成必须准点响铃的闹钟。 */
class AndroidReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("connector-reminder-schedules", Context.MODE_PRIVATE)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    restore(context)
                } else {
                    val key = intent.data?.lastPathSegment ?: return@launch
                    val row = prefs.getString(key, null)?.let { JSONObject(it) } ?: return@launch
                    show(context, key, row)
                }
            } catch (_: Exception) {
                // 系统授权或提供器变化不应让广播接收器崩溃；保留调度记录供下次启动核对。
            } finally { pendingResult.finish() }
        }
    }

    companion object {
        private const val CHANNEL = "connector-reminders"
        fun restore(context: Context) {
            val prefs = context.getSharedPreferences("connector-reminder-schedules", Context.MODE_PRIVATE)
            prefs.all.forEach { (key, raw) ->
                runCatching {
                    val row = JSONObject(raw as String)
                    if (row.getLong("at") > System.currentTimeMillis()) arm(context, key, row.getLong("at")) else show(context, key, row)
                }
            }
        }
        fun schedule(context: Context, hostId: String, row: JSONObject) {
            val key = connectorDigest(hostId + ":" + row.getString("id"))
            val at = Instant.parse(row.getString("dueDate")).toEpochMilli()
            val data = JSONObject().put("title", row.getString("title")).put("at", at)
            if (!context.getSharedPreferences("connector-reminder-schedules", Context.MODE_PRIVATE).edit().putString(key, data.toString()).commit()) throw ConnectorFailure("storage_unavailable")
            arm(context, key, at)
        }
        private fun arm(context: Context, key: String, at: Long) {
            val intent = Intent(context, AndroidReminderReceiver::class.java).setData(Uri.parse("whaleness-reminder://scheduled/$key"))
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        private fun show(context: Context, key: String, row: JSONObject) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, appText("提醒事项"), NotificationManager.IMPORTANCE_HIGH))
            if (notificationsEnabled(context)) {
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(key, 1, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_drawer_schedule)
                    .setContentTitle(row.getString("title")).setContentText(appText("Whaleness 提醒事项"))
                    .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
            context.getSharedPreferences("connector-reminder-schedules", Context.MODE_PRIVATE).edit().remove(key).apply()
        }
    }
}
