package com.clarklevis.dsh.android.ui

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupPositionProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.TailscaleTunnelUiState
import com.clarklevis.dsh.android.platform.TunnelFailureKind
import com.clarklevis.dsh.android.platform.TunnelUiStage
import com.clarklevis.dsh.shared.tailscale.TailscaleAccount
import com.clarklevis.dsh.shared.tailscale.TailscaleAddition
import kotlinx.coroutines.delay

private val TunnelBlue = DshColors.Ocean

@Composable
private fun tunnelSecondaryColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFEBEBF5).copy(alpha = .6f) else Color(0xFF3C3C43).copy(alpha = .6f)

@Composable
private fun tunnelTertiaryColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFEBEBF5).copy(alpha = .3f) else Color(0xFF3C3C43).copy(alpha = .3f)

@Composable
private fun tunnelRed(): Color = if (isSystemInDarkTheme()) Color(0xFFFF453A) else Color(0xFFFF3B30)

/** 采用 iOS 的文字行高与零额外字距，避免 Material 默认字距扩大段落及编辑面板。 */
@Composable
private fun TunnelMenuHost(content: @Composable () -> Unit) {
    // Dialog 会重新提供系统配置和密度，恢复应用选择的外观及字号。
    val configuration = Configuration(LocalConfiguration.current).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (MaterialTheme.colorScheme.background.luminance() < .5f) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val density = LocalDensity.current
    val fontScale = LocalAppearanceSettings.current.effectiveFontScale
    val appDensity = remember(density, fontScale) {
        if (density is AppFontDensity) density else AppFontDensity(density, fontScale)
    }
    val foreground = MaterialTheme.colorScheme.onSurface
    val style = SettingsTypography.item.copy(
        color = foreground,
        letterSpacing = 0.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    )
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalDensity provides appDensity,
        LocalContentColor provides foreground,
        LocalTextStyle provides style
    ) { DshMenuHost(content) }
}

@Composable
internal fun TailscaleTunnelScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as DshAndroidApplication
    val controller = app.tunnel
    val state by controller.state.collectAsStateWithLifecycle()
    val addition by controller.accountAddition.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    TailscaleTunnelContent(
        settings = LocalTailscaleTunnelSettings.current, state = state, addition = addition,
        onBack = onBack, onEnabled = controller::setEnabled,
        onAccount = controller::selectAccount, onAdd = controller::beginAccountAddition,
        onCancel = controller::cancelAccountAddition,
        onConfirm = { controller.confirmAccountAddition()?.let { uri.openUri(it) } },
        onRetry = controller::start, onOpen = { uri.openUri(it) },
        onCopy = { clipboard.setText(AnnotatedString(it)) }, onDelete = controller::deleteAccount
    )
}

