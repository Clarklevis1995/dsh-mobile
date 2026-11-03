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
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAccountCatalog
import com.clarklevis.dsh.shared.tailscale.TailscalePolicy
import java.util.UUID

/** 平台仅负责偏好存储和 Compose 通知；迁移、过滤和账号选择规则由 KMP 管理。 */
internal class TailscaleTunnelSettings(private val preferences: SharedPreferences) {
    private val catalog = TailscaleAccountCatalog(
        preferences.getString(KEY_ACCOUNTS, null),
        TailscaleAccount(
            id = UUID.randomUUID().toString(),
            nodeName = preferences.getString(KEY_NODE_NAME, "").orEmpty(),
            authenticated = preferences.getBoolean(KEY_AUTHENTICATED, false),
            usesLegacyState = true
        )
    )
    var enabled by mutableStateOf(preferences.getBoolean(KEY_ENABLED, false))
        private set
    var accounts by mutableStateOf(catalog.accounts)
        private set
    var selectedAccount by mutableStateOf(catalog.selectedAccount)
        private set
    var authKey by mutableStateOf(preferences.getString(KEY_AUTH_KEY, "").orEmpty())
        private set
    val nodeName: String get() = selectedAccount.nodeName
    val authenticated: Boolean get() = selectedAccount.authenticated

    init {
        persistAccounts()
    }

    fun selectEnabled(value: Boolean) {
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun selectAccount(id: String): Boolean {
        val changed = selectedAccount.id != id
        if (!catalog.select(id)) return false
        if (changed) selectAuthKey("")
        persistAccounts()
        return true
    }

    fun renameAccount(id: String, name: String) {
        catalog.rename(id, name)
        persistAccounts()
    }

    fun removeAccount(id: String): TailscaleAccount? {
        val selected = selectedAccount.id == id
        val removed = catalog.remove(id, UUID.randomUUID().toString()) ?: return null
        if (selected) {
            selectEnabled(false)
            selectAuthKey("")
        }
        if (removed.usesLegacyState || catalog.accounts.isEmpty()) {
            preferences.edit().remove(KEY_AUTHENTICATED).remove(KEY_NODE_NAME).apply()
        }
        persistAccounts()
        return removed
    }

    fun saveAuthenticatedAccount(account: TailscaleAccount, loginName: String?, tailnetName: String?) {
        catalog.saveAuthenticated(account, loginName, tailnetName)
        selectAuthKey("")
        persistAccounts()
    }

    fun markAuthenticated(id: String = selectedAccount.id, loginName: String? = null, tailnetName: String? = null) {
        if (id != selectedAccount.id) return
        catalog.markAuthenticated(id, loginName, tailnetName)
        selectAuthKey("")
        persistAccounts()
    }

    fun shouldAutoStart(): Boolean = enabled && authenticated

    fun selectNodeName(raw: String): Boolean {
        if (!catalog.setNodeName(raw)) return false
        persistAccounts()
        preferences.edit().putString(KEY_NODE_NAME, nodeName).apply()
        return true
    }

    fun selectAuthKey(raw: String) {
        authKey = raw.trim()
        preferences.edit().apply {
            if (authKey.isEmpty()) remove(KEY_AUTH_KEY) else putString(KEY_AUTH_KEY, authKey)
        }.apply()
    }

    fun effectiveNodeName(): String = nodeName.ifBlank { defaultNodeName() }

    private fun persistAccounts() {
        accounts = catalog.accounts
        selectedAccount = catalog.selectedAccount
        preferences.edit().putString(KEY_ACCOUNTS, catalog.snapshotJSON()).apply()
    }

    companion object {
        private const val KEY_ENABLED = "tunnel_enabled"
        private const val KEY_AUTHENTICATED = "tunnel_authenticated"
        private const val KEY_NODE_NAME = "tunnel_node_name"
        private const val KEY_AUTH_KEY = "tunnel_auth_key"
        private const val KEY_ACCOUNTS = "tunnel_accounts_v1"
        fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("tailscale_tunnel", Context.MODE_PRIVATE)
        fun normalizeNodeName(raw: String): String? = TailscalePolicy.normalizeNodeName(raw)
        fun defaultNodeName(): String = TailscalePolicy.defaultNodeName(Build.MODEL.orEmpty(), "android")
    }
}

internal val LocalTailscaleTunnelSettings = staticCompositionLocalOf<TailscaleTunnelSettings> {
    error("TailscaleTunnelSettings must be provided by DshTheme")
}

@Composable
internal fun rememberTailscaleTunnelSettings(): TailscaleTunnelSettings {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        (context as? DshAndroidApplication)?.tunnelSettings
            ?: TailscaleTunnelSettings(TailscaleTunnelSettings.preferences(context))
    }
}
