package com.clarklevis.dsh.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
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
import com.clarklevis.dsh.shared.facade.BotChatMessage
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
@Composable
private fun Modifier.botHomeGlass(shape: androidx.compose.ui.graphics.Shape): Modifier =
    dshClearCardGlass(shape, if (isSystemInDarkTheme()) botNightColors.row else null)

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
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var chatSearch by rememberSaveable { mutableStateOf("") }
    var schedules by rememberSaveable { mutableStateOf(false) }
    var roster by rememberSaveable { mutableStateOf(false) }
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
                SideEffect { onChatOpened(opened != null || roster) }
                BackHandler(enabled = opened != null) { opened = null }
                val homeHeader: @Composable () -> Unit = header ?: {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WorkspaceHeader(holder.gatewayState, {}, {}, {}, openDrawer, onDarkBackground = false)
                        GatewaySwitcherBar(onDarkBackground = false)
                    }
                }
                DshLiquidGlassHost(modifier = Modifier.fillMaxSize(), frostOnOlderDevices = opened == null && !roster, background = { backdrop ->
                    if (opened == null && !roster) HarnessAnimatedBackground(backdrop,
                        animationEnabled = scrollEnabled && !holder.botDesignPreview, lavender = true,
                        modeWave = modeWave)
                    else Box(backdrop.fillMaxSize().background(chatSurface))
                }) {
                    val select: (String) -> Unit = { focus.clearFocus(); holder.selectBotChat(it); opened = it }
                    if (roster) {
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
                                    BotChatHeader(state, chatId, { focus.clearFocus(); opened = null; holder.selectBotChat(null) },
                                        { schedules = true }, holder::refreshBotMode, showProfile) { memberRoomId = chatId }
                                }
                            }
                        }
                    } else if (wide) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().adaptiveContentWidth(AdaptiveLayout.WorkspaceWidth)
                            .padding(horizontal = 40.dp).padding(top = 18.dp)) {
                            homeHeader()
                            Spacer(Modifier.height(40.dp))
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                BotHome(state, colors, Modifier.width(leftWidth), wide = true, header = {}, scrollEnabled,
                                    select, { schedules = true }, { roster = true }, holder::refreshBotMode, showProfile)
                                BotWideChats(state, colors, chatSearch, { chatSearch = it }, select, scrollEnabled,
                                    Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else BotHome(state, colors, Modifier.fillMaxSize().statusBarsPadding(), wide = false,
                        header = homeHeader, scrollEnabled, select, { schedules = true }, { roster = true }, holder::refreshBotMode, showProfile)
                }
                imagePreview?.let { image ->
                    val attachment = com.clarklevis.dsh.shared.protocol.GatewayImageAttachment("bot-preview", image.mediaType, image.bytes.size, image.width, image.height, "image." + when (image.mediaType) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/bmp" -> "bmp"; else -> "png" })
                    ConversationImagePreviewDialog(ConversationImagePreviewRequest(listOf(attachment), 0),
                        mapOf(attachment.attachmentId to image.image), { imagePreview = null }, { image.bytes }, markdownStyle = true)
                }
                memberRoomId?.let { roomId ->
                    val members = state.chats.firstOrNull { it.id == roomId }?.memberIds.orEmpty()
                    BotPanel(appText("群成员"), { memberRoomId = null }) {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(members.mapNotNull { memberId -> state.chats.firstOrNull { it.id == memberId } }, key = { it.id }) { member ->
                                BotCard(colors, { memberRoomId = null; showProfile(member.id) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        BotAvatar(member, 40.dp)
                                        Column { Text(member.name); Text(member.role, color = colors.secondary) }
                                    }
                                }
                            }
                        }
                    }
                }
                role?.let { BotRolePanel(holder, it, colors) { roleId = null } }
                if (schedules) BotSchedulesPanel(state, holder::refreshBotMode) { schedules = false }

            }
        }
    }
}