/** 尺寸以 iOS 的逻辑点为基准；系统状态栏和导航栏由 Android 处理。 */
@Suppress("LongParameterList")
@Composable
internal fun TailscaleTunnelContent(
    settings: TailscaleTunnelSettings,
    state: TailscaleTunnelUiState,
    addition: TailscaleAddition?,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onAccount: (String) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var editingAccount by remember { mutableStateOf<TailscaleAccount?>(null) }
    var deletingAccount by remember { mutableStateOf<TailscaleAccount?>(null) }
    val pending = addition != null
    TunnelPage(onBack) {
        EnabledSection(settings, pending, onEnabled)
        AccountsSection(
            settings,
            pending,
            onAccount,
            onAdd,
            onRename = { editingAccount = it },
            onDelete = { id -> deletingAccount = settings.accounts.firstOrNull { it.id == id } }
        )
        if (addition?.confirmed == true) AdditionSection(addition, onCancel)
        if (settings.enabled) {
            StatusSection(state, onEnabled, onRetry, onOpen, onCopy)
            IdentitySection(settings, pending, onRetry)
        }
    }
    editingAccount?.let { account ->
        RenameAccountSheet(account, onCancel = { editingAccount = null }) { name ->
            settings.renameAccount(account.id, name)
            editingAccount = null
        }
    }
    deletingAccount?.let { account ->
        DeleteAccountDialog(account, onCancel = { deletingAccount = null }) {
            deletingAccount = null
            onDelete(account.id)
        }
    }
    if (addition != null && !addition.confirmed) {
        TailscaleLoginDialog(addition, onCancel, onConfirm, onCopy)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelPage(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val background = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    TunnelMenuHost {
        Scaffold(containerColor = background, topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("Tailscale 内嵌隧道"), style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(R.drawable.ic_back_chevron, appText("返回"), onBack, Modifier.padding(start = 16.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                    .adaptiveContentWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 20.dp)
                    .testTag("tunnel-content"),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                content = content
            )
        }
    }
}

@Composable
private fun EnabledSection(settings: TailscaleTunnelSettings, pending: Boolean, onEnabled: (Boolean) -> Unit) {
    TunnelSection(
        appText("Tailscale 内嵌隧道"),
        appText("在应用内建立一个 Tailscale 节点：不需要安装 Tailscale，也不会占用系统 VPN。节点只在你自己的 tailnet 内可达。")
    ) {
        TunnelRow {
            Text(appText("启用内嵌隧道"), style = SettingsTypography.item, modifier = Modifier.weight(1f))
            TunnelSwitch(settings.enabled, !pending, onEnabled)
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun AccountsSection(
    settings: TailscaleTunnelSettings,
    pending: Boolean,
    onAccount: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (TailscaleAccount) -> Unit,
    onDelete: (String) -> Unit
) {
    TunnelSection(
        appText("账号与网络"),
        appText("仅显示已登录账号。点击账号可切换网络，登录状态会分别保留。")
    ) {
        settings.accounts.forEach { account ->
            AccountRow(
                account,
                account.id == settings.selectedAccount.id,
                onSelect = { onAccount(account.id) },
                onRename = { onRename(account) },
                onDelete = { onDelete(account.id) }
            )
            DividerRow()
        }
        TunnelRow(Modifier.clickable(enabled = !pending, onClick = onAdd).testTag("tunnel-add-account")) {
            Icon(
                painterResource(R.drawable.ic_tailscale_add),
                contentDescription = null,
                tint = TunnelBlue.copy(alpha = if (pending) .4f else 1f),
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(18.dp))
            Text(appText("添加 Tailscale 账号"), style = SettingsTypography.item, color = TunnelBlue.copy(alpha = if (pending) .4f else 1f))
        }
    }
}

@Composable
private fun AdditionSection(addition: TailscaleAddition, onCancel: () -> Unit) {
    TunnelSection {
        TunnelRow {
            if (addition.status.stage != TunnelUiStage.FAILED) {
                CircularProgressIndicator(Modifier.size(14.dp), color = tunnelSecondaryColor(), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                appText(
                    when (addition.status.stage) {
                        TunnelUiStage.FAILED -> "新账号连接失败"
                        TunnelUiStage.WAITING_ROUTES -> "正在准备新账号的路由…"
                        else -> "等待新账号登录…"
                    }
                ),
                style = SettingsTypography.description,
                color = tunnelSecondaryColor()
            )
        }
        DividerRow()
        ActionRow(appText("取消添加"), onClick = onCancel)
    }
}

@Composable
private fun StatusSection(
    state: TailscaleTunnelUiState,
    onEnabled: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    var copied by remember(state.authUrl) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    TunnelSection(
        appText("隧道状态"),
        if (state.stage == TunnelUiStage.NEEDS_LOGIN) appText("等待你在浏览器里完成登录。") else null
    ) {
        StatusSummary(state)
        state.nodeAddress?.let {
            DividerRow()
            ValueRow(appText("本机地址"), it)
        }
        failureText(state)?.let {
            DividerRow()
            ValueRow(appText("失败原因"), it)
        }
        state.authUrl?.let { url ->
            DividerRow()
            ActionRow(appText("打开登录链接")) { onOpen(url) }
            DividerRow()
            ActionRow(appText(if (copied) "已复制" else "复制链接")) {
                onCopy(url)
                copied = true
            }
        }
        if (state.stage == TunnelUiStage.DISABLED || state.stage == TunnelUiStage.FAILED) {
            DividerRow()
            ActionRow(appText("继续连接"), tag = "tunnel-retry", onClick = onRetry)
        }
        if (state.stage != TunnelUiStage.DISABLED) {
            DividerRow()
            ActionRow(
                appText(
                    if (state.stage == TunnelUiStage.STARTING || state.stage == TunnelUiStage.NEEDS_LOGIN) {
                        "停止启动"
                    } else {
                        "停止隧道"
                    }
                ),
                color = tunnelRed()
            ) { onEnabled(false) }
        }
    }
}

@Composable
private fun StatusSummary(state: TailscaleTunnelUiState) {
    TunnelRow(Modifier.testTag("tunnel-status-row")) {
        Box(Modifier.size(8.dp).background(stageColor(state), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(tailscaleStageLabel(state.stage), style = SettingsTypography.item, modifier = Modifier.weight(1f))
        if (state.isTransient) {
            CircularProgressIndicator(
                Modifier.size(14.dp).testTag("tunnel-starting-progress"),
                color = tunnelSecondaryColor(),
                strokeWidth = 2.dp
            )
        } else if (state.peerCount > 0) {
            Text("${appText("对端数量")} ${state.peerCount}", style = SettingsTypography.item, color = tunnelSecondaryColor())
        }
    }
}

@Composable
private fun IdentitySection(settings: TailscaleTunnelSettings, pending: Boolean, onRetry: () -> Unit) {
    var nodeName by remember(settings.selectedAccount.id) { mutableStateOf(settings.nodeName) }
    var invalidName by remember(settings.selectedAccount.id) { mutableStateOf(false) }
    TunnelSection(
        appText("节点身份"),
        appText("节点名称只允许小写字母、数字和连字符。登录时默认在浏览器里完成；也可以粘贴 Tailscale 控制台生成的 Auth Key。")
    ) {
        FieldRow(
            appText("节点名称"),
            nodeName,
            TailscaleTunnelSettings.defaultNodeName(),
            "tunnel-field-node-name",
            enabled = !pending
        ) {
            nodeName = it
            invalidName = !settings.selectNodeName(it)
        }
        if (invalidName) {
            Text(
                appText("只允许小写字母、数字和连字符，最长 63 个字符。"),
                style = SettingsTypography.description,
                color = tunnelRed(),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        DividerRow()
        FieldRow(
            appText("登录密钥（可选）"),
            settings.authKey,
            "tskey-auth-…",
            "tunnel-field-auth-key",
            enabled = !pending,
            secure = true,
            onChange = settings::selectAuthKey
        )
        if (settings.authKey.isNotEmpty()) {
            DividerRow()
            ActionRow(appText("使用登录密钥连接"), enabled = !pending, onClick = onRetry)
        }
    }
}

@Composable
internal fun TailscaleLoginDialog(addition: TailscaleAddition, onCancel: () -> Unit, onConfirm: () -> Unit, onCopy: (String) -> Unit) {
    val url = addition.loginURL
    val failed = addition.status.stage == TunnelUiStage.FAILED
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(.28f) }
        TunnelMenuHost {
            Column(
                Modifier.padding(horizontal = 24.dp).widthIn(max = 340.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)).background(tunnelCardColor())
                    .padding(22.dp).verticalScroll(rememberScrollState()).testTag("tunnel-login-dialog"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    appText(if (addition.isReauthentication) "登录 Tailscale 账号" else "添加 Tailscale 账号"),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    lineHeight = 22.sp
                )
                Text(
                    appText(
                        when {
                            failed -> "无法获取登录链接，请取消后重试。"
                            url == null -> "正在获取登录链接…"
                            else -> "即将跳转到 Tailscale 登录页面。你可以复制下方链接；点击确认打开页面，登录完成后应用会自动连接。"
                        }
                    ),
                    color = tunnelSecondaryColor(),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                if (url != null) {
                    LoginURLRow(url) { onCopy(url) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogAction(appText("取消"), Modifier.weight(1f).testTag("tunnel-login-cancel"), onClick = onCancel)
                    DialogAction(
                        appText("确认"),
                        Modifier.weight(1f).testTag("tunnel-login-confirm"),
                        enabled = url != null && !failed,
                        onClick = onConfirm
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: TailscaleAccount, isSelected: Boolean, onSelect: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TunnelRow(
            Modifier.combinedClickable(onClick = onSelect, onLongClick = { menu = true })
                .semantics { selected = isSelected }.testTag("tunnel-account-${account.id}")
        ) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    account.displayName.ifBlank { appText("已登录账号") },
                    color = TunnelBlue,
                    style = SettingsTypography.item,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (account.name.isNotEmpty() && !account.loginName.isNullOrBlank() && account.loginName != account.displayName) {
                    Text(account.loginName.orEmpty(), color = tunnelSecondaryColor(), style = SettingsTypography.description)
                }
                if (!account.tailnetName.isNullOrBlank() &&
                    account.tailnetName != account.displayName &&
                    account.tailnetName != account.loginName
                ) {
                    Text(account.tailnetName.orEmpty(), color = tunnelSecondaryColor(), style = SettingsTypography.description)
                }
            }
            if (isSelected) Text("✓", color = TunnelBlue, fontSize = 22.sp)
        }
        AccountActionsMenu(menu, onDismiss = { menu = false }, onRename, onDelete)
    }
}

@Composable
private fun AccountActionsMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val shadow = with(LocalDensity.current) { DshMenuShadowPadding.roundToPx() }
    val gap = with(LocalDensity.current) { 16.dp.roundToPx() }
    val position = remember(shadow, gap) { AccountMenuPosition(shadow, gap) }
    DshMenuPopup(
        expanded,
        onDismissRequest = onDismiss,
        positionProvider = position,
        modifier = Modifier.width(250.dp).testTag("tunnel-account-menu"),
        shape = RoundedCornerShape(34.dp)
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DshMenuItem(
                minHeight = 56.dp,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                text = { Text(appText("重命名"), fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_session_rename), contentDescription = null, modifier = Modifier.size(24.dp))
                },
                onClick = {
                    onDismiss()
                    onRename()
                }
            )
            DshMenuItem(
                minHeight = 56.dp,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
                text = { Text(appText("删除账号"), color = tunnelRed(), fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp) },
                leadingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_trash),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = tunnelRed()
                    )
                },
                onClick = {
                    onDismiss()
                    onDelete()
                }
            )
        }
    }
}

private class AccountMenuPosition(private val shadow: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maximumX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val maximumY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
        val below = anchorBounds.bottom + gap - shadow
        val above = anchorBounds.top - popupContentSize.height - gap + shadow
        val y = if (below <= maximumY) below else above
        return IntOffset((anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maximumX), y.coerceIn(0, maximumY))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameAccountSheet(account: TailscaleAccount, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    val background = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    val height = adaptiveWindowSize().height * .93f
    ModalBottomSheet(
        onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.adaptiveSheetBounds(), sheetMaxWidth = adaptiveSheetWidth(),
        shape = RoundedCornerShape(40.dp), containerColor = background,
        contentColor = MaterialTheme.colorScheme.onSurface, dragHandle = null, tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, scrimColor = Color.Black.copy(alpha = .2f)
    ) {
        TunnelMenuHost {
            Column(Modifier.fillMaxWidth().height(height).imePadding().testTag("tunnel-account-editor")) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).height(44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RenameSheetAction(appText("取消"), Modifier.testTag("tunnel-account-cancel"), onClick = onCancel)
                    Spacer(Modifier.weight(1f))
                    RenameSheetAction(appText("确定"), Modifier.testTag("tunnel-account-save"), emphasized = true) { onSave(name) }
                }
                Text(
                    appText("重命名"),
                    Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 10.dp),
                    fontSize = 34.sp,
                    lineHeight = 41.sp,
                    fontWeight = FontWeight.Bold
                )
                BasicTextField(
                    name,
                    { name = it },
                    singleLine = true,
                    cursorBrush = SolidColor(TunnelBlue),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
                        .background(tunnelCardColor()).padding(horizontal = 16.dp, vertical = 15.dp)
                        .testTag("tunnel-account-name").semantics { contentDescription = appText("账号备注（可选）") },
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text(appText("账号备注（可选）"), fontSize = 17.sp, lineHeight = 22.sp, color = tunnelTertiaryColor())
                        inner()
                    }
                )
            }
        }
    }
}

@Composable
private fun RenameSheetAction(text: String, modifier: Modifier, emphasized: Boolean = false, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier.height(44.dp).widthIn(min = 68.dp).clip(CircleShape)
            .background(if (dark) Color(0xFF242426) else Color.White.copy(alpha = .55f))
            .border(.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .15f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun DeleteAccountDialog(account: TailscaleAccount, onCancel: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(.2f) }
        TunnelMenuHost {
            Column(
                Modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(38.dp)).background(if (isSystemInDarkTheme()) Color(0xFF303034) else Color(0xFFE8E8EC))
                    .border(.5.dp, Color.White.copy(alpha = if (isSystemInDarkTheme()) .15f else .7f), RoundedCornerShape(38.dp))
                    .verticalScroll(rememberScrollState()).testTag("tunnel-delete-dialog")
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp).padding(top = 24.dp)) {
                    Text(appText("删除账号？"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        account.displayName.ifBlank { appText("已登录账号") } + "\n\n" +
                            appText("此账号及其登录状态将从本机删除；若正在使用，会停止对应隧道。再次使用需重新登录。"),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = tunnelSecondaryColor()
                    )
                }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NativeAlertAction(appText("取消"), Modifier.weight(1f).testTag("tunnel-delete-cancel"), onClick = onCancel)
                    NativeAlertAction(
                        appText("删除账号"),
                        Modifier.weight(1f).testTag("tunnel-delete-confirm"),
                        destructive = true,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeAlertAction(text: String, modifier: Modifier, destructive: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape)
            .background(if (isSystemInDarkTheme()) Color(0xFF48484C) else Color(0xFFCACACE))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            color = if (destructive) tunnelRed() else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (destructive) FontWeight.Normal else FontWeight.SemiBold
        )
    }
}

@Composable
private fun DialogAction(text: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp))
            .background(if (isSystemInDarkTheme()) Color(0xFF303033) else Color(0xFFEFEFF0))
            .clickable(enabled = enabled, onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        val color = if (enabled) MaterialTheme.colorScheme.onSurface else tunnelTertiaryColor()
        Text(text, fontSize = 17.sp, lineHeight = 22.sp, color = color)
    }
}

