package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText

/** 定时任务沿用 iOS 的系统背景、分组表单和字号；不改变其他模块的主题。 */
@Composable
internal fun ScheduleTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val primary = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        background = if (dark) Color.Black else Color.White,
        surface = if (dark) Color.Black else Color.White,
        surfaceVariant = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
        onSurface = if (dark) Color.White else Color.Black,
        onBackground = if (dark) Color.White else Color.Black,
        onSurfaceVariant = Color(0xFF8E8E93),
        primary = primary,
        error = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30),
        outlineVariant = if (dark) Color(0xFF38383A) else Color(0xFFE5E5EA)
    ), content = content)
}

@Composable
internal fun ScheduleSection(spacing: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable
internal fun ScheduleSettingRow(label: String, value: String, enabled: Boolean = true, tag: String = "",
    icon: Int? = R.drawable.ic_question_chevron_down, rowHeight: Dp = 44.dp, divider: Boolean = false, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).testTag(tag)
        .drawBehind { if (divider) drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f) }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(value, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ScheduleValueRow(label: String, value: String, onChange: (String) -> Unit, suffix: String = "", enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        BasicTextField(value, onChange, enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.width(80.dp).scheduleInputRegion().padding(vertical = 8.dp).semantics { contentDescription = label })
        if (suffix.isNotEmpty()) Text(suffix, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ScheduleFilledField(value: String, onChange: (String) -> Unit, hint: String = "", enabled: Boolean = true,
    singleLine: Boolean = false, modifier: Modifier = Modifier) {
    BasicTextField(value, onChange, enabled = enabled, singleLine = singleLine,
        textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.fillMaxWidth().scheduleInputRegion().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(13.dp),
        decorationBox = { field -> Box { if (value.isEmpty()) Text(hint, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); field() } })
}

/** 固定拖动锚点，同时让可见面板始终位于顶部安全区下方。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduledTaskModalSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: @Composable () -> WindowInsets = {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    },
    content: @Composable ColumnScope.() -> Unit
) {
    val topGap = maxOf(adaptiveWindowSize().height * 0.06f,
        WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() + 8.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.adaptiveSheetBounds().fillMaxHeight().padding(top = topGap).then(modifier),
        sheetMaxWidth = adaptiveSheetWidth(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        contentWindowInsets = contentWindowInsets,
        dragHandle = {
            Box(Modifier.padding(top = 6.dp, bottom = 2.dp).size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), androidx.compose.foundation.shape.CircleShape))
        },
        containerColor = MaterialTheme.colorScheme.surface,
        content = content
    )
}

internal data class ScheduleChoice(val value: String, val title: String, val subtitle: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleChoiceSheet(title: String, searchHint: String, selected: String, choices: List<ScheduleChoice>,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.adaptiveSheetBounds(), sheetMaxWidth = adaptiveSheetWidth(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        ScheduleKeyboardDismissHost {
            DshMenuColumnHost {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding()) {
                    DshPickerHeader(title, onDismiss)
                    ScheduleFilledField(query, { query = it }, searchHint, singleLine = true, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 20.dp)) {
                        items(choices.filter { it.value.isEmpty() || query.isEmpty() || it.title.contains(query, true) || it.subtitle.contains(query, true) || it.value.contains(query, true) }, key = { it.value }) { choice ->
                            Row(Modifier.fillMaxWidth().clickable { onSelect(choice.value); onDismiss() }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(18.dp)) {
                                    if (choice.value == selected) Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(choice.title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                                    if (choice.subtitle.isNotEmpty()) Text(choice.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScheduleEmptyState(title: String, description: String, icon: Int, onRetry: (() -> Unit)? = null) {
    Column(Modifier.adaptiveContentWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(description, fontSize = 16.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(appText("重试"), fontSize = 17.sp) }
    }
}
