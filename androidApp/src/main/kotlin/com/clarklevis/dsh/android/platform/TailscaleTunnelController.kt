package com.clarklevis.dsh.android.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.ui.TailscaleTunnelSettings
import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscalePolicy
import com.clarklevis.dsh.shared.tailscale.TailscaleSessionManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tsnetgo.Tsnetgo

internal typealias TunnelUiStage = com.clarklevis.dsh.shared.tailscale.TailscaleStage
internal typealias TailscaleTunnelUiState = com.clarklevis.dsh.shared.tailscale.TailscaleStatus

internal object TunnelFailureKind {
    const val INTERFACE_SOURCE = "interface-source"
    const val NATIVE_LOAD = "native-load"
    const val BACKEND_START = "backend-start"
    const val LOGIN_WAIT = "login-wait"
    const val STATUS = "status"
    const val SHUTDOWN = "shutdown"
    const val UNKNOWN = "unknown"
}

/** 应用级原生适配：节点调度和登录切换规则在 KMP，Android 只提供系统接口。 */
internal class TailscaleTunnelController(
    private val context: Context,
    scope: CoroutineScope,
    private val settings: TailscaleTunnelSettings,
    diagnostics: AndroidGatewayDiagnostics = AndroidGatewayDiagnostics.disabled()
) {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val manager = TailscaleSessionManager(
        scope = scope,
        makeNode = { account ->
            TsnetTunnel(context, diagnostics, File(context.filesDir, account.stateRelativePath))
        },
        saveAccount = { account, status ->
            // 普通状态刷新不能选中另一个账号；只有当前会话或已确认候选会调用此处。
            settings.saveAuthenticatedAccount(account, status.loginName, status.tailnetName)
            settings.selectEnabled(true)
        },
        discardAccount = { account ->
            withContext(Dispatchers.IO) { File(context.filesDir, account.stateRelativePath).deleteRecursively() }
        },
        sessionsChanged = { active ->
            (context.applicationContext as? DshAndroidApplication)?.setTunnelKeepAlive(active)
            if (active) watchNetworks() else unwatchNetworks()
        }
    )
    val state = manager.state
    val accountAddition = manager.addition
    val hasSession: Boolean get() = manager.hasSession

    /** 连接线程只能读取当前节点。候选登录不改变出口，避免影响现有 TCP/WebSocket。 */
    fun proxyForRoute(): TsnetProxy? {
        val node = manager.currentNode as? TsnetTunnel ?: return null
        val status = runCatching { TailscalePolicy.parseStatus(node.statusJson(), false, false) }.getOrNull()
        if (status?.backendRunning != true || manager.currentNode !== node) return null
        val proxy = runCatching { node.proxyInfo() }.getOrNull()
        return proxy.takeIf { manager.currentNode === node }
    }

    fun setEnabled(enabled: Boolean) {
        settings.selectEnabled(enabled)
        if (enabled) start() else stop()
    }

    fun start() {
        pushInterfaces()
        manager.start(settings.selectedAccount, settings.authKey, settings.effectiveNodeName())
    }

    fun stop() = manager.stop()

    fun beginAccountAddition() {
        pushInterfaces()
        val id = UUID.randomUUID().toString()
        manager.beginAddition(
            TailscaleAccount(
                id = id,
                nodeName = TailscalePolicy.candidateNodeName(TailscaleTunnelSettings.defaultNodeName(), id)
            )
        )
    }

    fun confirmAccountAddition(): String? = manager.confirmAddition()
    fun cancelAccountAddition() = manager.cancelAddition()
    fun refreshAfterReturningToApp() {
        manager.refresh()
    }

    fun selectAccount(id: String) {
        if (manager.currentAccountID == id) {
            manager.promptCurrentLogin()
            return
        }
        if (!settings.selectAccount(id)) return
        settings.selectEnabled(true)
        start()
        manager.promptCurrentLogin()
    }

    fun deleteAccount(id: String) {
        val selected = settings.selectedAccount.id == id
        val account = settings.removeAccount(id) ?: return
        manager.deleteAccount(account, selected)
    }

    private fun pushInterfaces(): Boolean = runCatching {
        Tsnetgo.setInterfacesJSON(JavaInterfaceSnapshot.json())
    }.isSuccess

    private fun watchNetworks() {
        if (networkCallback != null) return
        val service = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pushInterfaces()
            }
            override fun onLost(network: Network) {
                pushInterfaces()
            }
        }
        runCatching {
            service.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
        }.onSuccess { networkCallback = callback }
    }

    private fun unwatchNetworks() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback)
        }
    }
}
