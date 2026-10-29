package com.clarklevis.dsh.android.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSidebarTest {
    @Test
    fun groupingKeepsSharedSessionsAndHidesBlankAndSubagentSessions() {
        val recent = session("recent", 30.0)
        val old = session("old", 10.0)
        val fork = session("fork", 20.0).copy(parentSessionId = "old")
        val groups = conversationSidebarGroups(
            listOf(workspace("one", listOf("recent", "old", "blank")), workspace("two", listOf("recent"))),
            listOf(old, fork, recent, session("blank", 40.0).copy(hasConversation = false),
                session("child", 50.0).copy(origin = "subagent"))
        )
        assertEquals(listOf("recent", "old"), groups[0].sessions.map { it.id })
        assertEquals(listOf("recent"), groups[1].sessions.map { it.id })
        assertTrue(groups[2].ungrouped)
        assertEquals(listOf("fork"), groups[2].sessions.map { it.id })
    }

    @Test
    fun emptyWorkspacesAndUngroupedRemainAvailable() {
        val groups = conversationSidebarGroups(listOf(workspace("empty", emptyList())), emptyList())
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.sessions.isEmpty() })
        assertTrue(groups.last().ungrouped)
    }

    @Test
    fun popupFollowsButtonSideInBothDirectionsAndStaysInsideWindow() {
        var opensFromRight = false
        val provider = ConversationSidebarPositionProvider(24) { opensFromRight = it.pivotFractionX == 1f }
        val window = IntSize(626, 890)
        val popup = IntSize(348, 842)
        for (direction in LayoutDirection.entries) {
            for ((anchor, expectedX) in listOf(IntRect(60, 40, 106, 86) to 0, IntRect(550, 800, 596, 846) to 278)) {
                val position = provider.calculatePosition(anchor, window, direction, popup)
                assertEquals(expectedX, position.x)
                assertEquals(expectedX > 0, opensFromRight)
                assertTrue(position.x >= 0 && position.y >= 0)
                assertTrue(position.x + popup.width <= window.width)
                assertTrue(position.y + popup.height <= window.height)
            }
        }
    }

    private fun session(id: String, activity: Double) = SessionSummary(id, id, activity, false, false)
    private fun workspace(id: String, sessions: List<String>) = GatewayWorkspace(id, "/$id", id, sessions, "", "")
}
