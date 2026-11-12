package com.clarklevis.dsh.android.ui

import android.os.Build
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.detectTapGestures
import com.clarklevis.dsh.android.platform.saveImageToPhotos
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Base64
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AttachmentLoadState
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.localization.appTextKey
import com.clarklevis.dsh.android.platform.AndroidAttachmentThumbnailer
import com.clarklevis.dsh.android.platform.AndroidPreparedImage
import com.clarklevis.dsh.shared.facade.VoiceInputInteraction
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.clarklevis.dsh.shared.protocol.ComposerQuoteDraft

@OptIn(ExperimentalMaterial3Api::class)
internal val LocalMessageQuote = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun ConversationScreen(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit = {},
    onPlugins: () -> Unit = {},
    onScheduledTasks: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val quote: ((String) -> Unit)? = if ("message-quotes-v1" in stateHolder.gatewayState.capabilities) {
        { selected ->
            if (selected.isNotBlank()) {
                val text = stateHolder.composerText
                val start = minOf(stateHolder.messageSelectionStart, stateHolder.messageSelectionEnd).coerceIn(0, text.length)
                val end = maxOf(stateHolder.messageSelectionStart, stateHolder.messageSelectionEnd).coerceIn(start, text.length)
                stateHolder.messageDraft = ComposerQuoteDraft.insertQuote(stateHolder.messageDraft, selected, start, end)
                stateHolder.messageSelectionStart = start
                stateHolder.messageSelectionEnd = start
            }
        }
    } else null
    androidx.compose.runtime.CompositionLocalProvider(LocalMessageQuote provides quote) {
        ConversationScreenContent(stateHolder, onPickImage, onBack, onPickFile, onPlugins, onScheduledTasks, onSettings)
    }
}

@Composable
private fun ConversationScreenContent(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit = {},
    onPlugins: () -> Unit = {},
    onScheduledTasks: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val session = stateHolder.snapshot.sessions.firstOrNull { it.id == stateHolder.snapshot.selectedSessionId }
    val title = stateHolder.teamSnapshot.members.firstOrNull { it.id == stateHolder.snapshot.selectedSessionId && it.role != "lead" }?.name ?: session?.title ?: if (stateHolder.snapshot.selectedSessionId != null) appText("已缓存会话") else appText("新建 DeepSeek Harness")
    val agentPresetId = stateHolder.sessionAgentPreset.agentPreset
        .takeIf { stateHolder.sessionAgentPreset.sessionId == stateHolder.snapshot.selectedSessionId }
        ?: session?.agentPreset ?: stateHolder.snapshot.agentPresetDefault
    val agentPresetName = stateHolder.snapshot.agentPresets
        .firstOrNull { it.id == agentPresetId }
        ?.name
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissInput: () -> Unit = remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    var showStats by remember { mutableStateOf(false) }
    var showWorkspaceFiles by remember { mutableStateOf(false) }
    var workspaceInitialPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stateHolder.conversationDirectoryPath) {
        val path = stateHolder.conversationDirectoryPath ?: return@LaunchedEffect
        if (stateHolder.conversationDirectorySessionId != stateHolder.snapshot.selectedSessionId) {
            stateHolder.consumeConversationDirectoryPath()
            return@LaunchedEffect
        }
        workspaceInitialPath = path
        stateHolder.consumeConversationDirectoryPath()
        showWorkspaceFiles = true
    }
    val pendingApproval = stateHolder.snapshot.pendingApprovals.firstOrNull {
        it.sessionId == stateHolder.snapshot.selectedSessionId
    }
    LaunchedEffect(pendingApproval?.rpcId) {
        if (pendingApproval != null) {
            // 审批卡片位于对话页；收到当前会话的请求时立即显现，避免被轨迹页或键盘遮挡。
            dismissInput()
            pagerState.scrollToPage(0)
        }
    }
    LaunchedEffect(stateHolder.snapshot.selectedSessionId) {
        if (stateHolder.snapshot.selectedSessionId == null) onBack()
        else stateHolder.refreshSessionControls()
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != 0) dismissInput()
        stateHolder.setTrajectoryActive(pagerState.currentPage == 1)
    }
    val selectPage: (Int) -> Unit = { target ->
        dismissInput()
        scope.launch { pagerState.animateScrollToPage(target, animationSpec = tween(280)) }
    }
    DisposableEffect(stateHolder) {
        onDispose {
            stateHolder.setTrajectoryActive(false)
            stateHolder.leaveSessionAgentPreset()
        }
    }

    val compactHeader = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 600.dp
    val headerMode: @Composable () -> Unit = {
        ConversationHeaderMode(
            title = agentPresetDisplayName(agentPresetId, agentPresetName),
            connection = stateHolder.gatewayState.connection,
            compact = compactHeader
        )
    }
    val sidebarEntry: @Composable () -> Unit = {
        ConversationSidebarButton(
            workspaces = stateHolder.snapshot.workspaces,
            sessions = stateHolder.snapshot.sessions,
            selectedSessionId = stateHolder.snapshot.selectedSessionId,
            onOpen = dismissInput,
            onSelectSession = { id ->
                if (id != stateHolder.snapshot.selectedSessionId) stateHolder.selectSession(id)
            },
            onPlugins = onPlugins,
            onScheduledTasks = onScheduledTasks,
            onSettings = onSettings
        )
    }

    DshMenuBackdropHost { backdropModifier ->
        ConversationPagerChrome(
            pagerState = pagerState,
            sessionId = stateHolder.snapshot.selectedSessionId,
            onSelectPage = selectPage,
            modifier = backdropModifier.testTag("conversation-screen"),
            topBar = {
                ConversationTopBar(
                    title = {
                        ConversationPageTitle(title, pagerState.currentPage, status = if (compactHeader) headerMode else null)
                    },
                    navigationIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TopBarCircleButton(
                                iconRes = R.drawable.ic_back_chevron,
                                description = appText("返回"),
                                onClick = {
                                    dismissInput()
                                    onBack()
                                },
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                            )
                            if (!compactHeader) {
                                sidebarEntry()
                                Spacer(Modifier.width(14.dp))
                            }
                        }
                    },
                    actions = {
                        if (!compactHeader) {
                            Box(Modifier.padding(start = 4.dp, end = 8.dp)) { headerMode() }
                        }
                        if (stateHolder.teamSnapshot.visible) {
                            TeamEntryButton(stateHolder)
                            Spacer(Modifier.width(8.dp))
                        }
                        if (compactHeader) {
                            sidebarEntry()
                            Spacer(Modifier.width(8.dp))
                        }
                        ConversationMoreMenu(
                            canBrowseFiles = stateHolder.snapshot.selectedSessionId != null &&
                                stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                                "file-downloads" in stateHolder.gatewayState.capabilities,
                            onBrowseFiles = {
                                workspaceInitialPath = null
                                showWorkspaceFiles = true
                            },
                            canShowStats = stateHolder.snapshot.selectedSessionId != null,
                            onShowStats = {
                                dismissInput()
                                showStats = true
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                )
            }
        ) { page, topContentInset ->
            if (page == 0) {
                ConversationPage(
                    stateHolder = stateHolder,
                    onPickImage = onPickImage,
                    onPickFile = onPickFile,
                    onDismissInput = dismissInput,
                    topContentInset = topContentInset
                )
            } else {
                Box(Modifier.fillMaxSize().padding(top = topContentInset).navigationBarsPadding()) {
                    TrajectoryPage(
                        nodes = stateHolder.trajectoryNodes,
                        isActive = pagerState.currentPage == 1
                    )
                }
            }
        }
    }
    stateHolder.voiceInput.state.alertMessage?.let { message ->
        DshAlertDialog(
            title = appText("语音输入暂不可用"),
            message = appText(message),
            confirmLabel = appText("知道了"),
            onConfirm = stateHolder.voiceInput::dismissAlert,
            onDismissRequest = stateHolder.voiceInput::dismissAlert
        )
    }
    if (showStats) {
        SessionStatsSheet(stateHolder.snapshot.statsSnapshot) { showStats = false }
    }
    WorkspaceFilePreviewHost(stateHolder)

    if (showWorkspaceFiles) {
        WorkspaceFilesBottomSheet(stateHolder, initialPath = workspaceInitialPath) { showWorkspaceFiles = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    // Material TopAppBar 内部会 clipToBounds，裁掉圆形按钮超出栏底的阴影。
    // 保留原来的高度和安全区，用不裁切的容器承载浮动按钮。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        navigationIcon()
        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            title()
        }
        actions()
    }
}

@Composable
internal fun TopBarCircleButton(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopBarCircleButton(description = description, onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = dshGlassForeground()
        )
    }
}

@Composable
internal fun TopBarCircleButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    DshGlassButton(description, onClick, modifier.size(44.dp), content = content)
}

@Composable
internal fun ConversationMoreMenu(
    canBrowseFiles: Boolean,
    onBrowseFiles: () -> Unit,
    canShowStats: Boolean,
    onShowStats: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TopBarCircleButton(
            iconRes = R.drawable.ic_more_horizontal,
            description = appText("更多"),
            onClick = { expanded = true }
        )
        DshMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(200.dp).testTag("conversation-more-menu"),
            offset = DpOffset(x = (-192).dp, y = 8.dp)
        ) {
            ConversationMoreMenuItem(
                title = appText("工作区文件"),
                iconRes = R.drawable.ic_folder_outline,
                enabled = canBrowseFiles
            ) {
                expanded = false
                onBrowseFiles()
            }
            ConversationMoreMenuItem(
                title = appText("会话状态"),
                iconRes = R.drawable.ic_session_stats,
                enabled = canShowStats
            ) {
                expanded = false
                onShowStats()
            }
        }
    }
}

