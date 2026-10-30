package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow as TextShadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clarklevis.dsh.android.AndroidNotificationSessionRoute
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.domain.SessionSummary
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayRuntimeState
import com.clarklevis.dsh.shared.protocol.GatewayWorkspace
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ROUTE_WORKSPACE = "workspace"
private const val ROUTE_CONVERSATION = "conversation"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SETTINGS_STORAGE = "settings/storage"
private const val ROUTE_SETTINGS_FONT_SIZE = "settings/font-size"
private const val ROUTE_SETTINGS_AGENT_PRESETS = "settings/agent-presets"
private const val ROUTE_SETTINGS_DEFAULT_MODEL = "settings/default-model"
private const val ROUTE_PLUGINS = "plugins"
private const val ROUTE_PLUGIN_BUNDLE = "plugins/bundle/{bundleName}"
private const val ROUTE_PLUGIN_OFFICIAL = "plugins/official/{pluginId}"
private const val ROUTE_SCHEDULED_TASKS = "scheduled-tasks"

@Composable
internal fun DshProductApp(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    notificationRoute: AndroidNotificationSessionRoute?,
    onNotificationRouteOpened: (AndroidNotificationSessionRoute) -> Unit
) {
    val updateModel: com.clarklevis.dsh.android.update.AppUpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    AppUpdateDialogs(updateModel, updateState)
    LaunchedEffect(updateModel) { updateModel.checkOnStartup() }
    val navController = rememberNavController()
    LaunchedEffect(notificationRoute, stateHolder.snapshot.sessions) {
        val route = notificationRoute ?: return@LaunchedEffect
        if (stateHolder.snapshot.sessions.none { it.id == route.sessionId }) return@LaunchedEffect
        stateHolder.selectSession(route.sessionId)
        navController.navigate(ROUTE_CONVERSATION) {
            popUpTo(ROUTE_WORKSPACE)
            launchSingleTop = true
        }
        onNotificationRouteOpened(route)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val isWorkspace = backStackEntry?.destination?.route == null ||
        backStackEntry?.destination?.route == ROUTE_WORKSPACE
    val systemDark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        // 首页系统栏随抽屉阈值变化，由 WorkspaceDrawer 管理，避免网关刷新时覆盖。
        if (isWorkspace) return@SideEffect
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !systemDark
            isAppearanceLightNavigationBars = !systemDark
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
            val workspaceScope = rememberCoroutineScope()
            WorkspaceScreen(
                stateHolder = stateHolder,
                onOpenSession = { id ->
                    // 转场期间首页仍能收到点击，直接读取当前目的地以拦截重复事件。
                    if (navController.currentDestination?.route == ROUTE_WORKSPACE) {
                        stateHolder.selectSession(id)
                        navController.navigate(ROUTE_CONVERSATION) {
                            launchSingleTop = true
                        }
                    }
                },
                onNewSession = {
                    workspaceScope.launch {
                        if (stateHolder.prepareNewSession()) {
                            navController.navigate(ROUTE_CONVERSATION)
                        }
                    }
                },
                onSettings = { navController.navigate(ROUTE_SETTINGS) },
                onPlugins = { navController.navigate(ROUTE_PLUGINS) },
                onScheduledTasks = { navController.navigate(ROUTE_SCHEDULED_TASKS) }
            )
        }
        composable(ROUTE_CONVERSATION) {
            ConversationScreen(
                stateHolder, onPickImage, navController::popBackStack, onPickFile,
                onPlugins = { navController.navigate(ROUTE_PLUGINS) { launchSingleTop = true } },
                onScheduledTasks = { navController.navigate(ROUTE_SCHEDULED_TASKS) { launchSingleTop = true } },
                onSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                updateModel = updateModel,
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                onOpenAgentPresets = { navController.navigate(ROUTE_SETTINGS_AGENT_PRESETS) },
                onOpenDefaultModel = { navController.navigate(ROUTE_SETTINGS_DEFAULT_MODEL) },
                onOpenFontSize = { navController.navigate(ROUTE_SETTINGS_FONT_SIZE) },
                onOpenStorage = { navController.navigate(ROUTE_SETTINGS_STORAGE) }
            )
        }
        composable(ROUTE_SETTINGS_STORAGE) {
            StorageSpaceScreen(stateHolder, navController::popBackStack)
        }
        composable(ROUTE_SETTINGS_FONT_SIZE) {
            FontSizeSettingsScreen(navController::popBackStack)
        }
        composable(ROUTE_SETTINGS_AGENT_PRESETS) {
            AgentPresetSelectionScreen(stateHolder, navController::popBackStack)
        }
        composable(ROUTE_SETTINGS_DEFAULT_MODEL) {
            DefaultModelSelectionScreen(stateHolder, navController::popBackStack)
        }
        composable(ROUTE_PLUGINS) {
            PluginsScreen(
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                onOpenBundle = { navController.navigate("plugins/bundle/${Uri.encode(it)}") },
                onOpenOfficial = { navController.navigate("plugins/official/${Uri.encode(it)}") }
            )
        }
        composable(ROUTE_PLUGIN_BUNDLE) { entry ->
            PluginBundleDetailScreen(
                stateHolder = stateHolder,
                bundleName = entry.arguments?.getString("bundleName").orEmpty(),
                onBack = navController::popBackStack
            )
        }
        composable(ROUTE_PLUGIN_OFFICIAL) { entry ->
            OfficialPluginDetailScreen(
                stateHolder = stateHolder,
                pluginId = entry.arguments?.getString("pluginId").orEmpty(),
                onBack = navController::popBackStack
            )
        }
        composable(ROUTE_SCHEDULED_TASKS) {
            ScheduledTasksScreen(
                stateHolder = stateHolder,
                onBack = navController::popBackStack,
                onOpenSession = { sessionId ->
                    stateHolder.selectSession(sessionId)
                    navController.navigate(ROUTE_CONVERSATION) {
                        popUpTo(ROUTE_WORKSPACE)
                        launchSingleTop = true
                    }
                }
            )
        }
    }
    stateHolder.platformError?.let { error ->
        DshAlertDialog(
            title = "DeepSeek Harness",
            message = appText(error),
            onDismissRequest = stateHolder::clearPlatformError,
            dismissLabel = appText("好"),
            onDismissClick = stateHolder::clearPlatformError,
            confirmLabel = appText("重新连接"),
            onConfirm = {
                stateHolder.clearPlatformError()
                stateHolder.connect()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerDestinationScreen(title: String, message: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(message, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScreen(
    stateHolder: AndroidSharedStateHolder,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit,
    onPlugins: () -> Unit,
    onScheduledTasks: () -> Unit
) {
    val window = adaptiveWindowSize()
    val workspaceListState = rememberLazyListState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showManualPairing by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceMenu by rememberSaveable { mutableStateOf(false) }
    var showDirectoryBrowser by rememberSaveable { mutableStateOf(false) }
    var showWorkspaceRequiredAlert by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(stateHolder.gatewayState.connection) { stateHolder.refreshProductState() }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) delay(350)
        stateHolder.search(searchQuery)
    }

    val workspaces = stateHolder.availableWorkspaces
    val selectedWorkspace = stateHolder.activeWorkspace
    // 单工作区视图使用归属标签；同一索引也用于快速统计未分组会话。
    val sessionFolderLabels = remember(workspaces) {
        buildMap { workspaces.forEach { ws -> ws.sessionIds.forEach { sid -> put(sid, ws.title) } } }
    }
    val ungroupedCount = remember(stateHolder.snapshot.sessions, stateHolder.childSessionIds, sessionFolderLabels) {
        stateHolder.snapshot.sessions.count { session ->
            session.isVisibleInHistory && session.id !in stateHolder.childSessionIds && session.id !in sessionFolderLabels
        }
    }
    val showingAllWorkspaces = stateHolder.selectedWorkspaceId == AndroidSharedStateHolder.ALL_WORKSPACES_ID
    var expandedSectionIds by rememberSaveable(stateHolder.gatewayLocalId) { mutableStateOf(emptyList<String>()) }
    val ungroupedSelected = stateHolder.isUngroupedWorkspaceSelected
    val sessions = remember(
        stateHolder.snapshot.sessions,
        stateHolder.childSessionIds,
        stateHolder.gatewayState.connection,
        stateHolder.snapshot.searchResultSessionIds,
        stateHolder.localChatMatches,
        stateHolder.remoteSearchResultQuery,
        workspaces,
        stateHolder.selectedWorkspaceId,
        searchQuery
    ) {
        val scoped = workspaceScopedSessions(
            sessions = stateHolder.snapshot.sessions,
            workspaces = workspaces,
            selectedWorkspaceId = stateHolder.selectedWorkspaceId,
            childSessionIds = stateHolder.childSessionIds
        )
        if (searchQuery.isBlank()) {
            scoped
        } else {
            filterSessionsByQuery(
                scoped,
                searchQuery,
                stateHolder.localChatMatches.mapTo(mutableSetOf()) { it.sessionId },
                stateHolder.snapshot.searchResultSessionIds.toSet().takeIf {
                    stateHolder.remoteSearchResultQuery == searchQuery.trim()
                }.orEmpty()
            )
        }
    }

    // 分组排序与展开投影在后台执行；滚动只消费稳定 key 的扁平条目。
    val allSessionEntries by produceState(
        initialValue = emptyList<WorkspaceSessionListEntry>(),
        key1 = sessions,
        key2 = workspaces,
        key3 = showingAllWorkspaces to expandedSectionIds
    ) {
        value = if (showingAllWorkspaces) {
            withContext(Dispatchers.Default) {
                allWorkspaceSessionEntries(sessions, workspaces, expandedSectionIds.toSet())
            }
        } else emptyList()
    }

    WorkspaceDrawer(onPlugins = onPlugins, onScheduledTasks = onScheduledTasks) { openDrawer, canScrollVertically ->
        val backgroundAnimationEnabled by remember(canScrollVertically, workspaceListState) {
            derivedStateOf { canScrollVertically && !workspaceListState.isScrollInProgress }
        }
        DshLiquidGlassHost(
            modifier = Modifier.fillMaxSize().testTag("workspace-screen"),
            background = { backdropModifier ->
                HarnessAnimatedBackground(backdropModifier, animationEnabled = backgroundAnimationEnabled)
            }
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("workspace-session-list"),
                contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
                horizontalAlignment = Alignment.CenterHorizontally,
                userScrollEnabled = canScrollVertically,
                state = workspaceListState
            ) {
                item(key = "workspace-header", contentType = "header") {
                    AdaptiveWorkspaceContent(
                        window = window,
                        header = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                WorkspaceHeader(
                                    state = stateHolder.gatewayState,
                                    onScan = {
                                        stateHolder.clearPlatformError()
                                        showQrScanner = true
                                    },
                                    onManualEntry = {
                                        stateHolder.clearPlatformError()
                                        showManualPairing = true
                                    },
                                    onSettings = onSettings,
                                    onOpenDrawer = openDrawer
                                )
                                GatewaySwitcherBar()
                            }
                        },
                        actions = {
                            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    stringResource(R.string.workspace_hero_title),
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.semantics { heading() }.testTag("workspace-hero-title")
                                )
                                Text(appText("Deepseek Harness 预览版"), color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
                            }
                            Box(Modifier.fillMaxWidth()) {
                                WorkspaceCard(
                                    workspace = selectedWorkspace,
                                    ungrouped = ungroupedSelected,
                                    ungroupedCount = ungroupedCount,
                                    allWorkspaces = showingAllWorkspaces,
                                    sessionCount = sessions.size,
                                    state = stateHolder.gatewayState,
                                    onClick = { showWorkspaceMenu = true }
                                )
                                WorkspaceSelectionMenu(
                                    expanded = showWorkspaceMenu,
                                    workspaces = workspaces,
                                    selectedWorkspaceId = stateHolder.selectedWorkspaceId,
                                    onSelect = { workspaceId ->
                                        stateHolder.selectWorkspace(workspaceId)
                                        showWorkspaceMenu = false
                                    },
                                    onAddWorkspace = {
                                        showWorkspaceMenu = false
                                        showDirectoryBrowser = true
                                    },
                                    onDismiss = { showWorkspaceMenu = false }
                                )
                            }
                            GlassActionButton("＋", appText("新建会话")) {
                                if (showingAllWorkspaces) showWorkspaceRequiredAlert = true else onNewSession()
                            }
                        },
                        recentSessions = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (showingAllWorkspaces) appText("全部会话") else appText("最近会话"),
                                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Spacer(Modifier.weight(1f))
                                ConnectionStatusText(stateHolder.gatewayState)
                            }
                            LocalChatSearchContent(stateHolder, searchQuery, { searchQuery = it }, onOpenSession)
                            if (sessions.isEmpty()) EmptySessions()
                            else if (!showingAllWorkspaces) SessionList(sessions, { id ->
                                if (searchQuery.isNotBlank()) stateHolder.localChatMatches.firstOrNull { it.sessionId == id }
                                    ?.let(stateHolder::prepareLocalChatMatch)
                                onOpenSession(id)
                            }, stateHolder::renameSession, stateHolder::archiveSession,
                                folderLabels = sessionFolderLabels)
                            Spacer(Modifier.height(24.dp))
                        }
                    )
                }
                if (showingAllWorkspaces) {
                    items(allSessionEntries, key = { it.key }, contentType = { it::class }) { entry ->
                        AdaptiveWorkspaceSessionContent(window) {
                            when (entry) {
                                is WorkspaceSessionListEntry.Header -> WorkspaceSessionSectionHeader(entry)
                                is WorkspaceSessionListEntry.Toggle -> WorkspaceSessionSectionToggle(entry) { id ->
                                    expandedSectionIds = if (id in expandedSectionIds) expandedSectionIds - id else expandedSectionIds + id
                                }
                                is WorkspaceSessionListEntry.Session -> SessionListItem(
                                    session = entry.session,
                                    folderLabel = null,
                                    onOpen = { id ->
                                        if (searchQuery.isNotBlank()) stateHolder.localChatMatches.firstOrNull { it.sessionId == id }
                                            ?.let(stateHolder::prepareLocalChatMatch)
                                        onOpenSession(id)
                                    },
                                    onRename = stateHolder::renameSession,
                                    onArchive = stateHolder::archiveSession
                                )
                            }
                        }
                    }
                    item(key = "workspace-sessions-footer", contentType = "footer") { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showWorkspaceRequiredAlert) {
        DshAlertDialog(
            onDismissRequest = { showWorkspaceRequiredAlert = false },
            title = appText("请先选择工作区"),
            message = appText("请先选择一个具体工作区，再创建新会话。"),
            confirmLabel = appText("知道了"),
            onConfirm = { showWorkspaceRequiredAlert = false },
            modifier = Modifier.testTag("workspace-required-alert")
        )
    }
    if (showQrScanner) {
        GatewayQrScannerScreen(
            onCode = { payload ->
                showQrScanner = false
                stateHolder.pair(payload)
            },
            onCancel = { showQrScanner = false },
            onFailure = { message ->
                showQrScanner = false
                stateHolder.showPlatformError(message)
            }
        )
    }
    if (showManualPairing) {
        ManualGatewayPairingSheet(stateHolder) { showManualPairing = false }
    }
    if (showDirectoryBrowser) {
        WorkspaceDirectoryBrowserSheet(
            stateHolder = stateHolder,
            onDismiss = { showDirectoryBrowser = false }
        )
    }
}

@Composable
private fun WorkspaceHeader(
    state: GatewayRuntimeState,
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Row 先测量无权重的操作按钮，品牌只使用剩余宽度，避免窄屏压缩按钮。
        HarnessMark(onClick = onOpenDrawer, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        GatewayAuthenticationMenu(
            state = state,
            onScan = onScan,
            onManualEntry = onManualEntry
        )
        Spacer(Modifier.width(12.dp))
        GlassCircleButton(R.drawable.ic_settings, appText("设置"), onSettings)
    }
}

@Composable
private fun HarnessMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = appText("打开侧边栏") },
        contentAlignment = Alignment.CenterStart
    ) {
        // 不按机型或屏幕像素缩放整页：仅品牌图片根据其实际可用宽度等比例收缩。
        val showWordmark = maxWidth >= 128.dp
        val scale = (maxWidth / 219.dp).coerceIn(0f, 1f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp * scale)
        ) {
            WhalenessOrcaIcon(
                modifier = Modifier.size(if (showWordmark) 29.dp * scale else 29.dp),
                tint = Color.White
            )
            if (showWordmark) {
                Image(
                    painter = painterResource(R.drawable.dsh_whaleness_wordmark_dark),
                    contentDescription = null,
                    modifier = Modifier.size(width = 183.dp * scale, height = 29.dp * scale)
                )
            }
        }
    }
}

