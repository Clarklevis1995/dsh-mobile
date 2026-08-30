package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSelectionTest {
    private val sessions = listOf("s1", "s2", "loose").map { id ->
        SessionSummary(id, id, 1.0, isRunning = false, hasUnread = false)
    }
    private val workspaces = listOf(
        GatewayWorkspace("w1", "/one", "One", listOf("s1"), "now", "now"),
        GatewayWorkspace("w2", "/two", "Two", listOf("s2"), "now", "now")
    )

    @Test
    fun missingSelectionFallsBackToFirstWorkspaceRatherThanAllSessions() {
        assertEquals(
            listOf("s1"),
            workspaceScopedSessions(sessions, workspaces, selectedWorkspaceId = null).map { it.id }
        )
        assertEquals(
            listOf("s1"),
            workspaceScopedSessions(sessions, workspaces, selectedWorkspaceId = "missing").map { it.id }
        )
    }

    @Test
    fun ungroupedSelectionOnlyShowsUnassignedSessions() {
        assertEquals(
            listOf("loose"),
            workspaceScopedSessions(
                sessions,
                workspaces,
                AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
            ).map { it.id }
        )
    }
}
