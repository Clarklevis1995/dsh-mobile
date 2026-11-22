package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleTaskPresentationTest {
    @Test fun pausedTasksRemainEditableAndAreNotPresentedAsEnded() {
        assertEquals("schedule.status.paused", ScheduleTaskPresentation.statusLabel("paused").key)
        assertTrue(ScheduleTaskPresentation.canEdit("paused"))
    }
    @Test fun endedAndUnknownTasksNeverExposeEditing() {
        assertFalse(ScheduleTaskPresentation.canEdit("ended"))
        assertFalse(ScheduleTaskPresentation.canEdit("future-status"))
        assertEquals("schedule.status.unknown", ScheduleTaskPresentation.statusLabel("future-status").key)
        assertEquals("schedule.status.active", ScheduleTaskPresentation.statusLabel("active").key)
        assertTrue(ScheduleTaskPresentation.canEdit("active"))
    }

    @Test fun deferredTargetsNeverUseTheCreatingSessionAsTheirDeliveryTarget() {
        val presentation = ScheduleTaskPresentation
        assertNull(presentation.linkedSessionId("new", null, "creating-session"))
        assertNull(presentation.linkedSessionId("new_once", null, "creating-session"))
        assertEquals("reserved", presentation.linkedSessionId("new_once", "reserved", "creating-session"))
        assertEquals("legacy", presentation.linkedSessionId(null, null, "legacy"))
        assertEquals("schedule.session.pending", presentation.unavailableSessionLabel("new_once", false, false).key)
        assertEquals("schedule.session.unavailable", presentation.unavailableSessionLabel("new_once", true, false).key)
        assertEquals("schedule.session.unavailable", presentation.unavailableSessionLabel("new_once", false, true).key)
        assertEquals("schedule.session.unavailable", presentation.unavailableSessionLabel("session", false, false).key)
        assertEquals("schedule.session.perRun", presentation.unavailableSessionLabel("new", false, false).key)
    }
}
