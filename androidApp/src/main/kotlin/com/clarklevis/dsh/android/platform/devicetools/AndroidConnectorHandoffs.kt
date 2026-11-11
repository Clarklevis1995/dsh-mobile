package com.clarklevis.dsh.android.platform.devicetools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** 先完成持久回执和控制帧发送，再让外部 Activity 触发本应用的 ON_PAUSE。 */
internal class AndroidConnectorHandoffs(private val context: Context) {
    private val intents = ConcurrentHashMap<String, Intent>()
    fun prepare(request: JSONObject, intent: Intent, unavailable: String) {
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) throw ConnectorFailure(unavailable)
        if (intents.size >= 128) intents.clear()
        intents[request.getString("invocationId")] = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    fun complete(invocationId: String) {
        val intent = intents.remove(invocationId) ?: return
        Handler(Looper.getMainLooper()).post {
            // handoff-requested 只确认跳转请求；目标应用可能在此后被卸载或拒绝处理。
            runCatching { context.startActivity(intent) }
        }
    }
    fun cancel(invocationId: String) { intents.remove(invocationId) }
}
