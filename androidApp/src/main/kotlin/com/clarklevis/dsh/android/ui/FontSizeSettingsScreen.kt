package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeSettingsScreen(onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF7F7F8)
    val bubble = if (dark) Color(0xFF1C2D49) else Color(0xFFEDF2FF)
    Scaffold(
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("字体大小"), style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val compactHeight = maxHeight < 500.dp
            Column(
                Modifier.adaptiveContentWidth().fillMaxSize().testTag("font-size-content")
                    .then(if (compactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    Modifier.then(if (compactHeight) Modifier.height(260.dp) else Modifier.weight(1f))
                        .fillMaxWidth().padding(top = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            appText("预览文字大小"),
                            fontSize = 17.sp,
                            modifier = Modifier.testTag("font-size-preview-title")
                                .background(bubble, RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp))
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        )
                    }
                    Text(appText("你可以拖动滑块来调整字体大小。"), fontSize = 17.sp, lineHeight = 28.sp)
                    DshMarkdownText(appText("调整后的字体大小会应用到所有页面，包括对话、按钮和 `代码`。"))
                }
                FontSizeControls(Modifier.padding(horizontal = 12.dp).padding(bottom = 20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FontSizeControls(modifier: Modifier = Modifier) {
    val appearance = LocalAppearanceSettings.current
    val view = LocalView.current
    val haptics = remember(view) { ClickHapticFeedback(view) }
    val density = LocalDensity.current
    // 滑块没有文字，只保留物理密度，避免实时字号变化重置其 pointer input 手势。
    val sliderDensity = remember(density.density) { Density(density.density, fontScale = 1f) }
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val trackColor = foreground.copy(alpha = 0.22f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(appText("跟随系统"), style = SettingsTypography.item, modifier = Modifier.weight(1f))
            DshSwitch(
                checked = appearance.fontFollowsSystem,
                onCheckedChange = appearance::selectFontFollowsSystem,
                modifier = Modifier.semantics { contentDescription = appText("跟随系统字体大小") }
            )
        }
        HorizontalDivider(color = foreground.copy(alpha = 0.1f))
        Column(
            Modifier.padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = foreground)
                Spacer(Modifier.weight(1f))
                Text(
                    if (appearance.fontFollowsSystem) {
                        appText("跟随系统")
                    } else if (appearance.fontScale == 1f) {
                        appText("标准")
                    } else {
                        "${(appearance.fontScale * 100).roundToInt()}%"
                    },
                    style = SettingsTypography.item,
                    color = foreground,
                    modifier = Modifier.testTag("font-size-value")
                )
                Spacer(Modifier.weight(1f))
                Text("A", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = foreground)
            }
            CompositionLocalProvider(LocalDensity provides sliderDensity) {
                Slider(
                    value = appearance.fontScale,
                    onValueChange = { value ->
                        val oldTick = (appearance.fontScale * 20).roundToInt()
                        val newTick = (value * 20).roundToInt()
                        if (oldTick != newTick) haptics.perform()
                        appearance.selectFontScale(value)
                    },
                    valueRange = 0.8f..1.5f,
                    steps = 13,
                    enabled = !appearance.fontFollowsSystem,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = appText("字体大小") },
                    thumb = {
                        Box(
                            Modifier.size(28.dp).shadow(4.dp, CircleShape)
                                .background(MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                            val y = size.height / 2
                            drawLine(trackColor, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                            for (index in 0..14) {
                                val x = size.width * index / 14
                                drawLine(trackColor, Offset(x, y - 4.dp.toPx()), Offset(x, y + 4.dp.toPx()), 2.dp.toPx())
                            }
                        }
                    }
                )
            }
            TextButton(
                onClick = { appearance.selectFontScale(1f) },
                enabled = !appearance.fontFollowsSystem,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("font-size-reset")
            ) {
                Text(appText("恢复默认"), style = SettingsTypography.item, color = foreground)
            }
        }
    }
}
