package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.*
import org.junit.Test

class ShareDestinationsTest {
    private fun session(id: String, time: Double, running: Boolean = true) =
        SessionSummary(id, "任务 $id", time, running, false)
    private fun workspace(id: String, vararg sessions: String) =
        GatewayWorkspace(id, "/$id", id, sessions.toList(), "", "")

    @Test fun recentUsesThreeNewestRunningParentConversationsAcrossWorkspaces() {
        val rows = listOf(session("old", 1.0), session("two", 5.0), session("one", 6.0),
            session("three", 4.0), session("idle", 9.0, false),
            session("child", 10.0), session("hidden", 11.0).copy(hasConversation = false),
            session("agent", 12.0).copy(origin = "subagent"))
        val data = ShareDestinations(rows, listOf(workspace("A", "one"), workspace("B", "two")), setOf("child"))
        assertEquals(listOf("one", "two", "three"), data.recent.map { it.id })
        assertEquals("A", data.owner("one"))
        assertEquals("B", data.owner("two"))
        assertEquals(SHARE_UNGROUPED, data.owner("three"))
    }

    @Test fun searchStaysInsideWorkspaceAndDuplicatesHaveOneOwner() {
        val data = ShareDestinations(listOf(session("1", 1.0), session("2", 2.0), session("3", 3.0)),
            listOf(workspace("A", "1"), workspace("B", "1", "2")))
        assertEquals(listOf("1"), data.rows("A", " 任务 ").map { it.id })
        assertTrue(data.rows("A", "2").isEmpty())
        assertEquals(listOf("2"), data.rows("B").map { it.id })
        assertEquals(listOf("3"), data.rows(SHARE_UNGROUPED).map { it.id })
        assertEquals("B", data.initialWorkspace("B"))
        assertEquals("A", data.initialWorkspace("removed"))
        assertEquals(SHARE_UNGROUPED, data.initialWorkspace(SHARE_UNGROUPED))
        assertEquals(SHARE_UNGROUPED, ShareDestinations(emptyList(), emptyList()).initialWorkspace(null))
    }
}
