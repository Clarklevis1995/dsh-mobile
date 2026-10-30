package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionSectionsTest {
    @Test
    fun exactlyFiveStayVisibleWhileLargerAndUngroupedSectionsCollapse() {
        val small = sessions("small", 5)
        val large = sessions("large", 6)
        val loose = sessions("loose", 7)
        val entries = allWorkspaceSessionEntries(small + large + loose,
            listOf(workspace("small", small), workspace("large", large)))

        assertEquals(15, entries.filterIsInstance<WorkspaceSessionListEntry.Session>().size)
        assertEquals(18, entries.filterIsInstance<WorkspaceSessionListEntry.Header>().sumOf { it.count })
        assertFalse(entries.filterIsInstance<WorkspaceSessionListEntry.Toggle>().any { it.workspaceId == "small" })
        assertTrue(entries.contains(WorkspaceSessionListEntry.Toggle("large", 1, false)))
        assertTrue(entries.contains(WorkspaceSessionListEntry.Toggle(AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID, 2, false)))
    }

    @Test
    fun expansionIsIndependentAndCollapsingRestoresTheSameRowsAndKeys() {
        val first = sessions("first", 6)
        val second = sessions("second", 7)
        val rows = first + second
        val groups = listOf(workspace("w1", first), workspace("w2", second))
        val collapsed = allWorkspaceSessionEntries(rows, groups)
        val expanded = allWorkspaceSessionEntries(rows, groups, setOf("w1"))

        assertEquals(11, expanded.filterIsInstance<WorkspaceSessionListEntry.Session>().size)
        assertTrue(expanded.contains(WorkspaceSessionListEntry.Toggle("w1", 1, true)))
        assertTrue(expanded.contains(WorkspaceSessionListEntry.Toggle("w2", 2, false)))
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
        assertEquals(collapsed, allWorkspaceSessionEntries(rows, groups, emptySet()))
        assertEquals(collapsed.map { it.key }.toSet(), expanded.map { it.key }.toSet() - "session-first-0")
    }

    @Test
    fun groupsAndRowsUseLatestActivityAndDuplicateMembershipDoesNotDuplicateSessions() {
        val a = SessionSummary("a", "a", 10.0, false, false)
        val b = SessionSummary("b", "b", 30.0, false, false)
        val c = SessionSummary("c", "c", 20.0, false, false)
        val entries = allWorkspaceSessionEntries(listOf(a, c, b),
            listOf(workspace("w1", listOf(a, c)), workspace("w2", listOf(a, b))))

        assertEquals(listOf("w2", "w1"), entries.filterIsInstance<WorkspaceSessionListEntry.Header>().map { it.workspaceId })
        assertEquals(listOf("工作区 w2", "工作区 w1"), entries.filterIsInstance<WorkspaceSessionListEntry.Header>().map { it.title })
        assertEquals(listOf("b", "c", "a"), entries.filterIsInstance<WorkspaceSessionListEntry.Session>().map { it.session.id })
    }

    @Test
    fun tenThousandSessionsCollapseWithoutLosingTotalsAndAllRemainAvailableWhenExpanded() {
        val groups = (0..<100).map { workspace("w$it", sessions("s$it", 100)) }
        val rows = groups.flatMap { group -> sessions("s${group.workspaceId.drop(1)}", 100) }
        val collapsed = allWorkspaceSessionEntries(rows, groups)
        assertEquals(500, collapsed.filterIsInstance<WorkspaceSessionListEntry.Session>().size)
        assertEquals(10_000, collapsed.filterIsInstance<WorkspaceSessionListEntry.Header>().sumOf { it.count })
        val expanded = allWorkspaceSessionEntries(rows, groups, groups.mapTo(mutableSetOf()) { it.workspaceId })
        assertEquals(10_000, expanded.filterIsInstance<WorkspaceSessionListEntry.Session>().size)
        assertEquals(10_200, expanded.size)
        assertEquals(expanded.size, expanded.map { it.key }.toSet().size)
    }

    @Test
    fun emptyInputAndFilteredResultsDoNotCreateEmptySectionsOrUnnecessaryToggles() {
        val rows = sessions("s", 10)
        val groups = listOf(workspace("w", rows))
        assertTrue(allWorkspaceSessionEntries(emptyList(), groups).isEmpty())
        val filtered = allWorkspaceSessionEntries(rows.take(2), groups, setOf("w"))
        assertEquals(2, filtered.filterIsInstance<WorkspaceSessionListEntry.Session>().size)
        assertEquals(2, filtered.filterIsInstance<WorkspaceSessionListEntry.Header>().single().count)
        assertTrue(filtered.filterIsInstance<WorkspaceSessionListEntry.Toggle>().isEmpty())
    }

    private fun sessions(prefix: String, count: Int): List<SessionSummary> =
        (0..<count).map { SessionSummary("$prefix-$it", "$prefix $it", it.toDouble(), false, false) }

    private fun workspace(id: String, sessions: List<SessionSummary>) =
        GatewayWorkspace(id, "/$id", "工作区 $id", sessions.map { it.id }, "", "")
}
