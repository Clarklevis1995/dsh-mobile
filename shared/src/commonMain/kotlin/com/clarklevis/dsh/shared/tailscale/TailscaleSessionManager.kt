package com.clarklevis.dsh.shared.tailscale

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val RESUME_READY_TIMEOUT_MILLISECONDS = 30_000L
private const val CONNECTION_POLL_MILLISECONDS = 250L

/** 原生 SDK 适配器。各实例必须拥有独立节点、串行执行队列和状态目录。 */
interface TailscaleNode {
    suspend fun begin(authKey: String, hostname: String): String
    suspend fun status(): String
    suspend fun close(): Boolean
    suspend fun isHealthy(): Boolean = true
    suspend fun prepareAfterResume(): Boolean = true
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
 * 平台提供恢复检查；共享层负责原身份重建与就绪等待，清理屏障避免复用尚未关闭的目录。
 */
@Suppress("TooManyFunctions") // 账号命令与恢复统一经过同一状态机，避免平台自行调度节点。
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
        var hostname = ""
        var recovery: Deferred<Boolean>? = null

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
        return session.status.canConnect
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

    /** 恢复只替换当前节点，保留账号目录、登录身份和独立的候选登录流程。 */
    suspend fun prepareForConnection(recover: Boolean): Boolean {
        val session = active.value ?: return false
        val recovery = session.recovery ?: beginRecovery(session, recover)
        return recovery.await()
    }

    private fun beginRecovery(session: Session, recover: Boolean): Deferred<Boolean> {
        val recovery = scope.async(start = CoroutineStart.LAZY) { recoverSession(session, recover) }
        session.recovery = recovery
        recovery.invokeOnCompletion { if (session.recovery === recovery) session.recovery = null }
        recovery.start()
        return recovery
    }

    private suspend fun recoverSession(session: Session, recover: Boolean): Boolean {
        val healthy = when {
            !session.began -> true
            recover -> session.node.prepareAfterResume()
            else -> session.node.isHealthy()
        }
        return when {
            !live(session) || active.value !== session -> false
            !session.began -> awaitReady(session)
            healthy -> {
                apply(session, session.node.status(), false)
                // 状态查询曾经失败时原轮询可能已结束；重试必须重新观察 SDK，而非复用失败缓存。
                if (live(session) && session.status.isTransient && session.job?.isActive != true) {
                    session.job = scope.launch { poll(session) }
                }
                awaitReady(session)
            }
            else -> {
                val previous = retire(session, discard = false)
                val replacement = Session(session.account, makeNode(session.account), ++generation)
                active.value = replacement
                mutableState.value = replacement.status
                launch(replacement, "", session.hostname, previous)
                awaitReady(replacement)
            }
        }
    }

    private suspend fun awaitReady(session: Session): Boolean = withTimeoutOrNull(RESUME_READY_TIMEOUT_MILLISECONDS) {
        coroutineScope {
            // 只有连接屏障等待期间快速查询；就绪、停止或超时后取消，避免后台常驻高频轮询。
            val observer = launch { pollConnectionReadiness(session) }
            try {
                val (current, status) = combine(active, state) { current, status -> current to status }.first { (current, status) ->
                    current !== session ||
                        status.canConnect ||
                        status.stage in setOf(TailscaleStage.DISABLED, TailscaleStage.FAILED, TailscaleStage.NEEDS_LOGIN)
                }
                current === session && status.canConnect
            } finally {
                observer.cancel()
            }
        }
    } ?: false

    private suspend fun pollConnectionReadiness(session: Session) {
        while (live(session) && active.value === session && session.status.isTransient) {
            delay(CONNECTION_POLL_MILLISECONDS)
            if (!live(session) || active.value !== session) return
            if (!session.began || session.status.canConnect) continue
            val json = session.node.status()
            // 相同结果不重复保存账号或通知界面；迟到结果仍受会话身份检查约束。
            if (live(session) && json != session.lastJSON) apply(session, json, false)
        }
    }

    suspend fun awaitCleanup() {
        cleanups.values.toList().forEach { it.task.await() }
    }

    private fun live(session: Session): Boolean = !session.cancelled &&
        (active.value === session || candidate === session)

    private fun launch(session: Session, authKey: String, defaultHostname: String, previous: Deferred<Boolean>? = null) {
        session.hostname = session.account.nodeName.ifBlank { defaultHostname }
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
            val json = session.node.begin(authKey, session.hostname)
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
