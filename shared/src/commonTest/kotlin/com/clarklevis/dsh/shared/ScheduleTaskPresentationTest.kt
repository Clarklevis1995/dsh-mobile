package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.protocol.ScheduleTaskPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduleTaskPresentationTest {
    @Test fun pausedTasksRemainEditableAndAreNotPresentedAsEnded() {
        assertEquals("已暂停", ScheduleTaskPresentation.statusSource("paused"))
        assertTrue(ScheduleTaskPresentation.canEdit("paused"))
    }
    @Test fun endedAndUnknownTasksNeverExposeEditing() {
        assertFalse(ScheduleTaskPresentation.canEdit("ended"))
        assertFalse(ScheduleTaskPresentation.canEdit("future-status"))
        assertEquals("状态未知", ScheduleTaskPresentation.statusSource("future-status"))
        assertEquals("运行中", ScheduleTaskPresentation.statusSource("active"))
        assertTrue(ScheduleTaskPresentation.canEdit("active"))
    }
}
