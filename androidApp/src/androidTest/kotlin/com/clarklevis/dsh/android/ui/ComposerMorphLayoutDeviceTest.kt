package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComposerMorphLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editorMovesThroughIntermediatePositionsAndToolsHideWhenCollapsed() {
        var expanded by mutableStateOf(false)
        var editorCompositions = 0
        var toolCompositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DshTheme {
                Column {
                    ComposerMorphLayout(expanded, Modifier.testTag("layout"),
                        leading = { Text("+", Modifier.size(32.dp).testTag("add")) },
                        editor = {
                            SideEffect { editorCompositions++ }
                            Text("草稿", Modifier.height(if (expanded) 96.dp else 38.dp).testTag("editor"))
                        },
                        tools = {
                            SideEffect { toolCompositions++ }
                            Text("模型与权限", Modifier.testTag("tools"))
                        },
                        action = { Text("发送", Modifier.testTag("send")) })
                }
            }
        }
        val editor = compose.onNodeWithTag("editor")
        val originalID = editor.fetchSemanticsNode().id
        val compact = editor.fetchSemanticsNode().boundsInRoot
        val compactHeight = compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("send").assertIsNotDisplayed()
        compose.runOnIdle { expanded = true }
        // 先提交焦点状态，后面的时间从展开请求交给动画引擎时开始计。
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        val starting = editor.fetchSemanticsNode().boundsInRoot
        assertTrue("动画开始后应立即移动，不经过慢起步等待", starting.left < compact.left - 1f)
        val editorCount = editorCompositions
        val toolCount = toolCompositions
        compose.mainClock.advanceTimeBy(68)
        val moving = editor.fetchSemanticsNode().boundsInRoot
        val movingHeight = compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(300)
        val full = editor.fetchSemanticsNode().boundsInRoot
        val fullHeight = compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.height
        assertTrue(full.left < moving.left && moving.left < compact.left)
        assertTrue(compactHeight < movingHeight && movingHeight < fullHeight)
        assertEquals("中间帧不能重新组合输入节点", editorCount, editorCompositions)
        assertEquals("中间帧不能重新组合菜单", toolCount, toolCompositions)
        assertEquals(originalID, editor.fetchSemanticsNode().id)
        compose.onNodeWithTag("tools").assertIsDisplayed()
        compose.onNodeWithTag("send").assertIsDisplayed()
        val centerY = compose.onNodeWithTag("add").fetchSemanticsNode().boundsInRoot.center.y
        assertEquals("工具行与加号中轴对齐", centerY,
            compose.onNodeWithTag("tools").fetchSemanticsNode().boundsInRoot.center.y, 1f)
        assertEquals("发送与工具行中轴对齐", centerY,
            compose.onNodeWithTag("send").fetchSemanticsNode().boundsInRoot.center.y, 1f)
        val plus = compose.onNodeWithTag("add")
        val plusID = plus.fetchSemanticsNode().id
        val plusSize = plus.fetchSemanticsNode().boundsInRoot.size
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeByFrame()
        var previousHeight = fullHeight
        repeat(5) {
            compose.mainClock.advanceTimeBy(40)
            val height = compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.height
            val bounds = plus.fetchSemanticsNode().boundsInRoot
            plus.assertIsDisplayed()
            assertEquals(plusID, plus.fetchSemanticsNode().id)
            assertEquals(plusSize.height, bounds.height, 1f)
            assertTrue("加号不能移出正在收起的外框", bounds.bottom <=
                compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.bottom + 1f)
            assertTrue(height <= previousHeight)
            if (it == 0) assertTrue("长草稿收起不能在首帧跳到紧凑高度", height > compactHeight + 1f)
            previousHeight = height
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("send").assertIsNotDisplayed()
        compose.onNodeWithTag("tools").assertIsNotDisplayed()
        compose.onNodeWithTag("add").assertIsDisplayed()
        assertEquals(compactHeight, compose.onNodeWithTag("layout").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(originalID, editor.fetchSemanticsNode().id)
    }
}
