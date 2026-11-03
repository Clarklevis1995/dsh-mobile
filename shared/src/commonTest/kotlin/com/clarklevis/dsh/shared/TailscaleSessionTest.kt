package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAccountCatalog
import com.clarklevis.dsh.shared.tailscale.TailscaleNode
import com.clarklevis.dsh.shared.tailscale.TailscalePolicy
import com.clarklevis.dsh.shared.tailscale.TailscaleSessionManager
import com.clarklevis.dsh.shared.tailscale.TailscaleStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleSessionTest {
    private val idA = "00000000-0000-0000-0000-000000000001"
    private val idB = "00000000-0000-0000-0000-000000000002"
    private val idC = "00000000-0000-0000-0000-000000000003"
    private val login = """{"state":"NeedsLogin","authURL":"https://login.tailscale.com/a/test"}"""
    private fun running(routes: Boolean = true, peers: Int = 5) =
        """{"state":"Running","routesReady":$routes,"peers":$peers,"tailscaleIPs":["100.96.53.9"],"loginName":"user@example.com","tailnetName":"tailnet"}"""
    private class Node(var json: String) : TailscaleNode {
        var closed = 0
        var starts = 0
        var closesOK = true
        var gate: CompletableDeferred<Unit>? = null
        var closeGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(authKey: String, hostname: String): String {
            starts++
            withContext(NonCancellable) { gate?.await() }
            return json
        }
        override suspend fun status() = json
        override suspend fun close(): Boolean {
            closed++
            withContext(NonCancellable) { closeGate?.await() }
            return closesOK
        }
    }

    @Test fun catalogMigratesLegacyAndFiltersUnfinishedAccounts() {
        val legacy = TailscaleAccount(idA, authenticated = true, usesLegacyState = true)
        val migrated = TailscaleAccountCatalog(null, legacy)
        assertEquals("tsnet-state", migrated.selectedAccount.stateRelativePath)
        val restored =
            TailscaleAccountCatalog(
                """{"accounts":[{"id":"$idA","authenticated":true},{"id":"$idB","authenticated":false}],"selectedID":"$idB"}""",
                legacy
            )
        assertEquals(listOf(idA), restored.accounts.map { it.id })
        assertEquals(idA, restored.selectedID)
        assertFalse(restored.snapshotJSON().contains(idB))
    }

    @Test fun catalogRejectsUnsafeDirectoriesAndKeepsNamesAndSelection() {
        val legacy = TailscaleAccount(idA)
        val unsafe = TailscaleAccountCatalog("""{"accounts":[{"id":"../other","authenticated":true}]}""", legacy)
        assertTrue(unsafe.accounts.isEmpty())
        val store = TailscaleAccountCatalog(null, legacy)
        store.saveAuthenticated(legacy, "first", "net-a")
        store.saveAuthenticated(TailscaleAccount(idB), "second", "net-b")
        assertTrue(store.select(idA))
        store.rename(idA, " 工作 ")
        store.markAuthenticated(idB, "wrong", null)
        assertEquals("工作", store.selectedAccount.displayName)
        assertEquals("first", store.selectedAccount.loginName)
        val restored = TailscaleAccountCatalog(store.snapshotJSON(), legacy)
        assertEquals(idA, restored.selectedID)
        assertEquals(2, restored.accounts.size)
        assertTrue(store.setNodeName(""))
        assertFalse(store.setNodeName("中文"))
    }

    @Test fun statusUsesJSONAndRequiresRoutesAndConfirmationBeforePromotion() {
        val status = TailscalePolicy.parseStatus(running(false).replace("\":", "\" : "), true, true)
        assertEquals(TailscaleStage.WAITING_ROUTES, status.stage)
        assertFalse(status.canPromote(true))
        val ready = TailscalePolicy.parseStatus(running(), false, true)
        assertFalse(ready.canPromote(false))
        assertTrue(ready.canPromote(true))
        assertEquals("100.96.53.9", ready.nodeAddress)
        assertNull(TailscalePolicy.parseStatus("""{"authURL":"https://controlplane.tailscale.com"}""", true, true).authUrl)
        assertEquals(TailscaleStage.FAILED, TailscalePolicy.parseStatus("broken", false, false).stage)
        assertEquals(
            "native-load",
            TailscalePolicy.parseStatus("""{"stage":"native-load","error":"ABI mismatch"}""", true, false).failureKind
        )
    }

    @Test fun liveSessionMetadataDoesNotOverwriteRenamedAccountOrEditedHostname() {
        val account = TailscaleAccount(idA, nodeName = "old-node")
        val store = TailscaleAccountCatalog(null, account)
        store.saveAuthenticated(account, "user@example.com", "home")
        store.rename(idA, "工作")
        assertTrue(store.setNodeName("new-node"))
        store.saveAuthenticated(account, "user@example.com", "work")
        assertEquals("工作", store.selectedAccount.name)
        assertEquals("new-node", store.selectedAccount.nodeName)
        assertEquals("work", store.selectedAccount.tailnetName)
    }

    @Test fun namingAndBackoffAreShared() {
        assertEquals("dsh-pixel-9", TailscalePolicy.defaultNodeName("Pixel 9", "android"))
        assertEquals("dsh-ios", TailscalePolicy.defaultNodeName("手机", "ios"))
        assertEquals("my-node", TailscalePolicy.normalizeNodeName(" MY__NODE "))
        assertNull(TailscalePolicy.normalizeNodeName("a".repeat(64)))
        assertEquals(30_000, TailscalePolicy.nextPollMilliseconds(20_000))
    }

    @Test fun cancelPendingLoginKeepsActiveNodeAndSavedAccount() = runTest {
        val a = Node(running())
        val b = Node(login)
        val saved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(
            backgroundScope,
            { if (it.id == idA) a else b },
            { account, _ -> saved += account.id },
            { discarded += it.id },
            {}
        )
        manager.start(TailscaleAccount(idA), "", "dsh-a")
        runCurrent()
        manager.beginAddition(TailscaleAccount(idB))
        runCurrent()
        assertSame(a, manager.currentNode)
        assertEquals(TailscaleStage.READY, manager.state.value.stage)
        assertEquals(listOf(idA), saved)
        manager.cancelAddition()
        runCurrent()
        assertSame(a, manager.currentNode)
        assertEquals(0, a.closed)
        assertEquals(1, b.closed)
        assertEquals(listOf(idB), discarded)
        manager.stop()
        runCurrent()
    }

    @Test fun confirmedCandidateOnlyTakesOverOnceRoutesReady() = runTest {
        val a = Node(running())
        val b = Node(login)
        val saved = mutableListOf<String>()
        val manager = TailscaleSessionManager(
            backgroundScope,
            { if (it.id == idA) a else b },
            { account, _ -> saved += account.id },
            {},
            {}
        )
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.beginAddition(TailscaleAccount(idB))
        runCurrent()
        assertNotNull(manager.confirmAddition())
        b.json = running(false)
        manager.refresh()
        runCurrent()
        assertSame(a, manager.currentNode)
        assertEquals(TailscaleStage.WAITING_ROUTES, manager.addition.value?.status?.stage)
        b.json = running()
        manager.refresh()
        runCurrent()
        assertSame(b, manager.currentNode)
        assertNull(manager.addition.value)
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
        assertEquals(1, b.starts)
        assertEquals(idB, saved.last())
        manager.stop()
        runCurrent()
    }

    @Test fun failedCandidateAndLateStartupCannotReplaceActiveNode() = runTest {
        val a = Node(running())
        val b = Node(running()).apply { gate = CompletableDeferred() }
        val manager = TailscaleSessionManager(backgroundScope, { if (it.id == idA) a else b }, { _, _ -> }, {}, {})
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.beginAddition(TailscaleAccount(idB))
        runCurrent()
        manager.cancelAddition()
        runCurrent()
        assertSame(a, manager.currentNode)
        assertEquals(0, b.closed)
        b.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, b.closed)
        assertNull(manager.addition.value)
        assertSame(a, manager.currentNode)
        assertFalse(manager.acceptCurrentStatus(b, running()))
        manager.stop()
        runCurrent()
    }

    @Test fun deletingInactiveAccountKeepsCurrentAndCandidateAlive() = runTest {
        val a = Node(running())
        val b = Node(login)
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(
            backgroundScope,
            { if (it.id == idA) a else b },
            { _, _ -> },
            { discarded += it.id },
            {}
        )
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.beginAddition(TailscaleAccount(idB))
        runCurrent()
        manager.deleteAccount(TailscaleAccount(idC), selected = false)
        runCurrent()
        assertEquals(listOf(idC), discarded)
        assertSame(a, manager.currentNode)
        assertEquals(TailscaleStage.READY, manager.state.value.stage)
        assertEquals(idB, manager.addition.value?.account?.id)
        assertEquals(0, a.closed)
        assertEquals(0, b.closed)
        manager.stop()
        runCurrent()
    }

    @Test fun deletingStartingCurrentWaitsForNativeCallAndCannotResurrectAccount() = runTest {
        val a = Node(running()).apply { gate = CompletableDeferred() }
        val saved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(
            backgroundScope,
            { a },
            { account, _ -> saved += account.id },
            { discarded += it.id },
            {}
        )
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.deleteAccount(TailscaleAccount(idA), selected = true)
        runCurrent()
        assertNull(manager.currentNode)
        assertEquals(TailscaleStage.DISABLED, manager.state.value.stage)
        assertTrue(discarded.isEmpty())
        assertEquals(0, a.closed)
        a.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, a.closed)
        assertEquals(listOf(idA), discarded)
        assertTrue(saved.isEmpty())
        assertFalse(manager.acceptCurrentStatus(a, running()))
    }

    @Test fun deletingOldAccountWaitsForItsCloseWithoutStoppingNewAccount() = runTest {
        val a = Node(running()).apply { closeGate = CompletableDeferred() }
        val b = Node(running())
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(
            backgroundScope,
            { if (it.id == idA) a else b },
            { _, _ -> },
            { discarded += it.id },
            {}
        )
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.start(TailscaleAccount(idB), "", "b")
        runCurrent()
        manager.deleteAccount(TailscaleAccount(idA), selected = false)
        runCurrent()
        assertTrue(discarded.isEmpty())
        a.closeGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(idA), discarded)
        assertSame(b, manager.currentNode)
        assertEquals(0, b.closed)
        manager.stop()
        runCurrent()
    }

    @Test fun failedClosePreventsDeletingDirectoryStillUsedByNativeNode() = runTest {
        val a = Node(running()).apply { closesOK = false }
        val discarded = mutableListOf<String>()
        val manager = TailscaleSessionManager(backgroundScope, { a }, { _, _ -> }, { discarded += it.id }, {})
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.deleteAccount(TailscaleAccount(idA), selected = true)
        runCurrent()
        assertNull(manager.currentNode)
        assertFalse(manager.hasSession)
        assertTrue(discarded.isEmpty())
        assertEquals(1, a.closed)
    }

    @Test fun readdingDoesNotWaitForCancelledCandidateAndFailedCloseBlocksDirectoryReuse() = runTest {
        val a = Node(running()).apply { closesOK = false }
        val b = Node(login).apply { gate = CompletableDeferred() }
        val c = Node(login)
        val manager = TailscaleSessionManager(
            backgroundScope,
            {
                when (it.id) {
                    idA -> a
                    idB -> b
                    else -> c
                }
            },
            { _, _ -> },
            {},
            {}
        )
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        manager.beginAddition(TailscaleAccount(idB))
        runCurrent()
        manager.cancelAddition()
        manager.beginAddition(TailscaleAccount(idC))
        runCurrent()
        assertEquals(1, c.starts)
        manager.cancelAddition()
        b.gate!!.complete(Unit)
        runCurrent()
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        assertEquals(1, a.starts)
        assertEquals("shutdown", manager.state.value.failureKind)
        manager.start(TailscaleAccount(idA), "", "a")
        runCurrent()
        assertEquals(1, a.starts)
        manager.stop()
        runCurrent()
    }
}
