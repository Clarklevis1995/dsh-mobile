package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用统一的确认弹窗，视觉与设置页保持一致。
 *
 * [content] 用于承载输入框等额外内容；按钮为空时不会渲染按钮区域。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DshAlertDialog(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    dismissLabel: String? = null,
    onDismissClick: (() -> Unit)? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    confirmDestructive: Boolean = false,
    stackedButtons: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        DshMenuHost {
            Surface(
                modifier = modifier.adaptiveContentWidth(340.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = if (confirmDestructive) 0.dp else 8.dp,
                shadowElevation = 18.dp
            ) {
                Column(
                    modifier = Modifier.heightIn(max = (adaptiveWindowSize().height - 48.dp).coerceAtLeast(48.dp))
                        .verticalScroll(rememberScrollState()).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    message?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                    content?.invoke(this)
                    if (dismissLabel != null || confirmLabel != null) {
                        if (stackedButtons) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                confirmLabel?.let { label ->
                                    DshAlertDialogButton(
                                        label = label,
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = confirmEnabled,
                                        destructive = confirmDestructive,
                                        onClick = onConfirm ?: onDismissRequest
                                    )
                                }
                                dismissLabel?.let { label ->
                                    DshAlertDialogButton(
                                        label = label,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = onDismissClick ?: onDismissRequest
                                    )
                                }
                            }
                        } else Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            dismissLabel?.let { label ->
                                DshAlertDialogButton(
                                    label = label,
                                    modifier = if (confirmLabel == null) Modifier.fillMaxWidth() else Modifier.weight(1f),
                                    onClick = onDismissClick ?: onDismissRequest
                                )
                            }
                            confirmLabel?.let { label ->
                                DshAlertDialogButton(
                                    label = label,
                                    modifier = if (dismissLabel == null) Modifier.fillMaxWidth() else Modifier.weight(1f),
                                    enabled = confirmEnabled,
                                    destructive = confirmDestructive,
                                    onClick = onConfirm ?: onDismissRequest
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DshAlertDialogButton(
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val buttonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.08f else 0.04f)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(buttonColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                .copy(alpha = if (enabled) 1f else 0.38f),
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
