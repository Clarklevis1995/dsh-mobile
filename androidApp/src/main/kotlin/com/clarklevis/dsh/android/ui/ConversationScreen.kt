package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AttachmentLoadState
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.protocol.GatewayModelItem
import com.clarklevis.dsh.shared.protocol.GatewayReasoningEffort
import com.clarklevis.dsh.shared.projection.ConversationItem
import com.clarklevis.dsh.shared.projection.ConversationItemKind
import com.clarklevis.dsh.shared.protocol.GatewayImageAttachment
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import com.clarklevis.dsh.shared.projection.TrajectoryNode
import com.clarklevis.dsh.shared.projection.TrajectoryNodeKind
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onBack: () -> Unit
) {
    val session = stateHolder.snapshot.sessions.firstOrNull { it.id == stateHolder.snapshot.selectedSessionId }
    val title = session?.title ?: "新建 DeepSeek Harness"
    val agentPresetId = session?.agentPreset ?: stateHolder.snapshot.agentPresetDefault
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
    LaunchedEffect(stateHolder.snapshot.selectedSessionId) { stateHolder.refreshSessionControls() }
    LaunchedEffect(pagerState.currentPage) { stateHolder.setTrajectoryActive(pagerState.currentPage == 1) }
    DisposableEffect(stateHolder) {
        onDispose { stateHolder.setTrajectoryActive(false) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = "返回",
                        onClick = {
                            dismissInput()
                            onBack()
                        },
                        modifier = Modifier.padding(start = 8.dp, end = 14.dp)
                    )
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SmallConnectionDot(stateHolder.gatewayState.connection)
                        Text(
                            agentPresetDisplayName(agentPresetId, agentPresetName),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        ConversationMoreMenu(
                            canBrowseFiles = stateHolder.snapshot.selectedSessionId != null &&
                                stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED &&
                                "file-downloads" in stateHolder.gatewayState.capabilities,
                            canReloadHistory = stateHolder.snapshot.selectedSessionId != null &&
                                stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED,
                            canPing = stateHolder.gatewayState.connection == GatewayConnectionState.CONNECTED,
                            onBrowseFiles = { showWorkspaceFiles = true },
                            onReloadHistory = stateHolder::reloadSelectedHistory,
                            onPing = stateHolder::pingGateway
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            SegmentedControl(pagerState.currentPage) { target ->
                dismissInput()
                scope.launch { pagerState.animateScrollToPage(target) }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                if (page == 0) {
                    ConversationPage(
                        stateHolder = stateHolder,
                        onPickImage = onPickImage,
                        onShowFullStats = { showStats = true },
                        onDismissInput = dismissInput
                    )
                }
                else TrajectoryPage(
                    nodes = stateHolder.trajectoryNodes,
                    isActive = pagerState.currentPage == 1
                )
            }
        }
    }
    if (showStats) {
        SessionStatsSheet(stateHolder.snapshot.statsSnapshot) { showStats = false }
    }
    if (showWorkspaceFiles) {
        WorkspaceFilesBottomSheet(stateHolder) { showWorkspaceFiles = false }
    }
}

@Composable
internal fun TopBarCircleButton(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val shadowColor = Color.Black.copy(alpha = if (isDark) 0.24f else 0.075f)
    Box(
        modifier = modifier.size(46.dp)
            .dropShadow(
                shape = CircleShape,
                shadow = Shadow(
                    radius = 12.dp,
                    spread = 0.dp,
                    color = shadowColor,
                    offset = DpOffset(x = 0.dp, y = 4.dp)
                )
            )
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.82f else 0.94f))
            .border(
                0.7.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.13f else 0.055f),
                CircleShape
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun ConversationMoreMenu(
    canBrowseFiles: Boolean,
    canReloadHistory: Boolean,
    canPing: Boolean,
    onBrowseFiles: () -> Unit,
    onReloadHistory: () -> Unit,
    onPing: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TopBarCircleButton(
            iconRes = R.drawable.ic_more_horizontal,
            description = "更多",
            onClick = { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(228.dp).testTag("conversation-more-menu"),
            offset = DpOffset(x = (-220).dp, y = 8.dp),
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.7.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
            )
        ) {
            ConversationMoreMenuItem(
                title = "工作区文件",
                iconRes = R.drawable.ic_folder_outline,
                enabled = canBrowseFiles
            ) {
                expanded = false
                onBrowseFiles()
            }
            ConversationMoreMenuItem(
                title = "重新加载历史",
                iconRes = R.drawable.ic_history_reload,
                enabled = canReloadHistory
            ) {
                expanded = false
                onReloadHistory()
            }
            ConversationMoreMenuItem(
                title = "发送 Ping",
                iconRes = R.drawable.ic_ping_waves,
                enabled = canPing
            ) {
                expanded = false
                onPing()
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
    DropdownMenuItem(
        text = {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        },
        modifier = Modifier.height(58.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp),
        onClick = onClick
    )
}

@Composable
private fun SegmentedControl(selected: Int, onSelect: (Int) -> Unit) {
    val trackShape = RoundedCornerShape(16.dp)
    val segmentShape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 66.dp, vertical = 12.dp)
            .height(32.dp)
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f))
            .padding(2.dp)
    ) {
        listOf("对话", "轨迹").forEachIndexed { index, title ->
            val isSelected = selected == index
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(segmentShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                0.5.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f),
                                segmentShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(index) }
                    .semantics { contentDescription = title },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConversationPage(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onShowFullStats: () -> Unit,
    onDismissInput: () -> Unit
) {
    val density = LocalDensity.current
    val sessionId = stateHolder.snapshot.selectedSessionId
    var bottomContentHeight by remember { mutableStateOf(160.dp) }
    var imagePreview by remember(sessionId) { mutableStateOf<ConversationImagePreviewRequest?>(null) }
    var isPinnedToBottom by remember(sessionId) { mutableStateOf(true) }
    var scrollToBottomToken by remember(sessionId) { mutableIntStateOf(0) }
    val imeIsVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeIsVisible, sessionId) {
        if (imeIsVisible) scrollToBottomToken += 1
    }
    val isColdHistoryLoad = stateHolder.snapshot.selectedHistoryIsLoading &&
        !stateHolder.snapshot.selectedHistoryIsLoadingOlder &&
        stateHolder.snapshot.conversation.isEmpty()
    var coldHistoryLoadStarted by remember(sessionId) { mutableStateOf(isColdHistoryLoad) }
    LaunchedEffect(sessionId, isColdHistoryLoad, stateHolder.snapshot.selectedHistoryIsLoading) {
        when {
            isColdHistoryLoad -> coldHistoryLoadStarted = true
            !stateHolder.snapshot.selectedHistoryIsLoading -> coldHistoryLoadStarted = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.key(sessionId) {
            ConversationTimeline(
                stateHolder = stateHolder,
                bottomContentHeight = bottomContentHeight,
                scrollToBottomToken = scrollToBottomToken,
                onPinnedToBottomChanged = { isPinnedToBottom = it },
                onUserInteraction = onDismissInput,
                onPreviewImages = { attachments, initialIndex ->
                    imagePreview = ConversationImagePreviewRequest(attachments, initialIndex)
                }
            )
        }
        val showInitialHistoryOverlay = shouldShowInitialHistoryOverlay(
            isLoading = stateHolder.snapshot.selectedHistoryIsLoading,
            isLoadingOlder = stateHolder.snapshot.selectedHistoryIsLoadingOlder,
            hasLocalContent = stateHolder.snapshot.conversation.isNotEmpty(),
            coldLoadStarted = coldHistoryLoadStarted
        )
        AnimatedVisibility(
            visible = showInitialHistoryOverlay,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .padding(bottom = bottomContentHeight)
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
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .onSizeChanged { size ->
                    bottomContentHeight = with(density) { size.height.toDp() }
                }
                .navigationBarsPadding()
        ) {
            AnimatedVisibility(
                visible = stateHolder.snapshot.conversation.isNotEmpty() && !isPinnedToBottom,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f)
            ) {
                ScrollToBottomButton(
                    isGenerating = stateHolder.snapshot.sessions
                        .firstOrNull { it.id == sessionId }
                        ?.isRunning == true,
                    onClick = { scrollToBottomToken += 1 }
                )
            }
            stateHolder.snapshot.statsSnapshot?.let { snapshot ->
                SessionStatsBanner(
                    snapshot = snapshot,
                    sessionId = sessionId,
                    onViewFullStats = onShowFullStats
                )
            }
            TaskGoalPanels(
                stateHolder = stateHolder,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            val approval = stateHolder.snapshot.pendingApprovals.firstOrNull {
                stateHolder.snapshot.selectedSessionId == null || it.sessionId == stateHolder.snapshot.selectedSessionId
            }
            val question = stateHolder.snapshot.pendingQuestions.firstOrNull {
                stateHolder.snapshot.selectedSessionId == null || it.sessionId == stateHolder.snapshot.selectedSessionId
            }
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
                    Column {
                        SlashCommandMenus(
                            stateHolder = stateHolder,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                        Composer(stateHolder, onPickImage, onDismissInput)
                    }
                }
            }
        }
    }
    imagePreview?.let { request ->
        ConversationImagePreviewDialog(
            request = request,
            thumbnails = stateHolder.attachmentThumbnails,
            onDismiss = { imagePreview = null }
        )
    }
}