@Composable
internal fun WhalenessOrcaIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Image(
        painter = painterResource(R.drawable.dsh_orca_mark),
        contentDescription = "Whaleness",
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}

@Composable
internal fun GlassCircleButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.requiredSize(52.dp)
            .dshLiquidGlass(CircleShape, DshGlassStyle.CONTROL)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(Color.White)
        )
    }
}

@Composable
private fun WorkspaceCard(
    workspace: GatewayWorkspace?,
    ungrouped: Boolean,
    ungroupedCount: Int,
    allWorkspaces: Boolean = false,
    sessionCount: Int = 0,
    state: GatewayRuntimeState,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(15.dp), DshGlassStyle.CARD)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp)
            .testTag("workspace-card"),
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
                if (allWorkspaces) appText("全部会话")
                else if (ungrouped) appText("未分组")
                else workspace?.title ?: "DeepseekHarnessProject",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (allWorkspaces) appText("%1\$s 个会话（跨全部工作区）", sessionCount)
                else if (ungrouped) appText("%1\$s 个未归属会话", ungroupedCount)
                else workspace?.path ?: appText("通过 Mobile Gateway 连接"),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ConnectionDot(state)
        Image(
            painter = painterResource(R.drawable.ic_question_chevron_down),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.55f)),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun GlassActionButton(icon: String, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .dshLiquidGlass(RoundedCornerShape(18.dp), DshGlassStyle.ACTION)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("workspace-new-session"),
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
internal fun LocalChatSearchContent(
    stateHolder: AndroidSharedStateHolder,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onOpenSession: (String) -> Unit
) {
    SessionSearch(searchQuery, onQueryChange)
    if (searchQuery.isBlank()) return
    Text(
        text = if (stateHolder.localSearchHasMore) {
            appText("本地聊天内容（仅已缓存部分，显示前 50 条）。打开结果可定位对应消息。")
        } else {
            appText("本地聊天内容（仅已缓存部分）；联网时同时补充网关搜索。")
        },
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    val scopedIds = workspaceScopedSessions(
        stateHolder.snapshot.sessions,
        stateHolder.availableWorkspaces,
        stateHolder.selectedWorkspaceId,
        stateHolder.childSessionIds
    ).mapTo(mutableSetOf()) { it.id }
    val catalogIds = stateHolder.snapshot.sessions.mapTo(mutableSetOf()) { it.id }
    stateHolder.localChatMatches.filter {
        !stateHolder.isSubagentSession(it.sessionId) && (it.sessionId !in catalogIds || it.sessionId in scopedIds)
    }.forEach { match ->
        Column(
            Modifier.fillMaxWidth().clickable {
                stateHolder.prepareLocalChatMatch(match)
                onOpenSession(match.sessionId)
            }.padding(vertical = 10.dp)
        ) {
            Text(
                stateHolder.snapshot.sessions.firstOrNull { it.id == match.sessionId }?.title ?: appText("已缓存会话"),
                color = Color.White,
                fontWeight = FontWeight.Medium
            )
            Text(match.snippet, color = Color.White.copy(alpha = 0.8f), maxLines = 3, fontSize = 13.sp)
        }
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
                        Text(appText("搜索会话内容"), color = Color.White.copy(alpha = 0.58f), fontSize = 16.sp)
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
        appText("暂无已知会话。连接服务后创建第一个任务。"),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth()
            .dshLiquidGlass(RoundedCornerShape(15.dp), DshGlassStyle.SUBTLE)
            .padding(18.dp)
    )
}

// 会话归属标签：优先工作区标题，其次退回 cwd 末段；与标题相同则不显示，避免重复。
private fun sessionFolderLabel(session: SessionSummary, folderLabels: Map<String, String>): String? {
    val label = folderLabels[session.id]
        ?: session.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    return label?.takeIf { it != session.title }
}

@Composable
private fun SessionList(
    sessions: List<SessionSummary>,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String) -> Unit,
    folderLabels: Map<String, String> = emptyMap(),
    maxRows: Int = 12
) {
    Column(Modifier.background(Color.Transparent)) {
        sessions.take(maxRows).forEach { session ->
            androidx.compose.runtime.key(session.id) {
                SessionListItem(session, sessionFolderLabel(session, folderLabels), onOpen, onRename, onArchive)
            }
        }
    }
}

@Composable
private fun SessionListItem(
    session: SessionSummary,
    folderLabel: String?,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onArchive: (String) -> Unit
) {
    var menuVisible by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(session.title) }
    Column {
        Box {
            SessionRow(
                session,
                folderLabel = folderLabel,
                onClick = { onOpen(session.id) },
                onLongClick = { menuVisible = true }
            )
            SessionContextMenu(
                expanded = menuVisible,
                onDismissRequest = { menuVisible = false },
                onRename = {
                    title = session.title
                    menuVisible = false
                    renaming = true
                },
                onArchive = {
                    menuVisible = false
                    archiving = true
                }
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 18.dp),
            thickness = 0.5.dp,
            color = Color.White.copy(alpha = 0.10f)
        )
    }
    if (renaming) RenameSessionDialog(
        title = title,
        onTitleChange = { title = it },
        onDismissRequest = { renaming = false },
        onSave = {
            onRename(session.id, title)
            renaming = false
        }
    )
    if (archiving) AlertDialog(
        onDismissRequest = { archiving = false },
        title = { Text(appText("删除会话？")) },
        text = { Text(appText("会话将被归档并从列表隐藏，历史记录会保留。")) },
        confirmButton = {
            TextButton(onClick = { onArchive(session.id); archiving = false }) { Text(appText("删除")) }
        },
        dismissButton = { TextButton(onClick = { archiving = false }) { Text(appText("取消")) } }
    )
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    folderLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 4.dp, vertical = 10.dp)
            .testTag("workspace-session-${session.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        val dot = if (session.isRunning) DshColors.Success else if (session.hasUnread) DshColors.Ocean else Color.White.copy(alpha = 0.35f)
        StatusIndicatorDot(
            color = dot,
            modifier = Modifier.size(7.dp),
            glowing = session.isRunning
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = folderLabel?.let { "$it · ${session.id.take(16)}" } ?: session.id.take(16),
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Text(
            if (session.isRunning) appText("运行中") else relativeTime(session.lastActivityEpochSeconds),
            color = if (session.isRunning) Color(0xFF68A0FF) else Color.White.copy(alpha = 0.48f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ConnectionDot(state: GatewayRuntimeState) {
    val color = when (state.connection) {
        GatewayConnectionState.CONNECTED -> DshColors.Success
        else -> Color.Red
    }
    StatusIndicatorDot(
        color = color,
        modifier = Modifier.size(8.dp),
        glowing = state.connection == GatewayConnectionState.CONNECTED
    )
}

@Composable
private fun ConnectionStatusText(state: GatewayRuntimeState) {
    val connected = state.connection == GatewayConnectionState.CONNECTED
    val color = when (state.connection) {
        GatewayConnectionState.CONNECTED -> DshColors.Success
        else -> Color.Red.copy(alpha = 0.85f)
    }
    Text(
        when (state.connection) {
            GatewayConnectionState.CONNECTED -> appText("已连接")
            GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING -> appText("连接中")
            GatewayConnectionState.WAITING_FOR_NETWORK -> appText("等待网络")
            GatewayConnectionState.FAILED -> appText("连接失败")
            else -> appText("未连接")
        },
        color = color,
        fontSize = 12.sp,
        style = TextStyle(
            shadow = if (connected) {
                TextShadow(color = color.copy(alpha = 0.30f), offset = Offset.Zero, blurRadius = 7f)
            } else {
                null
            }
        )
    )
}

internal fun filterSessionsByQuery(
    sessions: List<SessionSummary>,
    query: String,
    localBodySessionIds: Set<String>,
    remoteSessionIds: Set<String> = emptySet()
): List<SessionSummary> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return sessions
    return sessions.filter {
        it.title.contains(normalized, ignoreCase = true) || it.id.contains(normalized, ignoreCase = true) ||
            it.id in localBodySessionIds || it.id in remoteSessionIds
    }
}

internal fun workspaceScopedSessions(
    sessions: List<SessionSummary>,
    workspaces: List<GatewayWorkspace>,
    selectedWorkspaceId: String?,
    childSessionIds: Set<String> = emptySet()
): List<SessionSummary> {
    if (selectedWorkspaceId == AndroidSharedStateHolder.ALL_WORKSPACES_ID) {
        return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds }
    }
    val selected = workspaces.firstOrNull { it.workspaceId == selectedWorkspaceId }
        ?: workspaces.firstOrNull()
    if (
        selectedWorkspaceId == AndroidSharedStateHolder.UNGROUPED_WORKSPACE_ID ||
        selected == null
    ) {
        val assigned = workspaces.flatMapTo(mutableSetOf()) { it.sessionIds }
        return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds && it.id !in assigned }
    }
    return sessions.filter { it.isVisibleInHistory && it.id !in childSessionIds && it.id in selected.sessionIds }
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
        dayDifference == 1L -> appText("昨天")
        dayDifference == 2L -> appText("前天")
        dayDifference > 2L -> appText("%1\$s 天前", dayDifference)
        elapsed < 60 -> appText("刚刚")
        elapsed < 3_600 -> appText("%1\$s 分钟前", elapsed / 60)
        else -> appText("%1\$s 小时前", elapsed / 3_600)
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

private const val WHALE_ICON_PATH = "M22.9168 1.43018C22.6713 1.31018 22.5658 1.53918 22.4223 1.65519C21.9317 2.1697 21.5127 2.42121 20.9657 2.39121C20.1657 2.34621 19.4827 2.59771 18.8787 3.20973C18.7502 2.45521 18.3236 2.0047 17.6746 1.71569C16.5876 .856163 16.5421 .597155 16.4591 .341647C16.3536 .0301382 16.1761 .00363739 15.8326 .270145C15.5306 .822162 15.4136 1.43018 15.4251 2.0462C15.4516 3.43174 16.0366 4.53527 17.1991 5.3203C17.3651 5.4103 17.3651 5.5003 17.3236 5.63181C17.0671 6.43533 16.9351 6.64584 16.7501 6.57033C14.2475 4.63328 13.5 3.75075 12.568 3.05973C10.9524 1.68169 12.028 .923165 12.277 .833162C12.5375 .739159 12.3675 .41615 11.5259 .42015C10.6844 .42365 9.91439 .705658 8.48384 1.21267C5.70226 1.11517 3.88321 1.31768 1.36213 3.64575C.0790928 5.4103-.222916 7.41536.146595 9.50642C.535106 11.7105 1.66014 13.535 3.38869 14.9616C5.18125 16.4406 7.24581 17.1657 9.60138 17.0266C11.0319 16.9441 12.6245 16.7526 14.421 15.2321C16.7456 15.6716 17.3306 15.5851 17.7836 15.4911C18.4931 15.3411 18.4441 14.6841 18.1876 14.5636C16.1081 13.595 16.5646 13.9891 16.1496 13.67C17.2061 12.42 18.8202 10.1979 19.3182 7.17235C19.4182 5.93231 19.4562 5.86831 19.6447 5.84931C22.4833 4.65528 23.0268 3.44624 23.1548 1.9972C23.1738 1.77569 23.1508 1.54668 22.9168 1.43018Z"
