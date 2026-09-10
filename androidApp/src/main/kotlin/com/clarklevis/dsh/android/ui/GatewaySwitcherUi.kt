package com.clarklevis.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import com.clarklevis.dsh.android.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.clarklevis.dsh.android.AndroidMultiGatewayStore
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.shared.gateway.GatewayProfile
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewaySwitcherBar() {
    val application = LocalContext.current.applicationContext as? DshAndroidApplication ?: return
    val hosts = application.hosts
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<GatewayProfile?>(null) }
    Row(
        Modifier
            .background(Color.White.copy(alpha = 0.09f), RoundedCornerShape(30.dp))
            .clickable { expanded = true }
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HostIcon(hosts.activeProfile?.server == true, Color.White)
        Text(hosts.activeProfile?.displayName ?: "选择主机", color = Color.White, fontSize = 12.sp, maxLines = 1)
        Box(Modifier.size(6.dp).background(if (hosts.activeId in hosts.onlineIds) Color.Green else Color.Gray, CircleShape))
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.clarklevis.dsh.android.R.drawable.ic_question_chevron_down),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
            modifier = Modifier.size(14.dp)
        )
    }
    if (expanded) {
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                hosts.startPresence()
                try {
                    awaitCancellation()
                } finally {
                    hosts.stopPresence()
                }
            }
        }
        HostPanelTheme {
            ModalBottomSheet(
                onDismissRequest = {
                    expanded = false
                    managing = false
                    selectedIds = emptySet()
                },
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                dragHandle = null,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                tonalElevation = 0.dp
            ) {
                HostPanelSurface {
                    Column(Modifier.fillMaxWidth().heightIn(min = 360.dp).padding(horizontal = 16.dp)) {
                        HostPanelHeader(
                            title = "切换主机",
                            action = gatewayDeleteActionLabel(selectedIds.size),
                            onAction = { confirmingDelete = true },
                            onCancel = {
                                managing = !managing
                                if (!managing) selectedIds = emptySet()
                            },
                            leadingText = if (managing) "完成编辑" else "编辑",
                            actionColor = Color(0xFFFF3B30)
                        )
                        Text(
                            "我的主机",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp)
                        )
                        LazyColumn(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(hostPanelCardColor())
                        ) {
                            items(hosts.profiles, key = { it.localId }) { profile ->
                                GatewayHostRow(
                                    hosts = hosts,
                                    profile = profile,
                                    managing = managing,
                                    selected = profile.localId in selectedIds,
                                    onSelect = {
                                        if (managing) {
                                            selectedIds = toggleSelection(selectedIds, profile.localId)
                                        } else {
                                            hosts.select(profile)
                                            expanded = false
                                        }
                                    },
                                    onEdit = { editing = profile }
                                )
                            }
                            if (hosts.profiles.isEmpty()) {
                                item {
                                    Text(
                                        "暂无已连接的主机",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(20.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
    HostPanelTheme {
        GatewayEditor(hosts, editing) { editing = null }
        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(if (selectedIds.size == 1) "删除主机？" else "批量删除主机？") },
                text = { Text("从 App 移除选中的 ${selectedIds.size} 台主机及连接凭证；网关上的会话和工作区不会被删除。再次连接需重新配对。") },
                confirmButton = {
                    TextButton(onClick = {
                        hosts.remove(hosts.profiles.filter { it.localId in selectedIds })
                        selectedIds = emptySet()
                        confirmingDelete = false
                    }) { Text("删除", color = Color.Red) }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
            )
        }
    }
}

@Composable
private fun GatewayHostRow(
    hosts: AndroidMultiGatewayStore,
    profile: GatewayProfile,
    managing: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = managing,
            enter = expandHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Start
            ) + fadeIn(tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Start
            ) + fadeOut(tween(160))
        ) {
            Box(
                Modifier.size(36.dp).clickable(onClick = onSelect),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(20.dp).border(1.7.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                    }
                }
            }
        }
        HostIcon(profile.server, MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp))
        Column(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(profile.displayName, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (profile.localId ==
                    hosts.activeId
                ) {
                    "当前主机"
                } else {
                    "点击连接"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            Modifier.size(8.dp).background(
                if (profile.localId in
                    hosts.onlineIds
                ) {
                    Color(0xFF32B966)
                } else {
                    Color.Gray
                },
                CircleShape
            )
        )
        AnimatedVisibility(
            visible = managing,
            enter = expandHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                expandFrom = Alignment.End
            ) + fadeIn(tween(160)),
            exit = shrinkHorizontally(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.End
            ) + fadeOut(tween(160))
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(com.clarklevis.dsh.android.R.drawable.ic_pencil_line),
                        contentDescription = "编辑 ${profile.displayName}",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Icon(
                    painter = androidx.compose.ui.res.painterResource(com.clarklevis.dsh.android.R.drawable.ic_drag_handle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp).size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayEditor(hosts: AndroidMultiGatewayStore, profile: GatewayProfile?, onDismiss: () -> Unit) {
    var alias by remember(profile) { mutableStateOf(profile?.alias.orEmpty()) }
    var server by remember(profile) { mutableStateOf(profile?.server == true) }
    var choosingKind by remember(profile) { mutableStateOf(false) }
    val menuVisibility = remember(profile) { MutableTransitionState(false) }
    SideEffect { menuVisibility.targetState = choosingKind }
    val menuTransition = rememberTransition(menuVisibility, label = "Host kind menu")
    val menuAlpha by menuTransition.animateFloat(
        transitionSpec = { tween(if (targetState) 160 else 120) },
        label = "Menu opacity"
    ) { visible -> if (visible) 1f else 0f }
    val menuScale by menuTransition.animateFloat(
        transitionSpec = { tween(if (targetState) 220 else 120, easing = FastOutSlowInEasing) },
        label = "Menu scale"
    ) { visible -> if (visible) 1f else 0.92f }
    val menuOrigin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
        TransformOrigin(1f, 1f)
    } else {
        TransformOrigin(0f, 1f)
    }
    if (profile != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            tonalElevation = 0.dp
        ) {
            HostPanelSurface(showDragHandle = false) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 420.dp)
                        .padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 32.dp)
                ) {
                    HostPanelHeader("编辑主机", "保存", {
                        hosts.edit(profile, alias, server)
                        onDismiss()
                    }, onDismiss)
                    Text(
                        "留空使用网关提供的名称：${profile.gatewayName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
                    )
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .background(hostPanelCardColor()).padding(horizontal = 16.dp)
                    ) {
                        BasicTextField(
                            value = alias,
                            onValueChange = { alias = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .semantics { contentDescription = "本地名称" },
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                                    if (alias.isEmpty()) {
                                        Text(
                                            profile.gatewayName,
                                            fontSize = 17.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("主机类型", fontSize = 17.sp, modifier = Modifier.weight(1f))
                            Box {
                                Row(
                                    Modifier.heightIn(min = 48.dp).clickable { choosingKind = true }
                                        .semantics { contentDescription = "主机类型，${if (server) "服务器" else "电脑"}，展开选择" },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    HostIcon(server, MaterialTheme.colorScheme.onSurface, Modifier.size(20.dp))
                                    Text(
                                        if (server) "服务器" else "电脑",
                                        fontSize = 17.sp,
                                        style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false))
                                    )
                                    Icon(
                                        painterResource(R.drawable.ic_chevrons_up_down),
                                        contentDescription = null,
                                        modifier = Modifier.width(12.dp).height(16.dp)
                                    )
                                }
                                if (menuVisibility.currentState || menuVisibility.targetState) {
                                    val density = LocalDensity.current
                                    val positionProvider = remember(density) {
                                        HostKindMenuPositionProvider(
                                            shadowPadding = with(density) { 32.dp.roundToPx() },
                                            gap = with(density) { 4.dp.roundToPx() }
                                        )
                                    }
                                    Popup(
                                        popupPositionProvider = positionProvider,
                                        onDismissRequest = { choosingKind = false },
                                        properties = PopupProperties(focusable = true)
                                    ) {
                                        // 留白属于 Popup 本身，不经过菜单 Surface 或滚动容器裁剪。
                                        Box(Modifier.padding(32.dp)) {
                                            Surface(
                                                modifier = Modifier.width(256.dp).graphicsLayer {
                                                    alpha = menuAlpha
                                                    scaleX = menuScale
                                                    scaleY = menuScale
                                                    transformOrigin = menuOrigin
                                                },
                                                shape = RoundedCornerShape(32.dp),
                                                color = if (isSystemInDarkTheme()) Color(0xFF343840) else Color(0xFFE1E7F0),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 8.dp,
                                                border = BorderStroke(0.6.dp, Color.White.copy(alpha = 0.35f))
                                            ) {
                                                Column(Modifier.padding(vertical = 8.dp)) {
                                                    listOf(false, true).forEach { isServer ->
                                                        Row(
                                                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                                                .selectable(
                                                                    selected = server == isServer,
                                                                    enabled = choosingKind,
                                                                    role = Role.RadioButton,
                                                                    onClick = {
                                                                        server = isServer
                                                                        choosingKind = false
                                                                    }
                                                                )
                                                                .padding(horizontal = 20.dp, vertical = 8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                                        ) {
                                                            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                                                if (server == isServer) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_host_selected),
                                                                        contentDescription = null,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                }
                                                            }
                                                            HostIcon(isServer, MaterialTheme.colorScheme.onSurface, Modifier.size(20.dp))
                                                            Text(if (isServer) "服务器" else "电脑", fontSize = 17.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
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
private fun HostPanelHeader(
    title: String,
    action: String?,
    onAction: () -> Unit,
    onCancel: (() -> Unit)? = null,
    leadingText: String = "取消",
    actionColor: Color = Color.Unspecified
) {
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (onCancel != null) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), CircleShape)
            ) { Text(leadingText) }
        }
        if (action != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), CircleShape)
            ) { Text(action, color = actionColor) }
        }
    }
}

internal fun gatewayDeleteActionLabel(selectionCount: Int): String? = when (selectionCount) {
    0 -> null
    1 -> "删除"
    else -> "批量删除"
}

internal fun toggleSelection(selectedIds: Set<String>, id: String): Set<String> =
    if (id in selectedIds) selectedIds - id else selectedIds + id

@Composable
private fun HostPanelTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E)
    val muted = if (dark) Color(0xFFAEAEB2) else Color(0xFF6C6C70)
    val background = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val surface = if (dark) Color(0xFF2C2C2E) else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = ink, onPrimary = surface, secondary = ink, onSecondary = surface,
            background = background, onBackground = ink, surface = surface, onSurface = ink,
            surfaceVariant = background, onSurfaceVariant = muted, surfaceTint = Color.Transparent,
            outline = muted, outlineVariant = muted.copy(alpha = 0.25f)
        ),
        content = content
    )
}

@Composable
private fun HostIcon(server: Boolean, color: Color, modifier: Modifier = Modifier.size(20.dp)) {
    androidx.compose.foundation.Image(
        painter =
        androidx.compose.ui.res.painterResource(
            if (server) {
                com.clarklevis.dsh.android.R.drawable.ic_gateway_server
            } else {
                com.clarklevis.dsh.android.R.drawable.ic_gateway_pc
            }
        ),
        contentDescription = if (server) "服务器" else "电脑",
        colorFilter =
        androidx.compose.ui.graphics.ColorFilter
            .tint(color),
        modifier = modifier
    )
}

@Composable
private fun hostPanelCardColor(): Color =
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.09f else 0.06f)

@Composable
private fun HostPanelDragHandle() {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(32.dp).height(4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape)
        )
    }
}

@Composable
private fun HostPanelSurface(showDragHandle: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .dshFrostedSheet(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .navigationBarsPadding()
    ) {
        if (showDragHandle) HostPanelDragHandle()
        content()
    }
}

/** 按可见卡片定位，阴影留白不计入卡片与触发按钮之间的距离。 */
internal class HostKindMenuPositionProvider(
    private val shadowPadding: Int,
    private val gap: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.right - popupContentSize.width + shadowPadding
        } else {
            anchorBounds.left - shadowPadding
        }
        val above = anchorBounds.top - gap - popupContentSize.height + shadowPadding
        val below = anchorBounds.bottom + gap - shadowPadding
        val y = if (above >= 0) above else below
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}
