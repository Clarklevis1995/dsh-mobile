package com.clarklevis.dsh.shared.tailscale

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 原生 SDK 适配器。各实例必须拥有独立节点、串行执行队列和状态目录。 */
interface TailscaleNode {
    suspend fun begin(authKey: String, hostname: String): String
    suspend fun status(): String
    suspend fun close(): Boolean
}

data class TailscaleAddition(
    val account: TailscaleAccount,
    val loginURL: String? = null,
    val confirmed: Boolean = false,
    val status: TailscaleStatus = TailscaleStatus(stage = TailscaleStage.STARTING),
    val isReauthentication: Boolean = false
)

/**
 * 当前节点与候选节点的共同调度。命令在调用方的主线程执行，原生阻塞调用由适配器切换线程。
 * 不依赖 Android 生命周期，返回前台只刷新；清理屏障避免重用尚未关闭的状态目录。
 */
class TailscaleSessionManager(
    private val scope: CoroutineScope,
    private val makeNode: (TailscaleAccount) -> TailscaleNode,
    private val saveAccount: (TailscaleAccount, TailscaleStatus) -> Unit,
    private val discardAccount: suspend (TailscaleAccount) -> Unit,
    private val sessionsChanged: (Boolean) -> Unit
) {
    private class Session(val account: TailscaleAccount, val node: TailscaleNode, val token: Long) {
        var job: Job? = null
        var status = TailscaleStatus(stage = TailscaleStage.STARTING)
        var lastJSON = ""
        var promptForLogin = false

        @Volatile var cancelled = false

        @Volatile var began = false
    }
    private data class Cleanup(val token: Long, val task: Deferred<Boolean>)
    private val active = MutableStateFlow<Session?>(null)
    private var generation = 0L
    private var candidate: Session? = null
    private val cleanups = mutableMapOf<String, Cleanup>()
    private val mutableState = MutableStateFlow(TailscaleStatus())
    private val mutableAddition = MutableStateFlow<TailscaleAddition?>(null)
    val state = mutableState.asStateFlow()
    val addition = mutableAddition.asStateFlow()
    val hasSession: Boolean get() = active.value != null || candidate != null
    val currentNode: TailscaleNode? get() = active.value?.takeIf { it.began && !it.cancelled }?.node
    val currentAccountID: String? get() = active.value?.account?.id
    val currentSessionToken: String? get() = active.value?.token?.toString()

    fun acceptCurrentStatus(node: TailscaleNode, json: String): Boolean {
        val session = active.value ?: return false
        if (session.node !== node || !live(session)) return false
        apply(session, json, false)
        return session.status.backendRunning
    }

    fun start(account: TailscaleAccount, authKey: String, defaultHostname: String) {
        cancelAddition()
        val previous = active.value?.let { retire(it, discard = false) }
        val session = Session(account, makeNode(account), ++generation)
        active.value = session
        mutableState.value = session.status
        sessionsChanged(true)
        launch(session, authKey, defaultHostname, previous)
    }

    fun stop() {
        cancelAddition()
        active.value?.let { retire(it, discard = false) }
        active.value = null
        mutableState.value = TailscaleStatus()
        sessionsChanged(false)
    }

    /** 删除非当前账号只清理自己的目录；目录仍被旧节点占用时等待关闭屏障。 */
    fun deleteAccount(account: TailscaleAccount, selected: Boolean) {
        if (selected || active.value?.account?.id == account.id) stop()
        scheduleCleanup(account, discard = true) { true }
    }

    fun beginAddition(account: TailscaleAccount) {
        if (candidate != null) return
        cancelAddition()
        val session = Session(account, makeNode(account), ++generation)
        candidate = session
        mutableAddition.value = TailscaleAddition(account)
        sessionsChanged(true)
        launch(session, "", account.nodeName)
    }

    fun confirmAddition(): String? {
        val addition = mutableAddition.value?.takeUnless { it.status.stage == TailscaleStage.FAILED }
        val session = (if (addition?.isReauthentication == true) active.value else candidate)
            ?.takeIf { it.account.id == addition?.account?.id }
        val url = addition?.loginURL
        if (addition == null || session == null || url == null) return null
        if (addition.isReauthentication) {
            cancelAddition()
        } else {
            mutableAddition.value = addition.copy(confirmed = true)
            if (session.lastJSON.isNotEmpty()) apply(session, session.lastJSON, false)
        }
        return url
    }

    fun cancelAddition() {
        active.value?.promptForLogin = false
        mutableAddition.value = null
        val session = candidate ?: return
        candidate = null
        retire(session, discard = true)
        sessionsChanged(active.value != null)
    }

    /** 点击已有账号时复用当前节点的认证链接，不新建节点或清理账号凭据。 */
    fun promptCurrentLogin() {
        cancelAddition()
        val session = active.value ?: return
        if (session.status.backendRunning || !session.status.isTransient) return
        session.promptForLogin = true
        updateCurrentLogin(session)
        if (session.began && session.job?.isActive != true) session.job = scope.launch { poll(session) }
    }

    fun refresh(): List<Job> = listOfNotNull(active.value, candidate).mapNotNull { session ->
        if (session.began || (candidate === session && mutableAddition.value?.confirmed == true)) {
            scope.launch {
                val json = session.node.status()
                if (live(session)) apply(session, json, false)
            }
        } else {
            null
        }
    }

    suspend fun awaitCleanup() {
        cleanups.values.toList().forEach { it.task.await() }
    }

    private fun live(session: Session): Boolean = !session.cancelled &&
        (active.value === session || candidate === session)

    private fun launch(session: Session, authKey: String, defaultHostname: String, previous: Deferred<Boolean>? = null) {
        val directoryCleanup = cleanups[session.account.id]?.task
        session.job = scope.launch {
            val priorClosed = previous?.await() ?: true
            val available = directoryCleanup?.await() ?: true
            if (!live(session)) return@launch
            if (!priorClosed || !available) {
                session.status = TailscaleStatus(stage = TailscaleStage.FAILED, failureKind = "shutdown")
                publish(session)
                return@launch
            }
            val json = session.node.begin(authKey, session.account.nodeName.ifBlank { defaultHostname })
            if (!live(session)) return@launch
            session.began = true
            apply(session, json, true)
            poll(session)
        }
    }

    private suspend fun poll(session: Session) {
        var wait = TailscalePolicy.MIN_POLL_MILLISECONDS
        while (live(session) && session.status.isTransient) {
            delay(wait)
            if (!live(session)) break
            wait = TailscalePolicy.nextPollMilliseconds(wait)
            val status = session.node.status()
            if (live(session)) apply(session, status, false)
        }
    }

    private fun apply(session: Session, json: String, bringUp: Boolean) {
        if (!live(session)) return
        session.lastJSON = json
        val pending = candidate === session
        val next = TailscalePolicy.parseStatus(json, bringUp, pending)
        session.status = next
        if (pending) {
            val addition = mutableAddition.value ?: return
            mutableAddition.value = addition.copy(loginURL = next.authUrl ?: addition.loginURL, status = next)
            if (next.canPromote(addition.confirmed)) {
                val old = active.value
                active.value = session
                candidate = null
                saveAccount(session.account, next)
                mutableAddition.value = null
                mutableState.value = next
                old?.let { retire(it, discard = false) }
            }
        } else {
            if (next.backendRunning) saveAccount(session.account, next)
            mutableState.value = next
            updateCurrentLogin(session)
        }
    }

    private fun updateCurrentLogin(session: Session) {
        if (!session.promptForLogin) return
        val previous = mutableAddition.value?.takeIf { it.isReauthentication && it.account.id == session.account.id }
        if (session.status.backendRunning) {
            session.promptForLogin = false
            if (previous != null) mutableAddition.value = null
        } else if (session.status.stage == TailscaleStage.NEEDS_LOGIN || previous != null) {
            mutableAddition.value = TailscaleAddition(
                account = session.account,
                loginURL = session.status.authUrl ?: previous?.loginURL,
                status = session.status,
                isReauthentication = true
            )
        }
    }

    private fun publish(session: Session) {
        if (candidate === session) {
            mutableAddition.value = mutableAddition.value?.copy(status = session.status)
        } else if (active.value === session) {
            mutableState.value = session.status
        }
    }

    private fun retire(session: Session, discard: Boolean): Deferred<Boolean> {
        session.cancelled = true
        val job = session.job
        job?.cancel()
        return scheduleCleanup(session.account, discard) {
            job?.join()
            session.node.close()
        }
    }

    private fun scheduleCleanup(account: TailscaleAccount, discard: Boolean, close: suspend () -> Boolean): Deferred<Boolean> {
        val previous = cleanups[account.id]?.task
        val token = ++generation
        val task = scope.async(start = CoroutineStart.LAZY) {
            val priorClosed = previous?.await() ?: true
            val ownClosed = close()
            val closed = priorClosed && ownClosed
            if (closed && discard) runCatching { discardAccount(account) }
            if (closed && cleanups[account.id]?.token == token) cleanups.remove(account.id)
            closed
        }
        cleanups[account.id] = Cleanup(token, task)
        task.start()
        return task
    }
}