internal fun shouldShowInitialHistoryOverlay(
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    hasLocalContent: Boolean,
    coldLoadStarted: Boolean = false
): Boolean = isLoading && !isLoadingOlder && (coldLoadStarted || !hasLocalContent)

internal fun historyLoadingProgressText(loadedEventCount: Int, totalEventCount: Int?): String = when {
    totalEventCount != null -> "正在加载历史记录 · $loadedEventCount/$totalEventCount"
    loadedEventCount > 0 -> "正在自动加载更早记录 · 已同步 $loadedEventCount 个事件"
    else -> "正在从 Mobile Gateway 同步会话内容…"
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
                "正在加载历史记录",
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
    contentHeight: Dp,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height((contentHeight - 44.dp).coerceAtLeast(0.dp))
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
    bottomContentHeight: Dp,
    scrollToBottomToken: Int,
    onPinnedToBottomChanged: (Boolean) -> Unit,
    onUserInteraction: () -> Unit,
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
) {
    val latestItems = stateHolder.snapshot.conversation
    val selectedSessionId = stateHolder.snapshot.selectedSessionId
    val latestHistoryLoading = selectedSessionId in stateHolder.historyPagingSessionIds
    var items by remember(selectedSessionId) { mutableStateOf(latestItems) }
    var hasHistoryLoadingRow by remember(selectedSessionId) {
        mutableStateOf(latestHistoryLoading)
    }
    val displayEntries = remember(items) { makeConversationDisplayEntries(items) }
    val timelineEntries = remember(displayEntries) {
        makeConversationTimelineEntries(displayEntries)
    }
    val attachmentIdsByTimelineId = remember(timelineEntries) {
        timelineEntries.associate { entry ->
            entry.id to entry.images.mapTo(mutableSetOf()) { it.attachmentId }
        }
    }
    val hasInitialContent = timelineEntries.isNotEmpty()
    val lastTimelineIndex = (
        timelineEntries.lastIndex + if (hasHistoryLoadingRow) 1 else 0
    ).coerceAtLeast(0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = lastTimelineIndex
    )
    var initialPositionApplied by remember { mutableStateOf(false) }
    var isPinnedToBottom by remember { mutableStateOf(true) }
    var programmaticScrollCount by remember { mutableIntStateOf(0) }
    var timelineUpdatesPaused by remember(selectedSessionId) { mutableStateOf(false) }
    val isUserTimelineScrolling by remember(listState) {
        derivedStateOf {
            listState.isScrollInProgress && programmaticScrollCount == 0
        }
    }
    LaunchedEffect(isUserTimelineScrolling) {
        if (isUserTimelineScrolling) {
            timelineUpdatesPaused = true
        } else if (timelineUpdatesPaused) {
            // fling 结束后留一帧稳定窗口，再把滚动期间积累的 token 一次性提交给列表。
            delay(TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS)
            timelineUpdatesPaused = false
        }
    }
    LaunchedEffect(
        latestItems,
        latestHistoryLoading,
        timelineUpdatesPaused,
        isUserTimelineScrolling
    ) {
        if (!timelineUpdatesPaused && !isUserTimelineScrolling) {
            items = latestItems
            hasHistoryLoadingRow = latestHistoryLoading
        }
    }
    val markdownPreloader = rememberDshMarkdownPreloader()
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
                            .filterNot { it.messageId.startsWith("stream-") }
                            .map(ConversationTimelineEntry.AssistantMarkdown::markdown)
                    )
                }
            }
    }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val targetHeight = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(windowInfo.containerSize.width, targetHeight) {
        stateHolder.updateThumbnailTargetSize(windowInfo.containerSize.width, targetHeight)
    }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.flatMapTo(mutableSetOf()) { visible ->
                attachmentIdsByTimelineId[visible.key.toString()].orEmpty()
            }
        }.distinctUntilChanged().collect(stateHolder::updateVisibleAttachments)
    }
    LaunchedEffect(hasInitialContent) {
        if (!initialPositionApplied && hasInitialContent) {
            programmaticScrollCount += 1
            try {
                listState.scrollToTimelineBottom(lastTimelineIndex, animated = false)
                initialPositionApplied = true
            } finally {
                programmaticScrollCount -= 1
            }
        }
    }
    val hasStreamingItem = items.any(::isStreamingConversationItem)
    LaunchedEffect(
        timelineEntries.size,
        items.lastOrNull()?.text?.length,
        initialPositionApplied,
        hasStreamingItem
    ) {
        if (initialPositionApplied && isPinnedToBottom && timelineEntries.isNotEmpty()) {
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
        if (scrollToBottomToken > 0 && timelineEntries.isNotEmpty()) {
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
    LaunchedEffect(listState, stateHolder.snapshot.selectedHistoryHasMore, initialPositionApplied) {
        if (!initialPositionApplied) return@LaunchedEffect
        snapshotFlow {
            HistoryPagingGestureState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                isScrollInProgress = listState.isScrollInProgress,
                lastScrolledBackward = listState.lastScrolledBackward,
                isProgrammaticScroll = programmaticScrollCount > 0
            )
        }
            .distinctUntilChanged()
            .collect { gesture ->
                if (
                    shouldLoadOlderHistory(gesture) &&
                    stateHolder.snapshot.selectedHistoryHasMore
                ) {
                    stateHolder.loadOlderHistory()
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .testTag("conversation-timeline")
            .pointerInput(onUserInteraction) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial
                    )
                    onUserInteraction()
                }
            }
    ) {
        if (items.isEmpty()) EmptyConversation(Modifier.align(Alignment.Center).padding(bottom = 150.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp)
                .alpha(if (hasInitialContent && !initialPositionApplied) 0f else 1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp,
                bottom = bottomContentHeight + 22.dp
            )
        ) {
            if (hasHistoryLoadingRow) {
                item("history-loading") {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(9.dp))
                        Text("正在加载更早记录…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                    }
                }
            }
            items(
                items = timelineEntries,
                key = ConversationTimelineEntry::id,
                contentType = ConversationTimelineEntry::contentType
            ) { entry ->
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
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                    is ConversationTimelineEntry.AssistantFooter -> Box(
                        Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 12.dp)
                    ) {
                        CopyButton(entry.text)
                    }
                }
            }
        }
    }
}

