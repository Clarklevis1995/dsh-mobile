// Compose 布局中的数值对应 iOS 设计规格；保留清晰的组合函数边界与事件参数。
@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber", "LongParameterList", "TooManyFunctions")

package com.clarklevis.dsh.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.LocalAppLog
import com.clarklevis.dsh.android.platform.LogExportSummary
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal val LogBlue = Color(0xFF2E6BE6)
internal val LogSecondary = Color(0xFF8E8E93)

@Composable
internal fun logCardColor(): Color = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White

@Composable
internal fun logTextColor(): Color = if (isSystemInDarkTheme()) Color.White else Color.Black

private enum class LogRange(val title: String, val hours: Int?) {
    HOUR("1小时", 1),
    TODAY("今天", null),
    DAY("24小时", 24),
    CUSTOM("自定义", null)
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun LogExportScreen(onBack: () -> Unit) {
    var end by rememberSaveable { mutableLongStateOf(logHourEnd(System.currentTimeMillis())) }
    var start by rememberSaveable { mutableLongStateOf(end - LOG_HOUR_MILLIS) }
    var range by rememberSaveable { mutableStateOf(LogRange.HOUR) }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<LogExportSummary?>(null) }
    var earliest by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var exportingShare by remember { mutableStateOf(false) }
    val background = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    val valid = start < end && start <= System.currentTimeMillis()

    fun select(choice: LogRange) {
        range = choice
        if (choice != LogRange.CUSTOM) {
            end = logHourEnd(System.currentTimeMillis())
            start = logPresetStart(end, choice.hours)
        }
    }
    LaunchedEffect(start, end, retry) {
        loading = true
        failed = false
        summary = null
        try {
            delay(180)
            val result = withContext(Dispatchers.IO) { LocalAppLog.summary(start, end.coerceAtLeast(start)) }
            summary = result
            earliest = result.earliest
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    CompositionLocalProvider(
        LocalTextStyle provides SettingsTypography.item
    ) {
        DshMenuHost {
            DshLiquidGlassHost(background = { Box(it.fillMaxSize().background(background)) }) {
                LogExportActions(start, end) { busy, export ->
                    val enabled = valid && !loading && !busy && (summary?.fileCount ?: 0) > 0
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            LogNavigationBar(onBack, enabled, busy, exportingShare) { share ->
                                exportingShare = share
                                export(share)
                            }
                        }
                    ) { padding ->
                        Column(
                            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Column(
                                Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                            ) {
                                LogSection(appText("时间范围")) {
                                    Column(
                                        Modifier.fillMaxWidth().background(logCardColor(), RoundedCornerShape(24.dp)).padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        LogRangeControl(range, ::select)
                                        LogDivider()
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LogTimeRow(appText("开始"), "start", start) { editor = it }
                                            LogTimeRow(appText("结束"), "end", end) { editor = it }
                                        }
                                        LogDivider()
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                if (valid) {
                                                    appText("已选择 %1\$s 小时", (end - start) / LOG_HOUR_MILLIS)
                                                } else {
                                                    appText("结束时间须晚于开始时间")
                                                },
                                                Modifier.weight(1f),
                                                style = SettingsTypography.description,
                                                color = if (valid) LogSecondary else Color(0xFFFF3B30)
                                            )
                                            Text(
                                                logTimeZone(start),
                                                style = SettingsTypography.description,
                                                fontFamily = FontFamily.Monospace,
                                                color = LogSecondary
                                            )
                                        }
                                    }
                                }
                                LogSection(appText("导出内容")) {
                                    LogSummary(summary, loading, failed)
                                    if (failed) {
                                        TextButton(onClick = { retry++ }) { Text(appText("重试"), color = LogBlue) }
                                    } else if (!loading && summary?.fileCount == 0) {
                                        TextButton(onClick = { select(LogRange.DAY) }) { Text(appText("选择最近24小时"), color = LogBlue) }
                                    }
                                    Text(
                                        appText(
                                            "日志按小时留存，仅导出所选时段\n" +
                                                "旧日志会自动清理，可选日期以本机留存为准"
                                        ),
                                        modifier = Modifier.padding(top = 4.dp),
                                        style = SettingsTypography.description,
                                        lineHeight = 18.sp,
                                        letterSpacing = 0.sp,
                                        color = LogSecondary
                                    )
                                }
                            }
                        }
                    }
                    editor?.let { field ->
                        LogDateTimeSheet(
                            field,
                            if (field.startsWith("start")) start else end,
                            earliest ?: 0L,
                            onDismiss = { editor = null },
                            onConfirm = {
                                if (field.startsWith("start")) start = it else end = it
                                range = LogRange.CUSTOM
                                editor = null
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogNavigationBar(onBack: () -> Unit, enabled: Boolean, busy: Boolean, sharing: Boolean, export: (Boolean) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            appText("日志导出与分享"),
            Modifier.padding(horizontal = 100.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = SettingsTypography.title,
            color = logTextColor()
        )
        LogIconButton(R.drawable.ic_back_chevron, appText("返回"), true, false, Modifier.align(Alignment.CenterStart), onBack)
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogIconButton(
                R.drawable.ic_log_download,
                appText("导出到文件"),
                enabled,
                busy && !sharing,
                Modifier.testTag("log-save")
            ) { export(false) }
            LogIconButton(
                R.drawable.ic_log_share,
                appText("分享日志"),
                enabled,
                busy && sharing,
                Modifier.testTag("log-share")
            ) { export(true) }
        }
    }
}

@Composable
private fun LogIconButton(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier,
    action: () -> Unit
) {
    LogGlassButton(modifier.size(44.dp), CircleShape, enabled, description, action) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = LogSecondary, strokeWidth = 2.dp)
        } else {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(if (icon == R.drawable.ic_back_chevron) 22.dp else 20.dp),
                tint = logTextColor().copy(alpha = if (enabled) 1f else 0.3f)
            )
        }
    }
}

