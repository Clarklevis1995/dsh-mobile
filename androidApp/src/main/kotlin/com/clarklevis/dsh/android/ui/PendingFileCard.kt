package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.facade.SharedUploadDraft
import com.clarklevis.dsh.shared.i18n.UiMessage

/** 与 iOS 待发送文件卡片共用视觉规格，避免受 Material 默认容器色和字号影响。 */
@Composable
internal fun PendingFileCard(
    file: SharedUploadDraft,
    enabled: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val secondary = foreground.copy(alpha = 0.6f)
    val background = if (isSystemInDarkTheme()) Color(0xFF2B3038) else Color(0xFFE9E9ED)
    val textStyle = TextStyle(letterSpacing = 0.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))
    val status = when (file.status) {
        "waiting" -> appText("等待上传")
        "opening" -> appText("开始上传…")
        "uploading" -> "${appText("上传中")} ${if (file.size > 0) file.confirmedBytes * 100 / file.size else 0}%"
        "committing" -> appText("处理中…")
        "ready" -> "${appText("可发送")} · ${android.text.format.Formatter.formatShortFileSize(LocalContext.current, file.size)}"
        else -> appText(file.error ?: UiMessage("upload.failed"))
    }
    Row(
        Modifier.width(240.dp).heightIn(min = 56.dp)
            .background(background, RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(R.drawable.ic_pending_file), null, Modifier.size(16.dp), tint = secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(file.file?.name ?: file.name, color = foreground, fontSize = 13.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Normal, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, color = if (file.status == "error") MaterialTheme.colorScheme.error else secondary,
                fontSize = 12.sp, lineHeight = 15.sp, style = textStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.status == "uploading") {
                LinearProgressIndicator(
                    progress = { if (file.size > 0) file.confirmedBytes.toFloat() / file.size else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (file.status == "error") {
            PendingFileAction(R.drawable.ic_refresh, appText("重试上传"), enabled, secondary, onRetry)
        }
        PendingFileAction(R.drawable.ic_pending_file_remove, appText("移除文件"), enabled, foreground, onRemove)
    }
}

@Composable
private fun PendingFileAction(icon: Int, label: String, enabled: Boolean, tint: Color, action: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = action),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), label, Modifier.size(16.dp), tint = tint.copy(alpha = if (enabled) tint.alpha else 0.38f))
    }
}
