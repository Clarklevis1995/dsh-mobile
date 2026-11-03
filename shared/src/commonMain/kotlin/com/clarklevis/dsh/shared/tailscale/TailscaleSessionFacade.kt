package com.clarklevis.dsh.shared.tailscale

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

interface TailscaleStringResult {
    fun complete(value: String)
}
interface TailscaleCloseResult {
    fun complete(closed: Boolean)
}

/** Swift 只实现原生调用；回调完成前不结束协程，确保关闭等待已发出的原生操作。 */
interface TailscaleNativeNode {
    fun begin(authKey: String, hostname: String, result: TailscaleStringResult)
    fun status(result: TailscaleStringResult)
    fun close(result: TailscaleCloseResult)
}

interface TailscaleSessionHost {
    fun makeNode(account: TailscaleAccount): TailscaleNativeNode
    fun saveAccount(account: TailscaleAccount, status: TailscaleStatus)
    fun discardAccount(account: TailscaleAccount)
}

interface TailscaleSessionListener {
    fun changed(state: TailscaleStatus, addition: TailscaleAddition?)
}

private class NativeNodeAdapter(val native: TailscaleNativeNode) : TailscaleNode {
    override suspend fun begin(authKey: String, hostname: String): String = suspendCoroutine { continuation ->
        native.begin(
            authKey,
            hostname,
            object : TailscaleStringResult {
                override fun complete(value: String) = continuation.resume(value)
            }
        )
    }
    override suspend fun status(): String = suspendCoroutine { continuation ->
        native.status(object : TailscaleStringResult {
            override fun complete(value: String) = continuation.resume(value)
        })
    }
    override suspend fun close(): Boolean = suspendCoroutine { continuation ->
        native.close(object : TailscaleCloseResult {
            override fun complete(closed: Boolean) = continuation.resume(closed)
        })
    }
}

/** Swift 的轻量入口，不向平台暴露协程、Flow 或其线程细节。 */
@Suppress("TooManyFunctions") // 保持 Swift 桥接命令直接对应共享调度器，避免平台再实现业务规则。
class TailscaleSessionFacade(host: TailscaleSessionHost) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: TailscaleSessionListener? = null
    private var disposed = false
    private val manager = TailscaleSessionManager(
        scope,
        makeNode = { NativeNodeAdapter(host.makeNode(it)) },
        saveAccount = host::saveAccount,
        discardAccount = { host.discardAccount(it) },
        sessionsChanged = {}
    )
    val state: TailscaleStatus get() = manager.state.value
    val addition: TailscaleAddition? get() = manager.addition.value
    val currentNode: TailscaleNativeNode? get() = (manager.currentNode as? NativeNodeAdapter)?.native
    val currentAccountID: String? get() = manager.currentAccountID
    val currentSessionToken: String? get() = manager.currentSessionToken

    init {
        scope.launch { manager.state.collect { notifyListener() } }
        scope.launch { manager.addition.collect { notifyListener() } }
    }

    fun observe(listener: TailscaleSessionListener) {
        this.listener = listener
        notifyListener()
    }
    private fun notifyListener() {
        if (!disposed) listener?.changed(state, addition)
    }
    fun dispose() {
        disposed = true
        listener = null
        manager.stop()
        scope.launch {
            manager.awaitCleanup()
            scope.cancel()
        }
    }
    fun start(account: TailscaleAccount, authKey: String, defaultHostname: String) = manager.start(account, authKey, defaultHostname)
    fun stop() = manager.stop()
    fun deleteAccount(account: TailscaleAccount, selected: Boolean) = manager.deleteAccount(account, selected)
    fun beginAddition(account: TailscaleAccount) = manager.beginAddition(account)
    fun confirmAddition(): String? = manager.confirmAddition()
    fun cancelAddition() = manager.cancelAddition()
    fun promptCurrentLogin() = manager.promptCurrentLogin()
    fun refresh(result: TailscaleCloseResult) {
        scope.launch {
            manager.refresh().joinAll()
            result.complete(true)
        }
    }
    fun acceptCurrentStatus(token: String, json: String): Boolean {
        val adapter = manager.currentNode as? NativeNodeAdapter ?: return false
        if (manager.currentSessionToken != token) return false
        return manager.acceptCurrentStatus(adapter, json)
    }
}
