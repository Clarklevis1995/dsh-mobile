package com.clarklevis.dsh.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUiParityDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun workspaceChromeMatchesTheIosSourceAndExposesAccessibilitySemantics() {
        compose.onNode(hasTestTag("workspace-screen")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-hero-title") and hasText("探索未至之境")).assertIsDisplayed()
        compose.onNode(hasText("DeepSeek Harness 预览版")).assertIsDisplayed()
        compose.onNode(hasText("新建会话")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-session-search")).assertIsDisplayed()
        compose.onNode(hasTestTag("workspace-card")).performClick()
        compose.onNode(hasTestTag("workspace-menu")).assertIsDisplayed()
        compose.onNode(hasText("添加工作区")).assertIsDisplayed()
        compose.onNode(hasText("全部会话")).assertDoesNotExist()
        compose.onNode(hasTestTag("workspace-ungrouped")).performClick()
        compose.onNode(hasText("归档")).assertDoesNotExist()
        compose.onNode(hasContentDescription("设备认证", substring = true)).assertIsDisplayed()
        compose.onNode(hasContentDescription("设置")).assertIsDisplayed().performClick()
        compose.onNode(hasText("新会话默认配置", substring = true)).assertIsDisplayed()
    }

    @Test
    fun offlineNewSessionOpensComposerWithoutShowingAnInternalSubscribeError() {
        compose.onNode(hasText("新建会话")).performClick()
        compose.onNode(hasTestTag("composer-input")).assertIsDisplayed()
        compose.onNode(hasText("unsubscribe: not-connected")).assertDoesNotExist()
    }
}
