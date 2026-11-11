package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleNode
import com.clarklevis.dsh.shared.tailscale.TailscaleSessionManager
import com.clarklevis.dsh.shared.tailscale.TailscaleStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleResumeRecoveryTest {
    private val account = TailscaleAccount("00000000-0000-0000-0000-000000000001", authenticated = true)
    private val pending = TailscaleAccount("00000000-0000-0000-0000-000000000002")
    private val running = """{"state":"Running","peers":5,"routesReady":true}"""
    private val cachedRunning = """{"state":"Running","peers":0,"routesReady":true}"""
    private val login = """{"state":"NeedsLogin","authURL":"https://login.tailscale.com/a/pending"}"""
    private val upTimeout = """{"ok":false,"stage":"up","error":"context deadline exceeded","authURL":""}"""

    private class Node(var json: String, var healthy: Boolean = true) : TailscaleNode {
        val starts = mutableListOf<Pair<String, String>>()
        var checks = 0
        var healthQueries = 0
        var statusQueries = 0
        var closes = 0
        var closeOK = true
        var closeGate: CompletableDeferred<Unit>? = null
        var healthGate: CompletableDeferred<Boolean>? = null
        var statusGate: CompletableDeferred<String>? = null
        override suspend fun begin(authKey: String, hostname: String): String {
            starts += authKey to hostname
            return json
        }
        override suspend fun status(): String {
            statusQueries++
            return statusGate?.await() ?: json
        }
        override suspend fun prepareAfterResume(): Boolean {
            checks++
            return healthGate?.await() ?: healthy
        }
        override suspend fun isHealthy(): Boolean {
            healthQueries++
            return healthy
        }
        override suspend fun close(): Boolean {
            closes++
            closeGate?.await()
            return closeOK
        }
    }

    @Test fun damagedRunningNodeWaitsForCloseAndReusesIdentityWhileCandidateSurvives() = runTest {
        val old = Node(running, healthy = false).apply { closeGate = CompletableDeferred() }
        val replacement = Node(running)
        val candidate = Node(login)
        val accounts = mutableListOf<TailscaleAccount>()
        var made = 0
        val manager = TailscaleSessionManager(backgroundScope, {
            accounts += it
            if (it.id == pending.id) {
                candidate
            } else if (made++ == 0) {
                old
            } else {
                replacement
            }
        }, { _, _ -> }, { error("恢复不能删除账号目录") }, {})
        manager.start(account, "initial-key", "original-hostname")
        runCurrent()
        manager.beginAddition(pending)
        runCurrent()
        val first = async { manager.prepareForConnection(recover = true) }
        val second = async { manager.prepareForConnection(recover = true) }
        runCurrent()
        assertEquals(TailscaleStage.STARTING, manager.state.value.stage)
        assertTrue(replacement.starts.isEmpty())
        assertFalse(first.isCompleted)
        assertEquals(pending.id, manager.addition.value?.account?.id)
        old.closeGate!!.complete(Unit)
        runCurrent()
        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(listOf("" to "original-hostname"), replacement.starts)
        assertEquals(1, old.checks)
        assertEquals(1, old.closes)
        assertSame(replacement, manager.currentNode)
        assertEquals(account.stateRelativePath, accounts.last().stateRelativePath)
        assertEquals(0, candidate.closes)
        manager.stop()
        runCurrent()
    }

    @Test fun healthyResumeKeepsNodeAndRepeatedConnectionPreparationDoesNotResetIt() = runTest {
        val node = Node(running)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same-host")
        runCurrent()
        assertTrue(manager.prepareForConnection(recover = true))
        assertTrue(manager.prepareForConnection(recover = false))
        assertEquals(1, node.checks)
        assertEquals(1, node.healthQueries)
        assertEquals(1, node.starts.size)
        assertEquals(0, node.closes)
        manager.stop()
        runCurrent()
    }

    @Test fun rebuiltCachedRunningNodeWaitsForPeersAndReleasesBothConnectionsWithinOneShortPoll() = runTest {
        val old = Node(running, healthy = false)
        val replacement = Node(cachedRunning)
        var made = 0
        var saves = 0
        val manager = TailscaleSessionManager(
            backgroundScope,
            { if (made++ == 0) old else replacement },
            { _, _ -> saves++ },
            {},
            {}
        )
        manager.start(account, "", "same-host")
        runCurrent()
        val control = async { manager.prepareForConnection(recover = true) }
        val conversation = async { manager.prepareForConnection(recover = true) }
        runCurrent()
        assertEquals(TailscaleStage.WAITING_ROUTES, manager.state.value.stage)
        assertFalse(control.isCompleted)
        assertFalse(conversation.isCompleted)
        assertFalse(manager.acceptCurrentStatus(replacement, cachedRunning))
        val savedBeforePolling = saves
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(control.isCompleted, "不能把 Running + 空对端缓存当成网关就绪")
        assertEquals(savedBeforePolling, saves, "相同状态不能每 250ms 写入账号")
        assertEquals(4, replacement.statusQueries, "两个通道必须共用快速观察器")

        replacement.json = running
        advanceTimeBy(250)
        runCurrent()
        assertTrue(control.await())
        assertTrue(conversation.await())
        assertTrue(manager.acceptCurrentStatus(replacement, running))
        val finishedQueries = replacement.statusQueries
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(finishedQueries, replacement.statusQueries, "就绪后必须停止快速查询")
        assertEquals(1, old.closes)
        manager.stop()
        runCurrent()
    }

    @Test fun stoppingWhileWaitingForCachedPeersCancelsReadinessPolling() = runTest {
        val node = Node(cachedRunning)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same-host")
        runCurrent()
        val preparation = async { manager.prepareForConnection(recover = false) }
        runCurrent()
        advanceTimeBy(250)
        runCurrent()
        assertFalse(preparation.isCompleted)
        manager.stop()
        runCurrent()
        assertFalse(preparation.await())
        val queriesAtStop = node.statusQueries
        node.json = running
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(queriesAtStop, node.statusQueries)
        assertEquals(TailscaleStage.DISABLED, manager.state.value.stage)
    }

    @Test fun damageReportedAfterResumeCheckIsRecoveredOnTheNextGatewayRetry() = runTest {
        val old = Node(running)
        val replacement = Node(running)
        var made = 0
        val manager = TailscaleSessionManager(backgroundScope, { if (made++ == 0) old else replacement }, { _, _ -> }, {}, {})
        manager.start(account, "", "original-host")
        runCurrent()
        assertTrue(manager.prepareForConnection(recover = true))
        old.healthy = false
        assertTrue(manager.prepareForConnection(recover = false))
        assertSame(replacement, manager.currentNode)
        assertEquals(1, old.checks)
        assertEquals(1, old.healthQueries)
        assertEquals(1, old.closes)
        assertEquals(listOf("" to "original-host"), replacement.starts)
        manager.stop()
        runCurrent()
    }

    @Test fun stoppingDuringHealthCheckCannotResurrectOldAccount() = runTest {
        val gate = CompletableDeferred<Boolean>()
        val old = Node(running).apply { healthGate = gate }
        var made = 0
        val manager = TailscaleSessionManager(backgroundScope, {
            made++
            old
        }, { _, _ -> }, {}, {})
        manager.start(account, "", "old")
        runCurrent()
        val recovery = async { manager.prepareForConnection(recover = true) }
        runCurrent()
        manager.stop()
        gate.complete(false)
        runCurrent()
        assertFalse(recovery.await())
        assertEquals(1, made)
        assertEquals(TailscaleStage.DISABLED, manager.state.value.stage)
    }

    @Test fun failedShutdownBlocksReplacementAndRetainsSavedAccount() = runTest {
        val old = Node(running, healthy = false).apply { closeOK = false }
        val replacement = Node(running)
        var made = 0
        val manager = TailscaleSessionManager(backgroundScope, { if (made++ == 0) old else replacement }, { _, _ -> }, {}, {})
        manager.start(account, "", "same")
        runCurrent()
        val recovery = async { manager.prepareForConnection(recover = true) }
        runCurrent()
        assertFalse(recovery.await())
        assertEquals("shutdown", manager.state.value.failureKind)
        assertTrue(replacement.starts.isEmpty())
        assertEquals(account.id, manager.currentAccountID)
        manager.stop()
        runCurrent()
    }

    @Test fun timeoutDoesNotPermanentlyBlockAnAccountThatLaterBecomesReady() = runTest {
        val node = Node("""{"state":"Starting"}""")
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same")
        runCurrent()
        val preparation = async { manager.prepareForConnection(recover = true) }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(preparation.await())
        node.json = running
        assertTrue(manager.prepareForConnection(recover = false))
        manager.stop()
        runCurrent()
    }

    @Test fun sdkUpTimeoutKeepsPollingAndBecomesReadyWithoutManualRefresh() = runTest {
        val node = Node(upTimeout)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same")
        runCurrent()
        assertEquals(TailscaleStage.STARTING, manager.state.value.stage)
        node.json = running
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(manager.state.value.canPromote(true))
        assertEquals(1, node.starts.size)
        assertEquals(0, node.closes)
        manager.stop()
        runCurrent()
    }

    @Test fun gatewayRetryRefreshesFailedStatusAndRestartsPollingUntilReady() = runTest {
        val node = Node(running)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same")
        runCurrent()
        manager.acceptCurrentStatus(node, """{"error":"temporary status failure"}""")
        assertEquals(TailscaleStage.FAILED, manager.state.value.stage)
        node.json = """{"state":"Starting"}"""
        val retry = async { manager.prepareForConnection(recover = false) }
        runCurrent()
        assertEquals(1, node.statusQueries)
        assertFalse(retry.isCompleted)
        node.json = running
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(retry.await())
        assertEquals(1, node.starts.size)
        assertEquals(0, node.checks)
        assertEquals(0, node.closes)
        manager.stop()
        runCurrent()
    }

    @Test fun stoppingDuringRetryStatusQueryCannotRestoreReadiness() = runTest {
        val node = Node(running)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "same")
        runCurrent()
        val statusGate = CompletableDeferred<String>()
        node.statusGate = statusGate
        val retry = async { manager.prepareForConnection(recover = false) }
        runCurrent()
        assertFalse(retry.isCompleted)
        manager.stop()
        statusGate.complete(running)
        runCurrent()
        assertFalse(retry.await())
        assertEquals(TailscaleStage.DISABLED, manager.state.value.stage)
    }
}
