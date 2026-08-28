package com.clarklevis.dsh.android.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.core.view.WindowCompat
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay

private const val ROUTE_WORKSPACE = "workspace"
private const val ROUTE_CONVERSATION = "conversation"
private const val ROUTE_SETTINGS = "settings"

@Composable
internal fun DshProductApp(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val isWorkspace = backStackEntry?.destination?.route == null ||
        backStackEntry?.destination?.route == ROUTE_WORKSPACE
    val systemDark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !isWorkspace && !systemDark
            isAppearanceLightNavigationBars = !isWorkspace && !systemDark
        }
    }
    NavHost(
        navController = navController,
        startDestination = ROUTE_WORKSPACE,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(260))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(260))
        }
    ) {
        composable(ROUTE_WORKSPACE) {
            WorkspaceScreen(
                stateHolder = stateHolder,
                onOpenSession = { id ->
                    stateHolder.selectSession(id)
                    navController.navigate(ROUTE_CONVERSATION)
                },
                onNewSession = {
                    stateHolder.prepareNewSession()
                    navController.navigate(ROUTE_CONVERSATION)
                },
                onSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(ROUTE_CONVERSATION) {
            ConversationScreen(stateHolder, onPickImage, navController::popBackStack)
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(stateHolder, navController::popBackStack)
        }
    }
    stateHolder.platformError?.let { error ->
        AlertDialog(
            onDismissRequest = stateHolder::clearPlatformError,
            title = { Text("DeepSeek Harness") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.clearPlatformError()
                    stateHolder.connect()
                }) { Text("重新连接") }
            },
            dismissButton = {
                TextButton(onClick = stateHolder::clearPlatformError) { Text("好") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScreen(
    stateHolder: AndroidSharedStateHolder,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showGateway by rememberSaveable { mutableStateOf(false) }
    var showWorkspaces by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            delay(350)
            stateHolder.search(searchQuery)
        }
    }

    val selectedWorkspace = stateHolder.snapshot.workspaces
        .firstOrNull { it.workspaceId == stateHolder.selectedWorkspaceId }
    val sessions = remember(
        stateHolder.snapshot.sessions,
        selectedWorkspace,
        stateHolder.selectedWorkspaceId,
        searchQuery
    ) {
        val scoped = when {
            selectedWorkspace != null -> stateHolder.snapshot.sessions.filter { it.id in selectedWorkspace.sessionIds }
            stateHolder.selectedWorkspaceId == UNGROUPED -> {
                val assigned = stateHolder.snapshot.workspaces.flatMapTo(mutableSetOf()) { it.sessionIds }
                stateHolder.snapshot.sessions.filterNot { it.id in assigned }
            }
            else -> stateHolder.snapshot.sessions
        }
        if (searchQuery.isBlank()) scoped else scoped.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.id.contains(searchQuery, ignoreCase = true) ||
                it.id in stateHolder.snapshot.searchResultSessionIds
        }
    }

    DshLiquidGlassHost(
        modifier = Modifier.fillMaxSize().testTag("workspace-screen"),
        background = { backdropModifier -> HarnessAnimatedBackground(backdropModifier) }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().widthIn(max = 680.dp).padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    WorkspaceHeader(
                        onGateway = { showGateway = true },
                        onSettings = onSettings
                    )
                    Spacer(Modifier.height(90.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            "探索未至之境",
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() }.testTag("workspace-hero-title")
                        )
                        Text("DeepSeek Harness 预览版", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
                    }
                    WorkspaceCard(
                        workspace = selectedWorkspace,
                        ungrouped = stateHolder.selectedWorkspaceId == UNGROUPED,
                        ungroupedCount = stateHolder.snapshot.sessions.count { session ->
                            stateHolder.snapshot.workspaces.none { session.id in it.sessionIds }
                        },
                        state = stateHolder.gatewayState,
                        onClick = { showWorkspaces = true }
                    )
                    GlassActionButton("＋", "新建会话", onNewSession)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近会话", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        ConnectionStatusText(stateHolder.gatewayState)
                    }
                    SessionSearch(searchQuery) { searchQuery = it }
                    if (sessions.isEmpty()) EmptySessions() else SessionList(sessions, onOpenSession)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showGateway) {
        GatewaySheet(stateHolder) { showGateway = false }
    }
    if (showWorkspaces) {
        WorkspacePicker(
            workspaces = stateHolder.snapshot.workspaces,
            selectedId = stateHolder.selectedWorkspaceId,
            onSelect = {
                stateHolder.selectWorkspace(it)
                showWorkspaces = false
            },
            onDismiss = { showWorkspaces = false }
        )
    }
}

