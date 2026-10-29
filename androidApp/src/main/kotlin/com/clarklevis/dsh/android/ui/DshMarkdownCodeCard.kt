package com.clarklevis.dsh.android.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import java.util.Locale
import kotlinx.coroutines.delay

/** 保留解析器产生的原文和高亮，卡片不重新解析或修改复制内容。 */
internal data class DshCodeBlockMarker(val language: String, val code: String, val highlighted: CharSequence)

/** 与 iOS 一致：标题和复制图标在卡片内，长行横向滚动，缩进不折行。 */
@Composable
internal fun DshMarkdownCodeCard(block: DshCodeBlockMarker, textSizeSp: Float, copyMarkdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val compact = textSizeSp <= 14f
    val padding = if (compact) 10.dp else 12.dp
    val codeSize = with(LocalDensity.current) { (if (compact) 11.sp else 13.sp).toPx() }
    val labelColor = colors.onSurface.copy(alpha = 0.55f)
    val background = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color(0xFFE9E9EB)
    var copied by remember(block.code) { mutableStateOf(false) }
    var messageAnchor by remember { mutableStateOf<IntOffset?>(null) }
    var selection by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Column(
        modifier.fillMaxWidth().testTag("markdown-code-card").clip(RoundedCornerShape(9.dp)).background(background)
            .padding(horizontal = padding).padding(bottom = padding),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DshCodeCardHeader(block.language, copied, labelColor) {
            context.copyMarkdownText(block.code)
            copied = true
        }
        DshCodeCardBody(block, codeSize, colors.onSurface) { messageAnchor = it }
    }
    DshPointMenu(messageAnchor, { messageAnchor = null }) {
        DshMenuItem(text = { Text(appText("复制")) }, onClick = {
            messageAnchor = null
            context.copyMarkdownText(markdownPlainText(copyMarkdown))
        })
        DshMenuItem(text = { Text(appText("选择文字")) }, onClick = {
            messageAnchor = null
            selection = copyMarkdown
        })
    }
    selection?.let { source -> DshMarkdownSelectionPage(source) { selection = null } }
}

@Composable
private fun DshCodeCardHeader(language: String, copied: Boolean, labelColor: Color, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            language.ifEmpty { appText("代码") }.uppercase(Locale.ROOT),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp).testTag("markdown-code-language")
        )
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(44.dp).testTag("markdown-code-copy")
        ) {
            Icon(
                painterResource(if (copied) R.drawable.ic_menu_check else R.drawable.ic_copy_message),
                appText(if (copied) "已复制" else "复制代码"),
                Modifier.size(if (copied) 14.dp else 16.dp),
                tint = if (copied) DshColors.Ocean else labelColor
            )
        }
    }
}

@Composable
private fun DshCodeCardBody(block: DshCodeBlockMarker, codeSize: Float, foreground: Color, onMessageMenu: (IntOffset?) -> Unit) {
    AndroidView(
        factory = { viewContext -> DshCodeScrollView(viewContext) },
        onReset = { scroll ->
            scroll.codeView.resetTextInteraction()
            scroll.codeView.onMessageMenuRequested = null
            scroll.codeView.tag = null
            scroll.scrollTo(0, 0)
        },
        onRelease = { scroll ->
            scroll.codeView.resetTextInteraction()
            scroll.codeView.onMessageMenuRequested = null
        },
        update = { scroll ->
            scroll.codeView.apply {
                onMessageMenuRequested = onMessageMenu
                if (textSize != codeSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, codeSize)
                setTextColor(foreground.toArgb())
                if (tag != block) {
                    tag = block
                    // 只去掉渲染用的最后一个换行，复制始终使用完整原文。
                    val end = block.code.length - if (block.code.endsWith('\n')) 1 else 0
                    text = block.highlighted.subSequence(0, end)
                }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("markdown-code-scroll")
    )
}

internal class DshCodeScrollView(context: Context) : HorizontalScrollView(context) {
    val codeView = DshMarkdownTextView(context).apply {
        setTextIsSelectable(false)
        includeFontPadding = false
        typeface = Typeface.MONOSPACE
        setPadding(0, 0, 0, 0)
        setHorizontallyScrolling(true)
        movementMethod = null
        isClickable = false
        isFocusable = false
    }

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        addView(codeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
