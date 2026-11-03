package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAccountCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleAccountRemovalTest {
    private val first = TailscaleAccount(
        id = "00000000-0000-0000-0000-000000000001",
        nodeName = "legacy-node",
        loginName = "first@example.com",
        tailnetName = "home",
        authenticated = true,
        usesLegacyState = true
    )
    private val second = first.copy(id = "00000000-0000-0000-0000-000000000002", loginName = "second@example.com", usesLegacyState = false)
    private val replacement = "00000000-0000-0000-0000-000000000003"

    @Test fun removingAnotherAccountPreservesSelectionAndMetadata() {
        val store = TailscaleAccountCatalog(null, first)
        store.saveAuthenticated(second, second.loginName, second.tailnetName)
        store.select(first.id)
        assertEquals(second, store.remove(second.id, replacement))
        assertEquals(listOf(first), store.accounts)
        assertEquals(first.id, store.selectedID)
        assertEquals(first, TailscaleAccountCatalog(store.snapshotJSON(), first).selectedAccount)
        store.markAuthenticated(second.id, second.loginName, second.tailnetName)
        assertEquals(listOf(first), store.accounts)
    }

    @Test fun removingLastAccountPersistsFreshUnauthenticatedDirectoryAcrossRestart() {
        val store = TailscaleAccountCatalog(null, first)
        assertEquals(first, store.remove(first.id, replacement))
        assertTrue(store.accounts.isEmpty())
        assertNull(store.selectedID)
        assertEquals(replacement, store.selectedAccount.id)
        assertFalse(store.selectedAccount.authenticated)
        assertFalse(store.selectedAccount.usesLegacyState)
        assertEquals("", store.selectedAccount.nodeName)
        // 即使旧迁移参数仍为已登录，也不能重新读到删除前的凭据。
        val restored = TailscaleAccountCatalog(store.snapshotJSON(), first)
        assertTrue(restored.accounts.isEmpty())
        assertEquals(store.selectedAccount, restored.selectedAccount)
        assertEquals("tsnet-accounts/$replacement", restored.selectedAccount.stateRelativePath)
        assertFalse(restored.select(first.id))
    }

    @Test fun removingSelectedAccountSelectsRemainingAccount() {
        val store = TailscaleAccountCatalog(null, first)
        store.saveAuthenticated(second, second.loginName, second.tailnetName)
        store.remove(second.id, replacement)
        assertEquals(first.id, store.selectedID)
        assertEquals(listOf(first), store.accounts)
    }

    @Test fun invalidRemovalDoesNotChangeSavedCredentials() {
        val store = TailscaleAccountCatalog(null, first)
        val before = store.snapshotJSON()
        assertNull(store.remove(second.id, replacement))
        assertNull(store.remove(first.id, "../other"))
        assertNull(store.remove(first.id, first.id))
        assertEquals(before, store.snapshotJSON())
    }
}
