package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleNode
import com.clarklevis.dsh.shared.tailscale.TailscaleSessionManager
import com.clarklevis.dsh.shared.tailscale.TailscaleStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleReauthenticationTest {
    private val account = TailscaleAccount("00000000-0000-0000-0000-000000000001", authenticated = true)
    private val url = "https://login.tailscale.com/a/existing"
    private val login = """{"state":"NeedsLogin","authURL":"$url"}"""
    private val running = """{"state":"Running","peers":1,"routesReady":true}"""

    private class Node(var json: String) : TailscaleNode {
        var starts = 0
        var closed = 0
        var beginGate: CompletableDeferred<String>? = null
        override suspend fun begin(authKey: String, hostname: String): String {
            starts++
            return beginGate?.await() ?: json
        }
        override suspend fun status() = json
        override suspend fun close(): Boolean {
            closed++
            return true
        }
    }

    @Test fun existingLoginCanBeCancelledReopenedAndConfirmedWithoutReplacingNode() = runTest {
        val node = Node(login)
        val saved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(backgroundScope, { node }, { value, _ -> saved += value.id }, { discarded += it.id }, {})
        manager.start(account, "", "dsh-existing")
        runCurrent()
        assertNull(manager.addition.value)
        manager.promptCurrentLogin()
        assertTrue(assertNotNull(manager.addition.value).isReauthentication)
        assertEquals(url, manager.addition.value?.loginURL)
        manager.cancelAddition()
        runCurrent()
        assertNull(manager.addition.value)
        assertSame(node, manager.currentNode)
        assertEquals(0, node.closed)
        assertTrue(discarded.isEmpty())
        manager.promptCurrentLogin()
        node.json = login.replace("/existing", "/renewed")
        manager.refresh()
        runCurrent()
        assertEquals(url.replace("/existing", "/renewed"), manager.confirmAddition())
        assertNull(manager.addition.value)
        node.json = running
        manager.refresh()
        runCurrent()
        assertEquals(TailscaleStage.READY, manager.state.value.stage)
        assertEquals(listOf(account.id), saved)
        assertEquals(1, node.starts)
        assertEquals(0, node.closed)
        manager.promptCurrentLogin()
        assertNull(manager.addition.value)
        manager.stop()
        runCurrent()
    }

    @Test fun selectingStartingAccountWaitsForLoginURLAndCancelledPromptStaysClosed() = runTest {
        val gate = CompletableDeferred<String>()
        val node = Node(login).apply { beginGate = gate }
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "dsh-existing")
        manager.promptCurrentLogin()
        runCurrent()
        assertNull(manager.addition.value)
        gate.complete("""{"state":"NeedsLogin"}""")
        runCurrent()
        assertNull(manager.addition.value?.loginURL)
        assertNotNull(manager.addition.value)
        manager.refresh()
        runCurrent()
        assertEquals(url, manager.addition.value?.loginURL)
        manager.cancelAddition()
        manager.refresh()
        runCurrent()
        assertNull(manager.addition.value)
        assertEquals(1, node.starts)
        manager.stop()
        runCurrent()
    }

    @Test fun browserLoginFromCopiedURLClosesPromptWithoutCreatingAnotherAccount() = runTest {
        val node = Node(login)
        val saved = mutableListOf<String>()
        val manager = TailscaleSessionManager(backgroundScope, { node }, { value, _ -> saved += value.id }, {}, {})
        manager.start(account, "", "dsh-existing")
        runCurrent()
        manager.promptCurrentLogin()
        node.json = running
        manager.refresh()
        runCurrent()
        assertNull(manager.addition.value)
        assertEquals(listOf(account.id), saved)
        assertSame(node, manager.currentNode)
        manager.stop()
        runCurrent()
    }

    @Test fun expiredReadySessionResumesPollingWhenLoginIsRequested() = runTest {
        val node = Node(running)
        val manager = TailscaleSessionManager(backgroundScope, { node }, { _, _ -> }, {}, {})
        manager.start(account, "", "dsh-existing")
        runCurrent()
        node.json = login
        manager.refresh()
        runCurrent()
        manager.promptCurrentLogin()
        assertEquals(url, manager.confirmAddition())
        runCurrent()
        node.json = running
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TailscaleStage.READY, manager.state.value.stage)
        assertEquals(1, node.starts)
        manager.stop()
        runCurrent()
    }
}
