package com.clarklevis.dsh.android.ui

import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.AndroidImagePreprocessor
import com.clarklevis.dsh.shared.facade.BotCreationLook
import com.clarklevis.dsh.shared.facade.BotCreationRules
import com.clarklevis.dsh.shared.facade.BotRoleOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.Lifecycle

private enum class BotManagementDestination { Roster, Create, Model, Appearance, Library }

private data class ManagementColors(
    val canvas: Color,
    val card: Color,
    val text: Color,
    val secondary: Color,
    val accent: Color,
    val soft: Color,
    val line: Color,
    val track: Color
)

private val managementLight = ManagementColors(
    canvas = Color(0xFFF5F3F9), card = Color.White, text = Color(0xFF252332), secondary = Color(0xFF74727E),
    accent = Color(0xFF7760B4), soft = Color(0xFFECE7F7), line = Color(0xFFDAD6E3), track = Color(0xFFE4E2EA)
)
private val managementDark = ManagementColors(
    canvas = Color(0xFF1B1924), card = Color(0xFF292733), text = Color(0xFFF2EEF6), secondary = Color(0xFFD8D2E1),
    accent = Color(0xFFC8B5E8), soft = Color(0xFF333044), line = Color(0xFF484354), track = Color(0xFF333044)
)
private val managementAction = Color(0xFF6C50A7)
private val managementCard = RoundedCornerShape(20.dp)
private val rosterTextStyle = TextStyle(
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

@Composable
internal fun BotManagementFlow(holder: AndroidSharedStateHolder, onDismiss: () -> Unit, showRole: (String) -> Unit, startCreating: Boolean = false) {
    val snapshot = holder.botSnapshot
    val state = snapshot.creation
    val colors = if (isSystemInDarkTheme()) managementDark else managementLight
    val canCreate = snapshot.canCreateBot
    val navigation = rememberNavController()
    val startPage = if (startCreating) BotManagementDestination.Create else BotManagementDestination.Roster
    val entry by navigation.currentBackStackEntryAsState()
    val page = entry?.destination?.route?.let(BotManagementDestination::valueOf) ?: startPage
    fun openPage(target: BotManagementDestination) {
        if (navigation.previousBackStackEntry?.destination?.route == target.name) navigation.popBotPage()
        else navigation.pushBotPage(target.name)
    }
    var name by rememberSaveable { mutableStateOf("") }
    var local by rememberSaveable { mutableStateOf(false) }
    var agentId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var localOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var effort by rememberSaveable { mutableStateOf<String?>(null) }
    var look by remember { mutableStateOf<BotCreationLook?>(null) }
    var photo by remember { mutableStateOf<String?>(null) }
    var customLook by rememberSaveable { mutableStateOf(false) }
    var appearanceDraft by remember { mutableStateOf<BotCreationLook?>(null) }
    var photoDraft by remember { mutableStateOf<String?>(null) }
    val appearance = state.appearance
    val agent = state.agents.firstOrNull { it.id == agentId && it.available }
    val cloudModel = cloudOverride ?: snapshot.defaultCreationModel
    val localDefault = agent?.let { "${it.id}/default" }
    val localModel = localOverride ?: localDefault
    val localModels = if (state.agentRoute == agentId) state.agentModels.filterNot { it.id == localDefault } else emptyList()
    val options = if (local) localModels else snapshot.creationModels
    val modelTitle = options.firstOrNull { it.id == (if (local) localModel else cloudModel) }?.title
        ?: if (local) appText("CLI 默认") else appText("系统默认")
    val suggestedName = BotCreationRules.freshName(appText("新 Bot"), snapshot.chats)
    val finalName = name.ifBlank { suggestedName }
    val nameError = BotCreationRules.nameProblem(finalName, snapshot.chats)
    val creationReady = canCreate && look != null && (!local || agent != null) && nameError == null && state.error != "uncertain"

    fun back() {
        if (state.saving) return
        if (navigation.previousBackStackEntry == null) onDismiss() else navigation.popBotPage()
    }
    val pageScroll = BotManagementDestination.entries.map { rememberScrollState() }
    val pageScope = rememberCoroutineScope()
    LaunchedEffect(state.createdId, entry) {
        if (state.createdId != null && page == BotManagementDestination.Create && entry != null) {
            // 快速创建成功也要等页面完成进入，不能被防重复点击的生命周期检查丢弃。
            entry?.lifecycle?.currentStateFlow?.first { it == Lifecycle.State.RESUMED }
            if (startCreating) onDismiss() else navigation.popBackStack(BotManagementDestination.Roster.name, false)
        }
    }
    LaunchedEffect(canCreate, page) {
        if (canCreate && page != BotManagementDestination.Roster && appearance == null && !state.loadingAppearance && !state.appearanceFailed) {
            holder.openBotCreation()
        }
    }
    LaunchedEffect(appearance, page) {
        if (page == BotManagementDestination.Create && look == null) look = appearance?.randomLook(null)
        if (page == BotManagementDestination.Appearance && appearanceDraft == null) {
            appearanceDraft = look ?: appearance?.randomLook(null)
        }
    }

    BotNavigationHost(navigation, startPage.name, Modifier.fillMaxSize()) {
        BotManagementDestination.entries.forEach { destination ->
            composable(destination.name) {
                val scroll = pageScroll[destination.ordinal]
                when (destination) {
                    BotManagementDestination.Roster -> {
                        val roster = snapshot.roster
                        ManagementPage(appText("我的 Bot"), colors, ::back, scroll, isRoster = true, action = {
                            ManagementPill(appText("新建 Bot"), colors, R.drawable.ic_add, enabled = canCreate,
                                modifier = Modifier.testTag("bot.new")) {
                                name = ""
                                local = false
                                agentId = null
                                cloudOverride = null
                                localOverride = null
                                effort = null
                                look = null
                                photo = null
                                customLook = false
                                holder.openBotCreation()
                                openPage(BotManagementDestination.Create)
                                pageScope.launch { pageScroll[BotManagementDestination.Create.ordinal].scrollTo(0) }
                            }
                        }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ManagementCaption(appText("团队成员"), colors, Modifier.weight(1f))
                                ManagementCaption(appText("%1\$s 个 Bot", roster.size), colors)
                            }
                            if (!canCreate) {
                                val status = when {
                                    !snapshot.connected -> appText("连接主机后可以创建 Bot")
                                    !snapshot.writable -> appText("当前主机为只读")
                                    else -> appText("请更新主机上的移动网关和 DS Bot 后再创建 Bot")
                                }
                                ManagementCaption(status, colors)
                            }
                            if (roster.isEmpty() && snapshot.loading) {
                                ManagementProgress(colors)
                            } else ManagementSection(colors) {
                                if (roster.isEmpty()) Text(appText("还没有 Bot"), Modifier.fillMaxWidth().padding(16.dp),
                                    color = colors.text, style = SettingsTypography.item, textAlign = TextAlign.Center)
                                roster.forEachIndexed { index, bot ->
                                    if (index > 0) ManagementDivider(colors.line.copy(alpha = 0.45f))
                                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("bot.roster." + bot.id)
                                        .clickable(role = Role.Button) { showRole(bot.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        val main = bot.id == snapshot.mainBotId
                                        BotAvatar(bot, 44.dp, main = main)
                                        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(bot.name, Modifier.weight(1f, fill = false), style = rosterTextStyle,
                                                    fontSize = 17.sp, lineHeight = 20.sp,
                                                    fontWeight = FontWeight.SemiBold, color = colors.text)
                                                if (main) {
                                                    val labelHeight = with(LocalDensity.current) { 12.sp.toDp() } + 6.dp
                                                    Box(
                                                        Modifier.testTag("bot.roster.main-badge").height(labelHeight)
                                                            .background(colors.soft, RoundedCornerShape(6.dp))
                                                            .padding(horizontal = 7.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text(appText("主 Bot"), style = rosterTextStyle, fontSize = 10.sp,
                                                            lineHeight = 12.sp, color = colors.accent, fontWeight = FontWeight.Medium,
                                                            maxLines = 1, softWrap = false)
                                                    }
                                                }
                                            }
                                            if (bot.role.isNotEmpty()) {
                                                Text(bot.role, style = rosterTextStyle, fontSize = 12.sp, lineHeight = 15.sp, color = colors.secondary)
                                            }
                                        }
                                        ManagementIcon(R.drawable.ic_chevron_right, colors.text, 14.dp)
                                    }
                                }
                            }
                        }
                    }

                    BotManagementDestination.Create -> ManagementPage(appText("新建 Bot"), colors, ::back, scroll, backEnabled = !state.saving,
                        footer = {
                            ManagementAction(appText("创建 Bot"), creationReady, state.saving, Modifier.testTag("bot.create.submit")) {
                                val selected = look ?: return@ManagementAction
                                holder.createBot(finalName, selected.shape, selected.color, photo, if (local) localModel else cloudModel,
                                    if (local) null else effort, agentAppearance = local && !customLook)
                            }
                        }) {
                        Row(Modifier.fillMaxWidth().managementSurface(colors).testTag("bot.create.appearance")
                            .clickable(enabled = canCreate && !state.saving && (look != null || state.appearanceFailed), role = Role.Button) {
                                if (state.appearanceFailed) holder.openBotCreation()
                                else {
                                    appearanceDraft = look
                                    photoDraft = photo
                                    openPage(BotManagementDestination.Appearance)
                                }
                            }.padding(16.dp).heightIn(min = 52.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            ManagementFace(colors, photo ?: agent?.image?.takeIf { local && !customLook }
                                ?: look?.let { appearance?.image(it.shape, it.color) }, 52.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("形象"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                val hint = when {
                                    local && agent != null && !customLook -> appText("使用 %1\$s 形象", agent.title)
                                    customLook -> appText("使用自定义形象")
                                    else -> appText("使用自动分配的形象")
                                }
                                Text(hint, color = colors.secondary, fontSize = 12.sp)
                            }
                            if (state.loadingAppearance) CircularProgressIndicator(Modifier.size(16.dp), color = colors.accent, strokeWidth = 2.dp)
                            else {
                                Text(if (state.appearanceFailed) appText("重试") else appText("编辑"), fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, color = colors.accent)
                                ManagementIcon(R.drawable.ic_chevron_right, colors.text, 14.dp)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManagementCaption(appText("名字"), colors)
                            val shape = RoundedCornerShape(16.dp)
                            BasicTextField(
                                value = name, onValueChange = { name = it }, enabled = !state.saving, singleLine = true,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(colors.card)
                                    .border(1.dp, colors.line, shape).testTag("bot.create.name")
                                    .semantics { contentDescription = appText("名字") },
                                cursorBrush = SolidColor(colors.accent), textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                                decorationBox = { input ->
                                    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                        contentAlignment = Alignment.CenterStart) {
                                        if (name.isEmpty()) Text(suggestedName, color = colors.secondary, fontSize = 16.sp)
                                        input()
                                    }
                                }
                            )
                            if (nameError == null) ManagementCaption(appText("留空会使用自动生成的名字。"), colors)
                            else Text(when (nameError) {
                                "duplicate" -> appText("已经有一个同名 Bot。")
                                "length" -> appText("名字最多 40 个字符。")
                                "reserved" -> appText("这个名字在群聊中有保留用途。")
                                else -> appText("名字不能包含 @、反引号或控制字符。")
                            }, fontSize = 13.sp, color = managementError())
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManagementCaption(appText("运行方式"), colors)
                            ManagementMode(local, !state.saving, colors) { selected ->
                                local = selected
                                if (selected) holder.loadBotCreationAgents()
                            }
                        }
                        if (local) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManagementCaption(appText("本地 Agent"), colors)
                            ManagementSection(colors) {
                                when {
                                    state.loadingAgents && state.agents.isEmpty() -> ManagementProgress(colors)
                                    state.agentsFailed || state.agents.isEmpty() -> ManagementRow(colors,
                                        if (state.agentsFailed) appText("无法读取本地 Agent") else appText("未发现本地 Agent"),
                                        appText("重试"), enabled = canCreate && !state.saving) { holder.loadBotCreationAgents() }
                                    else -> state.agents.forEachIndexed { index, item ->
                                        if (index > 0) ManagementDivider(colors.line)
                                        val status = when {
                                            item.signedIn == true -> appText("已登录")
                                            item.available -> appText("可用")
                                            else -> appText("未登录")
                                        }
                                        ManagementRow(colors, item.title, status,
                                            icon = R.drawable.ic_command_status, marked = item.id == agentId, chevron = false,
                                            enabled = item.available && !state.saving, modifier = Modifier.testTag("bot.create.agent." + item.id)) {
                                            agentId = item.id
                                            localOverride = null
                                            holder.loadBotCreationAgentModels(item.id)
                                        }
                                    }
                                }
                            }
                            if (agent == null) ManagementCaption(appText("请选择主机上可用的 Agent。"), colors)
                        }
                        if (!local || agent != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ManagementCaption(appText("模型"), colors)
                            ManagementSection(colors) {
                                ManagementRow(colors, if (local) appText("模型") else appText("所选模型"), modelTitle,
                                    enabled = !state.saving, modifier = Modifier.testTag("bot.create.model")) {
                                    if (!local) cloudModel?.let(holder::loadBotRoleEfforts)
                                    openPage(BotManagementDestination.Model)
                                }
                            }
                            ManagementCaption(if (local && agent != null) appText("沿用 %1\$s 的默认模型。", agent.title)
                                else appText("沿用主机预选模型，可在 Bot 详情中修改。"), colors)
                        }
                        state.error?.let { code ->
                            Text(if (code == "uncertain") appText("暂时无法确认创建结果，请返回列表查看。")
                                else appText("创建 Bot 失败，请检查主机连接和设置后重试。"), fontSize = 13.sp, color = managementError())
                        }
                        if (!canCreate) ManagementCaption(appText("连接主机后可以创建 Bot"), colors)
                    }

                    BotManagementDestination.Model -> ManagementPage(appText("选择模型"), colors, ::back, scroll) {
                        ManagementCaption(appText("可用模型"), colors)
                        ManagementSection(colors) {
                            if (local) {
                                ManagementRow(colors, appText("CLI 默认"),
                                    if (localModel == localDefault) appText("已选") else appText("选择")) {
                                    localOverride = null
                                    openPage(BotManagementDestination.Create)
                                }
                            }
                            options.forEachIndexed { index, option ->
                                if (index > 0 || local) ManagementDivider(colors.line)
                                val selected = option.id == (if (local) localModel else cloudModel)
                                ManagementRow(colors, option.title, if (selected) appText("已选") else appText("选择")) {
                                    if (local) {
                                        localOverride = option.id
                                        openPage(BotManagementDestination.Create)
                                    } else {
                                        if (!selected) effort = null
                                        cloudOverride = option.id
                                        holder.loadBotRoleEfforts(option.id)
                                    }
                                }
                            }
                            if (local && state.loadingAgentModels) ManagementProgress(colors)
                            if (!local && options.isEmpty()) ManagementCaption(appText("主机暂无可选模型"), colors, Modifier.padding(16.dp))
                        }
                        if (!local) {
                            ManagementCaption(appText("思考强度"), colors)
                            val efforts = if (snapshot.effortModel == cloudModel) snapshot.efforts else emptyList()
                            ManagementEffortPicker(colors, efforts, effort) { chosen ->
                                effort = chosen
                                openPage(BotManagementDestination.Create)
                            }
                            ManagementCaption(appText("主机会提供模型和它所支持的思考强度。"), colors)
                        }
                    }

                    BotManagementDestination.Appearance -> {
                        val current = appearanceDraft
                        var busy by remember { mutableStateOf(false) }
                        var photoError by remember { mutableStateOf(false) }
                        val pickPhoto = rememberManagementPhotoPicker(
                            onBusy = { busy = it; if (it) photoError = false },
                            onError = { photoError = true }
                        ) { image, _ -> photoDraft = image }
                        ManagementPage(appText("编辑形象"), colors, ::back, scroll, footer = {
                            ManagementAction(appText("完成"), !busy && current != null, false, Modifier.testTag("bot.appearance.done")) {
                                look = current
                                photo = photoDraft
                                customLook = true
                                openPage(BotManagementDestination.Create)
                            }
                        }) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 84.dp).managementSurface(colors),
                                contentAlignment = Alignment.Center) {
                                ManagementFace(colors, photoDraft ?: current?.let { appearance?.image(it.shape, it.color) }, 72.dp, tile = false)
                            }
                            if (appearance != null && current != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ManagementCaption(appText("形状"), colors)
                                    appearance.shapes.chunked(5).forEach { row ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            row.forEach { shape ->
                                                val selected = current.shape == shape.id && photoDraft == null
                                                val tile = RoundedCornerShape(14.dp)
                                                Box(Modifier.weight(1f).height(52.dp).clip(tile).background(if (selected) colors.soft else colors.card)
                                                    .border(if (selected) 1.5.dp else 0.dp, if (selected) colors.accent else Color.Transparent, tile)
                                                    .semantics { contentDescription = shape.id }
                                                    .selectable(selected, role = Role.Button) {
                                                        appearanceDraft = current.copy(shape = shape.id)
                                                        photoDraft = null
                                                    }, contentAlignment = Alignment.Center) {
                                                    ManagementFace(colors, shape.images[current.color], 40.dp, tile = false)
                                                }
                                            }
                                            repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ManagementCaption(appText("颜色"), colors)
                                    appearance.colors.chunked(6).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            row.forEach { color ->
                                                val selected = current.color == color.id && photoDraft == null
                                                val palette = color.stops.map { Color(android.graphics.Color.parseColor(it)) }
                                                Box(Modifier.size(37.dp, 38.dp).semantics { contentDescription = color.id }
                                                    .selectable(selected, role = Role.Button) {
                                                        appearanceDraft = current.copy(color = color.id)
                                                        photoDraft = null
                                                    }, contentAlignment = Alignment.Center) {
                                                    Box(Modifier.size(31.dp).clip(CircleShape)
                                                        .then(if (palette.size == 1) Modifier.background(palette.single())
                                                            else Modifier.background(Brush.linearGradient(palette)))
                                                        .border(if (selected) 2.dp else .75.dp, if (selected) colors.accent else Color.White, CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ManagementPill(appText("随机"), colors, R.drawable.ic_shuffle) {
                                    appearance?.randomLook(current)?.let { appearanceDraft = it; photoDraft = null }
                                }
                                ManagementPill(appText("上传照片"), colors, R.drawable.ic_photo_outline, enabled = !busy, onClick = pickPhoto)
                                ManagementPill(appText("头像库"), colors, R.drawable.ic_session_archive) {
                                    holder.loadBotCreationPhotos(true)
                                    openPage(BotManagementDestination.Library)
                                }
                            }
                            if (photoError) ManagementCaption(appText("无法读取或处理所选照片。"), colors)
                        }
                    }

                    BotManagementDestination.Library -> {
                        var busy by remember { mutableStateOf(false) }
                        var photoError by remember { mutableStateOf(false) }
                        val pickPhoto = rememberManagementPhotoPicker(
                            onBusy = { busy = it; if (it) photoError = false },
                            onError = { photoError = true },
                            onPhoto = holder::importBotCreationPhoto
                        )
                        ManagementPage(appText("头像库"), colors, ::back, scroll, action = {
                            ManagementPill(appText("导入照片"), colors, R.drawable.ic_add, enabled = !busy && !state.loadingPhotos && canCreate, onClick = pickPhoto)
                        }) {
                            if (photoError) ManagementCaption(appText("无法读取或处理所选照片。"), colors)
                            if (state.photosFailed) ManagementSection(colors) {
                                ManagementRow(colors, appText("头像库操作失败"), appText("重试")) { holder.loadBotCreationPhotos(true) }
                            }
                            if (state.photos.isEmpty() && !state.loadingPhotos && !state.photosFailed) ManagementSection(colors) {
                                ManagementCaption(appText("头像库还没有照片。"), colors, Modifier.padding(16.dp))
                            }
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = ((maxWidth + 10.dp) / 82.dp).toInt().coerceAtLeast(1)
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    state.photos.chunked(columns).forEach { row ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            row.forEach { entry ->
                                                Box(Modifier.weight(1f).heightIn(min = 80.dp).managementSurface(colors)
                                                    .semantics { contentDescription = entry.name }.clickable(role = Role.Button) {
                                                        photoDraft = entry.image
                                                        openPage(BotManagementDestination.Appearance)
                                                    }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                                    ManagementFace(colors, entry.image, 68.dp)
                                                }
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                            if (state.loadingPhotos || busy) ManagementProgress(colors)
                            else if (state.hasMorePhotos) ManagementSection(colors) {
                                ManagementRow(colors, appText("加载更多"), chevron = false) { holder.loadBotCreationPhotos(false) }
                            }
                        }
                    }
                }
            }
        }
    }
    BackHandler(onBack = ::back)
}

@Composable
private fun ManagementPage(
    title: String,
    colors: ManagementColors,
    back: () -> Unit,
    scroll: ScrollState,
    isRoster: Boolean = false,
    backEnabled: Boolean = true,
    contentWidth: Dp = 560.dp,
    contentInset: Dp = 24.dp,
    nativeToolbar: Boolean = false,
    action: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 24.dp)) {
            val narrow = maxWidth < 300.dp
            if (narrow) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManagementBackButton(isRoster, colors, backEnabled, nativeToolbar, back)
                    Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    action()
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    Text(title, Modifier.align(Alignment.Center), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Box(Modifier.align(Alignment.CenterStart)) { ManagementBackButton(isRoster, colors, backEnabled, nativeToolbar, back) }
                    Box(Modifier.align(Alignment.CenterEnd)) { action() }
                }
            }
        }
        Column(Modifier.weight(1f).widthIn(max = contentWidth).fillMaxWidth().verticalScroll(scroll)
            .padding(start = contentInset, end = contentInset, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        Box(Modifier.widthIn(max = contentWidth).fillMaxWidth().padding(horizontal = 24.dp)) { footer() }
    }
}

@Composable
private fun ManagementBackButton(isRoster: Boolean, colors: ManagementColors, enabled: Boolean, nativeToolbar: Boolean = false, click: () -> Unit) {
    if (nativeToolbar) {
        DshGlassButton(appText("取消"), click, Modifier.height(44.dp), enabled = enabled, shape = CircleShape) {
            Text(appText("取消"), Modifier.padding(horizontal = 16.dp), fontSize = 17.sp, color = colors.accent)
        }
        return
    }
    if (isRoster) {
        val back = appText("返回")
        Box(Modifier.size(44.dp).shadow(4.dp, CircleShape).managementSurface(colors, CircleShape, .75.dp)
            .semantics { contentDescription = back }.clickable(enabled = enabled, role = Role.Button, onClick = click),
            contentAlignment = Alignment.Center) {
            ManagementIcon(R.drawable.ic_back_chevron, colors.text, 18.dp)
        }
    } else ManagementPill(appText("取消"), colors, enabled = enabled, onClick = click)
}

@Composable
private fun ManagementPill(text: String, colors: ManagementColors, icon: Int? = null, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.alpha(if (enabled) 1f else .45f).managementSurface(colors, CircleShape, .75.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 40.dp)
        .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) ManagementIcon(icon, colors.text, 16.dp)
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.text)
    }
}

