package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
