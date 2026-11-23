package com.clarklevis.dsh.android.ui

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.AndroidImagePreprocessor
import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.shared.facade.BotRoleOption
import com.clarklevis.dsh.shared.facade.BotMemory
import com.clarklevis.dsh.shared.facade.BotSchedule
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.clarklevis.dsh.shared.facade.BotAvatarLayout
import com.clarklevis.dsh.shared.facade.BotBubblePalette
import com.clarklevis.dsh.shared.facade.BotMentionCandidate
import com.clarklevis.dsh.shared.facade.BotMentionDraft
import com.clarklevis.dsh.shared.facade.BotMentionEdit
import com.clarklevis.dsh.shared.facade.BotMentions
import com.clarklevis.dsh.shared.facade.BotChat
import com.clarklevis.dsh.shared.facade.BotChatIndicator
import com.clarklevis.dsh.shared.facade.BotChatMessage
import com.clarklevis.dsh.shared.facade.BotLiveWork
import com.clarklevis.dsh.shared.facade.BotInteraction
import com.clarklevis.dsh.shared.facade.SharedBotSnapshot
import com.clarklevis.dsh.shared.protocol.PresentedFile

private data class BotColors(
    val background: Color = Color(0xFFF4F1FA),
    val panel: Color = Color(0xFFF2F2F7),
    val row: Color = Color.White,
    val text: Color = Color(0xFF252332),
    val secondary: Color = Color(0xFF8E8E93),
    val cardSecondary: Color = Color(0xFF5C536C),
    val cardAccent: Color = Color(0xFF584190),
    val purple: Color = Color(0xFF7760B4),
    val blue: Color = Color(0xFF2E6BE6),
    val soft: Color = Color(0xFFECE7F7),
    val line: Color = Color(0xFFDAD6E3),
    val glass: Color = Color(0xFF2D2A38),
    val rim: Color = Color(0xFF777184)
)

private val botLightColors = BotColors()
private val botNightColors = BotColors(
    background = Color(0xFF17161F), panel = Color(0xFF211F2A), row = Color(0xFF292733),
    text = Color(0xFFF2EEF6), secondary = Color(0xFFD8D2E1), cardSecondary = Color(0xFFD8D2E1),
    cardAccent = Color(0xFFC8B5E8), purple = Color(0xFFC8B5E8), blue = Color(0xFFA1BFF6),
    soft = Color(0xFF333044), line = Color(0xFF484354)
)
private val botNightUserBubble = Color(0xFF2A3D57)
private val botNightSend = Color(0xFF101A42)
private val botMessageAvatarSize = 28.dp
private val botMessageAvatarSpacing = 8.dp
@Composable private fun botColors() = if (isSystemInDarkTheme()) botNightColors else botLightColors
internal val BotDesignColorScheme = lightColorScheme(primary = botLightColors.blue, background = botLightColors.background,
    surface = botLightColors.row, onBackground = botLightColors.text, onSurface = botLightColors.text)

@Composable
internal fun BotModeTheme(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val night = botNightColors
    val scheme = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.copy(
        primary = night.blue, background = night.background, surface = night.row, onBackground = night.text,
        onSurface = night.text, onSurfaceVariant = night.secondary, surfaceVariant = night.panel,
        surfaceContainer = night.row, surfaceContainerHigh = night.row, outline = night.rim, outlineVariant = night.line
    ) else BotDesignColorScheme
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
internal fun BotDrawerMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_bot_drawer_mark),
        contentDescription = null,
        modifier = modifier,
        colorFilter = ColorFilter.tint(botColors().text),
    )
}

/** 首页外壳由 WorkspaceScreen 提供；卡片、菜单、玻璃和弹窗复用项目组件。 */
@Composable
internal fun BotModeScreen(holder: AndroidSharedStateHolder, onStandardMode: () -> Unit,
    openDrawer: () -> Unit = {}, scrollEnabled: Boolean = true,
    header: (@Composable () -> Unit)? = null,
    onChatOpened: (Boolean) -> Unit = {},
    modeWave: WorkspaceModeWave? = null) {
    val state = holder.botSnapshot
    val colors = botColors()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val view = LocalView.current
    val night = isSystemInDarkTheme()
    val chatSurface = if (night) colors.background else Color.White
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
    LaunchedEffect(holder) { holder.activateBotMode() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(holder, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.setBotForeground(true)
                Lifecycle.Event.ON_PAUSE -> holder.setBotForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.setBotForeground(false)
        }
    }
    val homeListState = rememberLazyListState()
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSearch by rememberSaveable { mutableStateOf("") }
    var schedules by rememberSaveable { mutableStateOf(false) }
    var roster by rememberSaveable { mutableStateOf(false) }
    var newBot by rememberSaveable { mutableStateOf(false) }
    var conversationPicker by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.conversation.openedId) {
        state.conversation.openedId?.let { id ->
            conversationPicker = null
            focus.clearFocus()
            holder.selectBotChat(id)
            opened = id
        }
    }
    var roleId by rememberSaveable { mutableStateOf<String?>(null) }
    var memberRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    val role = state.chats.firstOrNull { it.id == roleId && !it.room }
    val openedExists = opened == null || state.chats.any { it.id == opened }
    LaunchedEffect(openedExists, roleId, role?.id) {
        if (!openedExists) {
            opened = null
            holder.selectBotChat(null)
        }
        if (role == null) roleId = null
    }
    val showProfile: (String) -> Unit = { roleId = it }
    var imagePreview by remember { mutableStateOf<com.clarklevis.dsh.android.platform.MarkdownImageAsset?>(null) }
    BotModeTheme {
        DshMenuHost {
            BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
                val fullHeight = maxHeight + WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                val wide = maxWidth >= 760.dp && fullHeight >= 500.dp && LocalDensity.current.fontScale < 1.5f
                val leftWidth = minOf(440.dp, maxWidth * .38f)
                SideEffect { onChatOpened(opened != null || roster || newBot || conversationPicker != null) }
                val closeChat = {
                    focus.clearFocus()
                    opened = null
                    holder.selectBotChat(null)
                }
                BackHandler(enabled = opened != null, onBack = closeChat)
                val homeHeader: @Composable () -> Unit = header ?: {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkspaceHeader(holder.gatewayState, {}, {}, {}, openDrawer, onDarkBackground = false)
                        GatewaySwitcherBar(onDarkBackground = false)
                    }
                }
                val showingHome = opened == null && !roster && !newBot && conversationPicker == null
                DshLiquidGlassHost(modifier = Modifier.fillMaxSize(), frostOnOlderDevices = showingHome,
                    workspaceScrollState = homeListState.takeIf { showingHome }, background = { backdrop ->
                    if (showingHome) HarnessAnimatedBackground(backdrop,
                        animationEnabled = scrollEnabled && !holder.botDesignPreview, lavender = true,
                        modeWave = modeWave)
                    else Box(backdrop.fillMaxSize().background(chatSurface))
                }) {
                    val select: (String) -> Unit = { focus.clearFocus(); holder.selectBotChat(it); opened = it }
                    if (conversationPicker != null) {
                        BotConversationPicker(holder, requireNotNull(conversationPicker)) { conversationPicker = null }
                    } else if (newBot) {
                        BotManagementFlow(holder, onDismiss = { newBot = false }, showRole = showProfile, startCreating = true)
                    } else if (roster) {
                        BotManagementFlow(holder, onDismiss = { roster = false }, showRole = showProfile)
                    } else if (opened != null) {
                        val chatId = requireNotNull(opened)
                        WorkspaceFilePreviewHost(holder, botChatId = chatId)
                        val backdrop = rememberLayerBackdrop()
                        val density = LocalDensity.current
                        val initialInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 64.dp
                        var topInset by remember(density) { mutableStateOf(initialInset) }
                        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(background = chatSurface, surface = chatSurface)) {
                            KeyboardDismissHost(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                                BotChatContent(holder, chatId, colors, Modifier.fillMaxSize().layerBackdrop(backdrop),
                                    topInset, showProfile) { imagePreview = it }
                                ConversationTopGlass(backdrop, topInset)
                                Box(Modifier.onSizeChanged { topInset = with(density) { it.height.toDp() } }) {
                                    BotChatHeader(state, chatId, closeChat,
                                        { schedules = true }, holder::refreshBotMode, showProfile) { memberRoomId = chatId }
                                }
                            }
                        }
                    } else {
                        val chats = holder.searchBotChats(chatSearch)
                        val safePadding = WindowInsets.safeDrawing.asPaddingValues()
                        val direction = LocalLayoutDirection.current
                        val assistant: @Composable (Modifier) -> Unit = { modifier ->
                            BotAssistantContent(state, colors, modifier, wide, select, { schedules = true },
                                { roster = true }, holder::refreshBotMode, showProfile)
                        }
                        val chatHeader: @Composable () -> Unit = {
                            BotChatSectionHeader(colors, chats.size, chatSearch, wide,
                                error = state.conversation.error?.let { state.conversation.errorDetail ?: it }, actions = {
                                BotNewConversationMenu(holder, colors, { newBot = true }, {
                                    holder.beginBotConversation(); conversationPicker = true
                                }, { holder.beginBotConversation(); conversationPicker = false })
                            }) { chatSearch = it }
                        }
                        val searching = chatSearch.isNotBlank()
                        if (wide) {
                            Column(
                                Modifier.fillMaxSize().padding(safePadding).adaptiveContentWidth(AdaptiveLayout.WorkspaceWidth)
                                    .padding(start = 40.dp, top = 18.dp, end = 40.dp),
                                verticalArrangement = Arrangement.spacedBy(40.dp),
                            ) {
                                homeHeader()
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                    Column(Modifier.width(leftWidth).verticalScroll(rememberScrollState(), enabled = scrollEnabled)
                                        .padding(bottom = 24.dp)) {
                                        assistant(Modifier.fillMaxWidth())
                                    }
                                    LazyColumn(
                                        Modifier.weight(1f).testTag("bot.wide-chats"),
                                        state = homeListState, userScrollEnabled = scrollEnabled,
                                        contentPadding = PaddingValues(bottom = 24.dp),
                                    ) {
                                        item(key = "bot-chat-heading", contentType = "heading") { chatHeader() }
                                        botChatItems(chats, state, searching, select)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                Modifier.fillMaxSize().adaptiveContentWidth(AdaptiveLayout.WorkspaceWidth).testTag("bot.home"),
                                state = homeListState, userScrollEnabled = scrollEnabled,
                                // 安全区只影响内容初始位置，滚动视口覆盖状态栏和导航栏。
                                contentPadding = PaddingValues(
                                    start = safePadding.calculateStartPadding(direction) + 22.dp,
                                    top = safePadding.calculateTopPadding() + 18.dp,
                                    end = safePadding.calculateEndPadding(direction) + 22.dp,
                                    bottom = safePadding.calculateBottomPadding() + 16.dp,
                                ),
                            ) {
                                item(key = "bot-home-header", contentType = "header") {
                                    Column {
                                        homeHeader()
                                        Spacer(Modifier.height(76.dp))
                                    }
                                }
                                item(key = "bot-home-assistant", contentType = "assistant") {
                                    assistant(Modifier.fillMaxWidth().padding(bottom = 16.dp))
                                }
                                item(key = "bot-chat-heading", contentType = "heading") { chatHeader() }
                                botChatItems(chats, state, searching, select)
                            }
                        }
                    }
                }
                imagePreview?.let { image ->
                    val attachment = com.clarklevis.dsh.shared.protocol.GatewayImageAttachment("bot-preview", image.mediaType, image.bytes.size, image.width, image.height, "image." + when (image.mediaType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/bmp" -> "bmp"; else -> "png" })
                    ConversationImagePreviewDialog(ConversationImagePreviewRequest(listOf(attachment), 0),
                        mapOf(attachment.attachmentId to image.image), { imagePreview = null }, { image.bytes }, markdownStyle = true)
                }
                memberRoomId?.let { roomId ->
                    val members = state.chats.firstOrNull { it.id == roomId }?.memberIds.orEmpty()
                        .mapNotNull { memberId -> state.chats.firstOrNull { it.id == memberId } }
                    BotGroupMembersPanel(members, showProfile) { memberRoomId = null }
                }
                role?.let { BotRolePanel(holder, it, colors) { roleId = null } }
                if (schedules) BotSchedulesPanel(state, holder::refreshBotMode) { schedules = false }

            }
        }
    }
}

