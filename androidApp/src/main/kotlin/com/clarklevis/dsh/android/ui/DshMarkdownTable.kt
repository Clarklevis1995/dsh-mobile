package com.clarklevis.dsh.android.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.MarkdownTableExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableCell

@Composable
internal fun DshMarkdownTableCard(table: DshTableData, textSizeSp: Float, onOpenFile: ((String) -> Unit)?,
    copyMarkdown: String, sourceMarkdown: String, tableIndex: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onReply = LocalMessageQuote.current
    var messageAnchor by remember { mutableStateOf<androidx.compose.ui.unit.IntOffset?>(null) }
    var selection by remember { mutableStateOf<String?>(null) }
    var openingPreview by remember { mutableStateOf(false) }
    val currentOpenFile by rememberUpdatedState(onOpenFile)
    val preview = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(DshMarkdownTablePreviewActivity.EXTRA_OPEN_FILE)?.let { currentOpenFile?.invoke(it) }
    }
    val colors = dshDocumentColors()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.toolbar).testTag("markdown-table-card")) {
        DshTableActions(table, onExpand = {
            if (!openingPreview) scope.launch {
                openingPreview = true
                try {
                    val intent = withContext(Dispatchers.IO) {
                        DshMarkdownTablePreviewActivity.createIntent(context, sourceMarkdown, tableIndex, textSizeSp)
                    }
                    preview.launch(intent)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Toast.makeText(context, appText("加载失败"), Toast.LENGTH_SHORT).show()
                } finally {
                    openingPreview = false
                }
            }
        })
        DshTableGrid(table, textSizeSp, onOpenFile, onMessageMenu = { messageAnchor = it }, roundTopCorners = false)
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
        if (onReply != null) DshMenuItem(text = { Text(appText("回复")) }, onClick = {
            messageAnchor = null
            onReply(markdownPlainText(copyMarkdown))
        })
    }
    selection?.let { source -> DshMarkdownSelectionPage(source) { selection = null } }
}

/** 独立预览 Activity 复用与聊天卡片相同的网格和操作栏。 */
@Composable
internal fun DshMarkdownTablePreview(table: DshTableData, textSizeSp: Float,
    onClose: () -> Unit, onRotate: () -> Unit, onOpenFile: (String) -> Unit) {
    val colors = dshDocumentColors()
    Column(Modifier.fillMaxSize().background(colors.background)
        .documentPageInsets().testTag("markdown-table-fullscreen")) {
        DshTableActions(table, onClose = onClose, onRotate = onRotate)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 20.dp).clipToBounds()
            .verticalScroll(rememberScrollState())) {
            DshTableGrid(table, textSizeSp, onOpenFile)
        }
    }
}

@Composable
private fun DshTableActions(table: DshTableData, modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onRotate: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var pendingCsvPath by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val file = pendingCsvPath?.let { java.io.File(it) }
        pendingCsvPath = null
        if (file != null) scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (uri != null) requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { output ->
                        file.inputStream().use { it.copyTo(output) }
                    }
                }
            }
            withContext(Dispatchers.IO) { file.delete() }
            if (uri != null) Toast.makeText(context, appText(if (result.isSuccess) "已保存" else "保存失败"), Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    val colors = dshDocumentColors()
    val buttons: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (copied && onClose == null) Text(appText("已复制"), fontSize = 12.sp, color = colors.icon)
            if (onRotate != null) IconButton(onClick = onRotate, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_table_rotate), appText("旋转屏幕"), Modifier.size(20.dp), tint = colors.icon)
            }
            IconButton(onClick = { context.copyMarkdownText(MarkdownTableExport.plainText(table.plainRows)); copied = true },
                modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_table_copy), appText("复制表格"), Modifier.size(20.dp), tint = colors.icon)
            }
            IconButton(onClick = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            java.io.File.createTempFile("markdown-table-", ".csv", context.cacheDir).apply {
                                writeText(MarkdownTableExport.csv(table.plainRows), Charsets.UTF_8)
                            }
                        }
                    }.onSuccess { file ->
                        pendingCsvPath = file.absolutePath
                        exporter.launch("table.csv")
                    }.onFailure {
                        Toast.makeText(context, appText("保存失败"), Toast.LENGTH_SHORT).show()
                    }
                }
            }, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_table_download), appText("下载 CSV"), Modifier.size(20.dp), tint = colors.icon)
            }
            if (onExpand != null) IconButton(onClick = onExpand, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_table_expand), appText("全屏浏览表格"), Modifier.size(20.dp), tint = colors.icon)
            }
        }
    }
    if (onClose != null) {
        DshDocumentPageHeader(appText("表格"), "markdown-table-close", onClose) { buttons() }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(appText("表格"), fontSize = 12.sp, color = colors.icon)
            Spacer(Modifier.weight(1f))
            buttons()
        }
    }
}

private data class TableGridKey(val table: DshTableData, val size: Float, val density: Float, val text: Int, val border: Int, val header: Int, val odd: Int, val background: Int)