@Composable
private fun BotHome(state: SharedBotSnapshot, colors: BotColors, modifier: Modifier, wide: Boolean,
    header: @Composable () -> Unit, scrollEnabled: Boolean, select: (String) -> Unit,
    schedules: () -> Unit, roster: () -> Unit, refresh: () -> Unit, showProfile: (String) -> Unit) {
    val chief = state.chats.firstOrNull { it.id == state.mainBotId }
    val running = state.chats.filter { it.running }
    LazyColumn(modifier.testTag("bot.home"), userScrollEnabled = scrollEnabled,
        contentPadding = if (wide) PaddingValues(bottom = 32.dp) else PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!wide) {
            item { header() }
            item { Spacer(Modifier.height(44.dp)) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(if (wide) 6.dp else 3.dp)) {
                Text(appText("个人助理"), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = colors.text)
                Text(appText("今天也交给我吧。"), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).botHomeGlass(RoundedCornerShape(22.dp))
                .clickable(enabled = chief != null, role = Role.Button) { chief?.let { showProfile(it.id) } }.testTag("bot.chief")
                .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotAvatar(chief, 44.dp, main = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chief?.name ?: appText("个人助理"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(5.dp).background(if (chief?.running == true) DshColors.Success else colors.purple, CircleShape))
                        Text(if (chief?.running == true) appText("正在为你推进 %1\$s 件事", running.size) else appText("随时可以找我聊聊"), fontSize = 12.sp, lineHeight = 17.sp, color = colors.cardAccent)
                    }
                }
            }
        }
        item {
            DshGlassButton(chief?.let { appText("与 %1\$s 聊聊", it.name) } ?: appText("与助理聊聊"),
                { chief?.let { select(it.id) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("bot.start-chat"),
                enabled = chief != null, shape = RoundedCornerShape(26.dp), appearanceDark = isSystemInDarkTheme(), clearCard = true,
                clearCardTint = if (isSystemInDarkTheme()) colors.row else null) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_bot_conversation), null, Modifier.size(25.2422.dp, 20.1094.dp), tint = colors.text)
                    Text(chief?.let { appText("与 %1\$s 聊聊", it.name) } ?: appText("与助理聊聊"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                }
            }
        }
        if (!state.connected || !state.supported || state.error != null) item {
            BotCard(colors, homeGlass = true) {
                Text(if (!state.connected) appText("连接主机后查看个人助理") else if (!state.supported) appText("请更新主机上的移动网关以使用 Bot 模式") else botErrorText(state.error.orEmpty()), color = colors.cardSecondary, fontSize = 15.sp)
                if (state.connected && state.supported) Text(appText("重新同步"), Modifier.clickable(onClick = refresh).padding(vertical = 6.dp), color = colors.purple)
            }
        }
        if (state.loading && state.chats.isEmpty()) item { Text(appText("正在同步助理"), color = colors.secondary) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotShortcut(appText("我的 Bot"), state.chats.count { !it.room }, appText("查看与创建"), Modifier.weight(1f).testTag("bot.roster"), roster)
                BotShortcut(appText("定时任务"), state.schedules.size, state.schedules.firstOrNull()?.next?.let { appText("下次 %1\$s", it) } ?: appText("查看安排"), Modifier.weight(1f).testTag("bot.schedules"), schedules)
            }
        }
        if (!wide) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BotSectionTitle(appText("聊天"), appText("群聊与私聊"), colors)
                BotChatRows(state.chats.filter { it.id != state.mainBotId }, state.chats, select)
            }
        }
    }
}

