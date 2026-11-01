package com.clarklevis.dsh.android.platform

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val diagnostics: AndroidGatewayDiagnostics = AndroidGatewayDiagnostics.disabled()
) {
    private val lock = Any()
    private var node: Node? = null

    /** 最近一次状态 JSON，供诊断界面与测试读取；不含凭据。 */
    @Volatile
    var lastStatusJson: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    private fun stateDir(): File =
        File(appContext.filesDir, "tsnet-state").apply { mkdirs() }

    private fun nodeHostname(): String {
        val slug = Build.MODEL.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(40)
            .trimEnd('-')
        return if (slug.isEmpty()) "dsh-mobile" else "dsh-$slug"
    }

    /** 启动节点并等待一次状态。返回状态 JSON；失败信息在返回串与 [lastError] 里。 */
    suspend fun bringUp(
        authKey: String,
        hostname: String? = null,
        timeoutSeconds: Long = 90L,
        stage: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            try {
                stage("kotlin-entered")
                if (node == null) {
                    val name = hostname?.takeIf { it.isNotBlank() } ?: nodeHostname()
                    stage("kotlin-before-native-start")
                    node = Tsnetgo.start(name, stateDir().absolutePath)
                    stage("kotlin-after-native-start")
                }
                val current = node ?: return@withContext statusJson()
                // 只有可调试包才把节点内部日志转出去：真机排障需要知道它自己怎么说
                // （路由有没有安装、EditPrefs 有没有被拒绝），发布包不该刷日志。
                // 用 diagnostics.enabled 判断，它本身就等于 FLAG_DEBUGGABLE。
                current.setVerbose(diagnostics.enabled)
                current.setAuthKey(authKey.trim())
                stage("kotlin-before-native-bringup")
                val json = current.bringUp(timeoutSeconds)
                stage("kotlin-after-native-bringup")
                lastStatusJson = json
                lastError = if (json.contains("\"ok\":true")) "" else "bring-up-failed"
                report("bring-up", json)
                if (json.contains("\"ok\":true")) {
                    // 连上之后立刻接受别人广播的子网路由（等价 tailscale set --accept-routes）。
                    // 不做这一步，经内嵌节点就到不了网关广播的局域网段（真机实测）。
                    val routes = current.acceptRoutes()
                    val ok = routes.contains("\"ok\":true")
                    if (!ok) lastError = "routes-not-accepted"
                    diagnostics.tunnel("routes", ok = ok, backendState = null)
                }
                json
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

    fun statusJson(): String {
        val current = synchronized(lock) { node } ?: return lastStatusJson
        val json = current.statusNow()
        lastStatusJson = json
        report("status", json)
        return json
    }

    /** 让 Go 侧在 127.0.0.1 上开一个到 host:port 的转发器，返回本地端口。 */
    suspend fun forwardTo(host: String, port: Int): Int = withContext(Dispatchers.IO) {
        val current = synchronized(lock) { node } ?: error("tsnet-not-started")
        current.forwardTo(host, port.toLong()).toInt()
    }

    /** 用节点自己的 netstack 发一次 HTTP（不经过 SOCKS、不经过 OkHttp）。返回结果 JSON。 */
    fun probeHttp(url: String, maxBytes: Int = 4096): String =
        synchronized(lock) { node?.probeHTTP(url, maxBytes.toLong()) ?: "{\"ok\":false,\"error\":\"tsnet-not-started\"}" }

    /** 节点内部日志尾部（真机上 logcat 会被厂商丢掉，所以走 API）。 */
    fun logsJson(): String = synchronized(lock) { node?.logs() ?: "{}" }

    fun forwardsJson(): String = synchronized(lock) { node?.forwardsJSON() ?: "[]" }

    /**
     * 连接出口：127.0.0.1 上由 Go 侧起的 CONNECT 代理（内部经 tailnet 转发）。
     * 注意不要用 tsnet.Loopback()：那个端口是节点的 LocalAPI，会把我们的请求按
     * 本地 API 规则拒掉（真机上的 403 missing 'Sec-Tailscale: localapi' header 就是这么来的）。
     */
    fun proxyInfo(): TsnetProxy? {
        val current = synchronized(lock) { node } ?: return null
        val json = current.startProxy()
        if (!json.contains("\"ok\":true")) return null
        val host = Regex("\"host\":\"([^\"]+)\"").find(json)?.groupValues?.getOrNull(1) ?: return null
        val port = Regex("\"port\":(\\d+)").find(json)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return TsnetProxy(host, port, "", "")
    }

    fun shutdown() {
        synchronized(lock) {
            try {
                // Go 侧的 Close() 现在会在 10 秒内返回错误（而不是无限吊住），
                // gomobile 把 error 变成异常，所以这里接住并留诊断——关闭失败不能让调用方崩。
                node?.close()
            } catch (failure: Exception) {
                lastError = "close-failed: ${failure.message}"
                diagnostics.tunnel("close", ok = false, backendState = null)
            }
            node = null
        }
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

    private fun quote(value: String?): String {
        val escaped = (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"" + escaped + "\""
    }
}
