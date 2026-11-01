package com.clarklevis.dsh.android.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * 内嵌 Tailscale 隧道的应用本地偏好。
 *
 * 不放进入网关作用域的 DataStore：隧道要在「连接之前」就可读可写，
 * 而 AndroidGatewayPreferences 只在已配对主机上存在（且 UI 从不直接 collect 它）。
 * 这里用与 AppearanceSettings 相同的 SharedPreferences + snapshot state 形态。
 */
internal class TailscaleTunnelSettings(private val preferences: SharedPreferences) {

    var enabled by mutableStateOf(preferences.getBoolean(KEY_ENABLED, false))
        private set

    var nodeName by mutableStateOf(preferences.getString(KEY_NODE_NAME, "").orEmpty())
        private set

    var authKey by mutableStateOf(preferences.getString(KEY_AUTH_KEY, "").orEmpty())
        private set

    fun selectEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /**
     * 「这台设备登录过」的事实：只有在节点真的进过 Running（含用 Auth Key 直接认证成功）之后为真。
     * 它决定开关是否值得被记住：没登录过的开启不该在下次启动时自动生效。
     */
    var authenticated by mutableStateOf(preferences.getBoolean(KEY_AUTHENTICATED, false))
        private set

    fun markAuthenticated() {
        if (authenticated) return
        authenticated = true
        preferences.edit().putBoolean(KEY_AUTHENTICATED, true).apply()
    }

    fun shouldAutoStart(): Boolean = enabled && authenticated

    /** 返回 false 表示不合法：调用方保留原值并提示，不把脏值写进偏好。 */
    fun selectNodeName(raw: String): Boolean {
        val normalized = normalizeNodeName(raw) ?: return false
        if (normalized == nodeName) return true
        nodeName = normalized
        preferences.edit().putString(KEY_NODE_NAME, normalized).apply()
        return true
    }

    fun selectAuthKey(raw: String) {
        val value = raw.trim()
        if (value == authKey) return
        authKey = value
        preferences.edit().putString(KEY_AUTH_KEY, value).apply()
    }

    /** 留空 = 走交互式登录（设备流程）；非空 = 用 Auth Key。 */
    fun effectiveAuthKey(): String = authKey

    /** 没填就用可读的默认名，避免把 Build.MODEL 原文（可能含空格）交给 Tailscale。 */
    fun effectiveNodeName(): String = nodeName.ifBlank { defaultNodeName() }

    companion object {
        private const val KEY_ENABLED = "tunnel_enabled"
        private const val KEY_AUTHENTICATED = "tunnel_authenticated"
        private const val KEY_NODE_NAME = "tunnel_node_name"
        private const val KEY_AUTH_KEY = "tunnel_auth_key"

        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("tailscale_tunnel", Context.MODE_PRIVATE)

        private val nodeNamePattern = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

        /** Tailscale 主机名规则：小写字母/数字/连字符，首尾必须是字母或数字，最长 63。 */
        fun normalizeNodeName(raw: String): String? {
            val candidate = raw.trim().lowercase(Locale.ROOT)
                .replace(Regex("[\\s_.]+"), "-")
                .replace(Regex("-{2,}"), "-")
                .trim('-')
            return candidate.takeIf { nodeNamePattern.matches(it) }
        }

        fun defaultNodeName(): String {
            val slug = Build.MODEL.orEmpty()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
                .trimEnd('-')
            return normalizeNodeName("dsh-$slug") ?: "dsh-android"
        }
    }
}

internal val LocalTailscaleTunnelSettings = staticCompositionLocalOf<TailscaleTunnelSettings> {
    error("TailscaleTunnelSettings must be provided by DshTheme")
}

@Composable
internal fun rememberTailscaleTunnelSettings(): TailscaleTunnelSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) { TailscaleTunnelSettings(TailscaleTunnelSettings.preferences(context)) }
}