@Composable
private fun BotAssistantContent(
    state: SharedBotSnapshot, colors: BotColors, modifier: Modifier, wide: Boolean, select: (String) -> Unit,
    schedules: () -> Unit, roster: () -> Unit, refresh: () -> Unit, showProfile: (String) -> Unit,
) {
    val chief = state.chats.firstOrNull { it.id == state.mainBotId }
    val running = state.chats.count { it.running }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 3.dp)) {
            Text(appText("个人助理"), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = colors.text)
            Text(appText("今天也交给我吧。"), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).dshWorkspaceGlass(RoundedCornerShape(22.dp))
            .clickable(enabled = chief != null, role = Role.Button) { chief?.let { showProfile(it.id) } }.testTag("bot.chief")
            .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotAvatar(chief, 44.dp, main = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chief?.name ?: appText("个人助理"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(5.dp).background(if (chief?.running == true) DshColors.Success else colors.purple, CircleShape))
                    Text(if (chief?.running == true) appText("正在为你推进 %1\$s 件事", running) else appText("随时可以找我聊聊"), fontSize = 12.sp, lineHeight = 17.sp, color = colors.cardAccent)
                }
            }
        }
        DshGlassButton(chief?.let { appText("与 %1\$s 聊聊", it.name) } ?: appText("与助理聊聊"),
            { chief?.let { select(it.id) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bot.start-chat"),
            enabled = chief != null, shape = RoundedCornerShape(26.dp), appearanceDark = isSystemInDarkTheme(),
            workspaceStyle = DshGlassStyle.ACTION) {
            val indicator = chief?.let { state.chatIndicator(it.id) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.padding(horizontal = if (indicator != null) 64.dp else 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_bot_conversation), null, Modifier.size(25.2422.dp, 20.1094.dp), tint = colors.text)
                    Text(chief?.let { appText("与 %1\$s 聊聊", it.name) } ?: appText("与助理聊聊"), fontSize = 17.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.text)
                }
                if (chief != null && indicator != null) {
                    BotChatStatusMark(chief.id, indicator, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
                }
            }
        }
        if (!state.connected || !state.supported || state.error != null) {
            BotCard(colors, homeGlass = true) {
                Text(if (!state.connected) appText("连接主机后查看个人助理") else if (!state.supported) appText("请更新主机上的移动网关以使用 Bot 模式") else botErrorText(state.error.orEmpty()), color = colors.cardSecondary, fontSize = 15.sp)
                if (state.connected && state.supported) Text(appText("重新同步"), Modifier.clickable(onClick = refresh).padding(vertical = 6.dp), color = colors.purple)
            }
        }
        if (state.loading && state.chats.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.secondary)
                Text(appText("正在同步助理"), color = colors.secondary, style = SettingsTypography.item)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotShortcut(appText("我的 Bot"), state.chats.count { !it.room }, appText("查看与创建"), Modifier.weight(1f).testTag("bot.roster"), roster)
            BotShortcut(appText("定时任务"), state.schedules.size, state.schedules.firstOrNull()?.next?.let { appText("下次 %1\$s", it) } ?: appText("查看安排"), Modifier.weight(1f).testTag("bot.schedules"), schedules)
        }
    }
}

@Composable
private fun BotChatSectionHeader(colors: BotColors, resultCount: Int, query: String, wide: Boolean,
    error: String?, actions: @Composable () -> Unit, onQueryChange: (String) -> Unit) {
    val spacing = if (wide) 16.dp else 10.dp
    Column(Modifier.padding(bottom = spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(appText("聊天"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.weight(1f))
            if (query.isNotBlank()) Text(appText("%1\$s 个结果", resultCount), fontSize = 12.sp, color = colors.secondary,
                modifier = Modifier.padding(end = 8.dp))
            actions()
        }
        if (error != null) Text(error, fontSize = 13.sp, color = Color.Red)
        SessionSearch(query, onQueryChange, placeholder = appText("搜索聊天名称或内容"), appearanceDark = false,
            modifier = Modifier.testTag("bot.chat-search"), foreground = colors.text, clearable = true)
    }
}

@Composable
private fun BotNewConversationMenu(holder: AndroidSharedStateHolder, colors: BotColors,
    createBot: () -> Unit, createGroup: () -> Unit, searchBots: () -> Unit) {
    val state = holder.botSnapshot
    var expanded by remember { mutableStateOf(false) }
    Box {
        DshGlassButton(appText("新建对话"), { expanded = !expanded }, Modifier.size(44.dp).testTag("bot.conversation.new"), enabled = !state.conversation.saving) {
            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp), tint = colors.text)
        }
        if (state.conversation.saving) CircularProgressIndicator(Modifier.size(18.dp).align(Alignment.Center), color = colors.purple, strokeWidth = 2.dp)
        DshMenu(expanded, { expanded = false }, Modifier.width(250.dp).heightIn(max = 520.dp), alignToEnd = true) {
            DshMenuItem({ Text(appText("新建 Bot"), fontSize = 17.sp) }, { expanded = false; createBot() },
                enabled = state.canCreateBot, minHeight = 44.dp)
            DshMenuItem({ Text(appText("新建群聊"), fontSize = 17.sp) }, { expanded = false; createGroup() },
                enabled = state.connected && state.writable && state.conversation.supported, minHeight = 44.dp,
                modifier = Modifier.testTag("bot.conversation.group"))
            DshMenuItem({ Text(appText("查找 Bot…"), fontSize = 17.sp) }, { expanded = false; searchBots() }, minHeight = 44.dp,
                modifier = Modifier.testTag("bot.conversation.find"))
            HorizontalDivider(color = colors.line.copy(alpha = .45f), thickness = .5.dp)
            state.roster.forEach { bot ->
                DshMenuItem(text = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(bot.name, fontSize = 17.sp)
                        if (bot.role.isNotBlank()) Text(bot.role, color = colors.secondary, fontSize = 13.sp)
                    }
                }, onClick = { expanded = false; holder.beginBotConversation(); holder.openConversationBot(bot.id) },
                    leadingIcon = { BotAvatar(bot, 28.dp, state.isMainBot(bot.id)) }, minHeight = 56.dp,
                    enabled = !state.conversation.saving && (!bot.hidden || state.connected && state.writable && state.conversation.supported),
                    modifier = Modifier.testTag("bot.conversation.open.${bot.id}"))
            }
        }
    }
}

private fun LazyListScope.botChatItems(
    chats: List<BotChat>, state: SharedBotSnapshot, searching: Boolean, select: (String) -> Unit,
) {
    if (chats.isEmpty()) {
        item(key = "bot-chat-empty", contentType = "empty") {
            val colors = botColors()
            Column(Modifier.fillMaxWidth().dshWorkspaceGlass(RoundedCornerShape(24.dp), DshGlassStyle.SUBTLE)
                .then(if (searching) Modifier.heightIn(min = 170.dp).testTag("bot.search-empty") else Modifier)
                .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = if (searching) Alignment.CenterHorizontally else Alignment.Start) {
                if (searching) {
                    Text(appText("没有找到相关聊天"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text(appText("换个关键词试试"), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary)
                } else {
                    Text(appText("还没有可用的 Bot，请先在主机上配置助理"), fontSize = 15.sp, color = colors.cardSecondary)
                }
            }
        }
        return
    }

    // 限制单个玻璃层的高度。相邻分段使用直边和同样的分割线，长列表仍保持一张卡片的轮廓。
    val sections = chats.chunked(4)
    itemsIndexed(sections, key = { _, section -> "bot-chats-" + section.first().id }, contentType = { _, _ -> "chats" }) { index, section ->
        val colors = botColors()
        val top = if (index == 0) 24.dp else 0.dp
        val bottom = if (index == sections.lastIndex) 24.dp else 0.dp
        val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
        val extraTop = if (index > 0) 12.dp else 0.dp
        val extraBottom = if (index < sections.lastIndex) 12.dp else 0.dp
        val segmentBounds = if (sections.size == 1) Modifier else Modifier.clipToBounds().layout { measurable, constraints ->
            // 把装饰面的内侧边缘移出裁切范围，避免每一段额外绘制一条横向高光。
            val placeable = measurable.measure(constraints)
            val topOffset = extraTop.roundToPx()
            layout(placeable.width, placeable.height - topOffset - extraBottom.roundToPx()) {
                placeable.place(0, -topOffset)
            }
        }
        Column(Modifier.fillMaxWidth().then(segmentBounds).dshWorkspaceGlass(shape, DshGlassStyle.SUBTLE)
            .padding(top = extraTop, bottom = extraBottom)) {
            section.forEachIndexed { row, chat ->
                key(chat.id) { BotChatRow(chat, state, colors, select) }
                if (index < sections.lastIndex || row < section.lastIndex) {
                    HorizontalDivider(Modifier.padding(start = 76.dp), thickness = .5.dp,
                        color = if (isSystemInDarkTheme()) colors.line else colors.text.copy(alpha = 0.13f))
                }
            }
        }
    }
}

@Composable
private fun BotChatRow(chat: BotChat, state: SharedBotSnapshot, colors: BotColors, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { select(chat.id) }.testTag("bot.chat.${chat.id}")
        .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BotAvatar(chat, 48.dp, main = chat.id == state.mainBotId, roster = state.chats)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chat.name, style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chat.preview.ifBlank { if (chat.room) appText("群聊") else chat.role }, style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val indicator = state.chatIndicator(chat.id)
        if (chat.lastAt > 0 || indicator != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chat.lastAt > 0) {
                    val time = remember(chat.lastAt) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(chat.lastAt.toLong())) }
                    Text(time, style = SettingsTypography.item.copy(fontSize = 11.sp, lineHeight = 13.sp), color = colors.cardSecondary)
                }
                if (indicator != null) BotChatStatusMark(chat.id, indicator)
            }
        }
    }
}

