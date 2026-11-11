package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedNewConversationWorkspace
import kotlin.test.*

class NewConversationWorkspaceTest {
    @Test fun selectionOnlyCommitsAfterCreationAndLocksOnFirstInstruction() {
        val draft = SharedNewConversationWorkspace()
        draft.open("empty-a", "workspace-a")
        val token = draft.begin("workspace-b")
        assertTrue(draft.snapshot().changing)
        assertEquals("workspace-a", draft.snapshot().workspaceId)
        assertEquals(0L, draft.begin(null))
        assertTrue(draft.complete(token, "empty-b"))
        assertEquals("workspace-b", draft.snapshot().workspaceId)
        assertEquals("empty-a", draft.snapshot().previousSessionId)
        draft.lock("empty-b")
        assertFalse(draft.snapshot().visible)
        assertEquals(0L, draft.begin("workspace-c"))
    }
    @Test fun ungroupedFailureAndStaleCompletionCannotChangeCurrentDraft() {
        val draft = SharedNewConversationWorkspace()
        draft.open("first", null)
        assertNull(draft.snapshot().workspaceId)
        val failed = draft.begin("workspace-a")
        draft.fail(failed)
        assertNull(draft.snapshot().workspaceId)
        assertFalse(draft.snapshot().changing)
        val stale = draft.begin("workspace-b")
        draft.open("other", "workspace-c")
        assertFalse(draft.complete(stale, "late"))
        assertEquals("other", draft.snapshot().sessionId)
        val move = draft.begin(null)
        assertTrue(draft.complete(move, "ungrouped"))
        assertNull(draft.snapshot().workspaceId)
    }
}
