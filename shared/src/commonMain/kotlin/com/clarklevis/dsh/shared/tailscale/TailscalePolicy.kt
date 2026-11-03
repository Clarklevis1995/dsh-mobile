package com.clarklevis.dsh.shared.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class TailscaleStage { DISABLED, STARTING, NEEDS_LOGIN, WAITING_ROUTES, READY, FAILED }

data class TailscaleStatus(
    val stage: TailscaleStage = TailscaleStage.DISABLED,
    val authUrl: String? = null,
    val peerCount: Int = 0,
    val nodeAddress: String? = null,
    val failureKind: String? = null,
    val backendRunning: Boolean = false,
    val routesReady: Boolean = false,
    val loginName: String? = null,
    val tailnetName: String? = null
) {
    val isTransient: Boolean get() = stage == TailscaleStage.STARTING ||
        stage == TailscaleStage.NEEDS_LOGIN ||
        stage == TailscaleStage.WAITING_ROUTES

    /** 缓存 netmap 可以先报告 Running，但缺少对端时不能开始网关拨号。 */
    val canConnect: Boolean get() = stage == TailscaleStage.READY && backendRunning && routesReady

    /** 网页确认、认证和路由三个条件均满足后才允许替换当前节点。 */
    fun canPromote(confirmed: Boolean): Boolean = confirmed && backendRunning && routesReady
}

/** 两端共用的状态解释、账号命名和轮询策略，不依赖 SwiftUI/Compose/JNI。 */
object TailscalePolicy {
    fun parseStatus(raw: String, bringUp: Boolean, candidate: Boolean): TailscaleStatus {
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        fun text(key: String): String? = runCatching { obj?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
        val backend = text("state").orEmpty()
        val peers = runCatching { obj?.get("peers")?.jsonPrimitive?.intOrNull }.getOrNull()?.coerceAtLeast(0) ?: 0
        val url = text("authURL")?.takeIf {
            Regex("https://login\\.tailscale\\.com/a/[A-Za-z0-9_-]+(?:\\?[^\\s\"<>]*)?").matches(it)
        }
        val address = runCatching { (obj?.get("tailscaleIPs") as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
        val routes = runCatching { obj?.get("routesReady")?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
        val error = text("error").orEmpty()
        val running = backend == "Running"
        // 原生适配器明确返回路由结果时，当前节点也必须继续轮询到路由配置完成。
        val backendStage = stage(backend, peers, url, routes, candidate || obj?.containsKey("routesReady") == true)
        // Up 的等待超时不会停止 SDK 的控制面登录，必须继续查询，不能把它变成永久失败。
        val waitingForLogin = bringUp &&
            text("stage") == "up" &&
            (error.contains("deadline") || error.contains("timeout"))
        val stage = if (backendStage == TailscaleStage.FAILED && waitingForLogin) TailscaleStage.STARTING else backendStage
        val failure = if (stage == TailscaleStage.FAILED) failure(text("stage"), error, bringUp) else null
        return TailscaleStatus(
            stage, url, peers, address, failure, running, routes,
            text("loginName"), text("tailnetName") ?: text("magicDNS")
        )
    }

    private fun stage(backend: String, peers: Int, url: String?, routes: Boolean, requireRoutes: Boolean): TailscaleStage = when {
        backend == "Running" && requireRoutes && !routes -> TailscaleStage.WAITING_ROUTES
        backend == "Running" && peers > 0 -> TailscaleStage.READY
        backend == "Running" -> TailscaleStage.WAITING_ROUTES
        backend == "NeedsLogin" || url != null -> TailscaleStage.NEEDS_LOGIN
        backend == "Starting" || backend == "NoState" -> TailscaleStage.STARTING
        else -> TailscaleStage.FAILED
    }

    private fun failure(stage: String?, error: String, bringUp: Boolean): String = when {
        error.contains("interface") || error.contains("netlinkrib") -> "interface-source"
        stage == "native-load" || error.contains("native-load") -> "native-load"
        bringUp && (error.contains("deadline") || error.contains("timeout")) -> "login-wait"
        bringUp && error.isNotBlank() -> "backend-start"
        else -> "unknown"
    }

    fun normalizeNodeName(raw: String): String? {
        val value = raw.trim().lowercase().replace(Regex("[\\s_.]+"), "-")
            .replace(Regex("-{2,}"), "-").trim('-')
        return value.takeIf { Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$").matches(it) }
    }

    fun defaultNodeName(deviceName: String, platform: String): String {
        val slug = deviceName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
            .trim('-').take(DEVICE_SLUG_LENGTH).trimEnd('-')
        return if (slug.isEmpty()) "dsh-$platform" else normalizeNodeName("dsh-$slug") ?: "dsh-$platform"
    }

    fun candidateNodeName(base: String, id: String): String =
        base.take(CANDIDATE_BASE_LENGTH).trimEnd('-') + "-" + id.take(ID_SUFFIX_LENGTH).lowercase()
    fun nextPollMilliseconds(previous: Long): Long = (previous * 2).coerceIn(MIN_POLL_MILLISECONDS, MAX_POLL_MILLISECONDS)
    const val MIN_POLL_MILLISECONDS = 5_000L
    private const val MAX_POLL_MILLISECONDS = 30_000L
    private const val DEVICE_SLUG_LENGTH = 40
    private const val CANDIDATE_BASE_LENGTH = 48
    private const val ID_SUFFIX_LENGTH = 8
}