private const val TIMELINE_STREAM_RESUME_DELAY_MILLISECONDS = 80L
private const val MARKDOWN_PREFETCH_ROWS = 24

internal data class HistoryPagingGestureState(
    val firstVisibleItemIndex: Int,
    val isScrollInProgress: Boolean,
    val lastScrolledBackward: Boolean,
    val isProgrammaticScroll: Boolean
)

internal fun shouldLoadOlderHistory(gesture: HistoryPagingGestureState): Boolean =
    gesture.firstVisibleItemIndex <= 2 &&
        gesture.isScrollInProgress &&
        gesture.lastScrolledBackward &&
        !gesture.isProgrammaticScroll

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
    val shape = CircleShape
    Surface(
        onClick = onClick,
        modifier = Modifier.padding(bottom = 4.dp).size(48.dp)
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 12.dp,
                    spread = 0.dp,
                    color = Color.Black.copy(alpha = 0.10f),
                    offset = DpOffset(0.dp, 4.dp)
                )
            )
            .semantics {
                contentDescription = if (isGenerating) "正在生成，滚动到最新消息" else "滚动到最新消息"
            }
            .testTag("scroll-to-bottom"),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            0.7.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = DshColors.Ocean
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WhaleIcon(Modifier.width(52.dp).height(39.dp))
        Text("操作远端 DSH Agent", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "发送任务后，工具调用、推理进度和最终回复会通过 Mobile Gateway 实时返回。",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    stateHolder: AndroidSharedStateHolder,
    onPickImage: () -> Unit,
    onDismissInput: () -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val isDark = isSystemInDarkTheme()
    val shadowColor = dshFloatingSurfaceShadow(isDark)
    val glassEdge = dshGlassEdge(isDark)
    val composerHasContent = stateHolder.messageDraft.trim().isNotEmpty() || stateHolder.preparedImages.isNotEmpty()
    val keyboardController = LocalSoftwareKeyboardController.current
    var inputIsFocused by remember { mutableStateOf(false) }
    var observedSuccessfulSendCount by remember(stateHolder) {
        mutableStateOf(stateHolder.successfulMessageSendCount)
    }
    LaunchedEffect(inputIsFocused) {
        if (inputIsFocused) keyboardController?.show() else keyboardController?.hide()
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
                text = stateHolder.messageDraft,
                selection = TextRange(stateHolder.messageDraft.length)
            )
        )
    }
    LaunchedEffect(stateHolder.messageDraft) {
        if (inputValue.text != stateHolder.messageDraft) {
            inputValue = TextFieldValue(
                text = stateHolder.messageDraft,
                selection = TextRange(stateHolder.messageDraft.length)
            )
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
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
        Column(
            Modifier.padding(horizontal = 14.dp).padding(top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (stateHolder.preparedImages.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stateHolder.preparedImages.size) { index ->
                        val image = stateHolder.preparedImages[index]
                        Box(
                            Modifier.height(72.dp).width(82.dp).background(DshColors.Ocean.copy(alpha = 0.11f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${image.width}×${image.height}", fontSize = 11.sp)
                            Text(
                                "×",
                                modifier = Modifier.align(Alignment.TopEnd).clickable { stateHolder.removePreparedImage(index) }.padding(4.dp),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            val commandToken = stateHolder.slashCommands.commandToken
            val commandHint = stateHolder.slashCommands.argumentHint
            BasicTextField(
                value = inputValue,
                onValueChange = {
                    inputValue = it
                    stateHolder.messageDraft = it.text
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 38.dp, max = 120.dp)
                    .onFocusChanged { inputIsFocused = it.isFocused }
                    .testTag("composer-input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(DshColors.Ocean),
                visualTransformation = slashCommandVisualTransformation(commandToken),
                decorationBox = { field ->
                    Box {
                        if (stateHolder.messageDraft.isEmpty()) {
                            Text(
                                "描述你想要构建的内容",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
                            )
                        } else if (
                            commandToken != null &&
                            commandHint != null &&
                            stateHolder.messageDraft.trimEnd() == commandToken
                        ) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = Color.Transparent)) {
                                        append(stateHolder.messageDraft)
                                    }
                                    if (!stateHolder.messageDraft.last().isWhitespace()) append(" ")
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
                        field()
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ComposerIconButton(R.drawable.ic_photo_stack, "添加图片", onPickImage)
                PermissionControl(stateHolder, Modifier.width(82.dp))
                Spacer(Modifier.width(2.dp))
                ModelControl(
                    stateHolder = stateHolder,
                    modifier = Modifier.weight(1f)
                )
                ContextUsageRing(stateHolder)
                Box(
                    Modifier.size(42.dp)
                        .alpha(if (composerHasContent) 1f else 0.48f)
                        .clip(CircleShape)
                        .background(DshColors.Ocean)
                        .clickable(enabled = stateHolder.canSend, onClick = stateHolder::sendMessage)
                        .semantics { contentDescription = "发送" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                }
            }
        }
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
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 7.dp)
        ) {
            if (optionsCommand != null) item {
                Text(
                    text = "/${optionsCommand.name}",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
            if (state.catalogLoading || state.optionsLoading || state.selectionLoading) item {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = DshColors.Ocean)
            }
            if (optionsCommand == null) {
                state.filteredGroups.forEach { group ->
                    item(key = "group-${group.id}") {
                        Text(
                            text = group.title,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.64f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(group.items, key = { "catalog-${it.stableId}" }) { command ->
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
                items(state.options, key = { "option-${it.id}" }) { option ->
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

private fun slashCommandVisualTransformation(commandToken: String?): VisualTransformation {
    if (commandToken == null) return VisualTransformation.None
    return VisualTransformation { source ->
        val highlightsCommand = source.text == commandToken || source.text.startsWith("$commandToken ")
        val transformed = buildAnnotatedString {
            append(source)
            if (highlightsCommand) {
                addStyle(
                    SpanStyle(color = DshColors.Ocean, fontWeight = FontWeight.SemiBold),
                    start = 0,
                    end = commandToken.length
                )
            }
        }
        TransformedText(transformed, OffsetMapping.Identity)
    }
}

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
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
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
        ?: "workspace-write"
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
                permissionTitle(selected),
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
                DropdownMenuItem(
                    text = {
                        Text(
                            permissionTitle(option.value),
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
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 9.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun ComposerMenuItem(title: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                title,
                fontSize = 17.sp,
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
        contentPadding = PaddingValues(horizontal = 18.dp),
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
                ComposerMenuSectionTitle("推理等级")
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
            groups.forEach { group ->
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
    if (!expanded) return
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxSurfaceHeight = (windowHeight - 180.dp).coerceIn(240.dp, 560.dp)
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
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = true)
    ) {
        Box(Modifier.padding(20.dp)) {
            Surface(
                modifier = Modifier.width(width).heightIn(max = maxSurfaceHeight).dropShadow(
                    shape = RoundedCornerShape(24.dp),
                    shadow = Shadow(
                        radius = 18.dp,
                        spread = 0.dp,
                        color = Color.Black.copy(alpha = 0.18f),
                        offset = DpOffset(x = 0.dp, y = 8.dp)
                    )
                ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    content = content
                )
            }
        }
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
    val pressure = stateHolder.snapshot.contextSnapshot?.pressure
    val contextWindow = pressure?.contextWindow
    val progress = if (contextWindow != null && contextWindow > 0) {
        (pressure.pressureTokens ?: 0).toFloat() / contextWindow
    } else 0f
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(18.dp),
            strokeWidth = 3.dp,
            color = DshColors.Ocean,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
        )
    }
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
        names.isEmpty() -> "查看 ${tools.size} 个工具结果"
        else -> "使用了 ${names.joinToString("、")}${if (tools.size > 2) " 等工具" else ""}"
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
    ProcessDisclosure(
        id = "tool-${tool.id}",
        title = tool.call?.title ?: tool.result?.title ?: "工具",
        preview = tool.result?.let { if (failed) "失败" else "完成" }.orEmpty(),
        iconRes = processToolIcon(tool.call?.title),
        tint = if (failed) Color.Red else DshColors.Orange
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            tool.call?.text?.takeIf(String::isNotEmpty)?.let { arguments ->
                Text(
                    "调用参数",
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
                    if (failed) "错误" else "结果",
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
    startsWith("Context · ") -> "上下文注入 · ${removePrefix("Context · ")}"
    else -> this
}

private fun processToolIcon(toolName: String?): Int {
    val normalized = toolName.orEmpty()
        .lowercase()
        .filter(Char::isLetterOrDigit)
    return when (normalized) {
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
        if (item.text.isNotEmpty()) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
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
    onPreviewImages: (List<GatewayImageAttachment>, Int) -> Unit
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
                cornerRadius = 11.dp,
                borderWidth = 0.dp
            )
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
                    text = "${attachments.size}张",
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
                contentDescription = attachment.name ?: "图片附件",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(
                text = when (state) {
                    AttachmentLoadState.FAILED -> "加载失败 · 点击重试"
                    AttachmentLoadState.DEFERRED -> "已释放 · 点击重载"
                    else -> "正在加载图片…"
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
    onDismiss: () -> Unit
) {
    if (request.attachments.isEmpty()) return
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("conversation-image-preview")
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val attachment = request.attachments[page]
                ZoomablePreviewImage(
                    image = thumbnails[attachment.attachmentId],
                    contentDescription = attachment.name ?: "图片"
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .semantics { contentDescription = "关闭图片预览" },
                    shape = RoundedCornerShape(19.dp),
                    color = Color.White.copy(alpha = 0.16f)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                    }
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

@Composable
private fun ZoomablePreviewImage(
    image: ImageBitmap?,
    contentDescription: String
) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var translation by remember(image) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(image) {
                awaitEachGesture {
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2 || scale > 1f) {
                            val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            if (nextScale == 1f) {
                                translation = Offset.Zero
                            } else {
                                val maximumX = size.width * (nextScale - 1f) / 2f
                                val maximumY = size.height * (nextScale - 1f) / 2f
                                val pan = event.calculatePan()
                                translation = Offset(
                                    x = (translation.x + pan.x).coerceIn(-maximumX, maximumX),
                                    y = (translation.y + pan.y).coerceIn(-maximumY, maximumY)
                                )
                            }
                            scale = nextScale
                            event.changes.forEach { it.consume() }
                        }
                        event = awaitPointerEvent()
                    }
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
        AssistantMessageHeaderContent(item, thumbnails, states, onRetry)
        if (item.text.isNotEmpty()) {
            DshStreamingAwareMarkdownText(
                markdown = item.text,
                isStreaming = isStreamingConversationItem(item),
                modifier = Modifier.fillMaxWidth()
            )
        }
        CopyButton(item.text)
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
        AssistantMessageHeaderContent(item, thumbnails, states, onRetry)
    }
}

@Composable
private fun AssistantMessageHeaderContent(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        WhaleIcon(Modifier.width(26.dp).height(20.dp))
        Text(
            item.title,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
    AttachmentGrid(item.images, thumbnails, states, onRetry)
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
private fun AttachmentGrid(
    attachments: List<GatewayImageAttachment>,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    if (attachments.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { attachment ->
                    val image = thumbnails[attachment.attachmentId]
                    Box(
                        Modifier.weight(1f).heightIn(min = 80.dp, max = 240.dp)
                            .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .clickable(enabled = states[attachment.attachmentId] in setOf(AttachmentLoadState.FAILED, AttachmentLoadState.DEFERRED)) {
                                onRetry(attachment.attachmentId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (image != null) {
                            Image(image, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
                        } else {
                            Text(
                                when (states[attachment.attachmentId]) {
                                    AttachmentLoadState.FAILED -> "加载失败 · 点击重试"
                                    AttachmentLoadState.DEFERRED -> "已释放 · 点击重载"
                                    else -> "正在加载图片…"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
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
            clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
            copied = true
        }.semantics { contentDescription = if (copied) "已复制" else "复制正文" },
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

internal fun isStreamingConversationItem(item: ConversationItem): Boolean =
    item.id.startsWith("stream-")

@Composable
private fun SmallConnectionDot(state: GatewayConnectionState) {
    val color = when (state) {
        GatewayConnectionState.CONNECTED -> DshColors.Success
        GatewayConnectionState.FAILED -> Color.Red
        GatewayConnectionState.CONNECTING, GatewayConnectionState.AUTHENTICATING, GatewayConnectionState.WAITING_FOR_NETWORK -> DshColors.Amber
        else -> Color.Gray
    }
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

private fun permissionTitle(value: String?) = when (value) {
    "read-only" -> "只读"
    "workspace-write" -> "工作区写入"
    "danger-full-access" -> "完全访问"
    else -> "默认权限"
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
