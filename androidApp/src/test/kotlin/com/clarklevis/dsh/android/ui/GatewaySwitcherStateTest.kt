package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySwitcherStateTest {
    @Test
    fun deleteActionTracksSelectionCount() {
        assertNull(gatewayDeleteActionLabel(0))
        assertEquals("删除", gatewayDeleteActionLabel(1))
        assertEquals("批量删除", gatewayDeleteActionLabel(2))
    }

    @Test
    fun radioSelectionCanAddAndRemoveMultipleHosts() {
        var selected = emptySet<String>()
        selected = toggleSelection(selected, "a")
        selected = toggleSelection(selected, "b")
        assertEquals(setOf("a", "b"), selected)
        assertEquals(setOf("b"), toggleSelection(selected, "a"))
    }
}