@Composable
private fun ConversationMoreMenuItem(
    title: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    DshMenuItem(
        text = {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        modifier = Modifier.height(44.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        minHeight = 44.dp,
        horizontalSpacing = 8.dp,
        onClick = onClick
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationPage(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onDismissInput: () -> Unit,
    topContentInset: Dp = 0.dp
) {
    val density = LocalDensity.current
    val sessionId = stateHolder.snapshot.selectedSessionId
    LaunchedEffect(sessionId) { stateHolder.fileUploads.select(sessionId, !stateHolder.isViewingTeamMember) }
    val bottomContentHeight = remember { mutableStateOf(160.dp) }
    var imagePreview by remember(sessionId) { mutableStateOf<ConversationImagePreviewRequest?>(null) }
    var isPinnedToBottom by remember(sessionId) { mutableStateOf(true) }
    var scrollToBottomToken by remember(sessionId) { mutableIntStateOf(0) }
    val imeIsVisible = WindowInsets.isImeVisible
    LaunchedEffect(stateHolder.cacheViewGeneration) {
        if (stateHolder.cacheViewGeneration > 0) scrollToBottomToken += 1
    }
    LaunchedEffect(imeIsVisible, sessionId) {
        if (imeIsVisible) scrollToBottomToken += 1
    }
    val approval = stateHolder.snapshot.pendingApprovals.firstOrNull {
        stateHolder.snapshot.selectedSessionId == null || it.sessionId == stateHolder.snapshot.selectedSessionId
    }
    val question = stateHolder.snapshot.pendingQuestions.firstOrNull {
        stateHolder.snapshot.selectedSessionId == null || it.sessionId == stateHolder.snapshot.selectedSessionId
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(sessionId) {
            ConversationTimeline(
                stateHolder = stateHolder,
                bottomContentHeight = bottomContentHeight,
                topContentInset = topContentInset,
                scrollToBottomToken = scrollToBottomToken,
                onPinnedToBottomChanged = { isPinnedToBottom = it },
                onUserInteraction = onDismissInput,
                onPreviewImages = { attachments, initialIndex ->
                    imagePreview = ConversationImagePreviewRequest(attachments, initialIndex)
                }
            )
        }
        if (stateHolder.localSearchContextSessionId == sessionId && sessionId != null) {
            Row(
                Modifier.align(
                    Alignment.TopCenter
                ).fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (stateHolder.localSearchIsOpening) {
                        appText("正在定位搜索结果")
                    } else if (stateHolder.localSearchIsFragment) {
                        appText("只读本地搜索片段")
                    } else {
                        appText("已定位搜索结果")
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = stateHolder::returnToLatestCached) { Text(appText("返回最新消息")) }
            }
        }
        val showInitialHistoryOverlay = shouldShowInitialHistoryOverlay(
            isLoading = stateHolder.snapshot.selectedHistoryIsLoading,
            isLoadingOlder = stateHolder.snapshot.selectedHistoryIsLoadingOlder,
            hasLocalContent = stateHolder.snapshot.conversation.isNotEmpty()
        )
        AnimatedVisibility(
            visible = showInitialHistoryOverlay,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(remember(topContentInset) { ComposerViewportPadding(bottomContentHeight, topContentInset) })
                .zIndex(1f),
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120))
        ) {
            HistoryLoadingOverlay(
                loadedEventCount = stateHolder.snapshot.selectedHistoryLoadedEventCount,
                totalEventCount = stateHolder.snapshot.selectedHistoryTotalEventCount
            )
        }
        ConversationBottomFade(
            modifier = Modifier.align(Alignment.BottomCenter),
            contentHeight = bottomContentHeight
        )
        ConversationComposerChrome(
            Modifier
                .align(Alignment.BottomCenter)
                .adaptiveContentWidth()
                .graphicsLayer { alpha = if (stateHolder.voiceInput.state.overlayVisible) 0f else 1f }
                .imePadding(),
            composerModifier = Modifier
                .testTag("conversation-composer-column")
                .onSizeChanged { size ->
                    bottomContentHeight.value = with(density) { size.height.toDp() }
                }
                .navigationBarsPadding(),
            jumpVisible = stateHolder.snapshot.conversation.isNotEmpty() && !isPinnedToBottom,
            jumpButton = {
                ScrollToBottomButton(
                    isGenerating = stateHolder.snapshot.sessions
                        .firstOrNull { it.id == sessionId }
                        ?.isRunning == true,
                    onClick = { scrollToBottomToken += 1 }
                )
            }
        ) {
            if (approval != null) {
                ApprovalRequestCard(
                    request = approval,
                    status = stateHolder.snapshot.approvalRequestStatuses[approval.rpcId]
                        ?: com.clarklevis.dsh.shared.facade.SharedApprovalStatusSnapshot("idle"),
                    commandPreview = stateHolder.snapshot.approvalCommandPreviews[approval.rpcId],
                    details = stateHolder.snapshot.approvalDetails[approval.rpcId],
                    onDecision = { stateHolder.respondToApproval(approval.rpcId, it) }
                )
            } else {
                AnimatedHumanQuestionPanel(
                    request = question,
                    onAnswer = { answeredRequest, answers ->
                        stateHolder.answerQuestion(
                            answeredRequest.rpcId,
                            answeredRequest.sessionId,
                            answers
                        )
                    },
                    onCancel = { cancelledRequest ->
                        stateHolder.cancelQuestion(cancelledRequest.rpcId, cancelledRequest.sessionId)
                    }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Pending interaction cards replace normal composer
                        // chrome. Stats/tasks/goals must not consume height and
                        // push the approval or question actions off screen.
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (!stateHolder.isViewingTeamMember) SessionAgentPresetControl(
                                state = stateHolder.sessionAgentPreset,
                                onSelect = stateHolder::selectSessionAgentPreset,
                                onRetry = stateHolder::refreshSessionAgentPreset
                            )
                            Spacer(Modifier.weight(1f))
                            if (stateHolder.showsNewConversationWorkspace) NewConversationWorkspaceControl(
                                selectedId = stateHolder.newConversationWorkspace.workspaceId,
                                workspaces = stateHolder.availableWorkspaces,
                                changing = stateHolder.isChangingNewConversationWorkspace,
                                enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                                    !stateHolder.fileUploads.preparing && !stateHolder.preparingImages,
                                onSelect = stateHolder::selectNewConversationWorkspace
                            )
                        }
                        TaskGoalPanels(
                            stateHolder = stateHolder,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        SlashCommandMenus(
                            stateHolder = stateHolder,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        Composer(stateHolder, onPickImage, onPickFile, onDismissInput)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = stateHolder.voiceInput.state.overlayVisible,
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth().height(adaptiveWindowSize().height * VoiceInputInteraction.OVERLAY_HEIGHT_FRACTION.toFloat()).zIndex(3f),
            enter = fadeIn(tween(VoiceInputInteraction.OVERLAY_FADE_IN_MILLISECONDS, easing = LinearOutSlowInEasing)) +
                slideInVertically(
                    animationSpec = tween(VoiceInputInteraction.OVERLAY_FADE_IN_MILLISECONDS, easing = LinearOutSlowInEasing),
                    initialOffsetY = { with(density) { VoiceInputInteraction.OVERLAY_ENTER_OFFSET.dp.roundToPx() } }
                ),
            exit = fadeOut(tween(VoiceInputInteraction.OVERLAY_FADE_OUT_MILLISECONDS))
        ) {
            VoiceInputPanel(
                voice = stateHolder.voiceInput,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    imagePreview?.let { request ->
        ConversationImagePreviewDialog(
            request = request,
            thumbnails = stateHolder.attachmentThumbnails,
            loadOriginal = { attachment -> sessionId?.let { stateHolder.imageAttachmentData(it, attachment.attachmentId) } },
            onDismiss = { imagePreview = null }
        )
    }
}

internal fun shouldShowInitialHistoryOverlay(
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    hasLocalContent: Boolean
): Boolean = isLoading && !isLoadingOlder && !hasLocalContent

internal fun historyLoadingProgressText(loadedEventCount: Int, totalEventCount: Int?): String = when {
    totalEventCount != null -> appText("正在加载历史记录 · %1\$s/%2\$s", loadedEventCount, totalEventCount)
    loadedEventCount > 0 -> appText("正在自动加载更早记录 · 已同步 %1\$s 个事件", loadedEventCount)
    else -> appText("正在从 Mobile Gateway 同步会话内容…")
}

@Composable
internal fun HistoryLoadingOverlay(
    loadedEventCount: Int,
    totalEventCount: Int?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("history-loading-overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(44.dp),
                strokeWidth = 4.dp,
                color = DshColors.Ocean,
                trackColor = DshColors.Ocean.copy(alpha = 0.20f)
            )
            Text(
                appText("正在加载历史记录"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                historyLoadingProgressText(loadedEventCount, totalEventCount),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.50f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ConversationBottomFade(
    contentHeight: State<Dp>,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height = (contentHeight.value - 44.dp).coerceAtLeast(0.dp).roundToPx()
                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                val child = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.3f to background,
                        1f to background
                    )
                )
            )
    )
}

@Composable
private fun ConversationTimeline(
    stateHolder: AndroidSharedStateHolder,
    bottomContentHeight: State<Dp>,
    topContentInset: Dp,
    scrollToBottomToken: Int,
    onPinnedToBottomChanged: (Boolean) -> Unit,
    onUserInteraction: () -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val latestItems = stateHolder.snapshot.conversation
    val selectedSessionId = stateHolder.snapshot.selectedSessionId
    val latestHistoryLoading = selectedSessionId in stateHolder.historyPagingSessionIds
    val selectedSessionIsRunning = stateHolder.selectedSessionIsRunning
    val latestOnUserInteraction by rememberUpdatedState(onUserInteraction)
    val latestSnapshot by rememberUpdatedState(stateHolder.snapshot)
    val latestRunning by rememberUpdatedState(selectedSessionIsRunning)
    var presentation by remember(selectedSessionId) {
        mutableStateOf(prepareConversationPresentation(latestItems, selectedSessionIsRunning,
            stateHolder.snapshot.selectedHistoryEarliestSequence))
    }
    val items = presentation.items
    val timelineEntries = presentation.entries
    val activeStreamingMessageId = presentation.streamingId
    val attachmentIdsByTimelineId = remember(timelineEntries) {
        timelineEntries.associate { entry ->
            entry.id to entry.images.mapTo(mutableSetOf()) { it.attachmentId }
        }
    }
    val hasInitialContent = timelineEntries.isNotEmpty() || selectedSessionIsRunning
    val lastTimelineIndex = (
        timelineEntries.lastIndex +
            (if (selectedSessionIsRunning) 1 else 0)
    ).coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = lastTimelineIndex
    )
    var initialPositionApplied by remember { mutableStateOf(false) }
    var isPinnedToBottom by remember { mutableStateOf(true) }
    var programmaticScrollCount by remember { mutableIntStateOf(0) }
    var timelineUpdatesPaused by remember(selectedSessionId) { mutableStateOf(false) }
    var touchingTimeline by remember { mutableStateOf(false) }
    var interactionGeneration by remember { mutableIntStateOf(0) }
    var presentingHistory by remember { mutableStateOf(false) }
    var revealIds by remember { mutableStateOf(emptySet<String>()) }
    val revealAlpha = remember { Animatable(1f) }
    val isUserTimelineScrolling by remember(listState) {
        derivedStateOf { listState.isScrollInProgress && programmaticScrollCount == 0 }
    }
    val hasQueuedHistory = presentation.earliestSequence?.let { old ->
        stateHolder.snapshot.selectedHistoryEarliestSequence?.let { it < old }
    } == true
    var historyReadBatch by remember(selectedSessionId) { mutableStateOf<HistoryReadBatch?>(null) }
    val historyBusy = latestHistoryLoading || stateHolder.snapshot.selectedHistoryIsLoadingOlder ||
        hasQueuedHistory || presentingHistory || historyReadBatch != null
    val latestHistoryBusy by rememberUpdatedState(historyBusy)
    val readWindowDensity = LocalDensity.current
    var historyEdgeRevealed by remember(selectedSessionId) { mutableStateOf(false) }
    val edgePullThreshold = with(LocalDensity.current) { 8.dp.toPx() }
    val historyEdgePull = remember(selectedSessionId, edgePullThreshold) { HistoryEdgePull(edgePullThreshold) }
    val requestOlderHistory by rememberUpdatedState {
        if (initialPositionApplied && hasInitialContent && latestSnapshot.selectedHistoryHasMore && !latestHistoryBusy) {
            val before = latestSnapshot.selectedHistoryEarliestSequence
            if (before != null) {
                val viewport = listState.layoutInfo.viewportSize
                val batch = HistoryReadBatch(
                    originalIds = presentation.items.mapTo(mutableSetOf()) { it.id },
                    beforeSequence = before,
                    viewportHeightDp = (viewport.height / readWindowDensity.density - bottomContentHeight.value.value - 32f).coerceAtLeast(160f),
                    contentWidthDp = (viewport.width / readWindowDensity.density - 40f).coerceAtLeast(120f),
                    fontScale = readWindowDensity.fontScale,
                    startedAtMillis = android.os.SystemClock.elapsedRealtime()
                )
                historyEdgeRevealed = true
                isPinnedToBottom = false
                onPinnedToBottomChanged(false)
                if (stateHolder.loadOlderHistory()) {
                    historyReadBatch = batch
                    com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=read-batch-start")
                }
            }
        }
    }
    val historyEdgeScroll = remember(selectedSessionId, listState, historyEdgePull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 只在真正到顶后继续拖动时请求；惯性、程序定位、靠近顶部均不触发。
                if (historyEdgePull.shouldRequest(
                        deltaY = available.y,
                        atStart = !listState.canScrollBackward,
                        userInput = source == NestedScrollSource.UserInput && touchingTimeline &&
                            kotlin.math.abs(available.y) > kotlin.math.abs(available.x),
                        canLoad = programmaticScrollCount == 0 && !latestHistoryBusy
                    )) requestOlderHistory()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(selectedSessionId, listState) {
        snapshotFlow { listState.canScrollBackward }.collect { canScrollBackward ->
            if (canScrollBackward) historyEdgeRevealed = false
        }
    }
    val markdownPreloader = rememberDshMarkdownPreloader()
    LaunchedEffect(selectedSessionId, listState, markdownPreloader) {
        var preparedWhileMoving: ConversationTimelinePresentation? = null
        suspend fun warmReadingWindow(target: ConversationTimelinePresentation) {
            val visible = listState.layoutInfo.visibleItemsInfo.map { HistoryVisibleRow(it.key.toString(), it.offset) }
            val anchor = historyReadingAnchor(presentation.entries, target.entries, visible)
            val center = anchor?.index ?: 0
            markdownPreloader.preload(target.entries.subList((center - 3).coerceAtLeast(0),
                (center + 4).coerceAtMost(target.entries.size))
                .filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>().map { it.markdown })
        }
        snapshotFlow { HistoryPresentationTarget(latestSnapshot.conversation,
            latestSnapshot.selectedHistoryEarliestSequence, latestRunning, isUserTimelineScrolling || touchingTimeline,
            interactionGeneration, selectedSessionId in stateHolder.historyPagingSessionIds || latestSnapshot.selectedHistoryIsLoadingOlder,
            latestSnapshot.selectedHistoryHasMore, latestSnapshot.selectedHistoryError != null,
            historyReadBatch != null) }
            .distinctUntilChanged()
            .collect { targetState ->
                val batch = historyReadBatch
                if (batch != null) {
                    // 传输页不是阅读页。补页期间只累计数据，保留当前显示快照。
                    if (!targetState.readingBatch || targetState.loadingOlder) return@collect
                    val window = withContext(Dispatchers.Default) { historyReadWindow(batch, targetState.items) }
                    val stopReason = historyReadBatchStopReason(batch, window, targetState.earliestSequence,
                        targetState.hasMore, targetState.failed, android.os.SystemClock.elapsedRealtime())
                    if (stopReason == null) {
                        historyReadBatch = batch.copy(beforeSequence = requireNotNull(targetState.earliestSequence), pages = batch.pages + 1)
                        if (stateHolder.loadOlderHistory()) {
                            com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=read-batch-continue pages=${batch.pages + 1} " +
                                "messages=${window.messages} heightDp=${window.estimatedHeightDp.toInt()}")
                            return@collect
                        }
                    }
                    historyReadBatch = null
                    com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=read-batch-ready pages=${batch.pages + 1} " +
                        "messages=${window.messages} heightDp=${window.estimatedHeightDp.toInt()} reason=${stopReason ?: "unavailable"}")
                }
                if (targetState.moving) {
                    timelineUpdatesPaused = true
                    val older = presentation.earliestSequence?.let { old -> targetState.earliestSequence?.let { it < old } } == true
                    if (older && preparedWhileMoving?.items !== targetState.items) {
                        val target = withContext(Dispatchers.Default) {
                            prepareConversationPresentation(targetState.items, targetState.running, targetState.earliestSequence)
                        }
                        warmReadingWindow(target)
                        preparedWhileMoving = target
                        com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=background-ready entries=${target.entries.size} moving=true")
                    }
                    return@collect
                }
                if (timelineUpdatesPaused) {
                    delay(TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS)
                    if (isUserTimelineScrolling || touchingTimeline) return@collect
                    timelineUpdatesPaused = false
                }
                val source = latestSnapshot
                val running = latestRunning
                if (source.conversation === presentation.items && running == presentation.running &&
                    source.selectedHistoryEarliestSequence == presentation.earliestSequence) return@collect
                val generation = interactionGeneration
                val older = presentation.earliestSequence?.let { old ->
                    source.selectedHistoryEarliestSequence?.let { it < old }
                } == true
                val began = android.os.SystemClock.elapsedRealtime()
                presentingHistory = older
                if (older) com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=prepare-start rows=${source.conversation.size}")
                try {
                    val target = preparedWhileMoving?.takeIf { it.items === source.conversation &&
                        it.running == running && it.earliestSequence == source.selectedHistoryEarliestSequence }
                        ?: withContext(Dispatchers.Default) {
                            prepareConversationPresentation(source.conversation, running, source.selectedHistoryEarliestSequence)
                        }
                    preparedWhileMoving = null
                    if (older) {
                        // 只预热即将进入阅读窗口的几行，不等待整页Markdown都渲染完。
                        warmReadingWindow(target)
                        com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=prepare-ready entries=${target.entries.size} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - began}")
                        withFrameNanos { }
                    }
                    if (isUserTimelineScrolling || touchingTimeline || generation != interactionGeneration) return@collect
                    val oldIds = presentation.entries.mapTo(mutableSetOf()) { it.id }
                    val structureChanged = presentation.entries.map { it.id } != target.entries.map { it.id }
                    val anchor = if (structureChanged && initialPositionApplied && !isPinnedToBottom) {
                        historyReadingAnchor(presentation.entries, target.entries,
                            listState.layoutInfo.visibleItemsInfo.filter { it.offset + it.size > listState.layoutInfo.viewportStartOffset }
                                .map { HistoryVisibleRow(it.key.toString(), it.offset) })
                    } else null
                    revealIds = if (older) target.entries.mapTo(mutableSetOf()) { it.id }.subtract(oldIds) else emptySet()
                    revealAlpha.snapTo(if (revealIds.isEmpty()) 1f else 0f)
                    Snapshot.withMutableSnapshot {
                        presentation = target
                        if (anchor != null) listState.requestScrollToItem(anchor.index, -anchor.offset)
                    }
                    if (older) {
                        var stable = 0
                        var previous: List<Pair<Any, Int>>? = null
                        while (stable < 2 && generation == interactionGeneration && !isUserTimelineScrolling && !touchingTimeline) {
                            withFrameNanos { }
                            val geometry = listState.layoutInfo.visibleItemsInfo.map { it.key to it.offset }
                            stable = if (geometry == previous && listState.layoutInfo.totalItemsCount ==
                                target.entries.size + if (latestRunning) 1 else 0) stable + 1 else 0
                            previous = geometry
                        }
                        val actual = anchor?.let { saved -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == saved.id } }
                        com.clarklevis.dsh.android.platform.LocalAppLog.d("HistoryPresentation", "stage=presented inserted=${revealIds.size} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime() - began} " +
                            "anchorValid=${actual != null} anchorErrorPx=${actual?.let { it.offset - requireNotNull(anchor).offset }} " +
                            "interrupted=${generation != interactionGeneration}")
                    }
                    presentingHistory = false
                    if (revealIds.isNotEmpty() && generation == interactionGeneration && !touchingTimeline && !isUserTimelineScrolling) {
                        revealAlpha.animateTo(1f, tween(180))
                    } else revealAlpha.snapTo(1f)
                    revealIds = emptySet()
                } finally {
                    presentingHistory = false
                    revealIds = emptySet()
                }
            }
    }
    LaunchedEffect(listState, timelineEntries, markdownPreloader) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: timelineEntries.lastIndex
            val last = visible.lastOrNull()?.index ?: timelineEntries.lastIndex
            first to last
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                val start = (first - MARKDOWN_PREFETCH_ROWS).coerceAtLeast(0)
                val end = (last + MARKDOWN_PREFETCH_ROWS).coerceAtMost(timelineEntries.lastIndex)
                if (start <= end) {
                    markdownPreloader.preload(
                        timelineEntries.subList(start, end + 1)
                            .filterIsInstance<ConversationTimelineEntry.AssistantMarkdown>()
                            .filterNot { it.messageId == activeStreamingMessageId }
                            .map(ConversationTimelineEntry.AssistantMarkdown::markdown)
                    )
                }
            }
    }
    val window = adaptiveWindowSize()
    val density = LocalDensity.current
    val thumbnailWidth = with(density) { minOf(window.width, AdaptiveLayout.PageWidth).roundToPx() }
    val targetHeight = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(thumbnailWidth, targetHeight) {
        stateHolder.updateThumbnailTargetSize(thumbnailWidth, targetHeight)
    }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMapTo(mutableSetOf()) { visible ->
                attachmentIdsByTimelineId[visible.key.toString()].orEmpty()
            }
        }.distinctUntilChanged().collect(stateHolder::updateVisibleAttachments)
    }
    val searchTarget = stateHolder.localSearchScrollTarget?.takeIf { it.sessionId == selectedSessionId }
    LaunchedEffect(searchTarget, timelineEntries) {
        val target = searchTarget ?: return@LaunchedEffect
        val messageId = "${target.sessionId}-${target.seq}"
        val index = timelineEntries.indexOfFirst {
            it.id == messageId || it.id.startsWith("$messageId:") || it.id.startsWith("assistant:$messageId:")
        }
        if (index >= 0) {
            programmaticScrollCount += 1
            try {
                isPinnedToBottom = false
                onPinnedToBottomChanged(false)
                listState.scrollToItem(index)
                initialPositionApplied = true
                stateHolder.consumeLocalSearchScrollTarget()
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    LaunchedEffect(hasInitialContent, searchTarget) {
        if (!initialPositionApplied && hasInitialContent && searchTarget == null) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(lastTimelineIndex, animated = false)
                initialPositionApplied = true
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    val hasStreamingItem = selectedSessionIsRunning
    LaunchedEffect(
        timelineEntries.size,
        items.lastOrNull()?.text?.length,
        initialPositionApplied,
        hasStreamingItem
    ) {
        if (initialPositionApplied && isPinnedToBottom && hasInitialContent && searchTarget == null) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(
                    lastTimelineIndex,
                    animated = !hasStreamingItem
                )
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    LaunchedEffect(scrollToBottomToken) {
        if (scrollToBottomToken > 0 && hasInitialContent) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(lastTimelineIndex, animated = true)
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    LaunchedEffect(listState, initialPositionApplied) {
        if (!initialPositionApplied) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.isPinnedToBottom(),
                programmaticScrollCount > 0
            )
        }
            .distinctUntilChanged()
            .collect { (isScrolling, pinned, isProgrammaticScroll) ->
                // A new streamed row briefly makes the old last row stop being the
                // final item before auto-scroll runs. Only a real user scroll may
                // unpin; automatic movement must not flash the jump button.
                if (shouldPublishPinnedState(isScrolling, pinned, isProgrammaticScroll)) {
                    isPinnedToBottom = pinned
                    onPinnedToBottomChanged(pinned)
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("conversation-timeline")
            .pointerInput(selectedSessionId) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    touchingTimeline = true
                    historyEdgePull.reset()
                    interactionGeneration += 1
                    revealIds = emptySet()
                    latestOnUserInteraction()
                    try {
                        // 观察实际按压生命周期；列表消费拖动事件不代表手指已经抬起。
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        touchingTimeline = false
                    }
                }
            }
    ) {
        if (items.isEmpty() && latestItems.isEmpty() && (!selectedSessionIsRunning || stateHolder.offlineBrowsing) && !stateHolder.snapshot.selectedHistoryIsLoading) {
            val error = stateHolder.snapshot.selectedHistoryError
            if (stateHolder.offlineBrowsing) {
                Text(
                    appText("当前离线，此会话尚未缓存。连接网关后可加载聊天内容。"),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp).padding(top = topContentInset, bottom = 150.dp)
                        .testTag("offline-history-unavailable"),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (error == null) {
                EmptyConversation(Modifier.align(Alignment.Center).padding(top = topContentInset, bottom = 150.dp))
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp).padding(top = topContentInset, bottom = 150.dp)
                        .testTag("history-load-failure"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(appText("历史记录加载失败"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (error.contains("refuses this format")) {
                            appText("Host 无法读取旧格式会话，请修复或升级 Host 后重试。原始记录未修改。")
                        } else appText(error),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 6
                    )
                    TextButton(onClick = stateHolder::reloadSelectedHistory) { Text(appText("重新加载历史")) }
                }
            }
        }
        if (historyEdgeRevealed && !listState.canScrollBackward &&
            (historyBusy || stateHolder.snapshot.selectedHistoryHasMore)) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = topContentInset).heightIn(min = 32.dp).zIndex(2f),
                contentAlignment = Alignment.Center) {
                if (historyBusy) {
                    Row(Modifier.testTag("history-paging-progress").padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(appText(if (latestHistoryLoading || stateHolder.snapshot.selectedHistoryIsLoadingOlder)
                            "正在加载更早记录…" else "正在准备会话"), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(appText("加载更早记录"), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("history-load-more")
                            .clickable(onClick = requestOlderHistory)
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        val markdownImages = rememberDshMarkdownImages(stateHolder, selectedSessionId)
        androidx.compose.runtime.CompositionLocalProvider(LocalDshMarkdownImages provides markdownImages) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(historyEdgeScroll)
                    .adaptiveContentWidth().testTag("conversation-message-list")
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .alpha(if (hasInitialContent && !initialPositionApplied) 0f else 1f),
                contentPadding = remember(topContentInset) {
                    ComposerViewportPadding(bottomContentHeight, topContentInset + 8.dp, 22.dp)
                }
            ) {
                items(
                    items = timelineEntries,
                    key = ConversationTimelineEntry::id,
                    contentType = ConversationTimelineEntry::contentType
                ) { entry ->
                    Box(Modifier.testTag("conversation-entry:${entry.id}").graphicsLayer {
                        alpha = if (entry.id in revealIds) revealAlpha.value else 1f
                    }) {
                        when (entry) {
                            is ConversationTimelineEntry.Display -> when (val display = entry.entry) {
                                is ConversationDisplayEntry.Message -> ConversationRow(
                                    item = display.item,
                                    thumbnails = stateHolder.attachmentThumbnails,
                                    attachmentStates = stateHolder.attachmentStates,
                                    onRetryAttachment = stateHolder::retryAttachment,
                                    onPreviewImages = onPreviewImages
                                )
                                is ConversationDisplayEntry.Process -> ConversationProcessRow(display.group)
                            }
                            is ConversationTimelineEntry.AssistantHeader -> AssistantMessageHeader(
                                item = entry.item,
                                thumbnails = stateHolder.attachmentThumbnails,
                                states = stateHolder.attachmentStates,
                                onRetry = stateHolder::retryAttachment
                            )
                            is ConversationTimelineEntry.AssistantMarkdown -> DshLazyMarkdownText(
                                markdown = entry.markdown,
                                copyMarkdown = entry.copyMarkdown,
                                knownFiles = entry.knownFiles,
                                onOpenFile = { path ->
                                    selectedSessionId?.let { session ->
                                        stateHolder.openConversationFile(stateHolder.gatewayLocalId, session, path)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                            is ConversationTimelineEntry.AssistantFooter -> Column(
                                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                entry.files.forEach { file ->
                                    FileReferenceCard(
                                        path = file.path,
                                        description = file.description,
                                        modifier = Modifier.align(Alignment.End),
                                        onClick = {
                                            selectedSessionId?.let { session ->
                                                stateHolder.openConversationFile(stateHolder.gatewayLocalId, session, file.path)
                                            }
                                        }
                                    )
                                }
                                AssistantMessageActions(entry.text)
                            }
                        }
                    }
                }
                if (selectedSessionIsRunning) {
                    item(key = "deep-search-progress", contentType = "deep-search-progress") {
                        DeepSearchProgressRow(
                            timing = stateHolder.snapshot.activeTurnTiming,
                            cues = items.drop(items.indexOfLast { it.kind == ConversationItemKind.USER } + 1)
                                .filter { it.kind != ConversationItemKind.SYSTEM }
                                .map { DeepSearchProgressCue(it.kind, it.epochSeconds, it.isError) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeepSearchProgressRow(
    timing: com.clarklevis.dsh.shared.facade.SharedTurnTimingSnapshot?,
    cues: List<DeepSearchProgressCue>
) {
    val startMilliseconds = ((timing?.startEpochSeconds ?: 0.0) * 1_000).toLong()
    var elapsedSeconds by remember(timing) { mutableIntStateOf(0) }
    LaunchedEffect(timing) {
        if (timing == null) return@LaunchedEffect
        while (true) {
            elapsedSeconds = timing.elapsedSeconds(android.os.SystemClock.elapsedRealtimeNanos() / 1e9)
                .coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            delay(1_000)
        }
    }
    val bucket = elapsedSeconds / DEEP_SEARCH_PHRASE_INTERVAL_SECONDS
    // 同一 30 秒窗口内锁定文案，工具事件和流式 token 不会触发中途换词。
    val phrase = remember(startMilliseconds, bucket) {
        val cutoff = startMilliseconds / 1_000.0 + bucket * DEEP_SEARCH_PHRASE_INTERVAL_SECONDS
        val activity = deepSearchActivityAt(cues, cutoff)
        val options = DEEP_SEARCH_PHRASES.getValue(activity)
        options[((startMilliseconds / 1_000L + bucket) % options.size).toInt()]
    }

    val darkTheme = isSystemInDarkTheme()
    val base = if (darkTheme) Color(0xFFA8BEDB) else Color(0xFF18365A)
    val highlight = if (darkTheme) Color(0xFFD5E3F4) else Color(0xFF5B7FA9)
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val whaleSprite = remember(context) {
        BitmapFactory.decodeResource(context.resources, R.drawable.running_whale_tail_sprite).asImageBitmap()
    }
    val whaleFrame by rememberInfiniteTransition(label = "running-whale-tail").animateFloat(
        initialValue = 0f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "running-whale-tail-frame"
    )
    val shimmer by rememberInfiniteTransition(label = "deep-search-shimmer").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "deep-search-shimmer-position"
    )
    var textWidth by remember { mutableIntStateOf(1) }
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset((shimmer - 0.24f) * textWidth, 0f),
        end = Offset((shimmer + 0.24f) * textWidth, 0f)
    )

    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp)
            .testTag("deep-search-progress")
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.025f)
        )
        Row(
            modifier = Modifier.padding(top = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Web UI 的运行鲸尾是 60 帧 APNG 遮罩；这里使用同源帧图保持外观和时序。
            Canvas(modifier = Modifier.size(17.dp)) {
                drawImage(
                    image = whaleSprite,
                    srcOffset = IntOffset((if (reduceMotion) 0 else whaleFrame.toInt().coerceIn(0, 59)) * 28, 0),
                    srcSize = IntSize(28, 28),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    colorFilter = ColorFilter.tint(base)
                )
            }
            Text(
                text = if (timing == null) appText("正在同步执行状态…")
                    else appText("%1\$s，用时 %2\$s 秒…", appText(phrase), elapsedSeconds),
                modifier = Modifier.onSizeChanged { textWidth = it.width.coerceAtLeast(1) },
                style = TextStyle(brush = shimmerBrush, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                maxLines = 1
            )
        }
    }
}

private const val DEEP_SEARCH_PHRASE_INTERVAL_SECONDS = 30

private data class DeepSearchProgressCue(
    val kind: ConversationItemKind,
    val epochSeconds: Double,
    val isError: Boolean
)

private enum class DeepSearchActivity { THINKING, GATHERING, EXPLORING, REVIEWING, REVISITING, COMPOSING, WORKING }

private fun deepSearchActivityAt(cues: List<DeepSearchProgressCue>, cutoff: Double): DeepSearchActivity {
    var activity = DeepSearchActivity.THINKING
    var hasReasoned = false
    var hasToolResult = false
    for (cue in cues) {
        if (cue.epochSeconds >= cutoff) continue
        activity = when (cue.kind) {
            ConversationItemKind.CONTEXT -> DeepSearchActivity.GATHERING
            ConversationItemKind.REASONING -> {
                val next = if (hasReasoned || hasToolResult) DeepSearchActivity.REVISITING else DeepSearchActivity.THINKING
                hasReasoned = true
                next
            }
            ConversationItemKind.TOOL, ConversationItemKind.JSON_TOOL -> DeepSearchActivity.EXPLORING
            ConversationItemKind.TOOL_RESULT -> {
                hasToolResult = true
                if (cue.isError) DeepSearchActivity.REVISITING else DeepSearchActivity.REVIEWING
            }
            ConversationItemKind.ASSISTANT -> DeepSearchActivity.COMPOSING
            ConversationItemKind.STATUS -> DeepSearchActivity.WORKING
            ConversationItemKind.USER, ConversationItemKind.GENERATED_IMAGES, ConversationItemKind.SYSTEM -> activity
        }
    }
    return activity
}

private val DEEP_SEARCH_PHRASES = mapOf(
    DeepSearchActivity.THINKING to listOf(appTextKey("梳理脉络中"), appTextKey("推敲细节中"), appTextKey("酝酿下一步中"), appTextKey("沿着思路前行中")),
    DeepSearchActivity.GATHERING to listOf(appTextKey("翻阅已有线索中"), appTextKey("收拢相关信息中"), appTextKey("寻找切入点中"), appTextKey("铺开问题脉络中")),
    DeepSearchActivity.EXPLORING to listOf(appTextKey("向外寻找线索中"), appTextKey("打捞新线索中"), appTextKey("沿途探查中"), appTextKey("搜集更多拼图中")),
    DeepSearchActivity.REVIEWING to listOf(appTextKey("对照新发现中"), appTextKey("拼合新线索中"), appTextKey("检视所得中"), appTextKey("把发现串起来中")),
    DeepSearchActivity.REVISITING to listOf(appTextKey("回看已有线索中"), appTextKey("重新校准方向中"), appTextKey("再推敲一遍中"), appTextKey("换个角度审视中")),
    DeepSearchActivity.COMPOSING to listOf(appTextKey("组织回应中"), appTextKey("整理表达中"), appTextKey("将思路落成文字中"), appTextKey("勾勒答案中")),
    DeepSearchActivity.WORKING to listOf(appTextKey("继续推进中"), appTextKey("稳步探索中"), appTextKey("梳理当前进展中"), appTextKey("逐步展开中"))
)

private const val TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS = 120L
private const val MARKDOWN_PREFETCH_ROWS = 24

private fun androidx.compose.foundation.lazy.LazyListState.isPinnedToBottom(): Boolean {
    return isTimelinePinnedToBottom(
        totalItems = layoutInfo.totalItemsCount,
        canScrollForward = canScrollForward
    )
}

internal fun timelineBottomScrollDelta(
    lastItemOffset: Int,
    lastItemSize: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int
): Float = (
    lastItemOffset + lastItemSize + afterContentPadding - viewportEndOffset
).coerceAtLeast(0).toFloat()

internal suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToTimelineBottom(
    lastItemIndex: Int,
    animated: Boolean
) {
    if (layoutInfo.visibleItemsInfo.none { it.index == lastItemIndex }) {
        if (animated) animateScrollToItem(lastItemIndex) else scrollToItem(lastItemIndex)
    }
    fun remainingDistance(): Float {
        val lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastItemIndex }
            ?: return 0f
        return timelineBottomScrollDelta(
            lastItemOffset = lastItem.offset,
            lastItemSize = lastItem.size,
            viewportEndOffset = layoutInfo.viewportEndOffset,
            afterContentPadding = layoutInfo.afterContentPadding
        )
    }
    val distance = remainingDistance()
    if (distance > 0f) {
        if (animated) animateScrollBy(distance) else scrollBy(distance)
    }
    // The last row can remeasure while Markdown, images, or the composer are
    // settling. End with an exact correction so the list has no forward range.
    val correction = remainingDistance()
    if (correction > 0f) scrollBy(correction)
}

internal fun isTimelinePinnedToBottom(
    totalItems: Int,
    canScrollForward: Boolean
): Boolean = totalItems == 0 || !canScrollForward

internal fun shouldPublishPinnedState(
    isScrolling: Boolean,
    pinned: Boolean,
    isProgrammaticScroll: Boolean
): Boolean = pinned || (isScrolling && !isProgrammaticScroll)

@Composable
internal fun ScrollToBottomButton(isGenerating: Boolean, onClick: () -> Unit) {
    DshGlassButton(
        description = if (isGenerating) appText("正在生成，滚动到最新消息") else appText("滚动到最新消息"),
        onClick = onClick,
        modifier = Modifier.padding(bottom = 4.dp).size(48.dp).testTag("scroll-to-bottom"),
    ) {
        if (isGenerating) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = DshColors.Ocean)
        } else {
            Icon(painterResource(R.drawable.ic_arrow_down), null, Modifier.size(20.dp), tint = dshGlassForeground())
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WhalenessOrcaIcon(Modifier.size(52.dp))
        Text(appText("操作远端 DSH Agent"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            appText("发送任务后，工具调用、推理进度和最终回复会通过 Mobile Gateway 实时返回。"),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onDismissInput: () -> Unit
) {
    val voice = stateHolder.voiceInput
    val hapticView = LocalView.current
    val modeSwitchFeedback = remember(hapticView) { ClickHapticFeedback(hapticView) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val queueEditFocusRequester = remember { FocusRequester() }
    var requestTextFocus by remember { mutableStateOf(false) }
    var showsFileUploadUpgradeAlert by remember { mutableStateOf(false) }
    if (showsFileUploadUpgradeAlert) {
        DshAlertDialog(
            title = appText("请升级网关"),
            message = appText("当前网关不支持文件上传，请升级到支持文件上传的 Mobile Gateway，并重新连接。"),
            confirmLabel = appText("好"),
            onConfirm = { showsFileUploadUpgradeAlert = false },
            onDismissRequest = { showsFileUploadUpgradeAlert = false }
        )
    }
    LaunchedEffect(requestTextFocus, voice.state.voiceMode) {
        if (requestTextFocus && !voice.state.voiceMode) {
            queueEditFocusRequester.requestFocus()
            requestTextFocus = false
        }
    }
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { voice.permissionResult(it) }
    DisposableEffect(voice, lifecycleOwner, hapticView) {
        voice.onPermissionNeeded = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
        voice.onHapticFeedback = { cue ->
            val feedbackType = when (cue) {
                "hapticStart" -> HapticFeedbackConstants.LONG_PRESS
                "hapticCancel" -> HapticFeedbackConstants.CLOCK_TICK
                "hapticRelease" -> HapticFeedbackConstants.KEYBOARD_TAP
                else -> null
            }
            feedbackType?.let { hapticView.performHapticFeedback(it) }
        }
        voice.onDraftUpdate = { stateHolder.composerText = it }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) voice.suspendCapture()
            if (event == Lifecycle.Event.ON_RESUME) voice.resumeCapture()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voice.leave()
            voice.onPermissionNeeded = null
            voice.onDraftUpdate = null
            voice.onHapticFeedback = null
        }
    }
    LaunchedEffect(voice.state.inputBlocked, voice.state.voiceMode) {
        if (voice.state.inputBlocked || voice.state.voiceMode) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { voice.keyboardChanged(imeVisible) }
    LaunchedEffect(stateHolder.snapshot.selectedSessionId) {
        voice.open(stateHolder.snapshot.selectedSessionId ?: "new-session", stateHolder.composerText)
    }
    val shape = RoundedCornerShape(24.dp)
    val isDark = isSystemInDarkTheme()
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    val glassEdge = dshGlassEdge(isDark)
    val draftQuotes = remember(stateHolder.messageDraft) {
        com.clarklevis.dsh.shared.protocol.GatewayQuoteTextParts.parse(stateHolder.messageDraft)
    }
    val composerHasContent = stateHolder.messageDraft.trim().isNotEmpty() || stateHolder.preparedImages.isNotEmpty() || stateHolder.fileUploads.drafts.isNotEmpty() || stateHolder.fileUploads.preparing
    LaunchedEffect(stateHolder.preparedImages.isNotEmpty()) {
        if (stateHolder.preparedImages.isNotEmpty()) voice.setVoiceMode(false)
    }
    var observedQueueEditCount by remember { mutableStateOf(stateHolder.queueDraftRestoreCount) }
    LaunchedEffect(stateHolder.queueDraftRestoreCount) {
        if (stateHolder.queueDraftRestoreCount > observedQueueEditCount) {
            observedQueueEditCount = stateHolder.queueDraftRestoreCount
            voice.setVoiceMode(false)
            queueEditFocusRequester.requestFocus()
        }
    }
    var inputIsFocused by remember { mutableStateOf(false) }
    val composerExpanded = inputIsFocused || voice.state.voiceMode || voice.state.inputBlocked || voice.state.pendingTranscript != null
    val hasComposerAttachments = stateHolder.preparedImages.isNotEmpty() || stateHolder.fileUploads.preparing ||
        stateHolder.fileUploads.drafts.isNotEmpty()
    val quoteCount = draftQuotes.orEmpty().count { it.type == "quote" }
    var previousQuoteCount by remember(stateHolder.snapshot.selectedSessionId) { mutableStateOf(quoteCount) }
    LaunchedEffect(quoteCount) {
        if (quoteCount > previousQuoteCount) { voice.setVoiceMode(false); requestTextFocus = true }
        previousQuoteCount = quoteCount
    }
    val imageCount = stateHolder.preparedImages.size
    val fileCount = stateHolder.fileUploads.drafts.size
    var previousImageCount by remember(stateHolder) { mutableStateOf(imageCount) }
    var previousFileCount by remember(stateHolder) { mutableStateOf(fileCount) }
    LaunchedEffect(imageCount, fileCount) {
        // 打开/取消选择器、初始已有附件或删除都不抢焦点；成功新增附件才展开。
        if (imageCount > previousImageCount || fileCount > previousFileCount) {
            voice.setVoiceMode(false); requestTextFocus = true
        }
        previousImageCount = imageCount
        previousFileCount = fileCount
    }
    var previouslyVisibleIme by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, inputIsFocused, voice.state.voiceMode) {
        when {
            imeVisible -> previouslyVisibleIme = true
            !inputIsFocused || voice.state.voiceMode -> previouslyVisibleIme = false
            previouslyVisibleIme -> {
                // 切换输入法/候选面板时 IME 可能短暂消失，不能立刻清掉原生焦点。
                kotlinx.coroutines.delay(250)
                focusManager.clearFocus(force = true)
                previouslyVisibleIme = false
            }
        }
    }

    var observedSuccessfulSendCount by remember(stateHolder) {
        mutableStateOf(stateHolder.successfulMessageSendCount)
    }
    LaunchedEffect(inputIsFocused, voice.state.inputBlocked) {
        if (inputIsFocused && !voice.state.inputBlocked) keyboardController?.show() else keyboardController?.hide()
    }
    LaunchedEffect(stateHolder.successfulMessageSendCount) {
        if (stateHolder.successfulMessageSendCount > observedSuccessfulSendCount) {
            observedSuccessfulSendCount = stateHolder.successfulMessageSendCount
            onDismissInput()
        }
    }
    var inputValue by remember(stateHolder) {
        mutableStateOf(
            TextFieldValue(
                text = stateHolder.composerText,
                selection = TextRange(stateHolder.composerText.length)
            )
        )
    }
    LaunchedEffect(stateHolder.composerText) {
        if (inputValue.text != stateHolder.composerText) {
            inputValue = TextFieldValue(
                text = stateHolder.composerText,
                selection = TextRange(stateHolder.messageSelectionStart.coerceIn(0, stateHolder.composerText.length))
            )
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (stateHolder.selectedQueueItems.isEmpty()) 0.dp else (-24).dp)
    ) {
        AnimatedVisibility(
            visible = stateHolder.selectedQueueItems.isNotEmpty(),
            enter = expandVertically(tween(280), expandFrom = Alignment.Bottom) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(200), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120)),
            modifier = Modifier.fillMaxWidth()
        ) {
            QueueDock(stateHolder)
        }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer-card")
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 0.dp,
                        color = shadowColor,
                        offset = DpOffset(x = 0.dp, y = 4.dp)
                    )
            ),
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, glassEdge)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                AnimatedVisibility(
                    visible = composerExpanded && hasComposerAttachments,
                    enter = expandVertically(tween(220, easing = LinearOutSlowInEasing), expandFrom = Alignment.Top) +
                        fadeIn(tween(220, easing = LinearOutSlowInEasing)),
                    exit = shrinkVertically(tween(220, easing = LinearOutSlowInEasing), shrinkTowards = Alignment.Top) +
                        fadeOut(tween(220, easing = LinearOutSlowInEasing)),
                    modifier = Modifier.testTag("composer-attachment-previews")
                ) {
                    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (stateHolder.preparedImages.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(stateHolder.preparedImages.size) { index ->
                                    val image = stateHolder.preparedImages[index]
                                    key(image.outgoing.base64Data) {
                                        PreparedImagePreview(image) { stateHolder.removePreparedImage(index) }
                                    }
                                }
                            }
                        }
                        if (stateHolder.fileUploads.preparing) Text(appText("正在准备文件…"), style = MaterialTheme.typography.labelSmall)
                        if (stateHolder.fileUploads.drafts.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(stateHolder.fileUploads.drafts.size) { index ->
                                    val file = stateHolder.fileUploads.drafts[index]
                                    PendingFileCard(
                                        file = file,
                                        enabled = !stateHolder.messageSubmissionPending,
                                        onRetry = { stateHolder.fileUploads.retry(file.id) },
                                        onRemove = { stateHolder.fileUploads.remove(file.id) }
                                    )
                                }
                            }
                        }
                    }
                }
                ComposerQuoteStack(
                    parts = if ("message-quotes-v1" in stateHolder.gatewayState.capabilities) draftQuotes.orEmpty() else emptyList(),
                    visible = composerExpanded,
                    onRemove = { index -> stateHolder.messageDraft = ComposerQuoteDraft.removeQuote(stateHolder.messageDraft, index) }
                ) {
                    ComposerMorphLayout(
                        expanded = composerExpanded,
                        keepActionWhenCollapsed = stateHolder.showsSessionStopButton || composerHasContent,
                        modifier = Modifier.testTag("composer-morph-layout"),
                        leading = {
                            Box {
                                var attachmentMenu by remember { mutableStateOf(false) }
                                ComposerIconButton(R.drawable.ic_composer_add, appText("添加附件"), { attachmentMenu = true })
                                DshMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }) {
                                    DshMenuItem(text = { Text(appText("图片")) },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_photo_outline), null, Modifier.size(20.dp))
                                        },
                                        enabled = "images" in stateHolder.gatewayState.capabilities && stateHolder.preparedImages.size < 20,
                                        onClick = { attachmentMenu = false; onPickImage() })
                                    DshMenuItem(text = { Text(appText("文件")) },
                                        leadingIcon = {
                                            Icon(painterResource(R.drawable.ic_pending_file), null, Modifier.size(20.dp))
                                        },
                                        enabled = !stateHolder.isViewingTeamMember &&
                                            !stateHolder.fileUploads.preparing && stateHolder.fileUploads.drafts.size < 20 && !stateHolder.messageSubmissionPending,
                                        onClick = {
                                            attachmentMenu = false
                                            if (stateHolder.fileUploads.supported) onPickFile()
                                            else showsFileUploadUpgradeAlert = true
                                        })
                                }
                            }
                        },
                        editor = {
                            val commandToken = stateHolder.slashCommands.commandToken
                            val commandHint = stateHolder.slashCommands.argumentHint
                            if (voice.state.voiceMode) {
                                VoiceHoldInput(voice, Modifier.offset(y = (-4.5).dp))
                            } else {
                                ComposerPasteTextField(
                                    enabled = !voice.state.inputBlocked,
                                    value = inputValue,
                                    // 只限制显示行数，不切换 singleLine，避免聚焦时重启 IME 连接。
                                    maxLines = if (composerExpanded) Int.MAX_VALUE else 1,
                                    onValueChange = {
                                        inputValue = it
                                        stateHolder.composerText = it.text
                                        stateHolder.messageSelectionStart = it.selection.start
                                        stateHolder.messageSelectionEnd = it.selection.end
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 3.dp)
                                        .heightIn(min = 38.dp, max = 120.dp)
                                        .onFocusChanged { inputIsFocused = it.isFocused }
                                        .focusRequester(queueEditFocusRequester)
                                        .testTag("composer-input"),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                    cursorBrush = SolidColor(DshColors.Ocean),
                                    commandToken = commandToken,
                                    onPasteAttachments = stateHolder::pasteAttachments,
                                    decorationBox = { field ->
                                        Box(contentAlignment = Alignment.TopStart) {
                                            if (stateHolder.composerText.isEmpty()) {
                                                Text(
                                                    appText(voice.state.hint),
                                                    maxLines = if (composerExpanded) Int.MAX_VALUE else 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                                                    modifier = Modifier.testTag("composer-placeholder")
                                                )
                                            } else if (composerExpanded &&
                                                commandToken != null &&
                                                commandHint != null &&
                                                stateHolder.composerText.trimEnd() == commandToken
                                            ) {
                                                Text(
                                                    buildAnnotatedString {
                                                        withStyle(SpanStyle(color = Color.Transparent)) {
                                                            append(stateHolder.composerText)
                                                        }
                                                        if (!stateHolder.composerText.last().isWhitespace()) append(" ")
                                                        withStyle(
                                                            SpanStyle(
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                                                            )
                                                        ) {
                                                            append(commandHint)
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            }
                                            val compactPreview = !composerExpanded && stateHolder.composerText.isNotEmpty()
                                            // 保留同一个可聚焦编辑节点；收紧时只替换绘制，原始草稿不截断。
                                            Box(Modifier.alpha(if (compactPreview) 0f else 1f)) { field() }
                                            if (compactPreview) {
                                                Text(stateHolder.composerText,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.fillMaxWidth().testTag("composer-compact-preview")
                                                        .semantics { hideFromAccessibility() })
                                            }
                                        }
                                    }
                                )
                            }
                        },
                        tools = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (!stateHolder.isViewingTeamMember) PermissionControl(stateHolder, Modifier.width(82.dp))
                                Spacer(Modifier.width(2.dp))
                                if (!stateHolder.isViewingTeamMember) {
                                    ModelControl(
                                        stateHolder = stateHolder,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else if (stateHolder.selectedMemberCanSend) {
                                    Spacer(Modifier.weight(1f))
                                } else {
                                    Text(
                                        appText("只读成员会话"),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                ContextUsageRing(stateHolder)
                            }
                        },
                        action = {
                            Box(
                                Modifier.size(42.dp)
                                    .alpha(
                                        if (stateHolder.showsSessionStopButton) {
                                            if (stateHolder.canCancelSelectedSession) 1f else 0.66f
                                        } else 1f
                                    )
                                    .clip(CircleShape)
                                    // 背景直接绘制圆形，避免半透明状态依赖图层裁剪后露出方形底色。
                                    .background(DshColors.Ocean, CircleShape)
                                    .clickable(
                                        enabled = if (stateHolder.showsSessionStopButton) {
                                            stateHolder.canCancelSelectedSession
                                        } else {
                                            !composerHasContent || stateHolder.canSend
                                        },
                                        onClick = if (stateHolder.showsSessionStopButton) {
                                            stateHolder::cancelSelectedSession
                                        } else if (composerHasContent) {
                                            { stateHolder.sendMessage() }
                                        } else {
                                            {
                                                modeSwitchFeedback.perform()
                                                val enteringVoice = !voice.state.voiceMode
                                                voice.setVoiceMode(enteringVoice)
                                                if (enteringVoice) {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()
                                                } else {
                                                    // 先让文字输入框重新挂载，再请求焦点。
                                                    requestTextFocus = true
                                                }
                                            }
                                        }
                                    )
                                    .semantics {
                                        contentDescription = if (stateHolder.showsSessionStopButton) {
                                            appText("停止生成")
                                        } else if (composerHasContent) {
                                            appText("发送")
                                        } else if (voice.state.voiceMode) {
                                            appText("切换到文字输入")
                                        } else {
                                            appText("切换到语音输入")
                                        }
                                    }.testTag("composer-action"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (stateHolder.showsSessionStopButton) {
                                    Box(
                                        Modifier.size(14.dp)
                                            .background(Color.White, RoundedCornerShape(3.dp))
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(
                                            if (composerHasContent) R.drawable.ic_arrow_up
                                            else if (voice.state.voiceMode) R.drawable.ic_composer_keyboard
                                            else R.drawable.ic_voice_waveform
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    )
                }
                voice.state.pendingTranscript?.let { transcript ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(transcript, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        Row {
                            Text(appText("草稿已更改，请确认插入识别文字"), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = voice::insertPending) { Text(appText("插入")) }
                            TextButton(onClick = voice::dismissFeedback) { Text(appText("丢弃")) }
                        }
                    }
                }

            }
        }
    }
}

@Composable
private fun PreparedImagePreview(image: AndroidPreparedImage, onRemove: () -> Unit) {
    val preview by produceState<PreparedImagePreviewState>(PreparedImagePreviewState.Loading, image.outgoing.base64Data) {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { Base64.decode(image.outgoing.base64Data, Base64.NO_WRAP) }.getOrNull()
        }
        val bitmap = bytes?.let { AndroidAttachmentThumbnailer().decode(it, 256, 256)?.asImageBitmap() }
        value = bitmap?.let(PreparedImagePreviewState::Ready) ?: PreparedImagePreviewState.Unavailable
    }
    val shape = RoundedCornerShape(10.dp)
    val scale = 72f / maxOf(image.width, image.height, 1)
    val previewWidth = (image.width * scale).coerceAtLeast(1f).dp
    val previewHeight = (image.height * scale).coerceAtLeast(1f).dp
    Box(
        Modifier.padding(top = 5.dp, end = 5.dp)
            .size(width = maxOf(20.dp, previewWidth), height = maxOf(20.dp, previewHeight))
            .background(DshColors.Ocean.copy(alpha = 0.11f), shape),
        contentAlignment = Alignment.Center
    ) {
        when (val state = preview) {
            is PreparedImagePreviewState.Ready -> Image(
                bitmap = state.bitmap,
                contentDescription = image.outgoing.name ?: appText("待发送图片"),
                modifier = Modifier.align(Alignment.TopEnd).size(previewWidth, previewHeight).clip(shape),
                contentScale = ContentScale.Fit
            )
            PreparedImagePreviewState.Loading -> Text(appText("正在加载图片…"), fontSize = 10.sp)
            PreparedImagePreviewState.Unavailable -> Text(appText("无法预览图片"), fontSize = 10.sp)
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd)
                .offset(x = 5.dp, y = (-5).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.68f))
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRemove)
                .semantics { contentDescription = appText("移除图片") },
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = null,
                modifier = Modifier.size(16.dp), tint = Color.White)
        }
    }
}

private sealed interface PreparedImagePreviewState {
    data object Loading : PreparedImagePreviewState
    data class Ready(val bitmap: ImageBitmap) : PreparedImagePreviewState
    data object Unavailable : PreparedImagePreviewState
}

@Composable
private fun QueueDock(stateHolder: AndroidSharedStateHolder) {
    QueueDock(
        sessionId = stateHolder.snapshot.selectedSessionId,
        items = stateHolder.selectedQueueItems,
        pendingItemId = stateHolder.queueState.pendingItemId,
        enabled = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
            "queue-control" in stateHolder.gatewayState.capabilities,
        running = stateHolder.snapshot.sessions.firstOrNull { it.id == stateHolder.snapshot.selectedSessionId }?.isRunning == true,
        onAction = stateHolder::updateQueuedMessage
    )
}

@Composable
internal fun QueueDock(
    sessionId: String?,
    items: List<com.clarklevis.dsh.shared.facade.SharedQueueItem>,
    pendingItemId: String?,
    enabled: Boolean,
    running: Boolean,
    onAction: (String, String) -> Unit
) {
    var expanded by remember(sessionId) { mutableStateOf(false) }
    if (items.isEmpty()) return
    val actionsEnabled = enabled && pendingItemId == null
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier.fillMaxWidth()
            .dropShadow(
                shape = shape,
                shadow = Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.06f), offset = DpOffset(0.dp, 2.dp))
            )
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f).compositeOver(MaterialTheme.colorScheme.surface),
                shape
            )
            // 底部延伸到输入框圆角后方，按钮保留在输入框外。
            .padding(bottom = 24.dp)
    ) {
        if (items.size > 1) {
            Row(
                Modifier.fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { expanded = !expanded }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.ic_question_bubble), contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                Text(appText("排队消息 · %1\$s", items.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(painterResource(if (expanded) R.drawable.ic_question_chevron_down else R.drawable.ic_chevron_up),
                    contentDescription = if (expanded) appText("收起排队消息") else appText("展开排队消息"), modifier = Modifier.size(18.dp))
            }
        }
        if (items.size == 1 || expanded) {
            Column(Modifier.heightIn(max = 192.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    key(item.id) {
                        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (items.size == 1) {
                                Icon(painterResource(R.drawable.ic_question_bubble), contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(appText(item.preview), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (item.attachmentCount > 0) Text(appText("%1\$s 个附件", item.attachmentCount), style = MaterialTheme.typography.labelSmall)
                            }
                            if (pendingItemId == item.id) {
                                CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), strokeWidth = 2.dp)
                            } else {
                                QueueAction(R.drawable.ic_pencil_line, appText("编辑排队消息"), actionsEnabled && item.editable) { onAction(item.id, "edit") }
                                QueueAction(R.drawable.ic_trash, appText("删除排队消息"), actionsEnabled) { onAction(item.id, "remove") }
                                QueueAction(R.drawable.ic_arrow_up, appText("立即插话"), actionsEnabled && running) { onAction(item.id, "steer") }
                            }
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueAction(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).alpha(if (enabled) 1f else 0.38f).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick
    )
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SlashCommandMenus(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier
) {
    val state = stateHolder.slashCommands
    if (!state.catalogVisible && state.optionsCommand == null) return
    val isDark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 10.dp,
                    spread = 0.dp,
                    color = dshFloatingSurfaceShadow(isDark),
                    offset = DpOffset(x = 0.dp, y = 4.dp)
                )
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            dshGlassEdge(isDark)
        )
    ) {
        val optionsCommand = state.optionsCommand
        // 测量实际内容高度，短列表随内容收缩，超过面板上限时滚动。
        Column(
            modifier = Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 7.dp)
        ) {
            if (optionsCommand != null) {
                Text(
                    text = "/${optionsCommand.name}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
            if (state.catalogLoading || state.optionsLoading || state.selectionLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = DshColors.Ocean)
            }
            if (optionsCommand == null) {
                state.filteredGroups.forEach { group ->
                    Text(
                        text = group.title,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    group.items.forEach { command ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { stateHolder.selectSlashCatalogItem(command.stableId) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(command.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(
                                command.description,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 10.dp).weight(1f)
                            )
                            if (command.ui.kind == "select") {
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.56f))
                            }
                        }
                    }
                }
            } else {
                state.options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = !state.selectionLoading) {
                                stateHolder.selectSlashCommandOption(option.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            (option.description ?: option.detail)?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        if (option.selected == true) {
                            Text("✓", color = DshColors.Ocean, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

internal fun dshFloatingSurfaceShadow(isDark: Boolean): Color =
    Color.Black.copy(alpha = if (isDark) 0.20f else 0.08f)

internal fun dshGlassEdge(isDark: Boolean): Brush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = if (isDark) 0.21f else 0.72f),
        0.48f to Color.White.copy(alpha = if (isDark) 0.12f else 0.52f),
        1f to Color.White.copy(alpha = if (isDark) 0.15f else 0.62f)
    )
)

@Composable
private fun ComposerIconButton(iconRes: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.CenterStart
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            // 加号矢量的描边左侧还留有约 1.5dp 空白，按可见边缘与右侧语音圆按钮对齐。
            modifier = Modifier.size(22.dp).offset(x = (-1.5).dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun NewConversationWorkspaceControl(
    selectedId: String?,
    workspaces: List<com.clarklevis.dsh.shared.protocol.GatewayWorkspace>,
    changing: Boolean,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    val workspace = workspaces.firstOrNull { it.workspaceId == selectedId }
    var expanded by remember(selectedId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(13.dp)
    val isDark = isSystemInDarkTheme()
    Box {
        Surface(
            shape = shape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, dshGlassEdge(isDark)),
            modifier = Modifier.testTag("new-conversation-workspace-capsule")
                .widthIn(max = 180.dp)
                .dropShadow(shape, Shadow(radius = 10.dp, color = dshFloatingSurfaceShadow(isDark), offset = DpOffset(0.dp, 4.dp)))
                .clip(shape)
                .clickable(enabled = enabled && !changing) { expanded = true }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_folder_outline), contentDescription = null, modifier = Modifier.size(16.dp))
                Text(if (changing) appText("切换中…") else workspace?.title ?: appText("未分组"),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(painterResource(R.drawable.ic_question_chevron_down), contentDescription = appText("选择工作区"),
                    modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
            }
        }
        ComposerGlassPopover(expanded = expanded, onDismiss = { expanded = false }, width = 292.dp,
            alignToEnd = true, maximumContentHeight = 380.dp, contentPadding = PaddingValues(vertical = 6.dp),
            arrangement = Arrangement.Top, testTag = "new-conversation-workspace-popover") {
            val choices = listOf<Pair<String?, String>>(null to appText("未分组")) +
                workspaces.map { it.workspaceId to it.title }
            choices.forEach { (id, title) ->
                DshMenuItem(modifier = Modifier.testTag("new-conversation-workspace-${id ?: "ungrouped"}"),
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            workspaces.firstOrNull { it.workspaceId == id }?.path?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_outline), contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { if (id == selectedId) Icon(painterResource(R.drawable.ic_menu_check),
                        contentDescription = appText("已选择"), modifier = Modifier.size(18.dp)) },
                    onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
internal fun SessionAgentPresetControl(
    state: com.clarklevis.dsh.shared.facade.SharedSessionAgentPresetSnapshot,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit
) {
    if (!state.visible) return
    var expanded by remember(state.sessionId) { mutableStateOf(false) }
    val current = state.presets.firstOrNull { it.id == state.agentPreset }
    val retry = !state.loading && !state.saving && (!state.known || !state.catalogLoaded)
    val shape = RoundedCornerShape(13.dp)
    val isDark = isSystemInDarkTheme()
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    Box {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(0.8.dp, dshGlassEdge(isDark)),
            modifier = Modifier.testTag("session-agent-preset-capsule")
                .dropShadow(
                    shape = shape,
                    shadow = Shadow(
                        radius = 10.dp,
                        spread = 0.dp,
                        color = shadowColor,
                        offset = DpOffset(0.dp, 4.dp)
                    )
                )
                .clip(shape)
                .clickable(enabled = state.canSelect || (retry && state.connected)) {
                    if (retry) onRetry() else expanded = true
                }
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    painterResource(R.drawable.ic_agent_mode), contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    when {
                        state.saving -> appText("切换中…")
                        state.agentPreset != null -> current?.name?.takeIf(String::isNotBlank)
                            ?: agentPresetDisplayName(state.agentPreset)
                        state.loading -> appText("读取模式…")
                        else -> appText("重试模式")
                    },
                    modifier = Modifier.widthIn(max = 132.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Icon(
                    painterResource(R.drawable.ic_question_chevron_down), contentDescription = appText("选择会话模式"),
                    modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
        ComposerGlassPopover(
            expanded = expanded && state.canSelect,
            onDismiss = { expanded = false },
            width = 292.dp,
            alignToEnd = false,
            maximumContentHeight = 380.dp,
            contentPadding = PaddingValues(vertical = 6.dp),
            arrangement = Arrangement.Top,
            testTag = "session-agent-preset-popover"
        ) {
            state.presets.forEach { preset ->
                DshMenuItem(
                    modifier = Modifier.testTag("session-agent-preset-${preset.id}"),
                    enabled = preset.broken != true,
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                preset.name?.takeIf(String::isNotBlank) ?: agentPresetDisplayName(preset.id),
                                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (preset.broken == true) preset.brokenReason ?: appText("模式不可用")
                                else agentPresetCompactDescription(preset.id, preset.description),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    trailingIcon = {
                        if (preset.id == state.agentPreset) Icon(
                            painterResource(R.drawable.ic_menu_check), contentDescription = appText("已选择"),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                    onClick = { expanded = false; onSelect(preset.id) }
                )
            }
        }
    }
}

@Composable
private fun PermissionControl(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = stateHolder.snapshot.permissions?.currentValue
        ?: stateHolder.snapshot.permissionDefault
    val options = stateHolder.snapshot.permissions?.options.orEmpty()
    val enabled = stateHolder.snapshot.selectedSessionId != null && options.isNotEmpty()
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                painter = painterResource(permissionIcon(selected)),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
            )
            Text(
                options.firstOrNull { it.value == selected }?.name
                    ?: stateHolder.snapshot.permissionDefaultOptions.firstOrNull { it.value == selected }?.name
                    ?: permissionTitle(selected),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ComposerPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = 224.dp,
            alignment = Alignment.BottomStart,
            horizontalCompensation = (-28).dp
        ) {
            options.forEach { option ->
                DshMenuItem(
                    text = {
                        Text(
                            option.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(
                                if (option.value == selected) R.drawable.ic_menu_check
                                else permissionIcon(option.value)
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    onClick = {
                        stateHolder.setSessionPermission(option.value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ComposerMenuSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun ComposerMenuItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DshMenuItem(
        text = {
            Text(
                title,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(
                    if (selected) R.drawable.ic_menu_check else R.drawable.ic_menu_circle
                ),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        minHeight = 44.dp,
        onClick = onClick
    )
}

@Composable
private fun ModelControl(
    stateHolder: AndroidSharedStateHolder,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val effort = currentModelEffortTitle(stateHolder)
    val selection = currentModelSelection(stateHolder)
    val groups = stateHolder.snapshot.modelCatalog?.groups.orEmpty()
    val enabled = stateHolder.snapshot.selectedSessionId != null &&
        groups.isNotEmpty() && stateHolder.snapshot.modelCatalog?.routable != false
    Box(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                modelTitle(stateHolder),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            effort?.let {
                ReasoningEffortTag(it)
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevrons_vertical),
                contentDescription = null,
                modifier = Modifier.size(9.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
            )
        }
        ComposerPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = 286.dp,
            alignment = Alignment.BottomEnd,
            horizontalCompensation = 28.dp
        ) {
            val efforts = currentModelEfforts(stateHolder)
            if (efforts.isNotEmpty()) {
                ComposerMenuSectionTitle(appText("推理等级"))
                efforts.forEach { option ->
                    ComposerMenuItem(
                        title = option.name,
                        selected = option.id == selection?.reasoningEffort
                    ) {
                        selection ?: return@ComposerMenuItem
                        stateHolder.selectModel(selection.provider, selection.model, option.id)
                        expanded = false
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                )
            }
            groups.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                )
                ComposerMenuSectionTitle(group.name)
                group.models.forEach { model ->
                    ComposerMenuItem(
                        title = model.name,
                        selected = group.id == selection?.provider && model.id == selection.model
                    ) {
                        val retainedEffort = selection?.reasoningEffort?.takeIf { current ->
                            model.reasoning?.efforts.orEmpty().any { it.id == current }
                        }
                        stateHolder.selectModel(
                            group.id,
                            model.id,
                            retainedEffort ?: model.reasoning?.defaultEffort
                        )
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReasoningEffortTag(title: String) {
    Text(
        title,
        modifier = Modifier
            .testTag("reasoning-effort-tag")
            .background(
                DshColors.Purple.copy(alpha = 0.16f),
                RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 10.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

@Composable
private fun ComposerPopupMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    width: Dp,
    alignment: Alignment,
    horizontalCompensation: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxSurfaceHeight = (windowHeight - 100.dp).coerceIn(48.dp, 560.dp)
    val horizontalCompensationPx = with(density) { horizontalCompensation.roundToPx() }
    val screenMarginPx = with(density) { 8.dp.roundToPx() }
    val anchorGapPx = with(density) { 4.dp.roundToPx() }
    val positionProvider = remember(
        alignment,
        horizontalCompensationPx,
        screenMarginPx,
        anchorGapPx
    ) {
        ComposerPopupPositionProvider(
            alignToEnd = alignment == Alignment.BottomEnd,
            horizontalCompensationPx = horizontalCompensationPx,
            screenMarginPx = screenMarginPx,
            anchorGapPx = anchorGapPx
        )
    }
    DshMenuPopup(expanded, onDismissRequest, positionProvider,
        modifier = Modifier.width(width).heightIn(max = maxSurfaceHeight),
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
            if (alignment == Alignment.BottomEnd) 1f else 0f, 1f), shadowPadding = 20.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp), content = content)
    }

}

private class ComposerPopupPositionProvider(
    private val alignToEnd: Boolean,
    private val horizontalCompensationPx: Int,
    private val screenMarginPx: Int,
    private val anchorGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maximumX = (windowSize.width - popupContentSize.width - screenMarginPx).coerceAtLeast(0)
        val minimumX = screenMarginPx.coerceAtMost(maximumX)
        val preferredX = if (alignToEnd) {
            anchorBounds.right - popupContentSize.width
        } else {
            anchorBounds.left
        } + horizontalCompensationPx

        val maximumY = (windowSize.height - popupContentSize.height - screenMarginPx).coerceAtLeast(0)
        val minimumY = screenMarginPx.coerceAtMost(maximumY)
        val aboveAnchor = anchorBounds.top - popupContentSize.height - anchorGapPx
        val belowAnchor = anchorBounds.bottom + anchorGapPx
        val preferredY = when {
            aboveAnchor >= minimumY -> aboveAnchor
            belowAnchor <= maximumY -> belowAnchor
            anchorBounds.top >= windowSize.height - anchorBounds.bottom -> aboveAnchor
            else -> belowAnchor
        }

        return IntOffset(
            x = preferredX.coerceIn(minimumX, maximumX),
            y = preferredY.coerceIn(minimumY, maximumY)
        )
    }
}

@Composable
private fun ContextUsageRing(stateHolder: AndroidSharedStateHolder) {
    val snapshot = stateHolder.snapshot.contextSnapshot
    var expanded by remember(stateHolder.snapshot.selectedSessionId) { mutableStateOf(false) }
    val pressure = snapshot?.pressure
    val contextWindow = pressure?.contextWindow
    val progress = if (contextWindow != null && contextWindow > 0) {
        (pressure.pressureTokens ?: 0).toFloat() / contextWindow
    } else 0f
    Box(
        Modifier.size(32.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                expanded = true
                stateHolder.refreshContextUsage()
            }
            .semantics { contentDescription = appText("查看上下文用量") }
            .testTag("context-usage-ring"),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(18.dp),
            strokeWidth = 3.dp,
            color = DshColors.Ocean,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        )
        ComposerPopupMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            width = 300.dp,
            alignment = Alignment.BottomEnd,
            horizontalCompensation = 0.dp
        ) {
            Column(
                Modifier.padding(horizontal = 18.dp, vertical = 10.dp).testTag("context-usage-popover"),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("上下文已用"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (contextWindow != null && contextWindow > 0 && pressure.pressureTokens != null)
                            "${kotlin.math.round(progress.coerceIn(0f, 1f) * 100).toInt()}%" else "—",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${contextTokenCount(pressure?.pressureTokens?.toLong())} / ${contextTokenCount(contextWindow?.toLong())}", fontSize = 12.sp)
                }
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = DshColors.Ocean
                )
                val breakdown = snapshot?.breakdown
                val usage = snapshot?.tokenUsage
                if (breakdown != null) {
                    ContextUsageRow(appText("系统提示词"), breakdown.systemTokens?.toLong(), Color.Gray)
                    ContextUsageRow(appText("工具"), breakdown.toolsTokens?.toLong(), Color(0xFF8055CF))
                    ContextUsageRow(appText("对话消息"), breakdown.messageTokens?.toLong(), DshColors.Ocean)
                } else if (usage != null) {
                    ContextUsageRow(appText("未缓存输入"), usage.uncachedInputTokens, DshColors.Ocean)
                    ContextUsageRow(appText("缓存读取"), usage.cacheReadTokens, Color(0xFF8055CF))
                    ContextUsageRow(appText("模型输出"), usage.outputTokens, Color(0xFFE18B38))
                } else {
                    Text(appText("暂无上下文用量明细"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ContextUsageRow(title: String, tokens: Long?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(11.dp).background(color, RoundedCornerShape(3.dp)))
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(contextTokenCount(tokens), fontSize = 14.sp)
    }
}

private fun contextTokenCount(value: Long?): String = when {
    value == null -> "—"
    value >= 1_000_000 -> "~" + String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> "~" + String.format(java.util.Locale.ROOT, "%.1fK", value / 1_000.0)
    else -> "~$value"
}

@Composable
private fun ConversationRow(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    attachmentStates: Map<String, AttachmentLoadState>,
    onRetryAttachment: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    when (item.kind) {
        ConversationItemKind.USER -> UserMessage(
            item,
            thumbnails,
            attachmentStates,
            onRetryAttachment,
            onPreviewImages
        )
        ConversationItemKind.GENERATED_IMAGES -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            UserAttachmentPreview(item.images, thumbnails, attachmentStates, onRetryAttachment, onPreviewImages, stacked = false)
        }
        ConversationItemKind.ASSISTANT -> AssistantMessage(item, thumbnails, attachmentStates, onRetryAttachment)
        ConversationItemKind.STATUS -> StatusRow(item)
        ConversationItemKind.SYSTEM -> SystemRow(item)
        else -> Unit
    }
}

@Composable
private fun ConversationProcessRow(group: ConversationProcessGroup) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    val command = group.command
    val commandColor = if (command?.isError == true) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = group.isExpandable) {
                expanded = !expanded
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (command != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_command_status),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (command.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
            if (command != null) {
                Text(
                    command.title,
                    color = commandColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    "·",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                    fontSize = 14.sp
                )
                Text(
                    command.text.singleLinePreview(),
                    modifier = Modifier.weight(1f),
                    color = if (command.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    group.title,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (group.isExpandable) ProcessChevron(expanded)
        }
        if (expanded && group.isExpandable) {
            Column(
                modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (command != null && group.commandHasDetailedText) {
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            command.text,
                            color = commandColor,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                group.contexts.forEach { ProcessContextDisclosure(it) }
                if (group.reasoningText.isNotEmpty()) {
                    ProcessReasoningDisclosure(group.id, group.reasoningText)
                }
                if (group.tools.isNotEmpty()) {
                    ProcessToolBundle(group.id, group.tools)
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 7.dp),
            thickness = 1.dp,
            color = Color.Gray.copy(alpha = 0.16f)
        )
    }
}

@Composable
private fun ProcessContextDisclosure(item: ConversationItem) {
    ProcessDisclosure(
        id = "context-${item.id}",
        title = item.title.toContextDisplayTitle(),
        preview = item.text.singleLinePreview(),
        iconRes = R.drawable.ic_dsh_context_injection,
        tint = DshColors.Success
    ) {
        DshMarkdownText(item.text, Modifier.fillMaxWidth(), compact = true)
    }
}

@Composable
private fun ProcessReasoningDisclosure(groupId: String, text: String) {
    ProcessDisclosure(
        id = "reasoning-$groupId",
        title = "Think",
        preview = text.singleLinePreview(),
        iconRes = R.drawable.ic_dsh_think,
        tint = DshColors.Purple
    ) {
        DshMarkdownText(text, Modifier.fillMaxWidth(), compact = true)
    }
}

@Composable
private fun ProcessToolBundle(
    groupId: String,
    tools: List<ConversationProcessTool>
) {
    val names = tools.mapNotNull { it.call?.title }.take(2)
    val title = when {
        names.isEmpty() -> appText("查看 %1\$s 个工具结果", tools.size)
        else -> appText("使用了 %1\$s%2\$s", names.joinToString(appText("、")), if (tools.size > 2) appText(" 等工具") else "")
    }
    ProcessDisclosure(
        id = "tools-$groupId",
        title = title,
        preview = "",
        iconRes = R.drawable.ic_process_tool,
        tint = DshColors.Orange,
        bodyStartPadding = 14.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tools.forEach { ProcessToolDisclosure(it) }
        }
    }
}

@Composable
private fun ProcessToolDisclosure(tool: ConversationProcessTool) {
    val failed = tool.result?.isError == true
    val summary = remember(tool.call?.title, tool.call?.text, tool.result?.title) {
        com.clarklevis.dsh.shared.projection.ToolActivitySummaryFormatter.summarize(
            tool.call?.title ?: tool.result?.title.orEmpty(), tool.call?.text.orEmpty()
        )
    }
    ProcessDisclosure(
        id = "tool-${tool.id}",
        title = toolActivityTitle(summary),
        preview = listOf(
            summary.detail,
            if (failed) appText("失败") else if (tool.result == null) appText("等待结果") else ""
        ).filter(String::isNotBlank).joinToString(" · "),
        stackedPreview = true,
        iconRes = processToolIcon(tool.call?.title),
        tint = if (failed) Color.Red else DshColors.Orange
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            tool.call?.text?.takeIf(String::isNotEmpty)?.let { arguments ->
                Text(
                    appText("调用参数"),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                DshMarkdownText(
                    "```json\n$arguments\n```",
                    Modifier.fillMaxWidth(),
                    compact = true
                )
            }
            tool.result?.text?.takeIf(String::isNotEmpty)?.let { result ->
                Text(
                    if (failed) appText("错误") else appText("结果"),
                    color = if (failed) Color.Red else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                DshMarkdownText(result, Modifier.fillMaxWidth(), compact = true)
            }
        }
    }
}

@Composable
private fun ProcessDisclosure(
    id: String,
    title: String,
    preview: String,
    iconRes: Int,
    tint: Color,
    bodyStartPadding: Dp = 26.dp,
    stackedPreview: Boolean = false,
    content: @Composable () -> Unit
) {
    var expanded by remember(id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tint
                )
            }
            if (stackedPreview) {
                Column(Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (preview.isNotEmpty()) Text(preview,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(
                    title,
                    color = tint,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (preview.isNotEmpty()) {
                    Text("·", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f))
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            ProcessChevron(expanded)
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().padding(start = bodyStartPadding, bottom = 3.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun ProcessChevron(expanded: Boolean) {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        modifier = Modifier.size(15.dp).rotate(if (expanded) 90f else 0f),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    )
}

private fun String.singleLinePreview(): String =
    replace(Regex("\\s+"), " ").trim()

private fun String.toContextDisplayTitle(): String = when {
    startsWith("Context · ") -> appText("上下文注入 · %1\$s", removePrefix("Context · "))
    else -> this
}

private fun processToolIcon(toolName: String?): Int {
    val normalized = toolName.orEmpty()
        .lowercase()
        .filter(Char::isLetterOrDigit)
    return when (normalized) {
        "write", "edit", "writefile", "editfile", "applypatch" -> R.drawable.ic_session_rename
        "glob", "grep", "websearch" -> R.drawable.ic_dsh_search
        "read", "webfetch", "cordispackageinspect", "cordisruntimeinspect" ->
            R.drawable.ic_dsh_read
        "bash", "pwsh" -> R.drawable.ic_dsh_bash
        else -> R.drawable.ic_process_tool
    }
}

@Composable
private fun UserMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bubbleFill = DshColors.Ocean.copy(alpha = if (isDark) 0.24f else 0.11f)
    val bubbleEdge = DshColors.Ocean.copy(alpha = if (isDark) 0.34f else 0.08f)
    Column(
        Modifier.fillMaxWidth().padding(start = 34.dp, top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        UserAttachmentPreview(item.images, thumbnails, states, onRetry, onPreviewImages)
        item.files.filter { it.attachmentId != null }.forEach { file ->
            val name = file.name ?: appText("文件")
            val kind = remember(name) { com.clarklevis.dsh.shared.facade.FileIconCatalog.resolve(name, null) }
            Row(
                Modifier.widthIn(max = 320.dp).fillMaxWidth().background(fileCardColor(kind), RoundedCornerShape(12.dp))
                    .padding(12.dp).testTag("user-file-card"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FileTypeIcon(kind, Modifier.size(48.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    file.bytes?.let { Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, it),
                        fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (item.text.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val parts = item.textParts
                if (parts?.any { it.type == "quote" } == true) {
                    SentReplyBubble(parts)
                } else Text(
                    text = item.text,
                    modifier = Modifier
                        .background(bubbleFill, RoundedCornerShape(15.dp))
                        .border(0.7.dp, bubbleEdge, RoundedCornerShape(15.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("user-text-bubble"),
                    fontSize = 16.sp
                )
                CopyButton(item.text)
            }
        }
    }
}

internal data class UserAttachmentPreviewSize(val width: Dp, val height: Dp)

internal fun userSingleAttachmentPreviewSize(
    sourceWidth: Int,
    sourceHeight: Int,
    availableWidth: Dp
): UserAttachmentPreviewSize {
    val safeWidth = sourceWidth.coerceAtLeast(1).toFloat()
    val safeHeight = sourceHeight.coerceAtLeast(1).toFloat()
    val maximumWidth = minOf(320.dp, availableWidth)
    val maximumHeight = 320.dp
    val scale = minOf(
        1f,
        maximumWidth.value / safeWidth,
        maximumHeight.value / safeHeight
    )
    return UserAttachmentPreviewSize(
        width = (safeWidth * scale).dp,
        height = (safeHeight * scale).dp
    )
}

@Composable
private fun UserAttachmentPreview(
    attachments: List<GatewayImageAttachment>,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit,
    stacked: Boolean = true
) {
    if (attachments.isEmpty()) return
    BoxWithConstraints(Modifier.testTag("user-image-bubble")) {
        if (attachments.size == 1) {
            val attachment = attachments.first()
            val previewSize = userSingleAttachmentPreviewSize(
                sourceWidth = attachment.width,
                sourceHeight = attachment.height,
                availableWidth = maxWidth
            )
            UserAttachmentImage(
                attachment = attachment,
                image = thumbnails[attachment.attachmentId],
                state = states[attachment.attachmentId],
                onRetry = onRetry,
                onPreview = { onPreviewImages(attachments, 0) },
                modifier = Modifier.size(previewSize.width, previewSize.height),
                cornerRadius = 12.dp,
                borderWidth = 0.dp
            )
        } else if (!stacked) {
            val columnWidth = minOf(180.dp, (maxWidth - 6.dp) / 2)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.chunked(2).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { columnIndex, attachment ->
                            val previewSize = userSingleAttachmentPreviewSize(attachment.width, attachment.height, columnWidth)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                UserAttachmentImage(
                                    attachment, thumbnails[attachment.attachmentId], states[attachment.attachmentId],
                                    onRetry, { onPreviewImages(attachments, rowIndex * 2 + columnIndex) },
                                    Modifier.size(previewSize.width, previewSize.height), 12.dp, 0.dp
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else {
            val visibleAttachments = attachments.take(3)
            val cardOffset = 12.dp
            val cardSide = minOf(
                164.dp,
                (maxWidth - cardOffset * (visibleAttachments.size - 1)).coerceAtLeast(96.dp)
            )
            val previewSide = cardSide + cardOffset * (visibleAttachments.size - 1)
            Box(Modifier.size(previewSide)) {
                visibleAttachments.indices.reversed().forEach { index ->
                    val attachment = visibleAttachments[index]
                    UserAttachmentImage(
                        attachment = attachment,
                        image = thumbnails[attachment.attachmentId],
                        state = states[attachment.attachmentId],
                        onRetry = onRetry,
                        onPreview = { onPreviewImages(attachments, index) },
                        modifier = Modifier
                            .offset(
                                x = cardOffset * index,
                                y = cardOffset * (visibleAttachments.lastIndex - index)
                            )
                            .size(cardSide),
                        cornerRadius = 18.dp,
                        borderWidth = 2.dp
                    )
                }
                Text(
                    text = appText("%1\$s张", attachments.size),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun UserAttachmentImage(
    attachment: GatewayImageAttachment,
    image: ImageBitmap?,
    state: AttachmentLoadState?,
    onRetry: (String) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier,
    cornerRadius: Dp,
    borderWidth: Dp
) {
    val shape = RoundedCornerShape(cornerRadius)
    val retryable = state in setOf(AttachmentLoadState.FAILED, AttachmentLoadState.DEFERRED)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .then(
                if (borderWidth > 0.dp) Modifier.border(borderWidth, MaterialTheme.colorScheme.surface, shape)
                else Modifier
            )
            .clickable {
                if (retryable) onRetry(attachment.attachmentId) else onPreview()
            },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = attachment.name ?: appText("图片附件"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = when (state) {
                    AttachmentLoadState.FAILED -> appText("加载失败 · 点击重试")
                    AttachmentLoadState.DEFERRED -> appText("已释放 · 点击重载")
                    else -> appText("正在加载图片…")
                },
                modifier = Modifier.padding(10.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

internal data class ConversationImagePreviewRequest(
    val attachments: List<GatewayImageAttachment>,
    val initialIndex: Int
)

internal fun imagePreviewInitialPage(imageCount: Int, requestedIndex: Int): Int =
    requestedIndex.coerceIn(0, (imageCount - 1).coerceAtLeast(0))

@Composable
internal fun ConversationImagePreviewDialog(
    request: ConversationImagePreviewRequest,
    thumbnails: Map<String, ImageBitmap>,
    onDismiss: () -> Unit,
    loadOriginal: suspend (GatewayImageAttachment) -> ByteArray? = { null }
) {
    if (request.attachments.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saveTarget by remember { mutableStateOf<GatewayImageAttachment?>(null) }
    var saveMenuPosition by remember { mutableStateOf<IntOffset?>(null) }
    var permissionTarget by remember { mutableStateOf<GatewayImageAttachment?>(null) }
    fun save(attachment: GatewayImageAttachment) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val bytes = loadOriginal(attachment) ?: error(appText("图片尚未加载，请重试"))
                saveImageToPhotos(context, bytes, attachment.mediaType)
                Toast.makeText(context, appText("已保存到相册"), Toast.LENGTH_SHORT).show()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, appText("保存失败，请重试"), Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val attachment = permissionTarget
        permissionTarget = null
        if (granted && attachment != null) save(attachment)
        else Toast.makeText(context, appText("请允许存储权限后重试"), Toast.LENGTH_LONG).show()
    }
    var zoomedPages by remember { mutableStateOf(emptySet<Int>()) }
    val pagerState = rememberPagerState(
        initialPage = imagePreviewInitialPage(request.attachments.size, request.initialIndex),
        pageCount = request.attachments::size
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        DshMenuHost {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .testTag("conversation-image-preview")
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = pagerState.currentPage !in zoomedPages,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val attachment = request.attachments[page]
                    ZoomablePreviewImage(
                        image = thumbnails[attachment.attachmentId],
                        contentDescription = attachment.name ?: appText("图片"),
                        isActive = pagerState.currentPage == page,
                        onZoomChanged = { zoomed -> zoomedPages = if (zoomed) zoomedPages + page else zoomedPages - page },
                        onLongPress = { position ->
                            saveTarget = attachment
                            saveMenuPosition = IntOffset(position.x.toInt(), position.y.toInt())
                        }
                    )
                }
                DshPointMenu(point = saveMenuPosition, onDismiss = { saveMenuPosition = null; saveTarget = null }) {
                    DshMenuItem(text = { Text(appText("保存到相册")) }, enabled = !saving, onClick = {
                        val attachment = saveTarget
                        saveTarget = null
                        saveMenuPosition = null
                        if (attachment != null) {
                            if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                permissionTarget = attachment
                                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else save(attachment)
                        }
                    })
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DshGlassButton(appText("关闭图片预览"), onDismiss, Modifier.size(38.dp)) {
                        Icon(painterResource(R.drawable.ic_close), null, Modifier.size(16.dp), tint = dshGlassForeground())
                    }
                    Text(
                        text = "${pagerState.currentPage + 1} / ${request.attachments.size}",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.size(38.dp))
                }
            }
        }
    }
}

internal fun previewBoundedTranslation(
    offset: Offset, scale: Float, viewportWidth: Float, viewportHeight: Float, imageWidth: Int, imageHeight: Int
): Offset {
    val fit = minOf(viewportWidth / imageWidth.coerceAtLeast(1), viewportHeight / imageHeight.coerceAtLeast(1))
    val maximumX = ((imageWidth * fit * scale - viewportWidth) / 2f).coerceAtLeast(0f)
    val maximumY = ((imageHeight * fit * scale - viewportHeight) / 2f).coerceAtLeast(0f)
    return Offset(
        if (maximumX == 0f) 0f else offset.x.coerceIn(-maximumX, maximumX),
        if (maximumY == 0f) 0f else offset.y.coerceIn(-maximumY, maximumY)
    )
}

@Composable
internal fun ZoomablePreviewImage(
    image: ImageBitmap?,
    contentDescription: String,
    onLongPress: ((Offset) -> Unit)? = null,
    isActive: Boolean = true,
    onZoomChanged: (Boolean) -> Unit = {}
) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var translation by remember(image) { mutableStateOf(Offset.Zero) }
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentZoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(isActive, image) {
        scale = 1f
        translation = Offset.Zero
        currentZoomChanged(false)
    }
    LaunchedEffect(scale > 1f) { currentZoomChanged(scale > 1f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(image) {
                detectTapGestures(
                    onLongPress = { currentLongPress?.invoke(it) },
                    onDoubleTap = { point ->
                        if (image != null) {
                            val nextScale = if (scale > 1f) 1f else 3f
                            val focal = point - Offset(size.width / 2f, size.height / 2f)
                            translation = previewBoundedTranslation(
                                (translation - focal) * (nextScale / scale) + focal,
                                nextScale, size.width.toFloat(), size.height.toFloat(), image.width, image.height
                            )
                            scale = nextScale
                        }
                    }
                )
            }
            .pointerInput(image) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var accumulatedPan = Offset.Zero
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        val pan = event.calculatePan()
                        accumulatedPan += pan
                        // 保留静止触摸给双击/长按，只在两指操作或拖动越过阈值后消费事件。
                        if (pressedCount >= 2 || (scale > 1f && accumulatedPan.getDistance() > viewConfiguration.touchSlop)) {
                            transforming = true
                        }
                        if (transforming && image != null) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val focal = if (centroid.x.isFinite() && centroid.y.isFinite()) centroid - Offset(size.width / 2f, size.height / 2f) else Offset.Zero
                            translation = previewBoundedTranslation(
                                (translation - focal) * (nextScale / scale) + focal + pan,
                                nextScale, size.width.toFloat(), size.height.toFloat(), image.width, image.height
                            )
                            scale = nextScale
                            event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    },
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_photo_stack),
                contentDescription = contentDescription,
                modifier = Modifier.size(54.dp),
                tint = Color.White.copy(alpha = 0.42f)
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        AttachmentGrid(item.images, thumbnails, states, onRetry)
        if (item.text.isNotEmpty()) {
            DshStreamingAwareMarkdownText(
                markdown = item.text,
                isStreaming = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
        AssistantMessageActions(item.text)
    }
}

@Composable
private fun AssistantMessageHeader(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        AttachmentGrid(item.images, thumbnails, states, onRetry)
    }
}

@Composable
private fun StatusRow(item: ConversationItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_command_status),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (item.isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
        )
        Text(item.title, fontSize = 14.sp, color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("·", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        Text(item.text, fontSize = 14.sp, color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
    }
}

@Composable
private fun SystemRow(item: ConversationItem) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background((if (item.isError) Color.Red else DshColors.Ocean).copy(alpha = 0.06f), RoundedCornerShape(11.dp)).padding(10.dp)
    ) {
        Text(if (item.isError) "!" else "⌁", color = if (item.isError) Color.Red else DshColors.Ocean)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (item.text.isNotEmpty()) Text(item.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
internal fun AttachmentGrid(
    attachments: List<GatewayImageAttachment>,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    if (attachments.isEmpty()) return
    // 元数据先决定占位尺寸，缩略图和错误提示只填充区域，不参与行高计算。
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = if (attachments.size == 1) maxWidth else (maxWidth - 6.dp) / 2
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            attachments.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { attachment ->
                        val size = userSingleAttachmentPreviewSize(
                            attachment.width, attachment.height,
                            minOf(if (attachments.size == 1) 320.dp else 180.dp, columnWidth)
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                            UserAttachmentImage(
                                attachment, thumbnails[attachment.attachmentId], states[attachment.attachmentId],
                                onRetry, {}, Modifier.size(size.width, size.height), 12.dp, 0.dp
                            )
                        }
                    }
                    if (row.size == 1 && attachments.size > 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun AssistantMessageActions(text: String) {
    if (text.isEmpty()) return
    val onReply = LocalMessageQuote.current
    Row(
        Modifier.testTag("assistant-message-actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CopyButton(text, markdown = true)
        if (onReply != null) {
            IconButton(
                onClick = { onReply(markdownPlainText(text)) },
                modifier = Modifier.size(44.dp).testTag("message-reply")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_message_reply),
                    contentDescription = appText("回复"),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@Composable
private fun CopyButton(text: String, markdown: Boolean = false) {
    if (text.isEmpty()) return
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    Box(
        modifier = Modifier.width(16.dp).height(26.dp).clickable {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek", if (markdown) markdownPlainText(text) else text))
            copied = true
        }.semantics { contentDescription = if (copied) appText("已复制") else appText("复制正文") },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(if (copied) R.drawable.ic_menu_check else R.drawable.ic_copy_message),
            contentDescription = null,
            modifier = Modifier.size(if (copied) 14.dp else 16.dp),
            tint = if (copied) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun MarkdownLikeText(text: String) {
    DshMarkdownText(text, Modifier.fillMaxWidth())
}

@Composable
private fun ConversationHeaderMode(title: String, connection: GatewayConnectionState, compact: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 4.dp)
    ) {
        SmallConnectionDot(connection)
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = if (compact) 11.sp else 12.sp,
            lineHeight = if (compact) 14.sp else TextUnit.Unspecified,
            fontWeight = if (compact) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("conversation-mode-label").then(if (compact) Modifier else Modifier.widthIn(max = 96.dp))
        )
    }
}

@Composable
private fun SmallConnectionDot(state: GatewayConnectionState) {
    val color = when (state) {
        GatewayConnectionState.CONNECTED -> DshColors.Success
        else -> Color.Red
    }
    StatusIndicatorDot(
        color = color,
        modifier = Modifier.size(8.dp),
        glowing = state == GatewayConnectionState.CONNECTED
    )
}

private fun permissionTitle(value: String?) = when (value) {
    "read-only" -> appText("只读")
    "workspace-write" -> appText("工作区写入")
    "danger-full-access" -> appText("完全访问")
    else -> appText("默认权限")
}

private fun modelTitle(stateHolder: AndroidSharedStateHolder): String {
    val selection = currentModelSelection(stateHolder)
    return when (selection?.model) {
        "deepseek-chat" -> "DeepSeek Chat"
        "deepseek-reasoner" -> "DeepSeek Reasoner"
        null -> "DeepSeek Agent"
        else -> currentModelItem(stateHolder)?.name ?: selection.model
    }
}

private fun currentModelSelection(stateHolder: AndroidSharedStateHolder) =
    stateHolder.snapshot.modelCatalog?.current ?: stateHolder.snapshot.defaultModel

private fun currentModelItem(stateHolder: AndroidSharedStateHolder): GatewayModelItem? {
    val selection = currentModelSelection(stateHolder) ?: return null
    return stateHolder.snapshot.modelCatalog?.groups
        ?.firstOrNull { it.id == selection.provider }
        ?.models
        ?.firstOrNull { it.id == selection.model }
}

private fun currentModelEfforts(stateHolder: AndroidSharedStateHolder): List<GatewayReasoningEffort> =
    currentModelItem(stateHolder)?.reasoning?.efforts.orEmpty()

private fun currentModelEffortId(stateHolder: AndroidSharedStateHolder): String? =
    currentModelSelection(stateHolder)?.reasoningEffort

private fun currentModelEffortTitle(stateHolder: AndroidSharedStateHolder): String? {
    val effortId = currentModelEffortId(stateHolder) ?: return null
    return currentModelEfforts(stateHolder).firstOrNull { it.id == effortId }?.name
        ?: effortId.lowercase().replaceFirstChar(Char::uppercase)
}

private fun permissionIcon(value: String?): Int = when (value) {
    "workspace-write" -> R.drawable.ic_permission_write
    "read-only" -> R.drawable.ic_permission_read
    "danger-full-access" -> R.drawable.ic_permission_warning
    else -> R.drawable.ic_permission_ask
}