@Composable
private fun TunnelSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // 与 iOS 插件和设置页共用蓝色开启底色，保留 51×31 尺寸及标准 Toggle 语义。
    val blue = dshSwitchTrackColor()
    val track by animateColorAsState(
        if (checked) blue.copy(alpha = if (enabled) 1f else .5f) else tunnelSecondaryColor().copy(alpha = .3f),
        animationSpec = tween(180),
        label = "tunnel-switch-track"
    )
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 0.dp, animationSpec = tween(180), label = "tunnel-switch-thumb")
    androidx.compose.foundation.layout.Box(
        Modifier.size(51.dp, 31.dp).clip(CircleShape)
            .background(track)
            .then(Modifier.toggleableTunnel(checked, enabled, onChange))
            .testTag("tunnel-enabled-switch"),
        contentAlignment = Alignment.CenterStart
    ) { Box(Modifier.offset(x = thumbOffset).padding(2.dp).size(27.dp).background(Color.White, CircleShape)) }
}

private fun Modifier.toggleableTunnel(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): Modifier =
    this.then(Modifier.semantics { contentDescription = appText("启用内嵌隧道") })
        .then(Modifier.toggleable(checked, enabled = enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange))

@Composable
private fun tunnelCardColor() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White

@Composable
private fun TunnelSection(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        title?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = SettingsTypography.section, color = tunnelSecondaryColor()) }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(tunnelCardColor()).padding(horizontal = 16.dp),
            content = content
        )
        footer?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = SettingsTypography.description, color = tunnelSecondaryColor())
        }
    }
}

