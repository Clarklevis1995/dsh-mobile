package com.clarklevis.dsh.android.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/** 富内容走附件草稿，未消费的文字继续由原生编辑器插入选区。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerPasteTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    maxLines: Int,
    modifier: Modifier,
    textStyle: TextStyle,
    cursorBrush: Brush,
    commandToken: String?,
    onPasteAttachments: (List<Uri>) -> Unit,
    decorationBox: @Composable (@Composable () -> Unit) -> Unit
) {
    val state = rememberTextFieldState(value.text, value.selection)
    val changed by rememberUpdatedState(onValueChange)
    val pasted by rememberUpdatedState(onPasteAttachments)
    LaunchedEffect(value.text, value.selection) {
        if (state.text.toString() != value.text || state.selection != value.selection) {
            state.edit {
                if (toString() != value.text) replace(0, length, value.text)
                selection = value.selection
            }
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { TextFieldValue(state.text.toString(), state.selection) }
            // 初始值来自父级，不是用户编辑；回传它会覆盖刚回填的语音或外部草稿。
            .drop(1).distinctUntilChanged().collect { changed(it) }
    }
    BasicTextField(
        state = state,
        enabled = enabled,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
        modifier = modifier.contentReceiver(ReceiveContentListener { content ->
            val uris = mutableListOf<Uri>()
            val remaining = content.consume { item ->
                val uri = item.uri
                if (uri?.scheme == "content") {
                    uris += uri
                    true
                } else false
            }
            if (uris.isNotEmpty()) pasted(uris.distinct())
            remaining
        }),
        textStyle = textStyle,
        cursorBrush = cursorBrush,
        outputTransformation = {
            if (commandToken != null && (toString() == commandToken || toString().startsWith("$commandToken "))) {
                addStyle(SpanStyle(color = DshColors.Ocean, fontWeight = FontWeight.SemiBold), 0, commandToken.length)
            }
        },
        decorator = { inner -> decorationBox(inner) }
    )
}
