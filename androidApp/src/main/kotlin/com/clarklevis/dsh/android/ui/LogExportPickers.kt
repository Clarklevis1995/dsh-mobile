// Compose 布局中的数值对应 iOS 设计规格；保留清晰的组合函数边界与事件参数。
@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MagicNumber", "LongParameterList", "TooManyFunctions")

package com.clarklevis.dsh.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogDateTimeSheet(
    field: String,
    value: Long,
    earliest: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var draft by rememberSaveable(field) { mutableLongStateOf(value) }
    val isDate = field.endsWith("date")
    val isStart = field.startsWith("start")
    val title =
        appText(
            if (isStart) {
                if (isDate) "开始日期" else "开始时间"
            } else {
                if (isDate) "结束日期" else "结束时间"
            },
        )
    val now = remember(field) { System.currentTimeMillis() }
    val latest = if (isStart) logHourStart(now) else logHourEnd(now)
    val height = (if (isDate) 560 else 408).coerceAtMost(LocalConfiguration.current.screenHeightDp - 48)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 8.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = logCardColor(),
        dragHandle = {
            Box(
                Modifier
                    .padding(
                        top = 6.dp,
                        bottom = 8.dp,
                    ).width(36.dp)
                    .height(4.dp)
                    .background(LogSecondary.copy(alpha = 0.5f), CircleShape),
            )
        },
    ) {
        DshMenuHost {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(height.dp)
                    .padding(horizontal = 16.dp)
                    .testTag("log-editor"),
            ) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogSheetAction(appText("取消"), true, onDismiss)
                    Text(
                        title,
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = logTextColor(),
                    )
                    LogSheetAction(appText("完成"), draft <= latest) { onConfirm(logHourStart(draft)) }
                }
                Spacer(Modifier.height(24.dp))
                Text(logFullDate(draft), fontSize = 14.sp, color = LogSecondary)
                Spacer(Modifier.height(24.dp))
                if (isDate) {
                    LogCalendar(draft, earliest, latest) { draft = logDateWithHour(it, draft, latest) }
                } else {
                    LogHourWheel(draft, latest) { draft = it }
                }
                Spacer(Modifier.weight(1f))
                Text(appText(if (isDate) "灰色日期不在可导出的日志范围内" else "上下滑动选择整点"), fontSize = 12.sp, color = LogSecondary)
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun LogSheetAction(
    title: String,
    enabled: Boolean,
    action: () -> Unit,
) {
    LogGlassButton(Modifier.width(64.dp).height(44.dp), CircleShape, enabled, title, action) {
        Text(title, fontSize = 17.sp, color = logTextColor().copy(alpha = if (enabled) 1f else 0.3f))
    }
}

private fun logFullDate(value: Long): String {
    val pattern = if (Locale.getDefault().language == "zh") "yyyy年M月d日 EEE" else "EEE, d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(value))
}

@Composable
private fun LogHourWheel(
    value: Long,
    latest: Long,
    select: (Long) -> Unit,
) {
    val hours = remember(value, latest) { logHoursOnDay(value, latest) }
    LogWheel(hours.map(::logTimeText), hours.indexOf(value).coerceAtLeast(0), "log-native-hour-picker") { select(hours[it]) }
}