@Composable
private fun ManagementCaption(text: String, colors: ManagementColors, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 13.sp, lineHeight = 17.sp, color = colors.secondary)
}

private fun Modifier.managementSurface(colors: ManagementColors, shape: androidx.compose.ui.graphics.Shape = managementCard,
    rimWidth: Dp = 1.dp): Modifier = clip(shape).background(colors.card)
    .then(if (colors === managementDark) Modifier.border(rimWidth, Color(0xFF777184), shape) else Modifier)

@Composable
private fun ManagementSection(colors: ManagementColors, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().managementSurface(colors), content = content)
}

@Composable
private fun ManagementDivider(color: Color) {
    Spacer(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(color))
}

@Composable
private fun ManagementRow(colors: ManagementColors, title: String, value: String = "", icon: Int? = null, marked: Boolean = false,
    chevron: Boolean = true, enabled: Boolean = true, modifier: Modifier = Modifier, action: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).alpha(if (enabled) 1f else .56f)
        .clickable(enabled = enabled, role = Role.Button, onClick = action).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) ManagementIcon(icon, colors.accent, 20.dp)
        Text(title, Modifier.weight(1f), color = colors.text, fontSize = 15.sp,
            fontWeight = if (icon == null) FontWeight.Normal else FontWeight.SemiBold)
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 160.dp), color = colors.secondary, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        if (marked) ManagementIcon(R.drawable.ic_check_circle, colors.accent, 18.dp)
        else if (chevron) ManagementIcon(R.drawable.ic_chevron_right, colors.secondary, 14.dp)
    }
}