@Composable
private fun WorkspaceHeader(
    onGateway: () -> Unit,
    onSettings: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HarnessMark()
        Spacer(Modifier.weight(1f))
        GlassCircleButton(
            iconRes = R.drawable.ic_gateway_auth,
            description = "Mobile Gateway",
            onClick = onGateway
        )
        Spacer(Modifier.width(9.dp))
        GlassCircleButton(R.drawable.ic_settings, "设置", onSettings)
    }
}

@Composable
private fun HarnessMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        WhaleIcon(Modifier.width(27.dp).height(20.dp), Color.White)
        Text("deepseek", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "HARNESS",
            color = Color.White,
            fontSize = 9.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            modifier = Modifier.border(1.dp, Color.White, RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.5.dp)
        )
    }
}

@Composable
internal fun WhaleIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Image(
        painter = painterResource(R.drawable.deepseek_whale),
        contentDescription = "DeepSeek",
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}

@Composable
private fun GlassCircleButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp)
            .dshLiquidGlass(CircleShape, DshGlassStyle.CONTROL)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            colorFilter = ColorFilter.tint(Color.White)
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: GatewayWorkspace?,
    ungrouped: Boolean,
    ungroupedCount: Int,
    state: GatewayRuntimeState,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(15.dp), DshGlassStyle.CARD)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_folder_outline),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(Color(0xFF168BFF))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                if (ungrouped) "未分组" else workspace?.title ?: "DeepseekHarnessProject",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (ungrouped) "$ungroupedCount 个未归属会话" else workspace?.path ?: "通过 Mobile Gateway 连接",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ConnectionDot(state)
        Text("⌄", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
    }
}

@Composable
private fun GlassActionButton(icon: String, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .dshLiquidGlass(RoundedCornerShape(18.dp), DshGlassStyle.ACTION)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Text(icon, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
private fun SessionSearch(query: String, onChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(42.dp)
            .dshLiquidGlass(RoundedCornerShape(13.dp), DshGlassStyle.FIELD)
            .padding(horizontal = 12.dp)
            .testTag("workspace-session-search"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.68f))
        )
        BasicTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {}),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索会话内容", color = Color.White.copy(alpha = 0.58f), fontSize = 16.sp)
                    }
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun EmptySessions() {
    Text(
        "暂无已知会话。连接服务后创建第一个任务。",
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(15.dp), DshGlassStyle.SUBTLE)
            .padding(18.dp)
    )
}