/** 单列系统风格滚轮：中心吸附，取消时不修改外部范围，分钟始终为 00。 */
@Composable
private fun LogWheel(
    labels: List<String>,
    initial: Int,
    tag: String,
    select: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val scope = rememberCoroutineScope()
    val itemPx = with(LocalDensity.current) { 32.dp.toPx() }
    val view = LocalView.current
    LaunchedEffect(state, view, itemPx, labels.size) {
        if (labels.isEmpty()) return@LaunchedEffect

        fun centeredIndex(): Int =
            (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx)
                .roundToInt()
                .coerceIn(labels.indices)
        var previousIndex = centeredIndex()
        // 只在滚动跨过中心刻度时轻震；首次展示、相同刻度和停止滚动不额外触发。
        // 使用平台触感接口，遵守系统触感开关，不直接强制驱动振动器。
        snapshotFlow { centeredIndex() to state.isScrollInProgress }
            .collect { (index, scrolling) ->
                if (scrolling && index != previousIndex) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                previousIndex = index
            }
    }
    LaunchedEffect(state, labels) {
        snapshotFlow { if (state.isScrollInProgress) null else state.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> if (index != null) select(index.coerceIn(labels.indices)) }
    }
    Box(Modifier.fillMaxWidth().height(160.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(32.dp)
                .background(LogSecondary.copy(alpha = 0.09f), CircleShape),
        )
        LazyColumn(
            Modifier.fillMaxWidth().height(160.dp).testTag("$tag-list"),
            state = state,
            contentPadding = PaddingValues(vertical = 64.dp),
            flingBehavior = rememberSnapFlingBehavior(state),
        ) {
            itemsIndexed(labels) { index, label ->
                val distance = abs(index - state.firstVisibleItemIndex - state.firstVisibleItemScrollOffset / itemPx)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .graphicsLayer {
                            alpha = (1f - distance * 0.38f).coerceIn(0.03f, 1f)
                            scaleY =
                                (1f - distance * 0.13f).coerceAtLeast(0.5f)
                        }.clickable(role = Role.Button) { scope.launch { state.animateScrollToItem(index) } }
                        .semantics { selected = distance < 0.5f },
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 22.sp, color = logTextColor()) }
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun LogCalendar(
    value: Long,
    earliest: Long,
    latest: Long,
    select: (Long) -> Unit,
) {
    val initial =
        remember(value) {
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = value
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
        }
    var month by rememberSaveable { mutableLongStateOf(logDayStart(initial)) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    val calendar = Calendar.getInstance().apply { timeInMillis = month }
    val firstDay = logDayStart(earliest)
    val lastDay = logDayStart(latest)
    val monthEnd =
        Calendar
            .getInstance()
            .apply {
                timeInMillis = month
                add(Calendar.MONTH, 1)
                add(Calendar.DAY_OF_MONTH, -1)
            }.timeInMillis
    Column(Modifier.fillMaxWidth().testTag("log-calendar")) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                SimpleDateFormat(
                    if (Locale.getDefault().language ==
                        "zh"
                    ) {
                        "yyyy年M月  ›"
                    } else {
                        "MMMM yyyy  ›"
                    },
                    Locale.getDefault(),
                ).format(Date(month)),
                Modifier
                    .weight(
                        1f,
                    ).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { choosingMonth = !choosingMonth }
                    .padding(8.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = logTextColor(),
            )
            LogCalendarArrow(false, month > firstDay) {
                month =
                    Calendar
                        .getInstance()
                        .apply {
                            timeInMillis = month
                            add(Calendar.MONTH, -1)
                        }.timeInMillis
            }
            LogCalendarArrow(true, monthEnd < lastDay) {
                month =
                    Calendar
                        .getInstance()
                        .apply {
                            timeInMillis = month
                            add(Calendar.MONTH, 1)
                        }.timeInMillis
            }
        }
        if (choosingMonth) {
            LogMonthSelection(month, firstDay, lastDay) { month = it }
        } else {
            val weekdays = DateFormatSymbols.getInstance().shortWeekdays
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                for (day in Calendar.SUNDAY..Calendar.SATURDAY) {
                    Text(weekdays[day], Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = LogSecondary)
                }
            }
            val offset = calendar.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
            val count = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            repeat((offset + count + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth().height(50.dp)) {
                    repeat(7) { weekday ->
                        val day = week * 7 + weekday - offset + 1
                        Box(Modifier.weight(1f).height(50.dp), contentAlignment = Alignment.Center) {
                            if (day in 1..count) {
                                val date =
                                    Calendar
                                        .getInstance()
                                        .apply {
                                            timeInMillis = month
                                            set(Calendar.DAY_OF_MONTH, day)
                                        }.timeInMillis
                                val enabled = date in firstDay..lastDay
                                val selected = date == logDayStart(value)
                                val today = date == logDayStart(System.currentTimeMillis())
                                Box(
                                    Modifier
                                        .size(42.dp)
                                        .background(
                                            when {
                                                selected -> logTextColor()
                                                today -> LogBlue.copy(alpha = 0.12f)
                                                else -> Color.Transparent
                                            },
                                            CircleShape,
                                        ).clip(CircleShape)
                                        .clickable(enabled, role = Role.Button) { select(date) }
                                        .semantics {
                                            this.selected = selected
                                            contentDescription = logDateText(date)
                                        }.testTag("log-calendar-day-$day"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        day.toString(),
                                        fontSize = 20.sp,
                                        color =
                                            when {
                                                selected -> logCardColor()
                                                !enabled -> LogSecondary.copy(alpha = 0.3f)
                                                today -> LogBlue
                                                else -> logTextColor()
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogCalendarArrow(
    next: Boolean,
    enabled: Boolean,
    action: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = appText(if (next) "下个月" else "上个月") },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_back_chevron),
            null,
            Modifier.size(20.dp).graphicsLayer { rotationZ = if (next) 180f else 0f },
            tint = if (enabled) LogBlue else LogSecondary.copy(alpha = 0.4f),
        )
    }
}

@Composable
private fun LogMonthSelection(
    month: Long,
    earliest: Long,
    latest: Long,
    select: (Long) -> Unit,
) {
    val calendar = Calendar.getInstance().apply { timeInMillis = month }
    val firstYear = Calendar.getInstance().apply { timeInMillis = earliest }.get(Calendar.YEAR)
    val lastYear = Calendar.getInstance().apply { timeInMillis = latest }.get(Calendar.YEAR)
    var year by rememberSaveable { mutableIntStateOf(calendar.get(Calendar.YEAR)) }
    var selectedMonth by rememberSaveable { mutableIntStateOf(calendar.get(Calendar.MONTH)) }
    val monthNames = DateFormatSymbols.getInstance().shortMonths
    val firstMonth = if (year == firstYear) Calendar.getInstance().apply { timeInMillis = earliest }.get(Calendar.MONTH) else 0
    val lastMonth = if (year == lastYear) Calendar.getInstance().apply { timeInMillis = latest }.get(Calendar.MONTH) else 11
    val months = (firstMonth..lastMonth).toList()
    val allowedMonth = selectedMonth.coerceIn(firstMonth, lastMonth)
    Row(Modifier.fillMaxWidth().height(260.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            key(year) {
                LogWheel(months.map { monthNames[it] }, months.indexOf(allowedMonth), "log-month-picker") { selectedMonth = months[it] }
            }
        }
        Box(Modifier.weight(1f)) {
            LogWheel((firstYear..lastYear).map(Int::toString), year - firstYear, "log-year-picker") { year = firstYear + it }
        }
    }
    LaunchedEffect(year, selectedMonth) {
        val selected =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = month
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, allowedMonth)
                }.timeInMillis
        val minimum =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = earliest
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
        val maximum =
            Calendar
                .getInstance()
                .apply {
                    timeInMillis = latest
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
        selectedMonth = allowedMonth
        select(selected.coerceIn(minimum, maximum))
    }
}