@Composable
private fun TunnelRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun DividerRow() = HorizontalDivider(thickness = .5.dp, color = tunnelSecondaryColor().copy(alpha = .22f))

@Composable
private fun ValueRow(title: String, value: String) {
    TunnelRow {
        Text(title, style = SettingsTypography.item, modifier = Modifier.weight(1f))
        Text(
            value,
            Modifier.widthIn(max = 210.dp),
            style = SettingsTypography.item,
            color = tunnelSecondaryColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ActionRow(title: String, color: Color = TunnelBlue, tag: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    TunnelRow(Modifier.clickable(enabled = enabled, onClick = onClick).testTag(tag)) {
        Text(title, style = SettingsTypography.item, color = color)
    }
}

@Suppress("LongParameterList")
@Composable
private fun FieldRow(
    label: String,
    value: String,
    placeholder: String,
    tag: String,
    enabled: Boolean,
    secure: Boolean = false,
    onChange: (String) -> Unit
) {
    TunnelRow {
        BasicTextField(
            value, onChange, enabled = enabled, singleLine = true,
            textStyle = SettingsTypography.item.copy(
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .4f)
            ),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = if (secure) KeyboardType.Password else KeyboardType.Ascii
            ),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().padding(vertical = 15.dp).testTag(tag).semantics { contentDescription = label },
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = tunnelTertiaryColor(), style = SettingsTypography.item)
                inner()
            }
        )
    }
}