@Composable
private fun SessionList(
    sessions: List<SessionSummary>,
    onOpen: (String) -> Unit
) {
    Column(Modifier.background(Color.Transparent)) {
        sessions.take(12).forEach { session ->
            SessionRow(session) { onOpen(session.id) }
            HorizontalDivider(
                modifier = Modifier.padding(start = 18.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.10f)
            )
        }
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 6.dp)
            .testTag("workspace-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        val dot = if (session.isRunning) DshColors.Success else if (session.hasUnread) DshColors.Ocean else Color.White.copy(alpha = 0.35f)
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = session.id.take(16),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Text(
            if (session.isRunning) "运行中" else relativeTime(session.lastActivityEpochSeconds),
            color = if (session.isRunning) Color(0xFF68A0FF) else Color.White.copy(alpha = 0.48f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ConnectionDot(state: GatewayRuntimeState) {
    val color = when (state.connection) {
        GatewayConnectionState.CONNECTED -> DshColors.Success
        GatewayConnectionState.FAILED -> Color.Red
        GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING,
        GatewayConnectionState.WAITING_FOR_NETWORK -> DshColors.Amber
        else -> Color.Gray
    }
    Box(Modifier.size(8.dp).shadow(5.dp, CircleShape, ambientColor = color.copy(alpha = 0.65f)).background(color, CircleShape))
}

@Composable
private fun ConnectionStatusText(state: GatewayRuntimeState) {
    Text(
        when (state.connection) {
            GatewayConnectionState.CONNECTED -> "已连接"
            GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> "连接中"
            GatewayConnectionState.WAITING_FOR_NETWORK -> "等待网络"
            GatewayConnectionState.FAILED -> "连接失败"
            else -> "未连接"
        },
        color = when (state.connection) {
            GatewayConnectionState.CONNECTED -> DshColors.Success
            GatewayConnectionState.FAILED -> Color.Red.copy(alpha = 0.85f)
            else -> Color.White.copy(alpha = 0.55f)
        },
        fontSize = 12.sp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewaySheet(stateHolder: AndroidSharedStateHolder, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Mobile Gateway", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            ConnectionStatusTextDark(stateHolder.gatewayState)
            OutlinedTextField(
                value = stateHolder.endpoint,
                onValueChange = { stateHolder.endpoint = it },
                label = { Text("ws:// 或 wss:// 地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = stateHolder.pairingPayload,
                onValueChange = { stateHolder.pairingPayload = it },
                label = { Text("配对二维码中的 Base64URL 字符串") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = stateHolder::connect) { Text("连接") }
                OutlinedButton(onClick = stateHolder::pair, enabled = stateHolder.pairingPayload.isNotBlank()) { Text("配对") }
                TextButton(onClick = stateHolder::disconnect) { Text("断开") }
            }
            stateHolder.platformError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ConnectionStatusTextDark(state: GatewayRuntimeState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionDot(state)
        Text("状态：${state.connection.name}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspacePicker(
    workspaces: List<GatewayWorkspace>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("选择工作区", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            WorkspaceChoice("全部会话", "所有 Mobile Gateway 会话", selectedId == null) { onSelect(null) }
            WorkspaceChoice("未分组", "未归属任何工作区的会话", selectedId == UNGROUPED) { onSelect(UNGROUPED) }
            workspaces.forEach { workspace ->
                WorkspaceChoice(workspace.title, workspace.path, selectedId == workspace.workspaceId) {
                    onSelect(workspace.workspaceId)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun WorkspaceChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "●" else "○", color = if (selected) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1)
        }
    }
}

internal fun relativeTime(
    epochSeconds: Double,
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val eventMillis = (epochSeconds * 1_000).toLong().coerceAtMost(nowMillis)
    val elapsed = ((nowMillis - eventMillis) / 1_000).coerceAtLeast(0)
    val dayDifference = localDayIndex(nowMillis, timeZone) - localDayIndex(eventMillis, timeZone)
    return when {
        dayDifference == 1L -> "昨天"
        dayDifference == 2L -> "前天"
        dayDifference > 2L -> "$dayDifference 天前"
        elapsed < 60 -> "刚刚"
        elapsed < 3_600 -> "${elapsed / 60} 分钟前"
        else -> "${elapsed / 3_600} 小时前"
    }
}

private fun localDayIndex(epochMillis: Long, timeZone: TimeZone): Long {
    val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
    val previousYear = calendar.get(Calendar.YEAR).toLong() - 1
    return previousYear * 365 +
        previousYear / 4 -
        previousYear / 100 +
        previousYear / 400 +
        calendar.get(Calendar.DAY_OF_YEAR)
}

private const val UNGROUPED = "__ungrouped__"
private const val WHALE_ICON_PATH = "M22.9168 1.43018C22.6713 1.31018 22.5658 1.53918 22.4223 1.65519C21.9317 2.1697 21.5127 2.42121 20.9657 2.39121C20.1657 2.34621 19.4827 2.59771 18.8787 3.20973C18.7502 2.45521 18.3236 2.0047 17.6746 1.71569C16.5876 .856163 16.5421 .597155 16.4591 .341647C16.3536 .0301382 16.1761 .00363739 15.8326 .270145C15.5306 .822162 15.4136 1.43018 15.4251 2.0462C15.4516 3.43174 16.0366 4.53527 17.1991 5.3203C17.3651 5.4103 17.3651 5.5003 17.3236 5.63181C17.0671 6.43533 16.9351 6.64584 16.7501 6.57033C14.2475 4.63328 13.5 3.75075 12.568 3.05973C10.9524 1.68169 12.028 .923165 12.277 .833162C12.5375 .739159 12.3675 .41615 11.5259 .42015C10.6844 .42365 9.91439 .705658 8.48384 1.21267C5.70226 1.11517 3.88321 1.31768 1.36213 3.64575C.0790928 5.4103-.222916 7.41536.146595 9.50642C.535106 11.7105 1.66014 13.535 3.38869 14.9616C5.18125 16.4406 7.24581 17.1657 9.60138 17.0266C11.0319 16.9441 12.6245 16.7526 14.421 15.2321C16.7456 15.6716 17.3306 15.5851 17.7836 15.4911C18.4931 15.3411 18.4441 14.6841 18.1876 14.5636C16.1081 13.595 16.5646 13.9891 16.1496 13.67C17.2061 12.42 18.8202 10.1979 19.3182 7.17235C19.4182 5.93231 19.4562 5.86831 19.6447 5.84931C22.4833 4.65528 23.0268 3.44624 23.1548 1.9972C23.1738 1.77569 23.1508 1.54668 22.9168 1.43018Z"
