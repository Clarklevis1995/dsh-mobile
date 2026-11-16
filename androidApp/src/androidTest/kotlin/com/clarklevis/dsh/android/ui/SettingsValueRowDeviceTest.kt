package com.clarklevis.dsh.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsValueRowDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longValueKeepsTitleOnOneLineAtNormalAndLargeFontSizes() {
        val fontScale = mutableFloatStateOf(1f)
        var clicks = 0
        compose.setContent {
            DshTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp).testTag("settings-rows")) {
                        SettingsValueRow("Agent 预设", "标准模式", onClick = {})
                        SettingsValueRow("默认模型", LONG_MODEL, onClick = { clicks++ })
                        SettingsValueRow("权限", "workspace-write", onClick = {})
                        SettingsValueRow("加载中", "", isLoading = true, onClick = {})
                    }
                }
            }
        }
        for (scale in listOf(1f, 2f)) {
            compose.runOnIdle { fontScale.floatValue = scale }
            for (title in listOf("Agent 预设", "默认模型", "权限", "加载中")) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(title, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertEquals("$title 必须保持单行", 1, results.single().lineCount)
                assertFalse("$title 不能被长值截断", results.single().isLineEllipsized(0))
            }
            val titleBounds = compose.onNodeWithText("默认模型", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val valueBounds = compose.onNodeWithText(LONG_MODEL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rowBounds = compose.onNodeWithTag("settings-rows").fetchSemanticsNode().boundsInRoot
            assertTrue("标题与模型值不得重叠", titleBounds.right < valueBounds.left)
            assertTrue("模型值不得超出卡片", valueBounds.right <= rowBounds.right)
            compose.onNodeWithText("默认模型").performClick()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val orientation = context.resources.configuration.orientation
            File(context.getExternalFilesDir(null), "settings-row-$orientation-$scale.png").outputStream().use {
                compose.onNodeWithTag("settings-rows").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        assertEquals(2, clicks)
    }

    private companion object {
        const val LONG_MODEL = "DeepSeek-V4.1-Flash · x0.06 · High"
    }
}
