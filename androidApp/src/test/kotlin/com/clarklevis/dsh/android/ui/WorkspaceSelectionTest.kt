package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSelectionTest {
    @Test
    fun subagentsNeverAppearInWorkspaceUngroupedOrSearchButForksRemainVisible() {
        val rows = listOf(
            SessionSummary("lead", "lead", 1.0, false, false),
            SessionSummary("child", "needle child", 2.0, false, false, parentSessionId = "lead", origin = "subagent"),
            SessionSummary("legacy-child", "needle legacy", 2.0, false, false),
            SessionSummary("fork", "needle fork", 3.0, false, false, parentSessionId = "lead", origin = "fork")
        )
        val groups = listOf(GatewayWorkspace("w", "/repo", "repo", rows.map { it.id }, "now", "now"))
        val knownChildren = setOf("legacy-child")
        val grouped = workspaceScopedSessions(rows, groups, "w", knownChildren)
        val ungrouped = workspaceScopedSessions(rows, emptyList(), AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID, knownChildren)
        assertEquals(listOf("lead", "fork"), grouped.map { it.id })
        assertEquals(grouped, ungrouped)
        assertEquals(listOf("fork"), filterSessionsByQuery(ungrouped, "needle", setOf("child", "legacy-child", "fork")).map { it.id })
    }

    @Test
    fun allWorkspacesScopeListsEveryVisibleSessionRegardlessOfMembership() {
        val rows = listOf(
            SessionSummary("a", "a", 1.0, false, false),
            SessionSummary("b", "b", 2.0, false, false),
            SessionSummary("child", "child", 3.0, false, false, parentSessionId = "a", origin = "subagent"),
            SessionSummary("draft", "draft", 4.0, false, false, hasConversation = false)
        )
        val groups = listOf(GatewayWorkspace("w", "/repo", "repo", listOf("a"), "now", "now"))

        // 全部会话：不按工作区成员关系过滤，但仍隐藏子智能体与无正文会话
        val all = workspaceScopedSessions(rows, groups, AndroidSharedStateHolder.ALL_WORKSPACES_ID)
        assertEquals(listOf("a", "b"), all.map { it.id })

        // 既有行为不变：选中某个工作区时只看成员
        assertEquals(listOf("a"), workspaceScopedSessions(rows, groups, "w").map { it.id })
    }

    @Test
    fun blankSessionsAreHiddenInWorkspaceAndUngroupedHistory() {
        val draftSessions = sessions.map { it.copy(hasConversation = false) }
        assertEquals(emptyList<SessionSummary>(), workspaceScopedSessions(draftSessions, workspaces, "w1"))
        assertEquals(emptyList<SessionSummary>(), workspaceScopedSessions(
            draftSessions, workspaces, AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID
        ))
        val started = draftSessions.map { if (it.id == "s1") it.copy(hasConversation = true) else it }
        assertEquals(listOf("s1"), workspaceScopedSessions(started, workspaces, "w1").map { it.id })
    }

    @Test
    fun offlineBodyMatchesParticipateInRegularListWithoutCrossingWorkspaceScope() {
        val scoped = workspaceScopedSessions(sessions, workspaces, "w1")
        assertEquals(listOf("s1"), filterSessionsByQuery(scoped, "needle", setOf("s1", "s2")).map { it.id })
        assertEquals(emptyList<SessionSummary>(), filterSessionsByQuery(scoped, "needle", setOf("s2")))
        assertEquals(listOf("s1"), filterSessionsByQuery(scoped, " S1 ", emptySet()).map { it.id })
    }

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