/** 在原生测量阶段分配列宽，旋转、分屏和容器大小变化都使用实际视口宽度。 */
private class DshTableScrollView(context: android.content.Context) : HorizontalScrollView(context) {
    private var appliedWidth = -1
    var naturalColumnWidths: List<Int> = emptyList()
        set(value) {
            field = value
            appliedWidth = -1
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val naturalWidth = naturalColumnWidths.sum()
        val availableWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) 0
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val targetWidth = maxOf(naturalWidth, availableWidth)
        val table = getChildAt(0) as? TableLayout
        if (table != null && naturalWidth > 0 && targetWidth != appliedWidth) {
            var naturalEdge = 0L
            var previousEdge = 0
            val widths = naturalColumnWidths.map { width ->
                naturalEdge += width
                val edge = (naturalEdge * targetWidth / naturalWidth).toInt()
                (edge - previousEdge).also { previousEdge = edge }
            }
            repeat(table.childCount) { rowIndex ->
                val row = table.getChildAt(rowIndex) as TableRow
                widths.forEachIndexed { column, width ->
                    val cell = row.getChildAt(column)
                    val params = cell.layoutParams as TableRow.LayoutParams
                    if (params.width != width) {
                        params.width = width
                        cell.layoutParams = params
                    }
                }
            }
            appliedWidth = targetWidth
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** 自然列宽为 96～260dp；少列表格按比例铺满视口，宽表保持自然宽度并横向滚动。 */
@Composable
private fun DshTableGrid(table: DshTableData, sizeSp: Float, onOpenFile: ((String) -> Unit)?,
    onMessageMenu: ((androidx.compose.ui.unit.IntOffset?) -> Unit)? = null, roundTopCorners: Boolean = true) {
    val density = LocalDensity.current
    val colors = dshDocumentColors()
    val bodySize = if (sizeSp <= 14f) 13f else 17f
    val key = TableGridKey(table, with(density) { bodySize.sp.toPx() }, density.density,
        colors.foreground.toArgb(), colors.border.toArgb(), colors.header.toArgb(), colors.alternate.toArgb(), colors.background.toArgb())
    var savedScrollX by rememberSaveable { mutableStateOf(0) }
    val currentOpen by rememberUpdatedState(onOpenFile)
    val currentMenu by rememberUpdatedState(onMessageMenu)
    val outline = RoundedCornerShape(
        topStart = if (roundTopCorners) 12.dp else 0.dp,
        topEnd = if (roundTopCorners) 12.dp else 0.dp,
        bottomStart = 12.dp, bottomEnd = 12.dp
    )
    AndroidView(
        factory = { context -> DshTableScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = false
            setOnScrollChangeListener { _, x, _, _, _ -> savedScrollX = x }
            addView(TableLayout(context), ViewGroup.LayoutParams(-2, -2))
        } },
        update = { scroll ->
            if (scroll.tag != key) {
                scroll.tag = key
                val layout = scroll.getChildAt(0) as TableLayout
                val restoreX = savedScrollX
                layout.removeAllViews()
                scroll.post { scroll.scrollTo(restoreX, 0) }
                val paint = TextPaint().apply { textSize = key.size; typeface = Typeface.DEFAULT }
                val widths = List(table.columnCount) { column ->
                    val natural = table.rows.maxOfOrNull { row ->
                        row.getOrNull(column)?.plainText?.lineSequence()?.maxOfOrNull(paint::measureText) ?: 0f
                    } ?: 0f
                    (natural + 24 * key.density).coerceIn(96 * key.density, 260 * key.density).toInt()
                }
                scroll.naturalColumnWidths = widths
                table.rows.forEachIndexed { rowIndex, cells ->
                    val row = TableRow(scroll.context)
                    repeat(table.columnCount) { column ->
                        val cell = cells.getOrNull(column)
                        row.addView(DshMarkdownTextView(scroll.context).apply {
                            setTextIsSelectable(false)
                            if (onMessageMenu == null) setOnLongClickListener(null)
                            isLongClickable = onMessageMenu != null
                            onMessageMenuRequested = { currentMenu?.invoke(it) }
                            includeFontPadding = false
                            setTextSize(TypedValue.COMPLEX_UNIT_PX, key.size)
                            setTextColor(key.text)
                            if (rowIndex == 0) setTypeface(typeface, Typeface.BOLD)
                            gravity = Gravity.TOP or when (cell?.alignment) {
                                TableCell.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
                                TableCell.Alignment.RIGHT -> Gravity.RIGHT
                                else -> Gravity.LEFT
                            }
                            val pad = (12 * key.density).toInt()
                            setPadding(pad, pad, pad, pad)
                            setLineSpacing(3 * key.density, 1f)
                            setText(cell?.text ?: "")
                            this.onOpenFile = { currentOpen?.invoke(it) }
                            val hasLinks = cell?.text?.let { text ->
                                text.getSpans(0, text.length, android.text.style.ClickableSpan::class.java).isNotEmpty()
                            } == true
                            linksClickable = hasLinks
                            movementMethod = if (hasLinks) LinkMovementMethod.getInstance() else null
                            // LinkMovementMethod 会启用输入焦点并触发父滚动容器自动显露。
                            // 普通阅读单元格只接收长按；真实链接保留点击和键盘导航。
                            isClickable = hasLinks
                            isLongClickable = onMessageMenu != null
                            isFocusableInTouchMode = false
                            isFocusable = hasLinks
                            background = GradientDrawable().apply {
                                setColor(if (rowIndex == 0) key.header else if (rowIndex % 2 == 0) key.odd else key.background)
                                setStroke((.5f * key.density).toInt().coerceAtLeast(1), key.border)
                            }
                        }, TableRow.LayoutParams(widths[column], -1))
                    }
                    layout.addView(row, TableLayout.LayoutParams(-2, -2))
                }
            }
        },
        // 轮廓属于整张表格；弧形描边补齐底部封边，表头和正文之间保持直线。
        modifier = Modifier.fillMaxWidth().clip(outline).border(0.5.dp, colors.border, outline)
            .testTag("markdown-table-grid")
    )
}