@Composable
private fun ManagementIcon(icon: Int, tint: Color, size: Dp) {
    Icon(painterResource(icon), contentDescription = null, Modifier.size(size), tint = tint)
}

@Composable
private fun ManagementProgress(colors: ManagementColors) {
    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun ManagementAction(label: String, enabled: Boolean, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxWidth().heightIn(min = 50.dp).clip(CircleShape)
        .background(managementAction.copy(alpha = if (enabled || busy) 1f else .45f))
        .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ManagementMode(local: Boolean, enabled: Boolean, colors: ManagementColors, onSelect: (Boolean) -> Unit) {
    val thumb = if (colors === managementDark) Color(0xFF575264) else colors.card
    Row(Modifier.fillMaxWidth().selectableGroup().testTag("bot.create.mode").clip(CircleShape)
        .background(colors.track).padding(2.dp)) {
        listOf(false to appText("DS Bot 模型"), true to appText("本地 Agent")).forEach { (value, title) ->
            val selected = value == local
            Box(Modifier.weight(1f).heightIn(min = 28.dp).clip(CircleShape)
                .background(if (selected) thumb else Color.Transparent)
                .selectable(selected, enabled = enabled, role = Role.Tab) { onSelect(value) }.padding(4.dp),
                contentAlignment = Alignment.Center) {
                Text(title, color = colors.text, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ManagementEffortPicker(colors: ManagementColors, options: List<BotRoleOption>, effort: String?, select: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val systemDefault = appText("系统默认")
    Box(Modifier.managementSurface(colors)) {
        ManagementRow(colors, appText("思考强度"), options.firstOrNull { it.id == effort }?.title ?: systemDefault,
            enabled = options.isNotEmpty()) { expanded = true }
        DshMenu(expanded, { expanded = false }) {
            DshMenuItem(text = { Text(systemDefault) }, onClick = { expanded = false; select(null) })
            options.forEach { option ->
                DshMenuItem(text = { Text(option.title) }, onClick = { expanded = false; select(option.id) })
            }
        }
    }
}

@Composable
private fun ManagementFace(colors: ManagementColors, image: String?, size: Dp, tile: Boolean = true) {
    val context = LocalContext.current
    val cache = PluginIconImageCache.shared(context)
    val bitmap by produceState(cache.peekDataUrl(image), image) { value = cache.loadDataUrl(image) }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(if (tile) colors.soft else Color.Transparent),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun rememberManagementPhotoPicker(onBusy: (Boolean) -> Unit, onError: () -> Unit,
    onPhoto: (String, String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onBusy(true)
        scope.launch {
            try {
                val photo = AndroidImagePreprocessor(context.contentResolver).prepare(uri,
                    byteLimit = BotCreationRules.PHOTO_BYTES, pixelSideLimit = BotCreationRules.PHOTO_SIDE).outgoing
                onPhoto("data:${photo.mediaType};base64,${photo.base64Data}", photo.name ?: appText("照片"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onError()
            } finally {
                onBusy(false)
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun managementError() = if (isSystemInDarkTheme()) Color(0xFFFF8A89) else Color(0xFFB3261E)


@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BotConversationPicker(holder: AndroidSharedStateHolder, group: Boolean, dismiss: () -> Unit) {
    val snapshot = holder.botSnapshot
    val draft = snapshot.conversation
    val colors = if (isSystemInDarkTheme()) managementDark else managementLight.copy(canvas = Color(0xFFF2F2F7))
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = holder.searchConversationBots(query)
    val canWrite = snapshot.connected && snapshot.writable && draft.supported
    val locked = draft.saving || draft.createdId != null || draft.uncertain
    BackHandler { if (!draft.saving) dismiss() }
    KeyboardDismissHost(Modifier.fillMaxSize()) {
        ManagementPage(if (group) appText("新建群聊") else appText("查找 Bot"), colors,
            dismiss, rememberScrollState(), backEnabled = !draft.saving, contentWidth = 608.dp, contentInset = 24.dp, nativeToolbar = true, action = {
                if (group) Text(appText("已选 %1\$s", draft.memberIds.size), color = colors.secondary, fontSize = 14.sp)
            }, footer = {
                if (group) Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (draft.createdId == null) Text(appText("不填写首条消息时，管理员会先询问群聊用途。"),
                        color = colors.secondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    ManagementAction(if (draft.createdId == null) appText("创建群聊 · %1\$s 位 Bot", draft.memberIds.size)
                        else appText("重试发送首条消息"), canWrite && draft.memberIds.size >= 2 && !draft.uncertain,
                        draft.saving, Modifier.testTag("bot.group.create")) {
                        if (draft.createdId != null) holder.retryConversationMessage() else holder.createConversationRoom()
                    }
                    if (draft.createdId != null) ManagementPill(appText("进入群聊"), colors, enabled = !draft.saving,
                        modifier = Modifier.testTag("bot.group.enter"), onClick = holder::enterCreatedConversation)
                }
            }) {
            if (group) {
                Text(appText("选择群聊成员"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                Text(appText("第一位选中的成员为管理员。"), fontSize = 14.sp, lineHeight = 21.sp,
                    color = colors.secondary, modifier = Modifier.testTag("bot.group.admin-note"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.memberIds.forEachIndexed { index, id ->
                        val bot = snapshot.roster.firstOrNull { it.id == id } ?: return@forEachIndexed
                        Row(Modifier.clip(CircleShape).background(colors.soft).testTag("bot.group.remove.$id")
                            .clickable(enabled = !locked, role = Role.Button) { holder.toggleConversationMember(id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${index + 1} ${bot.name}" + if (index == 0) " · " + appText("管理员") else "",
                                color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            ManagementIcon(R.drawable.ic_close, colors.text, 12.dp)
                        }
                    }
                }
            }
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("bot.conversation.search").keyboardDismissExclusion()
                .background(colors.card, CircleShape).padding(horizontal = 20.dp, vertical = 14.dp),
                singleLine = true, textStyle = rosterTextStyle.copy(color = colors.text, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.accent), decorationBox = { field ->
                    Box { if (query.isEmpty()) Text(appText("搜索名称或角色"), color = colors.secondary.copy(alpha = .6f), fontSize = 17.sp); field() }
                })
            ManagementSection(colors) {
                candidates.forEachIndexed { index, bot ->
                    val order = draft.memberIds.indexOf(bot.id)
                    val admin = appText("管理员")
                    val selected = appText("已选择")
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("bot.conversation.member.${bot.id}")
                        .semantics { stateDescription = if (group && order == 0) admin else if (group && order > 0) selected else "" }
                        .clickable(enabled = if (group) !locked else !draft.saving && (!bot.hidden || canWrite), role = Role.Button) {
                            if (group) holder.toggleConversationMember(bot.id) else holder.openConversationBot(bot.id)
                        }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BotAvatar(bot, 36.dp, snapshot.isMainBot(bot.id))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(bot.name, color = colors.text, fontSize = 17.sp)
                            if (bot.role.isNotBlank()) Text(bot.role, color = colors.secondary, fontSize = 13.sp)
                        }
                        if (group && order >= 0) {
                            if (order == 0) Text(admin, color = colors.accent, fontSize = 12.sp)
                            Text("${order + 1}", color = colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (index < candidates.lastIndex) ManagementDivider(colors.line.copy(alpha = .45f))
                }
                if (candidates.isEmpty()) Text(if (query.isBlank()) appText("还没有 Bot") else appText("没有找到相关 Bot"),
                    color = colors.secondary, modifier = Modifier.padding(16.dp))
            }
            ManagementCaption(appText("可选择已从侧边栏隐藏的 Bot。"), colors)
            if (group) {
                ManagementCaption(appText("首条消息（选填）"), colors)
                BasicTextField(draft.message, holder::updateConversationMessage,
                    Modifier.fillMaxWidth().testTag("bot.group.message").keyboardDismissExclusion().managementSurface(colors).padding(16.dp),
                    enabled = !locked, minLines = 2, maxLines = 5,
                    textStyle = rosterTextStyle.copy(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(colors.accent), decorationBox = { field ->
                        Box { if (draft.message.isEmpty()) Text(appText("描述群聊用途"), color = colors.secondary, fontSize = 16.sp); field() }
                    })
            }
            if (!canWrite) ManagementCaption(when {
                !snapshot.connected -> appText("连接主机后可以新建会话")
                !snapshot.writable -> appText("当前主机为只读")
                else -> appText("请更新主机上的移动网关以新建会话")
            }, colors)
            if (draft.error != null) Column(Modifier.testTag("bot.conversation.error"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when {
                    draft.uncertain -> appText("暂时无法确认提交结果，请返回聊天列表核对，避免重复创建或发送。")
                    draft.createdId != null -> appText("群聊已创建，首条消息发送失败。")
                    else -> appText("操作失败，请重试。")
                }, color = Color.Red, fontSize = 13.sp)
                if (draft.errorDetail != null) Text(requireNotNull(draft.errorDetail), color = Color.Red, fontSize = 13.sp)
                else if (!draft.uncertain) Text(requireNotNull(draft.error), color = Color.Red, fontSize = 13.sp)
                if (draft.uncertain && draft.createdId == null) ManagementPill(appText("已核对列表，确认未创建"), colors,
                    onClick = holder::confirmConversationNotCreated)
            }
        }
    }
}
