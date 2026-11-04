package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 文件目录和预览共用的圆形操作按钮，图标颜色跟随当前主题。 */
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun WorkspaceSheetIconButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DshGlassButton(description, onClick, modifier.size(52.dp)) {
        Icon(painterResource(icon), null, Modifier.size(22.dp), tint = dshGlassForeground())
    }
}

/** 等待退出动效结束再移除弹层，连续点击只触发一次关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberWorkspaceSheetDismiss(
    sheetState: SheetState,
    onDismiss: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var closing by remember(sheetState) { mutableStateOf(false) }
    return {
        if (!closing) {
            closing = true
            scope.launch {
                try {
                    sheetState.hide()
                    if (!sheetState.isVisible) currentOnDismiss()
                } finally {
                    closing = false
                }
            }
        }
    }
}