@Composable
internal fun LogGlassButton(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    enabled: Boolean,
    description: String,
    action: () -> Unit,
    content: @Composable () -> Unit
) {
    DshGlassButton(description, action, modifier, enabled, shape, content)
}

@Composable
private fun LogRangeControl(range: LogRange, select: (LogRange) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(CircleShape)
            .background(if (isSystemInDarkTheme()) Color(0xFF38383A) else Color(0xFFEEEEF0))
            .padding(2.dp)
            .selectableGroup()
            .testTag("log-range-picker")
    ) {
        LogRange.entries.forEach { choice ->
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .background(if (choice == range) logCardColor() else Color.Transparent, CircleShape)
                    .clip(CircleShape)
                    .selectable(choice == range, role = Role.Tab) { select(choice) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    appText(choice.title),
                    style = SettingsTypography.description,
                    fontWeight =
                    if (choice ==
                        range
                    ) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    color = logTextColor()
                )
            }
        }
    }
}

@Composable
private fun LogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = SettingsTypography.section, color = LogSecondary)
        content()
    }
}

@Composable
internal fun LogDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = LogSecondary.copy(alpha = 0.25f))
}

@Composable
private fun LogTimeRow(title: String, field: String, value: Long, edit: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, Modifier.weight(1f), style = SettingsTypography.item, color = logTextColor())
        LogDateTimeButton(logDateText(value), "$field-date") { edit("$field-date") }
        LogDateTimeButton(logTimeText(value), "$field-time") { edit("$field-time") }
    }
}

@Composable
private fun LogDateTimeButton(label: String, tag: String, action: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 8.dp)
            .testTag("log-$tag"),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = SettingsTypography.item, color = LogBlue)
    }
}

@Composable
private fun LogSummary(summary: LogExportSummary?, loading: Boolean, failed: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(logCardColor(), RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(painterResource(R.drawable.ic_log_document), null, Modifier.size(20.dp), tint = LogSecondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(appText("应用运行日志"), style = SettingsTypography.item, color = logTextColor())
            Text(
                when {
                    loading -> appText("正在统计日志…")
                    failed -> appText("无法读取日志，请重试")
                    summary?.fileCount == 0 -> appText("所选时间范围内没有日志")
                    else -> appText("%1\$s 个日志文件", summary?.fileCount ?: 0)
                },
                style = SettingsTypography.description,
                color = LogSecondary
            )
        }
        if (!loading && !failed && (summary?.fileCount ?: 0) > 0) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    android.text.format.Formatter
                        .formatFileSize(context, summary?.bytes ?: 0),
                    style = SettingsTypography.item,
                    fontFamily = FontFamily.Default,
                    letterSpacing = 0.sp,
                    color = logTextColor()
                )
                Text(appText("预计大小"), style = SettingsTypography.description, color = LogSecondary)
            }
        }
    }
}

internal fun logDateText(value: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value))

internal fun logTimeText(value: Long): String = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(value))

private fun logTimeZone(value: Long): String {
    val minutes = TimeZone.getDefault().getOffset(value) / 60_000
    return String.format(
        Locale.ROOT,
        "UTC%s%02d:%02d",
        if (minutes <
            0
        ) {
            "−"
        } else {
            "+"
        },
        kotlin.math.abs(minutes) / 60,
        kotlin.math.abs(minutes) % 60
    )
}
