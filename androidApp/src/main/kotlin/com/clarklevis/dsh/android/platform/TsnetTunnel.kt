package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.tailscale.TailscaleNode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import tsnetgo.Node
import tsnetgo.Tsnetgo

/**
 * 进程内的 tailscale 节点：Go/tsnet 经 gomobile 绑定，跑在 app 进程里。
 * 不申请 VpnService、不建 TUN、不占用系统 VPN 槽位；app 只连 127.0.0.1 的转发端口。
 *
 * 边界刻意保持窄：跨 JNI 只传字符串与端口号，字节流全部留在 Go 侧。
 */
/** 进程内节点给出的 SOCKS5 入口。凭据不进 URL，只走 ProxyAuthenticator。 */
internal data class TsnetProxy(val host: String, val port: Int, val user: String, val pass: String)

internal class TsnetTunnel(
    private val appContext: Context,
    private val diagnostics: AndroidGatewayDiagnostics = AndroidGatewayDiagnostics.disabled(),
    private val directory: File = File(appContext.filesDir, "tsnet-state")
) : TailscaleNode {
    private val lock = Any()
    private var node: Node? = null
    private var routesAccepted = false

    /** 最近一次状态 JSON，供诊断界面与测试读取；不含凭据。 */
    @Volatile
    var lastStatusJson: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    private fun stateDir(): File = directory.apply { mkdirs() }

    override suspend fun status(): String = withContext(Dispatchers.IO) {
        runCatching { statusJson() }.getOrElse {
            """{"ok":false,"error":${quote(it.message)}}"""
        }
    }

    override suspend fun close(): Boolean = withContext(Dispatchers.IO) { shutdown() }

    // gomobile 用 Exception 统一承载底层 Go error，边界处转为结构化状态。
    @Suppress("TooGenericExceptionCaught")
    override suspend fun begin(authKey: String, hostname: String): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                if (node == null) {
                    node = Tsnetgo.start(hostname, stateDir().absolutePath)
                }
                val current = node ?: return@withContext statusJson()
                // 只有可调试包才把节点内部日志转出去：真机排障需要知道它自己怎么说
                // （路由有没有安装、EditPrefs 有没有被拒绝），发布包不该刷日志。
                // 用 diagnostics.enabled 判断，它本身就等于 FLAG_DEBUGGABLE。
                current.setVerbose(diagnostics.enabled)
                current.setAuthKey(authKey.trim())
                val json = if (authKey.isBlank()) current.beginLogin(LOGIN_TIMEOUT_SECONDS) else current.bringUp(LOGIN_TIMEOUT_SECONDS)
                lastStatusJson = enrichRoutes(current, json)
                lastError = if (json.contains("\"ok\":true")) "" else "bring-up-failed"
                report("bring-up", json)
                lastStatusJson
            } catch (linkFailure: UnsatisfiedLinkError) {
                // ABI 不匹配 / gomobile .so 未被打进包 / 低于 -androidapi 的设备，都会落到这里。
                lastError = "native-load-failed: ${linkFailure.message}"
                diagnostics.tunnel("native-load", ok = false, backendState = null)
                """{"ok":false,"stage":"native-load","error":${quote(linkFailure.message)}}"""
            } catch (failure: Exception) {
                lastError = "start-failed: ${failure.message}"
                diagnostics.tunnel("start", ok = false, backendState = null)
                """{"ok":false,"stage":"start","error":${quote(failure.message)}}"""
            }
        }
    }

    fun statusJson(): String = synchronized(lock) {
        val current = node ?: return lastStatusJson
        val json = enrichRoutes(current, current.statusNow())
        lastStatusJson = json
        report("status", json)
        json
    }

    /** 仅按需读取本机节点日志，诊断输出由调用方脱敏。 */
    fun logsJson(): String = synchronized(lock) { node?.logs() ?: "{}" }

    private fun enrichRoutes(current: Node, raw: String): String {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return raw
        if (obj["state"] == JsonPrimitive("Running") && !routesAccepted) {
            routesAccepted = runCatching {
                val routes = Json.parseToJsonElement(current.acceptRoutes()) as JsonObject
                routes["ok"] == JsonPrimitive(true)
            }.getOrDefault(false)
            diagnostics.tunnel("routes", ok = routesAccepted, backendState = null)
        }
        return JsonObject(obj + ("routesReady" to JsonPrimitive(routesAccepted))).toString()
    }

    /**
     * 连接出口：127.0.0.1 上由 Go 侧起的 CONNECT 代理（内部经 tailnet 转发）。
     * 注意不要用 tsnet.Loopback()：那个端口是节点的 LocalAPI，会把我们的请求按
     * 本地 API 规则拒掉（真机上的 403 missing 'Sec-Tailscale: localapi' header 就是这么来的）。
     */
    fun proxyInfo(): TsnetProxy? {
        val current = synchronized(lock) { node } ?: return null
        val obj = Json.parseToJsonElement(current.startProxy()) as? JsonObject ?: return null
        val host = obj["host"]?.jsonPrimitive?.contentOrNull
        val port = obj["port"]?.jsonPrimitive?.intOrNull
        return if (obj["ok"] == JsonPrimitive(true) && host != null && port != null) TsnetProxy(host, port, "", "") else null
    }

    // 原生关闭异常必须保留节点引用及目录屏障，防止第二个实例重用仍在使用的身份。
    @Suppress("TooGenericExceptionCaught")
    fun shutdown(): Boolean = synchronized(lock) {
        try {
            // Go 侧的 Close() 现在会在 10 秒内返回错误（而不是无限吊住），
            // gomobile 把 error 变成异常，所以这里接住并留诊断——关闭失败不能让调用方崩。
            node?.close()
        } catch (failure: Exception) {
            lastError = "close-failed: ${failure.message}"
            diagnostics.tunnel("close", ok = false, backendState = null)
            return false
        }
        node = null
        routesAccepted = false
        true
    }

    /** 只上报非敏感字段；状态 JSON 里的 tailnet 地址与节点密钥不进 logcat。 */
    private fun report(stage: String, json: String) {
        val backend = Regex("\"state\":\"([A-Za-z]*)\"").find(json)?.groupValues?.getOrNull(1)
        val peers = Regex("\"peers\":(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        diagnostics.tunnel(
            stage = stage,
            ok = json.contains("\"ok\":true"),
            backendState = backend,
            peerCount = peers,
            hasAuthUrl = Regex("\"authURL\":\"http").containsMatchIn(json)
        )
    }

    private fun quote(value: String?): String = JsonPrimitive(value.orEmpty()).toString()

    private companion object {
        const val LOGIN_TIMEOUT_SECONDS = 12L
    }
}
