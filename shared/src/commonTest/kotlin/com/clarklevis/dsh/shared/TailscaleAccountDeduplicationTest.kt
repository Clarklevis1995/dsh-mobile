package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAccountCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class TailscaleAccountDeduplicationTest {
    private val first = TailscaleAccount(
        id = "00000000-0000-0000-0000-000000000001",
        name = "工作账号",
        loginName = "User@Example.com",
        tailnetName = "Work.Example.",
        nodeName = "old-node",
        authenticated = true,
        usesLegacyState = true
    )
    private val second = TailscaleAccount(
        id = "00000000-0000-0000-0000-000000000002",
        loginName = " user@example.com ",
        tailnetName = " work.example ",
        nodeName = "new-node",
        authenticated = true
    )
    private val third = first.copy(id = "00000000-0000-0000-0000-000000000003", loginName = "other@example.com", usesLegacyState = false)

    private fun snapshot(accounts: List<TailscaleAccount>, selected: String): String = buildJsonObject {
        put("accounts", JsonArray(accounts.map { Json.encodeToJsonElement(it) }))
        put("selectedID", selected)
    }.toString()

    @Test fun repeatedLoginKeepsNewCredentialsAndOriginalRemark() {
        val store = TailscaleAccountCatalog(null, first)
        store.saveAuthenticated(second, second.loginName, second.tailnetName)
        assertEquals(1, store.accounts.size)
        assertEquals(second.id, store.selectedID)
        assertEquals("工作账号", store.selectedAccount.name)
        assertEquals("new-node", store.selectedAccount.nodeName)
        assertFalse(store.selectedAccount.usesLegacyState)
        assertEquals("tsnet-accounts/${second.id}", store.selectedAccount.stateRelativePath)
        store.markAuthenticated(first.id, first.loginName, first.tailnetName)
        assertEquals(second.id, store.selectedID)
        val restored = TailscaleAccountCatalog(store.snapshotJSON(), first)
        assertEquals(store.accounts, restored.accounts)
        assertEquals(second.id, restored.selectedID)
    }

    @Test fun storedDuplicatesPreserveSelectedDirectoryInEitherOrder() {
        listOf(listOf(first, third, second), listOf(second, third, first)).forEach { order ->
            listOf(first, second).forEach { selected ->
                val store = TailscaleAccountCatalog(snapshot(order, selected.id), third)
                assertEquals(listOf(selected.id, third.id), store.accounts.map { it.id })
                assertEquals(selected.id, store.selectedID)
                assertEquals(selected.nodeName, store.selectedAccount.nodeName)
                assertEquals(selected.stateRelativePath, store.selectedAccount.stateRelativePath)
                assertEquals("工作账号", store.selectedAccount.name)
            }
        }
    }

    @Test fun migrationDoesNotChangeAnotherSelectedAccount() {
        val store = TailscaleAccountCatalog(snapshot(listOf(first, third, second), third.id), third)
        assertEquals(listOf(first.id, third.id), store.accounts.map { it.id })
        assertEquals(third.id, store.selectedID)
    }

    @Test fun sameLoginOnDifferentTailnetsAndDifferentLoginsStaySeparate() {
        val store = TailscaleAccountCatalog(null, first)
        store.saveAuthenticated(second.copy(name = first.name), "user@example.com", "other.example")
        store.saveAuthenticated(third, "other@example.com", "work.example")
        assertEquals(3, store.accounts.size)
    }

    @Test fun incompleteIdentityDoesNotMergeByDisplayNameAndDeduplicatesWhenResolved() {
        val a = first.copy(loginName = "user@example.com", tailnetName = null)
        val b = second.copy(name = a.name, tailnetName = "")
        val c = third.copy(name = a.name, loginName = null)
        val store = TailscaleAccountCatalog(snapshot(listOf(a, b, c), b.id), third)
        assertEquals(3, store.accounts.size)
        store.saveAuthenticated(a, "user@example.com", "work.example")
        assertEquals(3, store.accounts.size)
        store.saveAuthenticated(b, "USER@example.com", "WORK.example")
        assertEquals(listOf(b.id, c.id), store.accounts.map { it.id })
        assertEquals(b.id, store.selectedID)
    }
}