@Composable
private fun BotWideChats(state: SharedBotSnapshot, colors: BotColors, query: String, onQueryChange: (String) -> Unit,
    select: (String) -> Unit, scrollEnabled: Boolean, modifier: Modifier = Modifier) {
    val term = query.trim()
    val night = isSystemInDarkTheme()
    val chats = state.chats.filter { it.id != state.mainBotId && (term.isEmpty() ||
        it.name.contains(term, ignoreCase = true) || it.preview.contains(term, ignoreCase = true)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BotSectionTitle(appText("聊天"), if (term.isEmpty()) appText("群聊与私聊") else appText("%1\$s 个结果", chats.size), colors)
        SessionSearch(query, onQueryChange, placeholder = appText("搜索聊天名称或内容"), appearanceDark = false,
            modifier = Modifier.testTag("bot.chat-search"), tint = colors.glass.takeIf { night }, rim = colors.rim)
        LazyColumn(Modifier.weight(1f).testTag("bot.wide-chats"), userScrollEnabled = scrollEnabled,
            contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                if (chats.isEmpty() && term.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().botHomeGlass(RoundedCornerShape(24.dp))
                        .heightIn(min = 170.dp).padding(16.dp).testTag("bot.search-empty"),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(appText("没有找到相关聊天"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                        Text(appText("换个关键词试试"), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary)
                    }
                } else BotChatRows(chats, state.chats, select)
            }
        }
    }
}

@Composable
private fun BotChatRows(chats: List<BotChat>, roster: List<BotChat>, select: (String) -> Unit) {
    val colors = botColors()
    Column(Modifier.fillMaxWidth().botHomeGlass(RoundedCornerShape(24.dp))) {
        if (chats.isEmpty()) Text(appText("还没有可用的 Bot，请先在主机上配置助理"), Modifier.padding(16.dp), fontSize = 15.sp, color = colors.cardSecondary)
        chats.forEachIndexed { index, chat ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { select(chat.id) }.testTag("bot.chat.${chat.id}")
                .padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotAvatar(chat, 48.dp, roster = roster)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(chat.name, style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chat.preview.ifBlank { if (chat.room) appText("群聊") else chat.role }, style = TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)), fontSize = 15.sp, lineHeight = 21.sp, color = colors.cardSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chat.lastAt > 0) Text(remember(chat.lastAt) { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(chat.lastAt.toLong())) }, fontSize = 11.sp, color = colors.cardSecondary)
            }
            if (index < chats.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp), thickness = .5.dp,
                color = if (isSystemInDarkTheme()) colors.line else colors.text.copy(alpha = 0.13f))
        }
    }
}

