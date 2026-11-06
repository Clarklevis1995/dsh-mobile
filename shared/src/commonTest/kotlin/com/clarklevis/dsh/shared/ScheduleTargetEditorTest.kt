package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.JsonValue
import com.clarklevis.dsh.shared.protocol.ScheduleTargetDraft
import com.clarklevis.dsh.shared.protocol.ScheduleTargetEditor
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScheduleTargetEditorTest {
    private val reserved = """{"target":{"kind":"new_once","sessionId":"reserved-not-created","create":{"cwd":"/projects/a","agentPreset":"coder"}}}"""
    private val existing = """{"target":{"kind":"session","sessionId":"existing-session"}}"""
    private fun patch(draft: ScheduleTargetDraft, record: String): JsonValue = JsonValue.fromJsonElement(
        Json.parseToJsonElement(requireNotNull(ScheduleTargetEditor.changeJson(draft, record))))

    @Test fun untouchedNewOnceDoesNotReallocateItsReservedSession() {
        assertNull(ScheduleTargetEditor.changeJson(ScheduleTargetEditor.fromRecord(reserved), reserved))
        assertNull(ScheduleTargetEditor.currentSessionId(reserved))
    }
    @Test fun changingNewModeInSameWorkspaceRetainsPresetButOmitsReservedId() {
        val change = patch(ScheduleTargetDraft("new", "/projects/a"), reserved)
        assertEquals("new", change["kind"]?.stringValue)
        assertEquals("coder", change["create"]?.get("agentPreset")?.stringValue)
        assertNull(change["sessionId"])
    }
    @Test fun workspaceChangeDropsPresetFromPreviousWorkspace() {
        val change = patch(ScheduleTargetDraft("new_once", "/projects/中文 space"), reserved)
        assertEquals("/projects/中文 space", change["create"]?.get("cwd")?.stringValue)
        assertNull(change["create"]?.get("agentPreset"))
        assertNull(change["sessionId"])
    }
    @Test fun defaultWorkspaceOmitsCwdInsteadOfUsingTheBrowsedWorkspace() {
        val change = patch(ScheduleTargetDraft("new", ""), reserved)
        assertEquals(emptyMap(), change["create"]?.objectValue)
    }
    @Test fun existingSessionCanSwitchToBothNewModesWithWorkspace() {
        for (kind in listOf("new_once", "new")) {
            val change = patch(ScheduleTargetDraft(kind, "/projects/b"), existing)
            assertEquals(kind, change["kind"]?.stringValue)
            assertEquals("/projects/b", change["create"]?.get("cwd")?.stringValue)
            assertNull(change["sessionId"])
        }
    }
    @Test fun returningToOriginalSessionIgnoresHiddenWorkspaceDraft() {
        assertNull(ScheduleTargetEditor.changeJson(ScheduleTargetDraft("session", "/projects/b"), existing))
        assertEquals("existing-session", ScheduleTargetEditor.currentSessionId(existing))
    }
    @Test fun reservedSessionCannotBeSelectedAsAnExistingSession() {
        val draft = ScheduleTargetDraft("session", "/projects/a")
        assertNotNull(ScheduleTargetEditor.validationError(draft, reserved))
        assertNull(ScheduleTargetEditor.changeJson(draft, reserved))
    }
    @Test fun unknownTargetIsPreservedUntilTheUserChoosesANewMode() {
        val record = """{"target":{"kind":"future-mode","create":{"cwd":"/missing/path"}}}"""
        val draft = ScheduleTargetEditor.fromRecord(record)
        assertEquals("future-mode", draft.kind)
        assertEquals("/missing/path", draft.cwd)
        assertNull(ScheduleTargetEditor.changeJson(draft, record))
    }
    @Test fun legacySessionRecordsStillSeedTheExistingSessionOption() {
        assertEquals(ScheduleTargetDraft("session", ""), ScheduleTargetEditor.fromRecord("""{"sessionId":"legacy"}"""))
        assertEquals("legacy", ScheduleTargetEditor.currentSessionId("""{"sessionId":"legacy"}"""))
    }
}