private fun failureText(state: TailscaleTunnelUiState): String? = when (state.failureKind) {
    TunnelFailureKind.INTERFACE_SOURCE -> appText("网络接口读取失败")
    TunnelFailureKind.NATIVE_LOAD -> appText("运行库加载失败")
    TunnelFailureKind.BACKEND_START -> appText("节点启动失败")
    TunnelFailureKind.LOGIN_WAIT -> appText("登录等待超时")
    TunnelFailureKind.STATUS -> appText("状态查询失败")
    TunnelFailureKind.SHUTDOWN -> appText("旧隧道未能关闭，请重试或重启应用。")
    TunnelFailureKind.UNKNOWN -> appText("未知错误")
    else -> null
}

internal fun tailscaleStageLabel(stage: TunnelUiStage): String = appText(
    when (stage) {
        TunnelUiStage.DISABLED -> "未启用"
        TunnelUiStage.STARTING -> "启动中"
        TunnelUiStage.NEEDS_LOGIN -> "需要登录"
        TunnelUiStage.WAITING_ROUTES -> "正在建立连接"
        TunnelUiStage.READY -> "已连接"
        TunnelUiStage.FAILED -> "连接失败"
    }
)

@Composable
private fun stageColor(state: TailscaleTunnelUiState): Color = when (state.stage) {
    TunnelUiStage.READY -> DshColors.Success
    TunnelUiStage.FAILED, TunnelUiStage.DISABLED -> tunnelRed()
    else -> TunnelBlue
}

@Composable
private fun LoginURLRow(url: String, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            url,
            Modifier.weight(1f).testTag("tunnel-login-url"),
            fontSize = 12.sp,
            color = tunnelSecondaryColor(),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp)
                .clickable {
                    onCopy()
                }.testTag("tunnel-login-copy"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                appText("复制"),
                color = if (isSystemInDarkTheme()) Color(0xFF0A84FF) else Color(0xFF007AFF),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
