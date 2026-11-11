package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposerAttachmentPasteDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mixedImageFileAndTextPastePreservesSelectionAndConsumesUrisOnce() {
        exercisePaste(shortcut = false)
    }

    @Test fun keyboardShortcutPastesAttachmentAndExternalDraftChangesStillSynchronize() {
        exercisePaste(shortcut = true)
    }

    @Test fun plainTextMimeWithLogFileUriPastesTheFileInsteadOfItsBody() {
        exercisePaste(shortcut = false, textFileOnly = true)
    }

    private fun exercisePaste(shortcut: Boolean, textFileOnly: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val uris = if (textFileOnly) listOf(Uri.parse("content://paste-test/report.log"))
            else listOf(Uri.parse("content://paste-test/image.png"), Uri.parse("content://paste-test/report.log"))
        var value by mutableStateOf(TextFieldValue("ab", TextRange(1)))
        val attachments = mutableListOf<List<Uri>>()
        compose.setContent {
            DshTheme {
                ComposerPasteTextField(value, { value = it }, true, 5,
                    Modifier.testTag("paste-input"), TextStyle(color = Color.Black), SolidColor(Color.Blue), null,
                    { attachments += it }, { it() })
            }
        }
        val input = compose.onNodeWithTag("paste-input")
        input.performClick().performTextInputSelection(TextRange(1))
        compose.runOnIdle {
            val types = if (textFileOnly) arrayOf("text/plain") else arrayOf("image/png", "text/plain")
            val first = if (textFileOnly) ClipData.Item("文件中的全部文字不应插入正文", null, null, uris[0])
                else ClipData.Item(uris[0])
            clipboard.setPrimaryClip(ClipData("attachments", types, first).apply {
                if (!textFileOnly) {
                    addItem(ClipData.Item("文字"))
                    addItem(ClipData.Item("文件中的全部文字不应插入正文", null, null, uris[1]))
                }
            })
        }
        if (shortcut) input.performKeyInput {
            keyDown(Key.CtrlLeft); keyDown(Key.V); keyUp(Key.V); keyUp(Key.CtrlLeft)
        } else input.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.waitUntil(5_000) { attachments.isNotEmpty() && (textFileOnly || value.text.contains("文字")) }
        compose.runOnIdle {
            assertEquals(listOf(uris), attachments)
            assertEquals(if (textFileOnly) "ab" else "a文字b", value.text)
            value = TextFieldValue("新草稿", TextRange(3))
        }
        compose.waitForIdle()
        input.performTextInput("X")
        compose.runOnIdle { assertEquals("新草稿X", value.text) }
    }
}
