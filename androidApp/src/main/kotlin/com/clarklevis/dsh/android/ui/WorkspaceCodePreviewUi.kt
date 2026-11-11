package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.shared.facade.WorkspaceCodeDocument
import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_CODE_PREVIEW_BYTES = 2L * 1_024 * 1_024

private sealed interface CodePreviewLoadState {
    data object Loading : CodePreviewLoadState
    data class Ready(val document: WorkspaceCodeDocument) : CodePreviewLoadState
    data class Failed(val message: String) : CodePreviewLoadState
}

@Composable
internal fun WorkspaceCodePreviewSheet(
    file: AndroidWorkspaceLocalFile,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onSave: (() -> Unit)? = null
) {
    WorkspaceFilePreviewSheet(file, onDismiss, onSave) { contentPadding ->
        WorkspaceCodePreviewContent(file, onOpenExternal, contentPadding)
    }
}

@Composable
internal fun WorkspaceCodePreviewContent(
    file: AndroidWorkspaceLocalFile,
    onOpenExternal: () -> Unit,
    contentPadding: PaddingValues
) {
    var loadState by remember(file.file) {
        mutableStateOf<CodePreviewLoadState>(CodePreviewLoadState.Loading)
    }
    LaunchedEffect(file.file) {
        loadState = withContext(Dispatchers.IO) {
            if (file.file.length() > MAX_CODE_PREVIEW_BYTES) {
                CodePreviewLoadState.Failed(appText("文件超过 2 MB，请使用系统应用打开"))
            } else {
                runCatching {
                    WorkspaceCodePreviewSupport.prepare(
                        source = file.file.readText(Charsets.UTF_8),
                        name = file.name,
                        mediaType = file.mediaType
                    )
                }.fold(
                    onSuccess = CodePreviewLoadState::Ready,
                    onFailure = { CodePreviewLoadState.Failed(appText("读取代码失败：%1\$s", it.localizedMessage)) }
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        when (val state = loadState) {
            CodePreviewLoadState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator()
                    Text(appText("正在准备代码预览…"), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f))
                }
            }
            is CodePreviewLoadState.Failed -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f))
                    TextButton(onClick = onOpenExternal) { Text(appText("使用系统应用打开")) }
                }
            }
            is CodePreviewLoadState.Ready -> {
                val images = LocalDshMarkdownImages.current
                val relativeImages = remember(images, file.remotePath) { images?.relativeTo(file.remotePath) }
                androidx.compose.runtime.CompositionLocalProvider(LocalDshMarkdownImages provides relativeImages) {
                    CodePreviewDocument(state.document)
                }
            }
        }
    }
}

@Composable
private fun CodePreviewDocument(document: WorkspaceCodeDocument) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val previewScroll = rememberScrollState()
    var previewsMarkdown by remember(document) { mutableStateOf(false) }
    val markdownPreloader = rememberDshMarkdownPreloader()
    var markdownReady by remember(document, markdownPreloader) { mutableStateOf(false) }
    LaunchedEffect(previewsMarkdown, document, markdownPreloader) {
        if (previewsMarkdown && !markdownReady) {
            markdownPreloader.preload(listOf(document.text))
            markdownReady = true
        }
    }
    val highlighted = remember(document, dark) { highlightedCode(document, dark) }
    val lineNumbers = remember(document.lineCount) {
        (1..document.lineCount).joinToString("\n")
    }
    val codeBackground = if (dark) Color(0xFF0D1117) else Color(0xFFFAFBFC)
    val gutterBackground = if (dark) Color(0xFF161B22) else Color(0xFFF0F2F5)
    val secondary = if (dark) Color(0xFF7D8590) else Color(0xFF8C959F)

    Column(Modifier.fillMaxSize().background(codeBackground).testTag("workspace-code-document")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(gutterBackground)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                document.languageDisplayName,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(appText("%1\$s 行 · UTF-8", document.lineCount), color = secondary, fontSize = 12.sp)
        }
        if (document.language == "markdown") {
            Box(Modifier.fillMaxWidth().background(gutterBackground).padding(horizontal = 14.dp)
                .padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                MarkdownPreviewModePicker(previewsMarkdown, dark) { previewsMarkdown = it }
            }
        }
        if (previewsMarkdown) {
            if (markdownReady) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(previewScroll).testTag("workspace-markdown-preview").padding(18.dp)) {
                    DshMarkdownText(document.text)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .testTag("workspace-code-content"),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = lineNumbers,
                    modifier = Modifier
                        .widthIn(min = 52.dp)
                        .background(gutterBackground)
                        .padding(start = 8.dp, end = 12.dp, top = 14.dp, bottom = 24.dp),
                    color = secondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.End
                )
                Text(
                    text = highlighted,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScroll)
                        .testTag("workspace-code-horizontal")
                        .padding(start = 14.dp, end = 28.dp, top = 14.dp, bottom = 24.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun MarkdownPreviewModePicker(previewsMarkdown: Boolean, dark: Boolean, onSelect: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(50)
    val track = if (dark) Color(0xFF303035) else Color(0xFFE3E3E8)
    val selectedBackground = if (dark) Color(0xFF636366) else Color.White
    Row(
        Modifier.widthIn(max = 320.dp).fillMaxWidth().height(32.dp)
            .clip(shape).background(track).padding(2.dp).selectableGroup()
    ) {
        listOf(false, true).forEach { preview ->
            val selected = previewsMarkdown == preview
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(shape)
                    .background(if (selected) selectedBackground else Color.Transparent)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(preview) })
                    .testTag(if (preview) "workspace-markdown-preview-tab" else "workspace-markdown-source-tab"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    appText(if (preview) "预览" else "源码"),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

private fun highlightedCode(document: WorkspaceCodeDocument, dark: Boolean): AnnotatedString =
    buildAnnotatedString {
        append(document.text)
        if (document.text.isNotEmpty()) {
            addStyle(
                SpanStyle(color = if (dark) Color(0xFFE6EDF3) else Color(0xFF1F2328)),
                0,
                document.text.length
            )
        }
        document.tokens.forEach { token ->
            if (token.start >= 0 && token.endExclusive <= document.text.length) {
                addStyle(SpanStyle(color = codeTokenColor(token.kind, dark)), token.start, token.endExclusive)
            }
        }
    }

private fun codeTokenColor(kind: WorkspaceCodeTokenKind, dark: Boolean): Color = when (kind) {
    WorkspaceCodeTokenKind.COMMENT -> if (dark) Color(0xFF8B949E) else Color(0xFF6E7781)
    WorkspaceCodeTokenKind.STRING -> if (dark) Color(0xFFA5D6FF) else Color(0xFF0A7A3D)
    WorkspaceCodeTokenKind.KEYWORD -> if (dark) Color(0xFFFF7B72) else Color(0xFF8250DF)
    WorkspaceCodeTokenKind.NUMBER -> if (dark) Color(0xFF79C0FF) else Color(0xFF0550AE)
    WorkspaceCodeTokenKind.TYPE -> if (dark) Color(0xFFD2A8FF) else Color(0xFF953800)
    WorkspaceCodeTokenKind.TAG -> if (dark) Color(0xFF7EE787) else Color(0xFFCF222E)
    WorkspaceCodeTokenKind.ATTRIBUTE -> if (dark) Color(0xFFFFA657) else Color(0xFF953800)
}
