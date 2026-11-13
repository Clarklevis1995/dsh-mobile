package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.devicetools.AndroidDeviceConnectors
import com.clarklevis.dsh.android.platform.devicetools.ConnectorRegistrationState
import java.text.DateFormat
import java.util.Date

/** 对应 iOS insetGrouped 状态页的逻辑尺寸，不继承 Material 按钮和文字默认样式。 */
@Composable
internal fun ConnectorStatusScreen(connectors: AndroidDeviceConnectors?, onBack: () -> Unit) {
    ConnectorRegistrationPolling(connectors)
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color.Black else Color(0xFFF2F2F7)
    val card = if (dark) Color(0xFF1C1C1E) else Color.White
    val ink = if (dark) Color.White else Color.Black
    val secondary = if (dark) Color(0xFF98989D) else Color(0xFF8A8A8E)
    val note = if (dark) Color(0xFF98989D) else Color(0xFF85858A)
    val blue = if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    val state = connectors?.registrationState ?: ConnectorRegistrationState.DISCONNECTED
    val density = LocalDensity.current
    val systemTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val window = adaptiveWindowSize()
    val topInset = if (window.width < 600.dp && window.height > window.width) systemTop.coerceAtLeast(62.dp) else systemTop
    val itemStyle = SettingsTypography.item
    val rowHeight = (157f / 3).dp
    DshMenuHost {
        Surface(Modifier.fillMaxSize().testTag("connector-status-screen"), color = background, contentColor = ink) {
            Column(Modifier.fillMaxSize().padding(top = topInset)) {
                Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp)) {
                    TopBarCircleButton(
                        R.drawable.ic_back_chevron, appText("返回"), onBack,
                        Modifier.align(Alignment.CenterStart)
                    )
                    Text(appText("连接器状态"), Modifier.align(Alignment.Center), style = SettingsTypography.title)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
                    Spacer(Modifier.height(20.dp))
                    Box(Modifier.padding(start = 20.dp, bottom = 8.dp).height(with(density) { 22.sp.toDp() }),
                        contentAlignment = Alignment.CenterStart) {
                        Text(appText("网关注册"), Modifier.wrapContentHeight(unbounded = true)
                            .testTag("connector-status-section-header"), style = SettingsTypography.title, color = note)
                    }
                    StatusCard(card, ink) {
                        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).testTag("connector-registration-status")
                            .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicatorDot(registrationColor(state), Modifier.size(8.dp))
                            Spacer(Modifier.width(9.dp))
                            Text(registrationTitle(state), Modifier.weight(1f), style = itemStyle)
                            if (connectors?.checkingRegistration == true || state == ConnectorRegistrationState.REGISTERING) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = secondary)
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 37.dp, end = 20.dp), thickness = (1f / 3).dp,
                            color = if (dark) Color(0xFF38383A) else Color(0xFFE5E5EA))
                        if (state == ConnectorRegistrationState.REGISTERED && connectors != null) {
                            Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).testTag("connector-confirmed-count")
                                .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(appText("网关已确认"), Modifier.weight(1f), style = itemStyle)
                                Text(appText("%1\$s 项能力", connectors.registeredToolCount), style = itemStyle, color = secondary)
                            }
                        } else {
                            Text(appText(when (state) {
                                ConnectorRegistrationState.DISCONNECTED -> "连接主机后，将显示网关确认的能力。"
                                ConnectorRegistrationState.UNSUPPORTED -> "请更新并重启主机端网关，然后重新连接。"
                                ConnectorRegistrationState.REGISTERING -> "正在等待网关确认本机能力。"
                                ConnectorRegistrationState.NOT_REGISTERED -> "聊天连接正常，但网关没有当前连接的能力登记。请重新注册。"
                                else -> "网关尚未返回有效的能力清单，请刷新状态或重新注册。"
                            }), Modifier.padding(horizontal = 20.dp, vertical = 15.dp), style = itemStyle.copy(fontSize = 15.sp, lineHeight = 20.sp), color = secondary)
                        }
                    }
                    if (state == ConnectorRegistrationState.REGISTERED && connectors != null) {
                        val at = connectors.registrationConfirmedAt
                        if (at != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.padding(horizontal = 20.dp).testTag("connector-last-confirmed"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(appText("最近确认"), style = itemStyle.copy(fontSize = 13.sp, lineHeight = 18.sp), color = note)
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)),
                                    style = itemStyle.copy(fontSize = 13.sp, lineHeight = 18.sp), color = note)
                            }
                            Spacer(Modifier.height((71f / 3).dp))
                        } else { Spacer(Modifier.height(35.dp)) }
                        var showingCapabilities by rememberSaveable { mutableStateOf(false) }
                        StatusCard(card, ink) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 82.dp).clickable(role = Role.Button) { showingCapabilities = true }
                                .testTag("connector-registered-capabilities").padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp), color = DshColors.Ocean.copy(alpha = .08f)) {
                                    Icon(painterResource(R.drawable.ic_registered_capabilities), null,
                                        Modifier.padding(8.dp).size(24.dp), tint = DshColors.Ocean)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(appText("网关已登记的能力"), style = itemStyle, color = blue)
                                    Text(appText("%1\$s 个连接器", connectors.registeredModules.size),
                                        style = itemStyle.copy(fontSize = 12.sp, lineHeight = 14.sp), color = blue.copy(alpha = .5f))
                                }
                                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = blue.copy(alpha = .25f))
                            }
                        }
                        if (showingCapabilities) ConnectorCapabilitiesSheet(connectors) { showingCapabilities = false }
                    }
                    Spacer(Modifier.height(35.dp))
                    StatusCard(card, ink) {
                        Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).testTag("connector-registration-actions")
                            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            StatusTextButton(appText("刷新状态"), "connector-refresh-registration", blue,
                                connectors?.canCheckRegistration == true && !connectors.checkingRegistration) { connectors?.refreshRegistrationStatus() }
                            StatusTextButton(appText("重新注册"), "connector-retry-registration", blue,
                                connectors?.canRetryRegistration == true) { connectors?.register() }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(background: Color, ink: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = ConnectorStatusCardShape, color = background, contentColor = ink) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun StatusTextButton(title: String, tag: String, blue: Color, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = (157f / 3).dp).testTag(tag), enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = blue, disabledContentColor = Color(0xFFBFBFBF))) {
        Text(title, style = SettingsTypography.item)
    }
}

/** 连续圆角的切线比圆弧更平缓，对应 iOS 27 的分组卡片轮廓。 */
private object ConnectorStatusCardShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = minOf(with(density) { 34.dp.toPx() }, size.width / 2, size.height / 2)
        val control = radius * .25f
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(radius, 0f); lineTo(w - radius, 0f)
            cubicTo(w - control, 0f, w, control, w, radius)
            lineTo(w, h - radius); cubicTo(w, h - control, w - control, h, w - radius, h)
            lineTo(radius, h); cubicTo(control, h, 0f, h - control, 0f, h - radius)
            lineTo(0f, radius); cubicTo(0f, control, control, 0f, radius, 0f); close()
        })
    }
}