@Composable
private fun BotChatStatusMark(chatId: String, indicator: BotChatIndicator, modifier: Modifier = Modifier) {
    if (indicator.running) {
        val label = appText("运行中")
        Box(modifier.size(20.dp).testTag("bot.running.$chatId").semantics { contentDescription = label },
            contentAlignment = Alignment.Center) {
            StatusIndicatorDot(DshColors.Success, Modifier.size(8.dp))
        }
    } else {
        val label = appText("未读消息：%1\$s", indicator.unreadCount)
        Box(modifier.testTag("bot.unread.$chatId").defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(Color(0xFFD6263C), CircleShape).semantics { contentDescription = label }
            .padding(horizontal = 5.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
            Text(indicator.unreadText,
                style = SettingsTypography.item.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White, maxLines = 1, softWrap = false)
        }
    }
}

@Composable private fun BotShortcut(title: String, count: Int, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = botColors()
    Column(modifier.heightIn(min = 66.dp).dshWorkspaceGlass(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 21.sp, color = colors.text)
            Text(count.toString(), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
        }
        Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = colors.cardSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
@Composable private fun BotSectionTitle(title: String, trailing: String, colors: BotColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
        Text(trailing, fontSize = 12.sp, lineHeight = 17.sp, color = colors.secondary)
    }
}
@Composable private fun BotCard(
    colors: BotColors,
    onClick: (() -> Unit)? = null,
    vertical: Dp = 16.dp,
    homeGlass: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val surface = if (homeGlass) Modifier.dshWorkspaceGlass(shape) else Modifier.clip(shape).background(colors.row)
    Column(modifier.fillMaxWidth().then(surface)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = vertical), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}
@Composable
internal fun BotAvatar(
    chat: BotChat?,
    size: Dp = 28.dp,
    main: Boolean = false,
    roster: List<BotChat> = emptyList(),
) {
    val colors = botColors()
    val members = if (chat?.room == true) chat.memberIds.mapNotNull { id -> roster.firstOrNull { it.id == id } } else emptyList()
    val seats = BotAvatarLayout.seats(members.size)
    if (seats.isNotEmpty()) {
        val outline = if (isSystemInDarkTheme()) colors.line else Color.White
        Box(Modifier.size(size)) {
            seats.forEachIndexed { index, seat ->
                val part = size * seat.side.toFloat()
                Box(Modifier.offset(size * seat.x.toFloat(), size * seat.y.toFloat()).size(part)
                    .clip(CircleShape).background(colors.soft).border(1.dp, outline, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (index == 3 && members.size > 4) {
                        Text("+${members.size - 3}", fontSize = (part.value * .36f).sp)
                    } else {
                        BotFace(members[index], part, main = false)
                    }
                }
            }
        }
    } else BotFace(members.firstOrNull() ?: chat, size, main)
}

@Composable
private fun BotFace(chat: BotChat?, size: Dp, main: Boolean) {
    val colors = botColors()
    val cache = PluginIconImageCache.shared(LocalContext.current)
    val data = chat?.image
    val bitmap by key(chat?.id) {
        produceState(cache.peekDataUrl(data), data) { value = cache.loadDataUrl(data) }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(requireNotNull(bitmap).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            main || chat?.id == "chief" -> WhalenessOrcaIcon(Modifier.size(size * .58f), tint = colors.text)
            chat?.room == true -> Image(painterResource(R.drawable.bot_group_avatar), null, Modifier.size(size))
            else -> {
                val scale = LocalDensity.current.fontScale
                Text(chat?.name?.take(1) ?: "C", fontSize = (size.value * .42f / scale).sp,
                    lineHeight = (size.value * .62f / scale).sp, fontWeight = FontWeight.SemiBold, color = colors.blue)
            }
        }
    }
}

@Composable
private fun BotChatHeader(
    state: SharedBotSnapshot,
    chatId: String,
    back: () -> Unit,
    schedules: () -> Unit,
    refresh: () -> Unit,
    showProfile: (String) -> Unit,
    showMembers: () -> Unit
) {
    val chat = state.chats.firstOrNull { it.id == chatId }
    val colors = botColors()
    var menu by remember { mutableStateOf(false) }
    ConversationTopBar(
        navigationIcon = {
            TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), back,
                Modifier.padding(horizontal = 8.dp).testTag("bot.chat-back"))
        },
        title = {
            Row(Modifier.testTag("bot.chat-identity").clickable(enabled = chat != null, role = Role.Button) {
                if (chat?.room == true) showMembers() else chat?.let { showProfile(it.id) }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotAvatar(chat, 36.dp, chatId == state.mainBotId, roster = state.chats)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chat?.name ?: appText("个人助理"), fontSize = 17.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (chat?.room == true) appText("群聊") else chat?.role?.ifEmpty { appText("随时为你效劳") } ?: appText("随时为你效劳"),
                        fontSize = 12.sp, lineHeight = 17.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        actions = {
            Box(Modifier.padding(horizontal = 8.dp)) {
                TopBarCircleButton(R.drawable.ic_more_horizontal, appText("更多操作"), { menu = true }, Modifier.testTag("bot.chat-more"))
                DshMenu(menu, { menu = false }, Modifier.width(248.dp), offset = DpOffset(0.dp, (-52).dp), alignToEnd = true) {
                    DshMenuItem(
                        text = { Text(appText("重新同步"), style = SettingsTypography.item) },
                        onClick = { menu = false; refresh() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(18.dp)) },
                        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 8.dp),
                        minHeight = 44.dp,
                    )
                    DshMenuItem(
                        text = { Text(appText("定时任务"), style = SettingsTypography.item) },
                        onClick = { menu = false; schedules() },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_drawer_schedule), null, Modifier.size(18.dp)) },
                        contentPadding = PaddingValues(horizontal = 30.dp, vertical = 8.dp),
                        minHeight = 44.dp,
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BotChatContent(holder: AndroidSharedStateHolder, chatId: String, colors: BotColors,
    modifier: Modifier, topInset: Dp, showProfile: (String) -> Unit,
    onPreviewImage: (com.clarklevis.dsh.android.platform.MarkdownImageAsset) -> Unit) {
    val state = holder.botSnapshot
    val chat = state.chats.firstOrNull { it.id == chatId }
    val roster = remember(state.chats) { state.chats.associateBy { it.id } }
    val palette = remember(state.chats, state.mainBotId) { BotBubblePalette(state.chats, state.mainBotId) }
    val night = isSystemInDarkTheme()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val quoteRequest = remember(chatId) { mutableStateOf<String?>(null) }
    val onReply: (String) -> Unit = remember(quoteRequest) { { quoteRequest.value = it } }
    var question by remember { mutableStateOf<BotInteraction?>(null) }
    val scroll = key(chatId) { rememberLazyListState() }
    var openedToBottom by remember(chatId) { mutableStateOf(false) }
    var pinnedToBottom by remember(chatId) { mutableStateOf(true) }
    var scrollToBottomRequest by remember(chatId) { mutableIntStateOf(0) }
    var programmaticScrolls by remember(chatId) { mutableIntStateOf(0) }
    val latest = state.messages.lastOrNull()
    val showsHistoryLoading = state.historyLoading && state.messages.isEmpty()
    val earlierItemCount = if (state.hasEarlier) 1 else 0
    val firstMessageIndex = earlierItemCount + if (showsHistoryLoading) 1 else 0
    val interactions = state.interactions.filter { it.chatId == chatId }
    val liveWork = state.visibleLiveWork(chatId)
    val hasTimelineContent = latest != null || interactions.isNotEmpty() || liveWork != null
    val lastItemIndex = state.messages.lastIndex + interactions.size + firstMessageIndex + if (liveWork != null) 1 else 0
    val markdownPreloader = rememberDshMarkdownPreloader()
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(chatId) { if (state.selectedChatId != chatId) holder.selectBotChat(chatId) }
    LaunchedEffect(chatId, lastItemIndex, latest?.text) {
        if (!hasTimelineContent || (openedToBottom && !pinnedToBottom && latest?.delivery != "sending")) {
            return@LaunchedEffect
        }
        programmaticScrolls += 1
        try {
            scroll.scrollToTimelineBottom(lastItemIndex, animated = false)
            openedToBottom = true
            pinnedToBottom = true
        } finally {
            programmaticScrolls -= 1
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible) scrollToBottomRequest += 1
    }
    LaunchedEffect(scrollToBottomRequest) {
        if (scrollToBottomRequest == 0 || !hasTimelineContent) return@LaunchedEffect
        programmaticScrolls += 1
        try {
            scroll.scrollToTimelineBottom(lastItemIndex, animated = true)
            pinnedToBottom = true
        } finally {
            programmaticScrolls -= 1
        }
    }
    LaunchedEffect(scroll, openedToBottom) {
        if (!openedToBottom) return@LaunchedEffect
        snapshotFlow {
            Triple(
                scroll.isScrollInProgress,
                isTimelinePinnedToBottom(scroll.layoutInfo.totalItemsCount, scroll.canScrollForward),
                programmaticScrolls > 0
            )
        }.distinctUntilChanged().collect { (isScrolling, pinned, isProgrammatic) ->
            if (shouldPublishPinnedState(isScrolling, pinned, isProgrammatic)) pinnedToBottom = pinned
        }
    }
    val messages by rememberUpdatedState(state.messages)
    LaunchedEffect(scroll, markdownPreloader, firstMessageIndex) {
        snapshotFlow {
            val visible = scroll.layoutInfo.visibleItemsInfo
            val lastIndex = messages.lastIndex
            val first = visible.firstOrNull()?.index?.minus(firstMessageIndex) ?: lastIndex
            val last = visible.lastOrNull()?.index?.minus(firstMessageIndex) ?: lastIndex
            // 按阅读窗口分批预热，避免滚动每经过一行就撤销并重开一轮解析。
            val batchSize = 8
            val start = (first.coerceAtLeast(0) / batchSize * batchSize - batchSize).coerceAtLeast(0)
            val end = ((last.coerceAtLeast(0) / batchSize + 2) * batchSize - 1).coerceAtMost(lastIndex)
            start..end
        }.distinctUntilChanged().collectLatest { range ->
            val current = messages
            if (!range.isEmpty() && range.last < current.size) {
                markdownPreloader.preload(current.subList(range.first, range.last + 1)
                    .filter { !it.streaming && it.text.isNotEmpty() && it.kind != "user" && it.kind != "event" && it.kind != "divider" }
                    .map { it.text })
            }
        }
    }
    val bottomHeight = remember { mutableStateOf(72.dp) }
    val density = LocalDensity.current
    val deliveredBySession = remember(state.messages) {
        state.messages.filter { it.sessionId.isNotEmpty() && it.files.isNotEmpty() }
            .groupBy { it.sessionId }
            .mapValues { (_, messages) -> messages.flatMap { it.files }.distinctBy { it.path }.sortedBy { it.path } }
    }
    ComposerGlassBackdropHost { contentBackdrop ->
        Box(modifier) {
            BoxWithConstraints(Modifier.fillMaxSize().then(contentBackdrop).background(MaterialTheme.colorScheme.surface)
                .imePadding().padding(horizontal = 16.dp)) {
                val bubbleAreaWidth = maxWidth.coerceAtMost(780.dp)
                LazyColumn(Modifier.fillMaxSize().testTag("bot.timeline"), state = scroll,
                    contentPadding = ComposerViewportPadding(bottomHeight, topInset + 18.dp, 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.hasEarlier) item { Text(appText("加载更早消息"), Modifier.clickable(enabled = !state.historyLoading && state.connected && state.supported) { holder.loadEarlierBotMessages() }, color = colors.purple) }
                    if (showsHistoryLoading) item { Text(appText("正在读取聊天"), color = colors.secondary) }
                    items(state.messages, key = { it.id }, contentType = { it.kind }) { message ->
                        val openFile: (String) -> Unit = { path ->
                            focus.clearFocus()
                            holder.openBotFile(chatId, message.id, path)
                        }
                        val replyAction = onReply.takeIf { (message.kind == "bot" || message.kind == "post") &&
                            !message.streaming && message.text.isNotBlank() }
                        val showsBody = message.kind != "file" &&
                            (message.files.isEmpty() || message.text.isNotEmpty() || message.images.isNotEmpty())
                        if (showsBody) {
                            val author = message.botId?.let(roster::get) ?: chat?.takeUnless { it.room || message.activity != null }
                            val containsInlineCode = '`' in message.text ||
                                message.quoteParts.any { it.type == "text" && '`' in it.text }
                            val knownFiles = if (containsInlineCode) deliveredBySession[message.sessionId].orEmpty() else emptyList()
                            BotBubble(holder, message, chat, author, author?.id == state.mainBotId, bubbleAreaWidth,
                                palette.rgb(message.botId ?: author?.id, dark = night), colors, showProfile,
                                onPreviewImage, replyAction, knownFiles, openFile)
                        }
                        if (message.files.isNotEmpty()) {
                            Column(
                                Modifier.width(bubbleAreaWidth).padding(
                                    start = botMessageAvatarSize + botMessageAvatarSpacing,
                                    top = if (showsBody) 8.dp else 0.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                message.files.forEach { file ->
                                    FileReferenceCard(path = file.path, description = file.description, onClick = { openFile(file.path) })
                                }
                                if (replyAction != null) {
                                    CompositionLocalProvider(LocalMessageQuote provides replyAction) {
                                        AssistantMessageActions(message.text)
                                    }
                                }
                            }
                        }
                    }
                    items(interactions, key = { "interaction-${it.id}" }) { interaction ->
                        BotCard(colors) {
                            Text(appText(if (interaction.kind == "approval") "需要你批准" else "助理在等你回答"), fontSize = 12.sp, color = colors.secondary)
                            Text(interaction.title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            if (interaction.detail.isNotEmpty()) Text(interaction.detail, color = colors.secondary, fontSize = 15.sp)
                            if (interaction.kind == "approval") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(appText("拒绝"), Modifier.clickable { holder.answerBotApproval(interaction.id, false) }, color = Color.Red)
                                Text(appText("仅允许这一次"), Modifier.clickable { holder.answerBotApproval(interaction.id, true) }, color = colors.blue)
                            } else Text(appText("回答"), Modifier.clickable { question = interaction }, color = colors.blue)
                        }
                    }
                    if (liveWork != null) {
                        item(key = "bot-live-work", contentType = "live-work") {
                            val author = liveWork.botId?.let(roster::get) ?: chat
                            BotLiveWorkRow(liveWork, author, state.chats, author?.id == state.mainBotId,
                                colors.cardSecondary, Modifier.width(bubbleAreaWidth))
                        }
                    }
                }
            }
            ConversationBottomFade(bottomHeight, Modifier.align(Alignment.BottomCenter))
            ConversationComposerChrome(
                modifier = Modifier.align(Alignment.BottomCenter).adaptiveContentWidth().imePadding(),
                composerModifier = Modifier
                    .onSizeChanged { bottomHeight.value = with(density) { it.height.toDp() } }
                    .navigationBarsPadding(),
                jumpVisible = openedToBottom && hasTimelineContent && !pinnedToBottom,
                jumpButton = {
                    ScrollToBottomButton(isGenerating = chat?.running == true, onClick = { scrollToBottomRequest += 1 })
                }
            ) {
                BotComposer(holder, chatId, quoteRequest)
            }
        }
    }
    question?.let { value -> BotQuestionPanel(value, { question = null }) { selected, custom -> holder.answerBotQuestion(value.id, selected, custom); question = null } }
}

@Composable
private fun BotLiveWorkRow(
    work: BotLiveWork,
    author: BotChat?,
    roster: List<BotChat>,
    main: Boolean,
    color: Color,
    modifier: Modifier,
) {
    val title = appText(when (work.kind) {
        "web-search" -> "上网搜索…"
        "web-read" -> "正在浏览网页…"
        "image" -> "正在生成图片…"
        "switching-folder" -> "正在切换工作文件夹…"
        "thinking" -> "正在思考…"
        else -> "正在处理…"
    })
    val startedAt = remember(work.startedAtMs) {
        val age = work.startedAtMs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0
        SystemClock.elapsedRealtime() - age
    }
    Row(modifier.testTag("bot.live-work"), horizontalArrangement = Arrangement.spacedBy(botMessageAvatarSpacing),
        verticalAlignment = Alignment.CenterVertically) {
        BotAvatar(author, botMessageAvatarSize, main, roster)
        Text(title, Modifier.weight(1f, fill = false), color = color, fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        BotLiveWorkElapsed(startedAt, color)
    }
}

@Composable
private fun BotLiveWorkElapsed(startedAt: Long, color: Color) {
    val seconds by produceState((SystemClock.elapsedRealtime() - startedAt) / 1_000, startedAt) {
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            value = elapsed / 1_000
            delay(1_000 - elapsed % 1_000)
        }
    }
    Text(appText("· %1\$s 秒", seconds), Modifier.testTag("bot.live-work-elapsed"),
        color = color, fontSize = 14.sp, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
}

@Composable private fun BotBubble(holder: AndroidSharedStateHolder, message: BotChatMessage, chat: BotChat?, author: BotChat?,
    mainAuthor: Boolean, bubbleAreaWidth: Dp, bubbleRGB: Int, colors: BotColors, showProfile: (String) -> Unit,
    onPreviewImage: (com.clarklevis.dsh.android.platform.MarkdownImageAsset) -> Unit, replyAction: ((String) -> Unit)?,
    knownFiles: List<PresentedFile>, onOpenFile: (String) -> Unit) {
    val time = remember(message.timeMs, LocalConfiguration.current) {
        message.timeMs?.let { conversationTimeLabel(it / 1_000.0, includesWeekday = true) }
    }
    if (message.activity != null) {
        Row(Modifier.width(bubbleAreaWidth), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Text(appText(if (message.activity == "bot-created") "已创建" else "已发消息给"),
                color = colors.secondary, fontSize = 13.sp, lineHeight = 17.sp)
            if (author != null) BotAvatar(author, 18.dp)
            Text(author?.name ?: message.name, Modifier.weight(1f, fill = false),
                color = colors.secondary, fontSize = 13.sp, lineHeight = 17.sp)
        }
        return
    }
    if (message.questionAnswers.isNotEmpty()) {
        BotAnsweredQuestions(message, bubbleAreaWidth, colors)
        return
    }
    val body = message.text.ifEmpty {
        if (message.kind == "divider" && time != null) time
        else appText(when (message.kind) { "view" -> "交互卡片"; "answer" -> "已回答"; "closed" -> "已处理"; else -> "协作动态" })
    }
    if (message.kind in listOf("event", "divider")) { Text(body, color = colors.secondary, fontSize = 12.sp); return }
    val user = message.kind == "user"
    val night = isSystemInDarkTheme()
    val parts = message.quoteParts
    // 与 iOS 一致：宽窗口放开阅读宽度，分屏窄窗口仍保留紧凑气泡。
    val maximumBubbleWidth = if (bubbleAreaWidth >= 560.dp) 720.dp else 292.dp
    val width = minOf(maximumBubbleWidth, bubbleAreaWidth - if (user) 40.dp else 76.dp).coerceAtLeast(80.dp)
    Row(Modifier.width(bubbleAreaWidth).testTag("bot.message.${message.id}"),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) {
            Box(Modifier.clickable(enabled = author != null, role = Role.Button) { author?.let { showProfile(it.id) } }) {
                BotAvatar(author, botMessageAvatarSize, mainAuthor)
            }
            Spacer(Modifier.width(botMessageAvatarSpacing))
        }
        Column(Modifier.widthIn(max = width), horizontalAlignment = if (user) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!user && chat?.room == true && message.name.isNotEmpty()) Text(message.name, color = colors.secondary, fontSize = 12.sp)
            val imageContext = remember(holder, chat?.id, message.sessionId, message.botId, message.images) {
                val refs = message.images.associateBy { "/__bot_image__/" + it.attachmentId }
                val session = message.sessionId.ifEmpty { chat?.id.orEmpty() }
                val load: suspend (String, Boolean) -> com.clarklevis.dsh.android.platform.MarkdownImageAsset = { path, preview ->
                    val ref = refs[path]
                    holder.loadBotImage(ref?.sessionId ?: session, ref?.attachmentId, if (ref == null) path else null, message.botId, preview)
                }
                DshMarkdownImageContext(holder.botImageNamespace + ":" + session + ":" + message.botId,
                    references = refs.mapValues { (_, ref) -> com.clarklevis.dsh.shared.protocol.GatewayImageAttachment(ref.attachmentId, ref.mediaType, 0, ref.width.coerceAtLeast(1), ref.height.coerceAtLeast(1), ref.name) },
                    cached = { null }, load = { load(it, true) }, loadOriginal = { load(it, false) }, onPreview = onPreviewImage)
            }
            CompositionLocalProvider(LocalDshMarkdownImages provides imageContext, LocalMessageQuote provides replyAction) {
                if (parts.isNotEmpty()) SentReplyBubble(parts, bubbleTint = botNightUserBubble.takeIf { night },
                    borderTint = Color.Transparent.takeIf { night }, quoteTint = colors.glass.takeIf { night },
                    knownFiles = knownFiles, onOpenFile = onOpenFile)
                if (parts.isEmpty() || message.images.isNotEmpty()) {
                    val shape = RoundedCornerShape(20.dp)
                    val surface = when {
                        !user -> Modifier.background(Color(bubbleRGB or 0xFF000000.toInt()), shape)
                        night -> Modifier.background(botNightUserBubble, shape)
                        else -> Modifier.userMessageBubbleBackground(shape, isDark = false)
                    }
                    Column(Modifier.testTag("bot.bubble.${message.id}").clip(shape).then(surface)
                        .padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (parts.isEmpty() && (message.text.isNotEmpty() || message.images.isEmpty())) {
                            if (user) Text(body, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, lineHeight = 24.sp)
                            else DshLazyMarkdownText(body, knownFiles = knownFiles, onOpenFile = onOpenFile,
                                textSizeSp = 17f, wrapContent = true)
                        }
                        message.images.forEach { ref ->
                            DshMarkdownImage(DshMarkdownImageMarker("/__bot_image__/" + ref.attachmentId, ref.name))
                        }
                    }
                }
                if (time != null && message.kind in listOf("user", "bot", "post")) {
                    Text(time, Modifier.testTag("bot.message-time.${message.id}"), color = colors.secondary,
                        fontSize = 11.sp, lineHeight = 14.sp)
                }
                message.delivery?.let { Text(appText(when (it) { "unknown" -> "发送状态待确认，请同步聊天后核对"; "sending" -> "发送中"; else -> "已发送" }), color = colors.secondary, fontSize = 11.sp) }
                if (replyAction != null && message.files.isEmpty()) AssistantMessageActions(message.text)
            }
        }
    }
}

@Composable
private fun BotAnsweredQuestions(message: BotChatMessage, areaWidth: Dp, colors: BotColors) {
    Column(Modifier.width(areaWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.questionAnswers.forEach { answer ->
            BotCard(colors, vertical = 12.dp, modifier = Modifier.width(minOf(areaWidth, 360.dp))
                .border(1.dp, colors.soft, RoundedCornerShape(20.dp))) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(answer.question, color = colors.secondary, fontSize = 15.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold)
                    answer.choices.forEach { choice ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            choice.letter?.let { letter ->
                                Text(letter, Modifier.background(colors.blue.copy(alpha = .12f), RoundedCornerShape(7.dp))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                    color = colors.blue, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(choice.label, color = colors.text, fontSize = 16.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
        }
    }
}

/** 首页和聊天菜单共用同一个定时任务面板。 */
@Composable
private fun BotSchedulesPanel(state: SharedBotSnapshot, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val colors = botColors()
    val night = isSystemInDarkTheme()
    val taskLabelStyle = SettingsTypography.title.copy(fontSize = 16.sp, lineHeight = 21.sp)
    val configuration = LocalConfiguration.current
    val footer: @Composable () -> Unit = {
        Text(appText("完成后，%1\$s 会在聊天里告诉你。",
            state.chats.firstOrNull { it.id == state.mainBotId }?.name ?: appText("助理")),
            color = colors.secondary, style = SettingsTypography.description)
    }
    BotPanel(
        appText("定时任务"), onDismiss, subtitle = appText("让助理按时替你做事。"),
        action = {
            DshGlassButton(appText("关闭"), onDismiss, Modifier.size(if (night) 44.dp else 54.dp)
                .testTag("bot.schedules-close")) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(22.dp))
            }
        },
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.schedules.isEmpty()) item {
                BotCard(colors) {
                    Column(Modifier.testTag("bot.panel-state"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(when {
                            state.error != null -> botErrorText(state.error!!)
                            state.loading -> appText("正在同步助理")
                            else -> appText("还没有定时任务")
                        }, color = colors.secondary, fontSize = 17.sp)
                        if (state.error != null) Text(appText("重新同步"), Modifier.clickable(onClick = onRefresh), color = colors.purple, fontSize = 17.sp)
                    }
                }
            }
            items(state.schedules, key = { it.id }) { task ->
                Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(22.dp)).background(colors.row).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(colors.soft), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_drawer_schedule), null, Modifier.size(20.dp), tint = colors.purple)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(task.title, style = taskLabelStyle, color = colors.text)
                        Text(task.status, style = SettingsTypography.description, color = colors.secondary)
                    }
                    val clock = remember(task.next, configuration) { formatScheduleClock(task.next) }
                    Text(clock, Modifier.widthIn(max = 110.dp).testTag("bot.schedule-clock.${task.id}"),
                        style = taskLabelStyle, color = colors.purple, textAlign = TextAlign.End,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            if (night) item { footer() }
        }
        if (!night) footer()
    }
}

/** 固定区域和安全区均计入总预算；列表只使用剩余高度。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotPanel(title: String, onDismiss: () -> Unit, action: (@Composable () -> Unit)? = null,
    subtitle: String? = null, roleStyle: Boolean = false, navigation: (@Composable () -> Unit)? = null,
    compactRole: Boolean = false, centerTitle: Boolean = false, systemGroupedColors: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = botColors()
    val night = isSystemInDarkTheme()
    val density = LocalDensity.current
    val appearance = LocalConfiguration.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val safeTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val safeBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val tablet = minOf(windowWidth, windowHeight) >= 600.dp
    val compactLandscape = !tablet && windowWidth > windowHeight
    val roleTopGap = maxOf(62.dp, safeTop + 12.dp)
    val scrimOpacity = if (night) .45f else .20f
    val surface = if (systemGroupedColors && night) Color(0xFF1C1C1E) else colors.panel
    val foreground = if (!systemGroupedColors) colors.text else if (night) Color.White else Color.Black
    val panelScheme = MaterialTheme.colorScheme.let { scheme ->
        if (!systemGroupedColors) scheme else scheme.copy(surface = if (night) Color(0xFF2C2C2E) else Color.White,
            onSurface = foreground, onBackground = foreground)
    }
    val fullHeight = (windowHeight - roleTopGap).coerceAtLeast(100.dp)
    val compactHeight = minOf(650.dp, fullHeight)
    val canExpand = compactRole && !tablet && !compactLandscape && compactHeight < fullHeight
    var expanded by rememberSaveable { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(canExpand) {
        if (!canExpand) expanded = false
    }
    val restingHeight = if (expanded) fullHeight else compactHeight
    val roleHeight by animateDpAsState(
        targetValue = if (dragging) {
            (restingHeight - with(density) { dragDistance.toDp() }).coerceIn(compactHeight, fullHeight)
        } else restingHeight,
        animationSpec = if (!canExpand || dragging) snap() else tween(240),
        label = "bot-role-sheet-height",
    )
    val handleDrag = if (canExpand) {
        Modifier.draggable(
            state = rememberDraggableState { delta -> dragDistance += delta },
            orientation = Orientation.Vertical,
            onDragStarted = {
                dragDistance = 0f
                dragging = true
            },
            onDragStopped = { velocity ->
                val threshold = with(density) { minOf(44.dp, (fullHeight - compactHeight) * .35f).toPx() }
                val down = dragDistance > threshold || velocity > 700f
                val up = dragDistance < -threshold || velocity < -700f
                dragging = false
                when {
                    up -> expanded = true
                    down && expanded -> expanded = false
                    down -> onDismiss()
                }
            },
        )
    } else Modifier
    val targetHeight = when {
        compactLandscape -> windowHeight
        tablet -> minOf(660.dp, windowHeight - safeTop - maxOf(20.dp, safeBottom) - 20.dp)
        canExpand -> roleHeight
        roleStyle -> fullHeight
        else -> minOf(674.dp, windowHeight - safeTop - 20.dp)
    }.coerceAtLeast(100.dp)
    val panel: @Composable (Dp) -> Unit = { height ->
        if (onNavigateBack != null) BackHandler(onBack = onNavigateBack)
        CompositionLocalProvider(LocalDensity provides density, LocalContentColor provides foreground,
            LocalConfiguration provides appearance) {
            MaterialTheme(colorScheme = panelScheme) {
                DshMenuHost {
                    Column(Modifier.fillMaxWidth().height(height).background(surface)
                        .then(if (compactLandscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)) else Modifier)) {
                        if (!compactLandscape) {
                            Box(Modifier.fillMaxWidth().height(16.dp).testTag("bot.panel-handle").then(handleDrag),
                                contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.padding(top = 6.dp).size(60.dp, 4.dp)
                                    .background(if (night) colors.rim else Color(0xFFB8B8BD), CircleShape))
                            }
                        }
                        val contentInset = if (roleStyle) 16.dp else 20.dp
                        Column(Modifier.weight(1f).padding(start = contentInset, end = contentInset,
                            top = if (compactLandscape) 28.dp else if (roleStyle) 0.dp else 10.dp,
                            bottom = if (tablet) 20.dp else maxOf(20.dp, safeBottom)),
                            verticalArrangement = Arrangement.spacedBy(if (roleStyle) 14.dp else 18.dp)) {
                            if (centerTitle) {
                                BotPanelCenteredHeader(title, foreground, navigation, action, handleDrag)
                            } else Row(Modifier.fillMaxWidth().heightIn(min = if (roleStyle) 44.dp else 54.dp).testTag("bot.panel-title").then(handleDrag),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                navigation?.invoke()
                                if (title.isEmpty()) Spacer(Modifier.weight(1f))
                                else Text(title, Modifier.weight(1f).testTag("bot.panel-heading"),
                                    style = if (roleStyle) SettingsTypography.title
                                        else SettingsTypography.title.copy(fontSize = 20.sp, lineHeight = 25.sp),
                                    color = colors.text)
                                action?.invoke()
                            }
                            subtitle?.let {
                                Text(it, style = SettingsTypography.item.copy(fontSize = 14.sp, lineHeight = 19.sp),
                                    color = colors.secondary)
                            }
                            content()
                        }
                    }
                }
            }
        }
    }
    if (compactLandscape) androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = onNavigateBack == null,
        )) {
        Box(Modifier.fillMaxSize().background(surface)) { panel(targetHeight) }
    } else if (roleStyle && tablet) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = onNavigateBack == null,
            )) {
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                    window.setDimAmount(scrimOpacity)
                    WindowCompat.getInsetsController(window, dialogView).apply {
                        isAppearanceLightStatusBars = !night
                        isAppearanceLightNavigationBars = !night
                    }
                }
            }
            Box(Modifier.widthIn(max = 580.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(32.dp))) { panel(targetHeight) }
        }
    } else {
        var keyboardDocked by remember { mutableStateOf(false) }
        val compactInset = if (canExpand) {
            8.dp * (1f - ((roleHeight - compactHeight) / (fullHeight - compactHeight)).coerceIn(0f, 1f))
        } else 8.dp
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = when {
                compactRole && !keyboardDocked -> Modifier.padding(start = compactInset, end = compactInset, bottom = compactInset)
                roleStyle || (night && !tablet) -> Modifier
                else -> Modifier.padding(
                    start = if (tablet) 0.dp else 8.dp,
                    end = if (tablet) 0.dp else 8.dp,
                    bottom = if (tablet) maxOf(20.dp, safeBottom) else 8.dp,
                )
            },
            sheetMaxWidth = 580.dp,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = if (keyboardDocked) RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp) else RoundedCornerShape(32.dp),
            containerColor = surface,
            scrimColor = Color.Black.copy(alpha = scrimOpacity),
            properties = ModalBottomSheetProperties(
                isAppearanceLightStatusBars = !night,
                isAppearanceLightNavigationBars = !night,
                shouldDismissOnBackPress = onNavigateBack == null,
            ),
            dragHandle = null,
            contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) },
        ) {
            if (roleStyle) {
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                SideEffect { keyboardDocked = imeVisible }
                // Material 已在弹窗外层扣除键盘，按窗口剩余高度给面板顶部留出安全区。
                BoxWithConstraints {
                    // 键盘停靠时弹窗可用边界会上移，顶部仍需为状态栏和面板间距留出空间。
                    val topClearance = if (imeVisible) roleTopGap + safeTop else (roleTopGap - safeTop).coerceAtLeast(0.dp)
                    panel(minOf(targetHeight, (maxHeight - topClearance).coerceAtLeast(1.dp)))
                }
            } else {
                panel(targetHeight)
            }
        }
    }
}

/** 居中标题为两侧预留对称空间，玻璃按钮的阴影可以绘制到栏外。 */
@Composable
private fun BotPanelCenteredHeader(
    title: String,
    foreground: Color,
    navigation: (@Composable () -> Unit)?,
    action: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    Layout(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .testTag("bot.panel-title").then(modifier).padding(horizontal = 4.dp),
        content = {
            Box { navigation?.invoke() }
            Box { action?.invoke() }
            Text(
                title, Modifier.testTag("bot.panel-heading"), style = SettingsTypography.title,
                color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
    ) { (navigationSlot, actionSlot, titleSlot), constraints ->
        val slotConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val leading = navigationSlot.measure(slotConstraints)
        val trailing = actionSlot.measure(slotConstraints)
        val width = constraints.maxWidth
        val sideWidth = maxOf(leading.width, trailing.width)
        val heading = titleSlot.measure(slotConstraints.copy(maxWidth = (width - 2 * sideWidth).coerceAtLeast(0)))
        val height = constraints.constrainHeight(maxOf(leading.height, trailing.height, heading.height))
        layout(width, height) {
            leading.placeRelative(0, (height - leading.height) / 2)
            trailing.placeRelative(width - trailing.width, (height - trailing.height) / 2)
            heading.placeRelative((width - heading.width) / 2, (height - heading.height) / 2)
        }
    }
}

@Composable
private fun BotPanelTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    DshGlassButton(label, onClick, modifier.widthIn(min = 68.dp).height(44.dp), enabled = enabled) {
        Text(label, Modifier.padding(horizontal = 14.dp), style = SettingsTypography.item, maxLines = 1)
    }
}

@Composable
private fun BotGroupMembersPanel(members: List<BotChat>, showProfile: (String) -> Unit, dismiss: () -> Unit) {
    val night = isSystemInDarkTheme()
    val link = if (night) Color(0xFF0A84FF) else Color(0xFF007AFF)
    BotPanel(
        appText("群成员"), dismiss, roleStyle = true, centerTitle = true, systemGroupedColors = true,
        navigation = { BotPanelTextButton(appText("关闭"), dismiss, Modifier.testTag("bot.members-close")) },
        action = {},
    ) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 28.dp, bottom = 12.dp)) {
            if (members.isNotEmpty()) item {
                BotRoleSettingsSection {
                    members.forEachIndexed { index, member ->
                        if (index != 0) Box(Modifier.padding(start = 52.dp)) { SettingsDivider() }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 69.dp).testTag("bot.member.${member.id}")
                                .clickable(role = Role.Button) { showProfile(member.id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            BotAvatar(member, 40.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(member.name, style = SettingsTypography.item, color = link)
                                if (member.role.isNotEmpty()) {
                                    Text(member.role, style = SettingsTypography.description, color = link.copy(alpha = .52f))
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
private fun BotQuestionPanel(question: BotInteraction, dismiss: () -> Unit, submit: (List<String>, String) -> Unit) {
    val night = isSystemInDarkTheme()
    val primary = if (night) Color.White else Color.Black
    val link = if (night) Color(0xFF0A84FF) else Color(0xFF007AFF)
    var selected by remember(question.id) { mutableStateOf(emptySet<String>()) }
    var custom by rememberSaveable(question.id) { mutableStateOf("") }
    BotPanel(
        appText("回答助理"), dismiss, roleStyle = true, centerTitle = true, systemGroupedColors = true,
        navigation = { BotPanelTextButton(appText("取消"), dismiss) },
        action = {
            BotPanelTextButton(appText("提交"), { submit(selected.toList(), custom) },
                Modifier.testTag("bot.question.submit"), enabled = selected.isNotEmpty() || custom.isNotBlank())
        },
    ) {
        KeyboardDismissHost(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("bot.question.form"),
                contentPadding = PaddingValues(top = 28.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(35.dp),
            ) {
                item {
                    BotRoleSettingsSection {
                        Box(Modifier.heightIn(min = 51.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            Text(question.title, style = SettingsTypography.item, color = primary)
                        }
                        if (question.detail.isNotEmpty()) {
                            SettingsDivider()
                            Box(Modifier.heightIn(min = 51.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                                Text(question.detail, style = SettingsTypography.item, color = primary.copy(alpha = .52f))
                            }
                        }
                    }
                }
                item {
                    BotRoleSettingsSection {
                        question.options.forEachIndexed { index, option ->
                            if (index > 0) SettingsDivider()
                            val checked = option in selected
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 51.dp)
                                    .selectable(checked, role = Role.Button) {
                                        selected = when {
                                            checked -> selected - option
                                            question.multiSelect -> selected + option
                                            else -> {
                                                custom = ""
                                                setOf(option)
                                            }
                                        }
                                    }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(option, Modifier.weight(1f), style = SettingsTypography.item, color = link)
                                if (checked) Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(18.dp), tint = link)
                            }
                        }
                        if (question.options.isNotEmpty()) SettingsDivider()
                        BotRoleField(
                            appText("补充回答"), custom, enabled = true, multiline = true, showsLabel = false,
                            modifier = Modifier.testTag("bot.question.custom"), minHeight = 68.dp, minLines = 2, maxLines = 6,
                        ) { custom = it }
                    }
                }
            }
        }
    }
}

private fun botErrorText(code: String): String = appText(when (code) {
    "bot-unavailable", "bot/not-found" -> "主机上的 DS-Bot 尚未启用，或版本暂不支持"
    "bot-version-unsupported" -> "请更新主机上的 DS-Bot"
    "not-connected" -> "主机连接已断开，重连后将同步聊天"
    "bot/main-protected" -> "主 Bot 不能删除。"
    "bot-delete-failed" -> "暂时无法删除此 Bot，请稍后重试。"
    "bot-delete-unconfirmed" -> "尚未确认删除结果，请重新同步后核对。"
    "bot-timeout" -> "同步超时，请稍后重试；发送中的消息请先核对"
    else -> "聊天同步暂时中断，可以重新同步"
})

@Composable
private fun BotComposer(holder: AndroidSharedStateHolder, chatId: String, quoteRequest: MutableState<String?>) {
    val state = holder.botSnapshot
    val colors = botColors()
    val night = isSystemInDarkTheme()
    val uploads = holder.botFileUploads
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editorFocus = remember(chatId) { FocusRequester() }
    val scope = rememberCoroutineScope()
    var mentionDraft by rememberSaveable(chatId, stateSaver = Saver(
        save = { it.encoded() }, restore = BotMentions::decodeDraft
    )) { mutableStateOf(BotMentionDraft()) }
    var editValue by remember(chatId) { mutableStateOf(TextFieldValue(mentionDraft.text, TextRange(mentionDraft.text.length))) }
    val draft = mentionDraft.text
    val applyMention: (BotMentionEdit) -> Unit = { edit ->
        mentionDraft = edit.draft
        editValue = TextFieldValue(edit.draft.text, TextRange(edit.cursor))
    }
    val quote = quoteRequest.value
    LaunchedEffect(quote) {
        if (quote.isNullOrBlank()) return@LaunchedEffect
        quoteRequest.value = null
        if (state.attachmentSending) return@LaunchedEffect
        applyMention(mentionDraft.insertQuote(quote, editValue.selection.start, editValue.selection.end))
        editorFocus.requestFocus()
        keyboard?.show()
    }
    val clearDraft = {
        mentionDraft = BotMentionDraft()
        editValue = TextFieldValue()
    }
    var focused by remember(chatId) { mutableStateOf(false) }
    var images by remember(chatId) { mutableStateOf<List<AndroidPreparedImage>>(emptyList()) }
    var preparingImages by remember(chatId) { mutableStateOf(false) }
    var submittedAttachment by remember(chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.lastAttachmentAck) {
        if (submittedAttachment != null && state.lastAttachmentAck == submittedAttachment) {
            clearDraft()
            images = emptyList()
            submittedAttachment = null
            focus.clearFocus()
        }
    }
    val chat = state.chats.firstOrNull { it.id == chatId }
    val session = chat?.sessionId
    val placeholder = when {
        !night || chat == null -> appText("描述你想要构建的内容")
        chat.room -> appText("发消息到群聊…")
        else -> appText("发消息给 %1\$s…", chat.name)
    }
    val canAttach = state.attachmentsSupported && state.connected && state.writable && session != null && !state.attachmentSending
    val imagesEnabled = canAttach && "images" in holder.gatewayState.capabilities && !preparingImages &&
        images.size < AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT && images.size + uploads.drafts.size < 20
    val filesEnabled = canAttach && uploads.supported && uploads.sessionId == session && !uploads.preparing &&
        !preparingImages && uploads.drafts.size + images.size < 20
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT)
    ) { uris ->
        if (uris.isNotEmpty() && imagesEnabled) {
            val remaining = minOf(AndroidImagePreprocessor.MAXIMUM_IMAGE_COUNT - images.size, 20 - images.size - uploads.drafts.size)
            if (uris.size > remaining) holder.botAttachmentError = appText("一次最多添加 20 个附件，请等待已有附件准备完成")
            else scope.launch {
                preparingImages = true
                try {
                    val processor = AndroidImagePreprocessor(context.contentResolver)
                    val prepared = images + uris.map { processor.prepare(it) }
                    if (prepared.sumOf { it.byteCount } <= AndroidImagePreprocessor.MAXIMUM_TOTAL_BYTES) {
                        images = prepared
                        holder.botAttachmentError = null
                    } else holder.botAttachmentError = appText("图片总大小超出限制")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    holder.botAttachmentError = appText("无法处理图片")
                } finally {
                    preparingImages = false
                }
            }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (filesEnabled && uris.isNotEmpty()) {
            if (uris.size + uploads.drafts.size + images.size > 20) {
                holder.botAttachmentError = appText("一次最多添加 20 个附件，请等待已有附件准备完成")
            } else scope.launch {
                holder.botAttachmentError = null
                uploads.importFiles(uris)
            }
        }
    }
    val trigger = if (focused && editValue.selection.collapsed && !state.attachmentSending) {
        mentionDraft.trigger(editValue.selection.end)
    } else null
    LaunchedEffect(holder, chatId, state.referencesSupported, trigger?.query, trigger?.quoted) {
        if (trigger != null && state.referencesSupported) {
            delay(180)
            holder.searchBotReferences(trigger.query, trigger.quoted)
        } else holder.clearBotReferenceSearch()
    }
    val hasContent = mentionDraft.hasQuotes() || draft.isNotBlank() || images.isNotEmpty() || uploads.drafts.isNotEmpty()
    val canSend = state.connected && state.supported && state.writable && hasContent && !preparingImages &&
        !uploads.preparing && !state.attachmentSending && (uploads.drafts.isEmpty() || uploads.canSend)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp)
        .keyboardDismissExclusion(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (trigger != null) {
            val matching = state.referenceSearch?.takeIf { it.chatId == chatId && it.query == trigger.query && it.quoted == trigger.quoted }
            val members = if (trigger.quoted || '/' in trigger.query) emptyList() else BotMentions.members(chatId, state.chats, trigger.query)
            BotMentionMenu(
                state = state,
                candidates = members + matching?.items.orEmpty(),
                loading = state.referencesSupported && state.connected && (matching?.loading ?: true),
                unavailable = matching?.unavailable == true,
                pick = { candidate, label -> applyMention(mentionDraft.insert(trigger, candidate, label)) },
                drill = { path -> applyMention(mentionDraft.drill(trigger, path)) }
            )
        }
        val error = holder.botAttachmentError ?: state.error?.let { botErrorText(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
        ComposerGlassSurface(Modifier.fillMaxWidth().testTag("bot.composer-card"), RoundedCornerShape(24.dp),
            surfaceTint = colors.glass.takeIf { night }, edgeTint = colors.rim.copy(alpha = 0.7f).takeIf { night }) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                val parts = remember(mentionDraft) { mentionDraft.quoteParts() }
                ComposerQuoteStack(parts = parts, visible = focused || hasContent, onRemove = { index ->
                    if (!state.attachmentSending) mentionDraft = mentionDraft.removeQuote(index)
                }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (preparingImages || uploads.preparing) {
                            Text(appText(if (preparingImages) "正在处理图片…" else "正在准备文件…"),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        if (images.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images.size) { index -> PreparedImagePreview(images[index]) {
                                if (!state.attachmentSending) images = images.filterIndexed { position, _ -> position != index }
                            } }
                        }
                        if (uploads.drafts.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(uploads.drafts, key = { it.id }) { file ->
                                PendingFileCard(file, enabled = state.writable && !state.attachmentSending,
                                    onRetry = { uploads.retry(file.id) }, onRemove = { uploads.remove(file.id) })
                            }
                        }
                        ComposerMorphLayout(
                            expanded = focused,
                            keepActionWhenCollapsed = hasContent || night,
                            leading = {
                                ConversationAttachmentMenu(imagesEnabled, filesEnabled,
                                    { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    { pickFiles.launch(arrayOf("*/*")) })
                            },
                            editor = {
                                BasicTextField(
                                    editValue, {
                                        mentionDraft = mentionDraft.replacingText(it.text)
                                        editValue = it
                                    },
                                    Modifier.fillMaxWidth().padding(horizontal = 3.dp).heightIn(min = 38.dp, max = 120.dp)
                                        .focusRequester(editorFocus).onFocusChanged { focused = it.isFocused }.testTag("bot.composer"),
                                    enabled = !state.attachmentSending,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(DshColors.Ocean),
                                    visualTransformation = VisualTransformation { text ->
                                        val styled = buildAnnotatedString {
                                            append(text)
                                            mentionDraft.spans.forEach { span ->
                                                if (span.start >= 0 && span.end <= text.length) {
                                                    addStyle(SpanStyle(color = Color(0xFF007AFF), background = Color(0x1A007AFF)), span.start, span.end)
                                                }
                                            }
                                        }
                                        TransformedText(styled, OffsetMapping.Identity)
                                    },
                                    maxLines = if (focused) Int.MAX_VALUE else 1,
                                    decorationBox = { content ->
                                        Box {
                                            if (draft.isEmpty()) Text(placeholder,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            content()
                                        }
                                    }
                                )
                            },
                            tools = {},
                            action = {
                                Box(Modifier.size(42.dp).background(if (night) botNightSend else DshColors.Ocean, CircleShape).clip(CircleShape)
                                    .clickable(enabled = canSend, role = Role.Button) {
                                        val hasAttachments = images.isNotEmpty() || uploads.drafts.isNotEmpty()
                                        holder.sendBotMessage(mentionDraft.wireText(), images, displayText = mentionDraft.displayText())?.let { requestId ->
                                            if (hasAttachments) submittedAttachment = requestId
                                            else { clearDraft(); focus.clearFocus() }
                                        }
                                    }.semantics { contentDescription = appText("发送消息") }, contentAlignment = Alignment.Center) {
                                    Icon(painterResource(R.drawable.ic_arrow_up), null, Modifier.size(24.dp),
                                        tint = Color.White.copy(alpha = if (canSend) 1f else .45f))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BotMentionMenu(
    state: SharedBotSnapshot,
    candidates: List<BotMentionCandidate>,
    loading: Boolean,
    unavailable: Boolean,
    pick: (BotMentionCandidate, String) -> Unit,
    drill: (String) -> Unit
) {
    val sections = listOf(
        appText("成员") to candidates.filter { it.kind == "member" || it.kind == "everyone" },
        appText("文件与文件夹") to candidates.filter { it.kind == "file" || it.kind == "directory" },
        appText("对话") to candidates.filter { it.kind == "session" }
    ).filter { it.second.isNotEmpty() }
    ComposerGlassSurface(Modifier.fillMaxWidth().testTag("bot.mentions"), RoundedCornerShape(20.dp)) {
        LazyColumn(Modifier.heightIn(max = 220.dp).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sections.forEach { (heading, rows) ->
                item { Text(heading, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.kind + "." + it.id }) { candidate ->
                    val label = if (candidate.kind == "everyone") appText("所有人") else candidate.label
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DshMenuItem(
                            modifier = Modifier.weight(1f).testTag("bot.mention." + candidate.kind + "." + candidate.id),
                            minHeight = 42.dp, horizontalSpacing = 10.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            onClick = { pick(candidate, label) },
                            leadingIcon = {
                                if (candidate.kind == "member") BotAvatar(state.chats.firstOrNull { it.id == candidate.id }, 22.dp, candidate.id == state.mainBotId)
                                else {
                                    val icon = when (candidate.kind) {
                                        "everyone" -> R.drawable.bot_group_avatar
                                        "directory" -> R.drawable.ic_folder_outline
                                        "session" -> R.drawable.ic_storage_chat
                                        else -> R.drawable.ic_pending_file
                                    }
                                    Icon(painterResource(icon), null, Modifier.size(22.dp))
                                }
                            }, text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    if (candidate.detail.isNotEmpty()) Text(candidate.detail, Modifier.weight(.6f), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            })
                        val path = candidate.path
                        if (path != null && candidate.kind == "directory") {
                            val description = appText("展开文件夹")
                            Box(Modifier.size(44.dp, 42.dp).clickable(role = Role.Button) { drill(path) }
                                .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            if (loading) item {
                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            } else if (candidates.isEmpty()) item {
                Text(appText(if (unavailable) "暂时无法读取引用" else "没有匹配的引用"), Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private enum class BotRolePage { Profile, Settings, Memory, MemoryEditor, Schedule, NewSchedule }

private val botRoleSettingsRowHeight = 52.dp

@Composable
private fun BotRolePanel(holder: AndroidSharedStateHolder, chat: BotChat, colors: BotColors, dismiss: () -> Unit) {
    val night = isSystemInDarkTheme()
    val state = holder.botSnapshot
    val profile = chat.profile
    var page by rememberSaveable(chat.id) { mutableStateOf(BotRolePage.Profile) }
    var name by rememberSaveable(chat.id) { mutableStateOf(chat.name) }
    var tag by rememberSaveable(chat.id) { mutableStateOf(chat.role) }
    var instructions by rememberSaveable(chat.id) { mutableStateOf(profile?.instructions.orEmpty()) }
    var model by rememberSaveable(chat.id) { mutableStateOf(profile?.model.orEmpty()) }
    var effort by rememberSaveable(chat.id) { mutableStateOf(profile?.effort.orEmpty()) }
    var workDir by rememberSaveable(chat.id) { mutableStateOf(profile?.workDir.orEmpty()) }
    var permission by rememberSaveable(chat.id) { mutableStateOf(profile?.permission.orEmpty()) }
    var privateRole by rememberSaveable(chat.id) { mutableStateOf(profile?.privateRole ?: false) }
    var confirm by remember { mutableStateOf(false) }
    var confirmCopy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<BotChat?>(null) }
    var returnTo by rememberSaveable(chat.id) { mutableStateOf(BotRolePage.Profile) }
    val pageScroll = BotRolePage.entries.map { rememberLazyListState() }
    val pageScope = rememberCoroutineScope()
    var memoryEntryId by rememberSaveable(chat.id) { mutableStateOf<String?>(null) }
    var memoryText by rememberSaveable(chat.id) { mutableStateOf("") }
    var deleteMemory by remember { mutableStateOf<BotMemory?>(null) }
    var deleteSchedule by remember { mutableStateOf<BotSchedule?>(null) }
    var taskName by rememberSaveable { mutableStateOf("") }
    var taskPrompt by rememberSaveable { mutableStateOf("") }
    var taskTime by rememberSaveable { mutableStateOf("") }
    var revealedRowId by remember { mutableStateOf<String?>(null) }
    val editable = state.connected && state.roleSettingsSupported && state.writable && !state.roleSaving
    val canEditSchedule = editable && state.schedulesAvailable
    val main = chat.id == state.mainBotId
    val showsDelete = state.botDeletionSupported && !state.isMainBot(chat.id)
    val inGroup = state.chats.any { it.room && chat.id in it.memberIds }
    val privacyHelp = when {
        !privateRole && main -> appText("主 Bot 不能开启隐私。")
        !privateRole && inGroup -> appText("将此 Bot 移出所有群聊后，才能开启隐私。")
        !privateRole && workDir.isBlank() -> appText("开启隐私前，请为此 Bot 设置独立的工作文件夹。")
        else -> appText("开启后，其他 Bot 无法读取它的工作文件夹、记忆和对话。向其他 Bot 分享内容需你批准。")
    }
    val permissionFooter = if (permission == "danger-full-access") {
        appText("允许修改工作区以外的文件，范围仍受主机系统权限限制。") + "\n" + privacyHelp
    } else privacyHelp
    val effectiveModel = profile?.model ?: profile?.appliedModel ?: state.defaultModel
    val effortRef = model.ifEmpty { profile?.appliedModel ?: state.defaultModel.orEmpty() }
    val openPage: (BotRolePage) -> Unit = { target ->
        returnTo = page
        revealedRowId = null
        page = target
        pageScope.launch { pageScroll[target.ordinal].scrollToItem(0) }
    }
    val details = state.roleDetails?.takeIf { it.botId == chat.id }
    val schedules = state.schedules.filter { it.botId == chat.id }
    val changes = buildMap<String, Any?> {
        if (name != chat.name) put("name", name)
        if (tag != chat.role) put("role", tag)
        if (instructions != profile?.instructions.orEmpty()) put("instructions", instructions)
        if (model != profile?.model.orEmpty()) put("model", model.ifEmpty { null })
        if (effort != profile?.effort.orEmpty()) put("effort", effort.ifEmpty { null })
        if (workDir != profile?.workDir.orEmpty()) put("workDir", workDir.ifEmpty { null })
        if (permission != profile?.permission.orEmpty()) put("permission", permission.ifEmpty { null })
        if (privateRole != (profile?.privateRole ?: false)) put("private", privateRole)
    }
    val save = { holder.updateBotRole(chat.id, changes) }
    LaunchedEffect(chat.id) { holder.openBotRole(chat.id) }
    LaunchedEffect(page, effortRef) { if (page == BotRolePage.Settings && effortRef.isNotBlank()) holder.loadBotRoleEfforts(effortRef) }
    val title = when (page) {
        BotRolePage.Profile -> ""
        BotRolePage.Settings -> appText("角色设置")
        BotRolePage.Memory -> appText("记忆")
        BotRolePage.MemoryEditor -> appText(if (memoryEntryId == null) "添加记忆" else "编辑记忆")
        BotRolePage.Schedule -> appText("定时任务")
        BotRolePage.NewSchedule -> appText("新建定时任务")
    }
    val goBack = {
        revealedRowId = null
        page = when (page) {
            BotRolePage.NewSchedule -> BotRolePage.Schedule
            BotRolePage.MemoryEditor -> BotRolePage.Memory
            BotRolePage.Memory, BotRolePage.Schedule -> returnTo
            else -> BotRolePage.Profile
        }
    }
    val showsMemory = page == BotRolePage.Memory
    val showsSchedule = page == BotRolePage.Schedule
    val showsScheduleForm = page == BotRolePage.NewSchedule
    val compactRole = night && (page == BotRolePage.Profile ||
        (page != BotRolePage.Settings && returnTo == BotRolePage.Profile))
    BotPanel(title, dismiss, roleStyle = true, compactRole = compactRole,
        centerTitle = page != BotRolePage.Profile, systemGroupedColors = showsMemory || showsSchedule || showsScheduleForm,
        onNavigateBack = goBack.takeIf { page != BotRolePage.Profile },
        navigation = {
        if (page == BotRolePage.Profile) {
            if (!night) {
                BotPanelTextButton(appText("关闭"), dismiss, Modifier.testTag("bot.role-close"))
            }
        } else TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), goBack, Modifier.testTag("bot.role-back"))
    }, action = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page == BotRolePage.Profile) {
                TopBarCircleButton(R.drawable.ic_settings_outline, appText("角色设置"), { page = BotRolePage.Settings },
                    Modifier.testTag("bot.role-settings"))
                if (night) TopBarCircleButton(R.drawable.ic_close, appText("关闭"), dismiss, Modifier.testTag("bot.role-close"))
            } else {
                if (page == BotRolePage.Settings) {
                    if (state.roleSaving) {
                        Box(Modifier.width(68.dp).height(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    } else if (night && changes.isEmpty()) TopBarCircleButton(R.drawable.ic_close, appText("关闭"), dismiss)
                    else DshGlassButton(appText("保存"),
                        { if (changes["permission"] == "danger-full-access") confirm = true else save() },
                        Modifier.widthIn(min = 68.dp).height(44.dp).testTag("bot.role-save"),
                        enabled = editable && changes.isNotEmpty() && name.isNotBlank()) {
                        Text(appText("保存"), Modifier.padding(horizontal = 14.dp), style = SettingsTypography.item, maxLines = 1)
                    }
                }
                if (showsMemory) {
                    DshGlassButton(appText("添加记忆"), {
                        memoryEntryId = null
                        memoryText = ""
                        revealedRowId = null
                        page = BotRolePage.MemoryEditor
                    }, Modifier.size(44.dp).testTag("bot.role-memory-add"), enabled = editable) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.size(22.dp))
                    }
                }
                if (page == BotRolePage.MemoryEditor) {
                    DshGlassButton(appText("保存"), {
                        holder.saveBotMemory(chat.id, memoryEntryId, memoryText)
                        page = BotRolePage.Memory
                    }, Modifier.widthIn(min = 68.dp).height(44.dp).testTag("bot.memory-save"),
                        enabled = editable && memoryText.isNotBlank()) {
                        Text(appText("保存"), Modifier.padding(horizontal = 14.dp), style = SettingsTypography.item, maxLines = 1)
                    }
                }
                if (showsSchedule) {
                    DshGlassButton(appText("新建定时任务"), {
                        taskName = ""
                        taskPrompt = ""
                        taskTime = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                        revealedRowId = null
                        page = BotRolePage.NewSchedule
                        pageScope.launch { pageScroll[BotRolePage.NewSchedule.ordinal].scrollToItem(0) }
                    }, Modifier.size(44.dp).testTag("bot.role-schedule-add"), enabled = canEditSchedule) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.size(22.dp))
                    }
                }
                if (showsScheduleForm) {
                    DshGlassButton(appText("保存"), {
                        holder.scheduleBotDaily(chat.id, taskName, taskPrompt, taskTime)
                        page = BotRolePage.Schedule
                    }, Modifier.widthIn(min = 68.dp).height(44.dp).testTag("bot.role-schedule-save"),
                        enabled = canEditSchedule && taskName.isNotBlank() && taskPrompt.isNotBlank()) {
                        Text(appText("保存"), Modifier.padding(horizontal = 14.dp), style = SettingsTypography.item, maxLines = 1)
                    }
                }
            }
        }
    }) {
        if (page == BotRolePage.MemoryEditor) {
            KeyboardDismissHost(Modifier.weight(1f).fillMaxWidth().imePadding().padding(top = 8.dp, bottom = 16.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(colors.row).padding(12.dp)) {
                    BotRoleField(
                        appText("记忆"), memoryText, enabled = true, multiline = true,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp).testTag("bot.memory-editor")
                    ) { memoryText = it }
                }
            }
            return@BotPanel
        }
        KeyboardDismissHost(Modifier.weight(1f).fillMaxWidth()) {
            val showsProfile = page == BotRolePage.Profile
            val showsSettings = page == BotRolePage.Settings
            LazyColumn(Modifier.fillMaxSize().testTag("bot.role-panel"), state = pageScroll[page.ordinal],
                verticalArrangement = Arrangement.spacedBy(when {
                    showsProfile -> 35.dp
                    showsSettings -> 15.dp
                    showsMemory || showsSchedule -> 0.dp
                    else -> 12.dp
                }),
                contentPadding = PaddingValues(top = if (showsMemory || showsSchedule || showsScheduleForm) 28.dp else 0.dp,
                    bottom = 12.dp)) {
                if (showsProfile) item {
                    Column(Modifier.fillMaxWidth().padding(top = 52.dp, bottom = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotAvatar(chat, 80.dp, main)
                        Text(chat.name, style = SettingsTypography.item.copy(fontSize = 32.sp, lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold), color = colors.text, textAlign = TextAlign.Center)
                        if (chat.role.isNotEmpty()) {
                            Text(chat.role, style = SettingsTypography.item.copy(fontSize = 15.sp, lineHeight = 18.sp),
                                color = colors.secondary, textAlign = TextAlign.Center)
                        }
                        if (!profile?.instructions.isNullOrBlank()) {
                            Text(profile.instructions, style = SettingsTypography.description.copy(fontSize = 13.sp),
                                color = colors.secondary, textAlign = TextAlign.Center)
                        }
                    }
                }
                when (page) {
                    BotRolePage.Profile -> {
                        val rowHeight = 52.dp
                        item { SettingsSection(horizontalPadding = 20.dp, cornerRadius = 26.dp) {
                            SettingsValueRow(
                                appText("模型"),
                                state.models.firstOrNull { it.id == effectiveModel }?.title ?: effectiveModel ?: "—",
                                minHeight = rowHeight,
                            )
                            SettingsDivider()
                            SettingsValueRow(
                                appText("权限"),
                                state.permissions.firstOrNull { it.id == profile?.permission }?.title ?: profile?.permission ?: "—",
                                minHeight = rowHeight,
                            )
                            SettingsDivider()
                            SettingsValueRow(
                                appText("工作文件夹"), profile?.workDir ?: appText("总工作区"),
                                minHeight = rowHeight, valueMaxLines = Int.MAX_VALUE,
                            )
                        } }
                        item { SettingsSection(horizontalPadding = 20.dp, cornerRadius = 26.dp) {
                            if (state.memoryAvailable) {
                                SettingsValueRow(appText("记忆"), details?.memories?.size?.toString() ?: "—", minHeight = rowHeight) {
                                    openPage(BotRolePage.Memory)
                                }
                                SettingsDivider()
                            }
                            SettingsValueRow(appText("定时任务"), schedules.size.toString(), minHeight = rowHeight) {
                                openPage(BotRolePage.Schedule)
                            }
                            details?.let { value ->
                                val input = value.inputTokens
                                val output = value.outputTokens
                                if (input != null && output != null) {
                                    SettingsDivider()
                                    SettingsValueRow(appText("近 30 天用量"),
                                        java.text.NumberFormat.getInstance().format(input + output), minHeight = rowHeight)
                                }
                            }
                        } }
                    }
                    BotRolePage.Settings -> {
                        item {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 44.dp, bottom = 27.dp)
                                .testTag("bot.role-settings-identity"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                BotAvatar(chat, 56.dp, main)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(chat.name, style = SettingsTypography.title.copy(fontSize = 22.sp, lineHeight = 28.sp,
                                        fontWeight = FontWeight.Bold), color = colors.text)
                                    if (chat.role.isNotEmpty()) {
                                        Text(chat.role, style = SettingsTypography.description, color = colors.secondary)
                                    }
                                }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("资料"), footer = appText("标签为选填，会显示在 Bot 名字下方。")) {
                                BotRoleField(appText("名字"), name, editable, minHeight = botRoleSettingsRowHeight) { name = it }
                                SettingsDivider()
                                BotRoleField(appText("标签"), tag, editable, minHeight = botRoleSettingsRowHeight) { tag = it }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("模型"), footer = appText("模型更改从下一条消息开始生效。")) {
                                BotRoleSelector(appText("模型"), model, appText("默认模型"), state.models, editable,
                                    minHeight = botRoleSettingsRowHeight, modifier = Modifier.testTag("bot.role-model-picker")) {
                                    if (model != it) { model = it; effort = "" }
                                }
                                val efforts = state.efforts.takeIf { state.effortModel == effortRef }.orEmpty()
                                if (efforts.isNotEmpty() || effort.isNotEmpty()) {
                                    SettingsDivider()
                                    BotRoleSelector(appText("推理强度"), effort, appText("默认"), efforts, editable,
                                        minHeight = botRoleSettingsRowHeight) { effort = it }
                                }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("工作区与权限"), footer = permissionFooter) {
                                BotRoleField(appText("工作文件夹"), workDir, editable, appText("总工作区"),
                                    minHeight = botRoleSettingsRowHeight) { workDir = it }
                                SettingsDivider()
                                BotRoleSelector(appText("权限"), permission, appText("默认权限"), state.permissions, editable,
                                    minHeight = botRoleSettingsRowHeight) {
                                    permission = it
                                }
                                SettingsDivider()
                                val canTogglePrivacy = editable && !main
                                Row(Modifier.fillMaxWidth().heightIn(min = botRoleSettingsRowHeight)
                                    .toggleable(privateRole, enabled = canTogglePrivacy, role = Role.Switch, onValueChange = { privateRole = it }),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(appText("隐私"), Modifier.weight(1f), style = SettingsTypography.item)
                                    DshSwitch(privateRole, onCheckedChange = null, enabled = canTogglePrivacy, wideTrack = true)
                                }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("说明"), footer = appText("这段说明会写入 Bot 的系统提示词。")) {
                                BotRoleField(appText("说明"), instructions, editable, appText("这个 Bot 负责什么…"),
                                    multiline = true) { instructions = it }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("操控"), footer = appText("各 Bot 的电脑操控授权可在 DS Bot 网页端管理。")) {
                                SettingsValueRow(appText("电脑操控"), appText(if (state.computerEnabled) "已开启" else "已关闭"),
                                    minHeight = botRoleSettingsRowHeight)
                            }
                        }
                        item {
                            BotRoleSettingsSection {
                                if (state.memoryAvailable) {
                                    SettingsValueRow(appText("记忆"), "", enabled = editable, minHeight = botRoleSettingsRowHeight) { openPage(BotRolePage.Memory) }
                                    SettingsDivider()
                                }
                                SettingsValueRow(appText("定时任务"), "", enabled = editable, minHeight = botRoleSettingsRowHeight) { openPage(BotRolePage.Schedule) }
                            }
                        }
                        if (main) item {
                            BotRoleSettingsSection(appText("主 Bot"), footer = appText("主 Bot 可以创建和修改团队 Bot，并管理群聊。")) {
                                val candidates = state.chats.filter { !it.room && it.id != chat.id && it.profile?.privateRole != true }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    SettingsValueRow(appText("更换主 Bot"), "", enabled = editable && candidates.isNotEmpty(),
                                        minHeight = botRoleSettingsRowHeight, showIndicator = false) { menu = true }
                                    DshMenu(menu, { menu = false }) {
                                        candidates.forEach { candidate ->
                                            DshMenuItem({ Text(candidate.name) }, { menu = false; replacement = candidate })
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            BotRoleSettingsSection(appText("管理"), footer = appText("复制分工和模型，新 Bot 从空白记忆开始。")) {
                                SettingsValueRow(appText("复制一份"), "", enabled = editable,
                                    minHeight = botRoleSettingsRowHeight, showIndicator = false) { confirmCopy = true }
                                if (showsDelete) {
                                    SettingsDivider()
                                    SettingsValueRow(
                                        appText("删除 Bot"), "", enabled = editable,
                                        modifier = Modifier.testTag("bot.role-delete"), destructive = true, minHeight = botRoleSettingsRowHeight,
                                        onClick = { confirmDelete = true }
                                    )
                                }
                            }
                        }
                    }
                    BotRolePage.Memory -> {
                        val memories = details?.memories.orEmpty()
                        if (memories.isEmpty()) item {
                            BotRoleSettingsSection {
                                Text(appText("还没有记忆"), Modifier.padding(vertical = 16.dp),
                                    style = SettingsTypography.item, color = colors.secondary)
                            }
                        }
                        itemsIndexed(memories, key = { _, memory -> memory.id }) { index, memory ->
                            BotRoleListRow(
                                modifier = Modifier.testTag("bot.role-memory.${memory.id}"),
                                first = index == 0,
                                last = index == memories.lastIndex,
                                canDelete = editable,
                                revealed = revealedRowId == memory.id,
                                onReveal = { revealedRowId = memory.id },
                                onClose = { revealedRowId = null },
                                onDelete = { deleteMemory = memory },
                                onClick = if (editable) ({
                                    memoryEntryId = memory.id
                                    memoryText = memory.text
                                    revealedRowId = null
                                    page = BotRolePage.MemoryEditor
                                }) else null,
                            ) {
                                Box(Modifier.heightIn(min = 52.dp).padding(vertical = 12.dp),
                                    contentAlignment = Alignment.CenterStart) {
                                    Text(memory.text, style = SettingsTypography.item, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                    BotRolePage.MemoryEditor -> Unit
                    BotRolePage.Schedule -> {
                        if (schedules.isEmpty()) item {
                            BotRoleSettingsSection {
                                Text(appText("还没有定时任务"), Modifier.padding(vertical = 16.dp),
                                    style = SettingsTypography.item, color = colors.secondary)
                            }
                        }
                        itemsIndexed(schedules, key = { _, schedule -> schedule.id }) { index, schedule ->
                            BotRoleListRow(
                                modifier = Modifier.testTag("bot.role-schedule.${schedule.id}"),
                                first = index == 0,
                                last = index == schedules.lastIndex,
                                canDelete = canEditSchedule && !schedule.sessionId.isNullOrBlank(),
                                revealed = revealedRowId == schedule.id,
                                onReveal = { revealedRowId = schedule.id },
                                onClose = { revealedRowId = null },
                                onDelete = { deleteSchedule = schedule },
                            ) {
                                BotRoleScheduleText(schedule.title, schedule.next)
                            }
                        }
                    }
                    BotRolePage.NewSchedule -> item {
                        BotRoleSettingsSection {
                            BotRoleField(appText("任务名称"), taskName, canEditSchedule, showsLabel = false,
                                modifier = Modifier.testTag("bot.role-schedule-name"), minHeight = 52.dp) { taskName = it }
                            SettingsDivider()
                            BotRoleField(appText("任务内容"), taskPrompt, canEditSchedule, multiline = true, showsLabel = false,
                                modifier = Modifier.testTag("bot.role-schedule-prompt"), minHeight = 52.dp) { taskPrompt = it }
                            SettingsDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(appText("每天运行时间"), Modifier.weight(1f), style = SettingsTypography.item)
                                DshCompactTimePicker(taskTime, canEditSchedule, Modifier.testTag("bot.role-schedule-time")) {
                                    taskTime = it
                                }
                            }
                        }
                    }
                }
                state.roleError?.let { error -> item { Text(botErrorText(error), color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    deleteMemory?.let { entry ->
        BotRoleConfirmation(appText("删除这条记忆？"), appText("删除"), { deleteMemory = null }, destructive = true) {
            holder.forgetBotMemory(chat.id, entry.id)
        }
    }
    deleteSchedule?.let { task ->
        DshAlertDialog(
            title = appText("删除这个定时任务？"),
            onDismissRequest = { deleteSchedule = null },
            dismissLabel = appText("取消"),
            confirmLabel = appText("删除"),
            confirmDestructive = true,
            confirmEnabled = canEditSchedule,
            onConfirm = {
                deleteSchedule = null
                holder.deleteBotSchedule(chat.id, task.id)
            },
        )
    }
    replacement?.let { candidate ->
        BotRoleConfirmation(appText("更换主 Bot？"), appText("确认"), { replacement = null }) {
            holder.replacePrimaryBotRole(chat.id, candidate.id)
        }
    }
    if (confirmCopy) BotRoleConfirmation(appText("复制这个角色？"), appText("复制一份"), { confirmCopy = false }) {
        holder.duplicateBotRole(chat.id)
    }
    if (confirmDelete) DshAlertDialog(
        title = appText("删除 Bot？"),
        message = String.format(appText("将“%s”移出团队和所在群聊。"), chat.name),
        onDismissRequest = { confirmDelete = false },
        dismissLabel = appText("取消"),
        confirmLabel = appText("删除"),
        confirmDestructive = true,
        confirmEnabled = editable && showsDelete,
        onConfirm = {
            confirmDelete = false
            holder.deleteBotRole(chat.id)
        }
    )
    if (confirm) DshAlertDialog(
        title = appText("确认授予完全权限？"),
        onDismissRequest = { confirm = false },
        dismissLabel = appText("取消"),
        confirmLabel = appText("确认"),
        confirmDestructive = true,
        onConfirm = { confirm = false; save() }
    )
}

@Composable
private fun BotRoleSettingsSection(
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SettingsSection(title, footer, horizontalPadding = 20.dp, cornerRadius = 26.dp,
        headingStyle = SettingsTypography.item, content = content)
}

@Composable
private fun BotRoleField(title: String, value: String, enabled: Boolean, placeholder: String = title,
    multiline: Boolean = false, modifier: Modifier = Modifier, minHeight: Dp = 44.dp,
    showsLabel: Boolean = true, minLines: Int = 1, maxLines: Int = Int.MAX_VALUE, change: (String) -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = SettingsTypography.item.copy(color = color.copy(alpha = if (enabled) 1f else 0.40f))
    val leading = multiline || !showsLabel
    val editor: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(value, change, fieldModifier.keyboardDismissExclusion().semantics { contentDescription = title }, enabled = enabled,
            singleLine = !multiline, minLines = minLines, maxLines = maxLines, textStyle = style.copy(
                textAlign = if (leading) TextAlign.Start else TextAlign.End
            ), decorationBox = { inner ->
                Box(contentAlignment = when {
                    multiline -> Alignment.TopStart
                    leading -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                }) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = color.copy(alpha = 0.30f))
                    inner()
                }
            })
    }
    if (!showsLabel) {
        editor(modifier.fillMaxWidth().heightIn(min = minHeight).wrapContentHeight().padding(vertical = 10.dp))
    } else if (multiline) {
        editor(modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = 8.dp))
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = minHeight), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = SettingsTypography.item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            editor(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BotRoleSelector(title: String, selected: String, fallback: String, options: List<BotRoleOption>,
    enabled: Boolean, minHeight: Dp = 44.dp, modifier: Modifier = Modifier, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = remember(options, selected, fallback) {
        buildList {
            add(DshSelectionOption("", fallback))
            if (selected.isNotEmpty() && options.none { it.id == selected }) add(DshSelectionOption(selected, selected))
            addAll(options.map { DshSelectionOption(it.id, it.title) })
        }
    }
    Box {
        SettingsValueRow(
            title = title,
            value = options.firstOrNull { it.id == selected }?.title ?: selected.ifEmpty { fallback },
            modifier = modifier,
            enabled = enabled,
            isMenu = true,
            minHeight = minHeight,
            emphasizeSelection = true,
        ) { expanded = true }
        DshSelectionPopup(
            expanded = expanded && enabled,
            options = choices,
            selectedKey = selected,
            onDismissRequest = { expanded = false },
            onSelect = { key -> expanded = false; select(key) },
            alignToEnd = true,
            menuWidth = 240.dp,
            labelMaxLines = 2,
        )
    }
}


@Composable
private fun BotRoleConfirmation(title: String, confirmLabel: String, dismiss: () -> Unit,
    destructive: Boolean = false, confirm: () -> Unit) {
    DshAlertDialog(title, dismiss, dismissLabel = appText("取消"), confirmLabel = confirmLabel,
        confirmDestructive = destructive, onConfirm = { dismiss(); confirm() })
}
