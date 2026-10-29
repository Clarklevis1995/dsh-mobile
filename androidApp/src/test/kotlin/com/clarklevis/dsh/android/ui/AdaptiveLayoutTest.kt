package com.clarklevis.dsh.android.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun workspaceReflowsForTabletSplitScreenFoldedCoverAndLargeText() {
        assertTrue(AdaptiveLayout.workspaceUsesColumns(1280f, 800f))
        assertTrue(AdaptiveLayout.workspaceUsesColumns(1024f, 1366f))
        assertFalse(AdaptiveLayout.workspaceUsesColumns(640f, 800f))
        assertFalse(AdaptiveLayout.workspaceUsesColumns(360f, 780f))
        assertFalse(AdaptiveLayout.workspaceUsesColumns(1000f, 400f))
        assertFalse(AdaptiveLayout.workspaceUsesColumns(1280f, 800f, 1.5f))
        assertEquals(2, AdaptiveLayout.pluginColumnCount(800f))
        assertEquals(1, AdaptiveLayout.pluginColumnCount(600f))
        assertEquals(1, AdaptiveLayout.pluginColumnCount(1040f, 1.5f))
    }

    @Test
    fun bookHingeSelectsContinuousPaneWithoutPlacingControlsUnderHinge() {
        val window = IntSize(1800, 1200)
        assertEquals(IntRect(0, 0, 890, 1200),
            continuousWindowPane(window, IntRect(890, 0, 910, 1200), vertical = true))
        assertEquals(IntRect(810, 0, 1800, 1200),
            continuousWindowPane(window, IntRect(790, 0, 810, 1200), vertical = true))
    }

    @Test
    fun tabletopHingeUsesLargerAreaAndUnfoldingRestoresFullWindow() {
        val window = IntSize(1600, 1800)
        assertEquals(IntRect(0, 0, 1600, 890),
            continuousWindowPane(window, IntRect(0, 890, 1600, 910), vertical = false))
        assertEquals(IntRect(0, 610, 1600, 1800),
            continuousWindowPane(window, IntRect(0, 590, 1600, 610), vertical = false))
        assertEquals(IntRect(0, 0, 1600, 1800), continuousWindowPane(window, null, true))
    }

    @Test
    fun creaseOutsideCurrentMultiWindowBoundsDoesNotShrinkContent() {
        val window = IntSize(800, 1200)
        val full = IntRect(0, 0, 800, 1200)
        assertEquals(full, continuousWindowPane(window, IntRect(900, 0, 920, 1200), true))
        assertEquals(full, continuousWindowPane(window, IntRect(390, -100, 410, -20), true))
        assertEquals(full, continuousWindowPane(window, IntRect(0, 1300, 800, 1320), false))
    }
}