@Composable private fun BotShortcut(title: String, count: Int, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = botColors()
    Column(modifier.heightIn(min = 66.dp).botHomeGlass(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
    val surface = if (homeGlass) Modifier.botHomeGlass(shape) else Modifier.clip(shape).background(colors.row)
    Column(modifier.fillMaxWidth().then(surface)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = vertical), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}
@Composable
internal fun BotAvatar(chat: BotChat?, size: Dp = 28.dp, main: Boolean = false, roster: List<BotChat> = emptyList()) {
    val colors = botColors()
    val members = if (chat?.room == true) chat.memberIds.mapNotNull { id -> roster.firstOrNull { it.id == id } } else emptyList()
    if (members.isNotEmpty()) {
        Box(Modifier.size(size)) {
            BotAvatarLayout.seats(members.size).forEachIndexed { index, seat ->
                val part = size * seat.side.toFloat()
                Box(Modifier.offset(size * seat.x.toFloat(), size * seat.y.toFloat()).size(part)
                    .background(if (isSystemInDarkTheme()) colors.line else Color.White, CircleShape)
                    .padding(1.dp).background(colors.soft, CircleShape),
                    contentAlignment = Alignment.Center) {
                    when {
                        index == 3 && members.size > 4 -> Text("+${members.size - 3}", fontSize = (part.value * .36f).sp)
                        index < members.size -> BotFace(members[index], part - 2.dp, false, circle = true)
                        else -> Icon(painterResource(R.drawable.bot_group_avatar), null,
                            Modifier.size(part * .7f), tint = colors.purple)
                    }
                }
            }
        }
    } else BotFace(chat, size, main)
}

@Composable
private fun BotFace(chat: BotChat?, size: Dp, main: Boolean, circle: Boolean = false) {
    val colors = botColors()
    val cache = PluginIconImageCache.shared(LocalContext.current)
    val data = chat?.image
    val bitmap by key(chat?.id) {
        produceState(cache.peekDataUrl(data), data) { value = cache.loadDataUrl(data) }
    }
    Box(Modifier.size(size).clip(if (circle) CircleShape else RoundedCornerShape(size / 3)).background(colors.soft),
        contentAlignment = Alignment.Center) {
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
            Row(Modifier.clickable(enabled = chat != null) {
                if (chat?.room == true) showMembers() else chat?.let { showProfile(it.id) }
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                DshMenu(menu, { menu = false }) {
                    DshMenuItem({ Text(appText("重新同步")) }, { menu = false; refresh() })
                    DshMenuItem({ Text(appText("定时任务")) }, { menu = false; schedules() })
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
    val firstMessageIndex = if (state.hasEarlier) 1 else 0
    val interactions = state.interactions.filter { it.chatId == chatId }
    val lastItemIndex = state.messages.lastIndex + interactions.size + firstMessageIndex
    val markdownPreloader = rememberDshMarkdownPreloader()
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(chatId) { if (state.selectedChatId != chatId) holder.selectBotChat(chatId) }
    LaunchedEffect(chatId, lastItemIndex, latest?.text) {
        if (latest == null || (openedToBottom && !pinnedToBottom && latest.delivery != "sending")) {
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
        if (scrollToBottomRequest == 0 || latest == null) return@LaunchedEffect
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
                    if (state.historyLoading && state.messages.isEmpty()) item { Text(appText("正在读取聊天"), color = colors.secondary) }
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
                }
            }
            ConversationBottomFade(bottomHeight, Modifier.align(Alignment.BottomCenter))
            ConversationComposerChrome(
                modifier = Modifier.align(Alignment.BottomCenter).adaptiveContentWidth().imePadding(),
                composerModifier = Modifier
                    .onSizeChanged { bottomHeight.value = with(density) { it.height.toDp() } }
                    .navigationBarsPadding(),
                jumpVisible = openedToBottom && latest != null && !pinnedToBottom,
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
    val width = minOf(292.dp, bubbleAreaWidth - if (user) 40.dp else 76.dp).coerceAtLeast(80.dp)
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
    val footer: @Composable () -> Unit = {
        Text(appText("完成后，%1\$s 会在聊天里告诉你。",
            state.chats.firstOrNull { it.id == state.mainBotId }?.name ?: appText("助理")),
            color = colors.secondary, fontSize = 12.sp, lineHeight = 17.sp)
    }
    BotPanel(appText("定时任务"), onDismiss, subtitle = appText("让助理按时替你做事。")) {
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
                        Text(task.title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                        Text(task.status, fontSize = 12.sp, lineHeight = 17.sp, color = colors.secondary)
                    }
                    Text(task.next, Modifier.widthIn(max = 110.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.purple)
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
    compactRole: Boolean = false, centerTitle: Boolean = false,
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
    val targetHeight = when {
        compactLandscape -> windowHeight
        tablet -> minOf(660.dp, windowHeight - safeTop - maxOf(20.dp, safeBottom) - 20.dp)
        compactRole -> minOf(650.dp, windowHeight - safeTop - 32.dp)
        roleStyle -> windowHeight - safeTop - 32.dp
        else -> minOf(674.dp, windowHeight - safeTop - 20.dp)
    }.coerceAtLeast(100.dp)
    val panel: @Composable () -> Unit = {
        CompositionLocalProvider(LocalDensity provides density, LocalContentColor provides colors.text,
            LocalConfiguration provides appearance) {
            DshMenuHost {
                Column(Modifier.fillMaxWidth().height(targetHeight).background(colors.panel)
                    .then(if (compactLandscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)) else Modifier)) {
                    if (!compactLandscape) Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.TopCenter) { Box(Modifier.padding(top = 6.dp).size(36.dp, 5.dp).background(if (night) colors.rim else Color(0xFFB8B8BD), CircleShape)) }
                    val contentInset = if (roleStyle) 16.dp else 20.dp
                    Column(Modifier.weight(1f).padding(start = contentInset, end = contentInset,
                        top = if (compactLandscape) 28.dp else if (roleStyle) 0.dp else 10.dp,
                        bottom = if (tablet) 20.dp else maxOf(20.dp, safeBottom)),
                        verticalArrangement = Arrangement.spacedBy(if (roleStyle) 14.dp else 18.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = if (roleStyle) 44.dp else 54.dp).testTag("bot.panel-title"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            navigation?.invoke()
                            if (title.isEmpty()) Spacer(Modifier.weight(1f))
                            else Text(title, Modifier.weight(1f).testTag("bot.panel-heading"),
                                fontSize = if (roleStyle) 17.sp else 22.sp, lineHeight = if (roleStyle) 22.sp else 28.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.text,
                                textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start)
                            if (action != null) action() else DshGlassButton(appText("完成"), onDismiss, Modifier.size(if (night) 44.dp else 54.dp)) { Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(22.dp)) }
                        }
                        subtitle?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = colors.secondary) }
                        content()
                    }
                }
            }
        }
    }
    if (compactLandscape) androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(colors.panel)) { panel() }
    } else if (roleStyle && tablet) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.widthIn(max = 580.dp).fillMaxWidth().padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(32.dp))) { panel() }
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss,
        modifier = if (roleStyle || (night && !tablet)) Modifier else Modifier.padding(start = if (tablet) 0.dp else 8.dp,
            end = if (tablet) 0.dp else 8.dp, bottom = if (tablet) maxOf(20.dp, safeBottom) else 8.dp),
        sheetMaxWidth = 580.dp,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(32.dp),
        containerColor = colors.panel, dragHandle = null,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal) }) { panel() }
}

