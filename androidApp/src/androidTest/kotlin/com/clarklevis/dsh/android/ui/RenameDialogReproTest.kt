package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clarklevis.dsh.android.localization.appText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 复刻 DshProductApp.SessionList（行 713-762）的重命名弹窗结构，
 * 用于在真机/模拟器上复现「长按会话 → 重命名」闪退。
 */
@RunWith(AndroidJUnit4::class)
class RenameDialogReproTest {
    @get:Rule
    val compose = createComposeRule()

    private var renamed: Pair<String, String>? = null

    @Composable
    private fun Harness() {
        DshTheme {
            var menuVisible by remember { mutableStateOf(false) }
            var renaming by remember { mutableStateOf(false) }
            var title by remember { mutableStateOf("旧标题") }
            Box {
                Text("会话行", Modifier.testTag("row").clickable { menuVisible = true })
                SessionContextMenu(
                    expanded = menuVisible,
                    onDismissRequest = { menuVisible = false },
                    onRename = {
                        title = "旧标题"
                        menuVisible = false
                        renaming = true
                    },
                    onArchive = { menuVisible = false }
                )
            }
            if (renaming) {
                AlertDialog(
                    onDismissRequest = { renaming = false },
                    title = { Text(appText("重命名会话")) },
                    text = {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(
                            enabled = title.isNotBlank(),
                            onClick = {
                                renamed = "s1" to title
                                renaming = false
                            }
                        ) { Text(appText("保存")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { renaming = false }) { Text(appText("取消")) }
                    }
                )
            }
        }
    }

    @Test
    fun renameFromContextMenu() {
        compose.setContent { Harness() }
        compose.onNodeWithTag("row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(appText("重命名")).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(appText("重命名会话")).assertExists()
        compose.onNodeWithText("旧标题").performClick()
        // 用户实测：弹窗出现后长按输入框里的会话名字才闪退；逐字删除再输入不崩。
        compose.onNodeWithText("旧标题").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("旧标题").performTextInput("X")
        compose.waitForIdle()
        compose.onNodeWithText(appText("保存")).performClick()
        compose.waitForIdle()
        assertEquals("旧标题X", renamed?.second)
    }
}
