package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.protocol.GatewayTextPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ComposerQuoteIdentityTest {
    @Test fun duplicateRemovalAndPartReindexKeepTheSurvivingIdentity() {
        val store = ComposerQuoteIdentity()
        val initial = listOf(GatewayTextPart("text", "正文"), GatewayTextPart("quote", "相同引用"),
            GatewayTextPart("text", "中间正文"), GatewayTextPart("quote", "相同引用"))
        val entries = store.update(initial)
        assertNotEquals(entries[0].id, entries[1].id)
        assertEquals(1, store.remove(entries[0].id, initial))
        val remaining = listOf(GatewayTextPart("text", "正文中间正文"), GatewayTextPart("quote", "相同引用"))
        val survivor = store.update(remaining).single()
        assertEquals(entries[1].id, survivor.id)
        assertEquals(1, survivor.partIndex)
        val reindexed = listOf(GatewayTextPart("quote", "相同引用"), GatewayTextPart("text", "编辑后的正文"))
        assertEquals(entries[1].id, store.update(reindexed).single().id)
        assertEquals(0, store.remove(entries[1].id, reindexed))
        store.update(emptyList())
        assertNotEquals(entries[1].id, store.update(listOf(GatewayTextPart("quote", "相同引用"))).single().id)
    }
}