@Composable
private fun BotQuestionPanel(question: BotInteraction, dismiss: () -> Unit, submit: (List<String>, String) -> Unit) {
    val colors = botColors()
    var selected by remember(question.id) { mutableStateOf(emptySet<String>()) }
    var custom by rememberSaveable(question.id) { mutableStateOf("") }
    BotPanel(appText("回答助理"), dismiss, action = {
        Text(appText("提交"), Modifier.clickable(enabled = selected.isNotEmpty() || custom.isNotBlank()) { submit(selected.toList(), custom) }.padding(10.dp), color = colors.blue)
    }) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BotCard(colors) { Text(question.title, color = colors.text); if (question.detail.isNotEmpty()) Text(question.detail, color = colors.secondary) } }
            items(question.options) { option ->
                BotCard(colors, {
                    selected = if (option in selected) selected - option else if (question.multiSelect) selected + option else setOf(option)
                    if (!question.multiSelect) custom = ""
                }) {
                    Row { Text(option, Modifier.weight(1f), color = colors.text); if (option in selected) Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(18.dp), tint = colors.blue) }
                }
            }
            item { BotCard(colors) {
                BasicTextField(custom, { custom = it }, Modifier.fillMaxWidth().heightIn(min = 70.dp), textStyle = TextStyle(color = colors.text, fontSize = 17.sp), cursorBrush = SolidColor(colors.blue),
                    decorationBox = { inner -> Box { if (custom.isEmpty()) Text(appText("补充回答"), color = colors.secondary); inner() } })
            } }
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
    var returnTo by remember { mutableStateOf(BotRolePage.Profile) }
    var memoryEntryId by rememberSaveable(chat.id) { mutableStateOf<String?>(null) }
    var memoryText by rememberSaveable(chat.id) { mutableStateOf("") }
    var deleteMemory by remember { mutableStateOf<BotMemory?>(null) }
    var deleteSchedule by remember { mutableStateOf<BotSchedule?>(null) }
    var taskName by rememberSaveable { mutableStateOf("") }
    var taskPrompt by rememberSaveable { mutableStateOf("") }
    var taskHour by rememberSaveable { mutableStateOf("09") }
    var taskMinute by rememberSaveable { mutableStateOf("00") }
    val editable = state.connected && state.roleSettingsSupported && state.writable && !state.roleSaving
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
    val openPage: (BotRolePage) -> Unit = { target -> returnTo = page; page = target }
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
        page = when (page) {
            BotRolePage.NewSchedule -> BotRolePage.Schedule
            BotRolePage.MemoryEditor -> BotRolePage.Memory
            BotRolePage.Memory, BotRolePage.Schedule -> returnTo
            else -> BotRolePage.Profile
        }
    }
    BotPanel(title, dismiss, roleStyle = true, compactRole = night && page == BotRolePage.Profile,
        centerTitle = page == BotRolePage.MemoryEditor, navigation = {
        if (page == BotRolePage.Profile) {
            if (!night) {
                DshGlassButton(appText("关闭"), dismiss, Modifier.height(44.dp)) {
                    Text(appText("关闭"), Modifier.padding(horizontal = 14.dp))
                }
            }
        } else if (page == BotRolePage.MemoryEditor) {
            Box(Modifier.width(68.dp)) {
                TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), goBack)
            }
        } else TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), goBack)
    }, action = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page == BotRolePage.Profile) {
                TopBarCircleButton(R.drawable.ic_settings, appText("角色设置"), { page = BotRolePage.Settings },
                    Modifier.testTag("bot.role-settings"))
                if (night) TopBarCircleButton(R.drawable.ic_close, appText("关闭"), dismiss)
            } else {
                if (page == BotRolePage.Settings) {
                    if (state.roleSaving) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    } else if (night && changes.isEmpty()) TopBarCircleButton(R.drawable.ic_close, appText("关闭"), dismiss)
                    else DshGlassButton(appText("保存"),
                        { if (changes["permission"] == "danger-full-access") confirm = true else save() },
                        Modifier.size(44.dp).testTag("bot.role-save"),
                        enabled = editable && changes.isNotEmpty() && name.isNotBlank()) {
                        Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(22.dp))
                    }
                }
                if (page == BotRolePage.MemoryEditor) {
                    DshGlassButton(appText("保存"), {
                        holder.saveBotMemory(chat.id, memoryEntryId, memoryText)
                        page = BotRolePage.Memory
                    }, Modifier.width(68.dp).height(44.dp).testTag("bot.memory-save"),
                        enabled = editable && memoryText.isNotBlank()) {
                        Text(appText("保存"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (page == BotRolePage.NewSchedule) {
                    DshGlassButton(appText("保存"), {
                        holder.scheduleBotDaily(chat.id, taskName, taskPrompt, "$taskHour:$taskMinute")
                        page = BotRolePage.Schedule
                    }, Modifier.size(44.dp), enabled = editable && taskName.isNotBlank() && taskPrompt.isNotBlank()) {
                        Icon(painterResource(R.drawable.ic_menu_check), null, Modifier.size(22.dp))
                    }
                }
            }
        }
    }) {
        if (page == BotRolePage.MemoryEditor) {
            KeyboardDismissHost(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                BotCard(colors, modifier = Modifier.fillMaxSize()) {
                    BotRoleField(
                        appText("记忆"), memoryText, enabled = true, multiline = true,
                        modifier = Modifier.fillMaxSize().testTag("bot.memory-editor")
                    ) { memoryText = it }
                }
            }
            return@BotPanel
        }
        KeyboardDismissHost(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag("bot.role-panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (page == BotRolePage.Profile || page == BotRolePage.Settings) item {
                    Column(Modifier.fillMaxWidth().padding(top = if (page == BotRolePage.Profile) 38.dp else 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BotAvatar(chat, if (page == BotRolePage.Profile) 80.dp else 56.dp, main)
                        Text(chat.name, fontSize = if (page == BotRolePage.Profile) 34.sp else 22.sp,
                            fontWeight = FontWeight.Bold)
                        if (chat.role.isNotEmpty()) Text(chat.role, color = colors.secondary)
                        if (page == BotRolePage.Profile && !profile?.instructions.isNullOrBlank()) {
                            Text(profile.instructions, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                        }
                    }
                }
                when (page) {
                    BotRolePage.Profile -> {
                        item { BotCard(colors) {
                            BotRoleValue(appText("模型"), state.models.firstOrNull { it.id == effectiveModel }?.title ?: effectiveModel ?: "—")
                            HorizontalDivider(thickness = 0.5.dp)
                            BotRoleValue(appText("权限"), state.permissions.firstOrNull { it.id == profile?.permission }?.title ?: profile?.permission ?: "—")
                            HorizontalDivider(thickness = 0.5.dp)
                            BotRoleValue(appText("工作文件夹"), profile?.workDir ?: appText("总工作区"))
                        } }
                        item { BotCard(colors) {
                            if (state.memoryAvailable) BotRoleValue(appText("记忆"), details?.memories?.size?.toString() ?: "—") {
                                openPage(BotRolePage.Memory)
                            }
                            BotRoleValue(appText("定时任务"), schedules.size.toString()) { openPage(BotRolePage.Schedule) }
                            details?.let { value ->
                                val input = value.inputTokens
                                val output = value.outputTokens
                                if (input != null && output != null) BotRoleValue(appText("近 30 天用量"),
                                    java.text.NumberFormat.getInstance().format(input + output))
                            }
                        } }
                    }
                    BotRolePage.Settings -> {
                        item {
                            SettingsSection(appText("资料"), footer = appText("标签为选填，会显示在 Bot 名字下方。")) {
                                BotRoleField(appText("名字"), name, editable) { name = it }
                                SettingsDivider()
                                BotRoleField(appText("标签"), tag, editable) { tag = it }
                            }
                        }
                        item {
                            SettingsSection(appText("模型"), footer = appText("模型更改从下一条消息开始生效。")) {
                                BotRoleSelector(appText("模型"), model, appText("默认模型"), state.models, editable) {
                                    if (model != it) { model = it; effort = "" }
                                }
                                val efforts = state.efforts.takeIf { state.effortModel == effortRef }.orEmpty()
                                if (efforts.isNotEmpty() || effort.isNotEmpty()) {
                                    SettingsDivider()
                                    BotRoleSelector(appText("推理强度"), effort, appText("默认"), efforts, editable) { effort = it }
                                }
                            }
                        }
                        item {
                            SettingsSection(appText("工作区与权限"), footer = permissionFooter) {
                                BotRoleField(appText("工作文件夹"), workDir, editable, appText("总工作区")) { workDir = it }
                                SettingsDivider()
                                BotRoleSelector(appText("权限"), permission, appText("默认权限"), state.permissions, editable) {
                                    permission = it
                                }
                                SettingsDivider()
                                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(appText("隐私"), Modifier.weight(1f))
                                    DshSwitch(privateRole, { privateRole = it }, enabled = editable && !main)
                                }
                            }
                        }
                        item {
                            SettingsSection(appText("说明"), footer = appText("这段说明会写入 Bot 的系统提示词。")) {
                                BotRoleField(appText("说明"), instructions, editable, appText("这个 Bot 负责什么…"),
                                    multiline = true) { instructions = it }
                            }
                        }
                        item {
                            SettingsSection(appText("操控"), footer = appText("各 Bot 的电脑操控授权可在 DS Bot 网页端管理。")) {
                                BotRoleValue(appText("电脑操控"), appText(if (state.computerEnabled) "已开启" else "已关闭"))
                            }
                        }
                        item {
                            SettingsSection {
                                if (state.memoryAvailable) {
                                    SettingsValueRow(appText("记忆"), "", onClick = { openPage(BotRolePage.Memory) })
                                    SettingsDivider()
                                }
                                SettingsValueRow(appText("定时任务"), "", onClick = { openPage(BotRolePage.Schedule) })
                            }
                        }
                        if (main) item {
                            SettingsSection(appText("主 Bot"), footer = appText("主 Bot 可以创建和修改团队 Bot，并管理群聊。")) {
                                val candidates = state.chats.filter { !it.room && it.id != chat.id && it.profile?.privateRole != true }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    BotRoleValue(appText("更换主 Bot"), "",
                                        onClick = if (editable && candidates.isNotEmpty()) ({ menu = true }) else null)
                                    DshMenu(menu, { menu = false }) {
                                        candidates.forEach { candidate ->
                                            DshMenuItem({ Text(candidate.name) }, { menu = false; replacement = candidate })
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            SettingsSection(appText("管理"), footer = appText("复制分工和模型，新 Bot 从空白记忆开始。")) {
                                BotRoleValue(appText("复制一份"), "", onClick = if (editable) ({ confirmCopy = true }) else null)
                                if (showsDelete) {
                                    SettingsDivider()
                                    SettingsValueRow(
                                        appText("删除 Bot"), "", enabled = editable,
                                        modifier = Modifier.testTag("bot.role-delete"), destructive = true,
                                        onClick = { confirmDelete = true }
                                    )
                                }
                            }
                        }
                    }
                    BotRolePage.Memory -> {
                        if (details?.memories.isNullOrEmpty()) item { Text(appText("还没有记忆"), color = colors.secondary) }
                        if (editable) item {
                            BotCard(colors, {
                                memoryEntryId = null
                                memoryText = ""
                                page = BotRolePage.MemoryEditor
                            }) { Text(appText("添加记忆"), color = colors.blue) }
                        }
                        items(details?.memories.orEmpty(), key = { it.id }) { entry ->
                            BotCard(colors) {
                                BotRoleValue(entry.text, "", onClick = if (editable) ({
                                    memoryEntryId = entry.id
                                    memoryText = entry.text
                                    page = BotRolePage.MemoryEditor
                                }) else null)
                                if (editable) Text(appText("删除"), Modifier.clickable { deleteMemory = entry }.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    BotRolePage.MemoryEditor -> Unit
                    BotRolePage.Schedule -> {
                        if (schedules.isEmpty()) item { Text(appText("还没有定时任务"), color = colors.secondary) }
                        if (editable && state.schedulesAvailable) item {
                            BotCard(colors, {
                                taskName = ""; taskPrompt = ""; taskHour = "09"; taskMinute = "00"
                                page = BotRolePage.NewSchedule
                            }) { Text(appText("新建定时任务"), color = colors.blue) }
                        }
                        items(schedules, key = { it.id }) { schedule ->
                            BotCard(colors) {
                                BotRoleValue(schedule.title, schedule.next)
                                if (editable) Text(appText("删除"), Modifier.clickable { deleteSchedule = schedule }.padding(vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    BotRolePage.NewSchedule -> item { BotCard(colors) {
                        BotRoleField(appText("任务名称"), taskName, editable) { taskName = it }
                        BotRoleField(appText("任务内容"), taskPrompt, editable, multiline = true) { taskPrompt = it }
                        BotRoleField(appText("每天运行时间"), "$taskHour:$taskMinute", false) {}
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val hours = remember { (0..23).map { it.toString().padStart(2, '0') }.map { BotRoleOption(it, it) } }
                            val minutes = remember { (0..59).map { it.toString().padStart(2, '0') }.map { BotRoleOption(it, it) } }
                            Box(Modifier.weight(1f)) {
                                BotRoleSelector(appText("时"), taskHour, "09", hours, editable, showDefault = false) { taskHour = it }
                            }
                            Box(Modifier.weight(1f)) {
                                BotRoleSelector(appText("分"), taskMinute, "00", minutes, editable, showDefault = false) { taskMinute = it }
                            }
                        }
                    } }
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
        BotRoleConfirmation(appText("删除这个定时任务？"), appText("删除"), { deleteSchedule = null }, destructive = true) {
            holder.deleteBotSchedule(chat.id, task.id)
        }
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
private fun BotRoleValue(title: String, value: String, onClick: (() -> Unit)? = null) {
    val row = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
    Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f))
        Text(value, Modifier.weight(1f), color = botColors().secondary, textAlign = TextAlign.End)
    }
}

@Composable
private fun BotRoleField(title: String, value: String, enabled: Boolean, placeholder: String = title,
    multiline: Boolean = false, modifier: Modifier = Modifier, change: (String) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val editor: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(value, change, fieldModifier.keyboardDismissExclusion().semantics { contentDescription = title }, enabled = enabled,
            singleLine = !multiline, textStyle = style.copy(
                textAlign = if (multiline) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.End
            ), decorationBox = { inner ->
                Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = botColors().secondary)
                    inner()
                }
            })
    }
    if (multiline) {
        editor(modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = 8.dp))
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, Modifier.weight(1f))
            editor(Modifier.weight(1f))
        }
    }
}

@Composable
private fun BotRoleSelector(title: String, selected: String, fallback: String, options: List<BotRoleOption>,
    enabled: Boolean, showDefault: Boolean = true, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BotRoleValue(title, options.firstOrNull { it.id == selected }?.title ?: selected.ifEmpty { fallback },
            onClick = if (enabled) ({ expanded = true }) else null)
        DshMenu(expanded, { expanded = false }) {
            if (showDefault) DshMenuItem({ Text(fallback) }, { expanded = false; select("") })
            options.forEach { option ->
                DshMenuItem({ Text(option.title) }, { expanded = false; select(option.id) })
            }
        }
    }
}


@Composable
private fun BotRoleConfirmation(title: String, confirmLabel: String, dismiss: () -> Unit,
    destructive: Boolean = false, confirm: () -> Unit) {
    DshAlertDialog(title, dismiss, dismissLabel = appText("取消"), confirmLabel = confirmLabel,
        confirmDestructive = destructive, onConfirm = { dismiss(); confirm() })
}
