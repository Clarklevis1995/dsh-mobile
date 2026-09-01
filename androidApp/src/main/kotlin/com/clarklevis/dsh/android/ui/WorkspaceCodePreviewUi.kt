package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.R
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
internal fun WorkspaceCodePreviewDialog(
    file: AndroidWorkspaceLocalFile,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    var loadState by remember(file.file) {
        mutableStateOf<CodePreviewLoadState>(CodePreviewLoadState.Loading)
    }
    LaunchedEffect(file.file) {
        loadState = withContext(Dispatchers.IO) {
            if (file.file.length() > MAX_CODE_PREVIEW_BYTES) {
                CodePreviewLoadState.Failed("文件超过 2 MB，请使用系统应用打开")
            } else {
                runCatching {
                    WorkspaceCodePreviewSupport.prepare(
                        source = file.file.readText(Charsets.UTF_8),
                        name = file.name,
                        mediaType = file.mediaType
                    )
                }.fold(
                    onSuccess = CodePreviewLoadState::Ready,
                    onFailure = { CodePreviewLoadState.Failed("读取代码失败：${it.localizedMessage}") }
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                CodePreviewToolbar(
                    fileName = file.name,
                    onDismiss = onDismiss,
                    onOpenExternal = onOpenExternal
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
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
                            Text("正在准备代码预览…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f))
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
                            TextButton(onClick = onOpenExternal) { Text("使用系统应用打开") }
                        }
                    }
                    is CodePreviewLoadState.Ready -> CodePreviewDocument(state.document)
                }
            }
        }
    }
}

@Composable
private fun CodePreviewToolbar(
    fileName: String,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "关闭代码预览",
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            fileName,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onOpenExternal) {
            Icon(
                painter = painterResource(R.drawable.ic_open_external),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text("系统打开", modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun CodePreviewDocument(document: WorkspaceCodeDocument) {
    val dark = isSystemInDarkTheme()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val highlighted = remember(document, dark) { highlightedCode(document, dark) }
    val lineNumbers = remember(document.lineCount) {
        (1..document.lineCount).joinToString("\n")
    }
    val codeBackground = if (dark) Color(0xFF0D1117) else Color(0xFFFAFBFC)
    val gutterBackground = if (dark) Color(0xFF161B22) else Color(0xFFF0F2F5)
    val secondary = if (dark) Color(0xFF7D8590) else Color(0xFF8C959F)

    Column(Modifier.fillMaxSize().background(codeBackground)) {
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
            Text("${document.lineCount} 行 · UTF-8", color = secondary, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(verticalScroll),
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
                    .horizontalScroll(horizontalScroll)
                    .padding(start = 14.dp, end = 28.dp, top = 14.dp, bottom = 24.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                softWrap = false
            )
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
