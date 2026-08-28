package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
    var showStats by remember { mutableStateOf(false) }
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
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp, end = 14.dp)
                    )
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SmallConnectionDot(stateHolder.gatewayState.connection)
                        Text(
                            agentPresetDisplayName(agentPresetId, agentPresetName),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        TopBarCircleButton(
                            iconRes = R.drawable.ic_more_horizontal,
                            description = "更多",
                            onClick = { showStats = true }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SegmentedControl(pagerState.currentPage) { target -> scope.launch { pagerState.animateScrollToPage(target) } }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1
            ) { page ->
                if (page == 0) ConversationPage(stateHolder, onPickImage)
                else TrajectoryPage(stateHolder.trajectoryNodes)
            }
        }
    }
    if (showStats) SessionStatsSheet(stateHolder) { showStats = false }
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

@Composable
private fun ConversationPage(stateHolder: AndroidSharedStateHolder, onPickImage: () -> Unit) {
    val density = LocalDensity.current
    var bottomContentHeight by remember { mutableStateOf(160.dp) }
    Box(Modifier.fillMaxSize()) {
        val sessionId = stateHolder.snapshot.selectedSessionId
        androidx.compose.runtime.key(sessionId) {
            ConversationTimeline(stateHolder)
        }
        ConversationBottomFade(
            modifier = Modifier.align(Alignment.BottomCenter),
            contentHeight = bottomContentHeight
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    bottomContentHeight = with(density) { size.height.toDp() }
                }
                .imePadding()
                .navigationBarsPadding()
        ) {
            val question = stateHolder.snapshot.pendingQuestions.firstOrNull {
                stateHolder.snapshot.selectedSessionId == null || it.sessionId == stateHolder.snapshot.selectedSessionId
            }
            if (question != null) {
                HumanQuestionCard(
                    request = question,
                    onAnswer = { stateHolder.answerQuestion(question.rpcId, question.sessionId, it) },
                    onCancel = { stateHolder.cancelQuestion(question.rpcId, question.sessionId) }
                )
            } else {
                Composer(stateHolder, onPickImage)
            }
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
private fun ConversationTimeline(stateHolder: AndroidSharedStateHolder) {
    val items = stateHolder.snapshot.conversation
    val displayEntries = remember(items) { makeConversationDisplayEntries(items) }
    val itemsById = remember(items) { items.associateBy(ConversationItem::id) }
    val hasInitialContent = displayEntries.isNotEmpty()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = displayEntries.lastIndex.coerceAtLeast(0)
    )
    var initialPositionApplied by remember { mutableStateOf(hasInitialContent) }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val targetHeight = with(density) { 240.dp.roundToPx() }
    LaunchedEffect(windowInfo.containerSize.width, targetHeight) {
        stateHolder.updateThumbnailTargetSize(windowInfo.containerSize.width, targetHeight)
    }
    LaunchedEffect(listState, items) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                itemsById[visible.key.toString()]?.images
            }.flatten().mapTo(mutableSetOf()) { it.attachmentId }
        }.distinctUntilChanged().collect(stateHolder::updateVisibleAttachments)
    }
    LaunchedEffect(hasInitialContent) {
        if (!initialPositionApplied && hasInitialContent) {
            listState.scrollToItem(displayEntries.lastIndex)
            initialPositionApplied = true
        }
    }
    LaunchedEffect(displayEntries.size, items.lastOrNull()?.text?.length, initialPositionApplied) {
        if (initialPositionApplied && displayEntries.isNotEmpty()) {
            listState.animateScrollToItem(displayEntries.lastIndex)
        }
    }
    LaunchedEffect(listState, stateHolder.snapshot.selectedHistoryHasMore, initialPositionApplied) {
        if (!initialPositionApplied) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index <= 2 && stateHolder.snapshot.selectedHistoryHasMore) {
                    stateHolder.loadOlderHistory()
                }
            }
    }
    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) EmptyConversation(Modifier.align(Alignment.Center).padding(bottom = 150.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .alpha(if (hasInitialContent && !initialPositionApplied) 0f else 1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 190.dp)
        ) {
            if (stateHolder.snapshot.selectedSessionId in stateHolder.historyPagingSessionIds) {
                item("history-loading") {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(9.dp))
                        Text("正在加载更早记录…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f))
                    }
                }
            }
            items(displayEntries, key = ConversationDisplayEntry::id) { entry ->
                when (entry) {
                    is ConversationDisplayEntry.Message -> ConversationRow(
                        item = entry.item,
                        thumbnails = stateHolder.attachmentThumbnails,
                        attachmentStates = stateHolder.attachmentStates,
                        onRetryAttachment = stateHolder::retryAttachment
                    )
                    is ConversationDisplayEntry.Process -> ConversationProcessRow(entry.group)
                }
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
private fun Composer(stateHolder: AndroidSharedStateHolder, onPickImage: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val isDark = isSystemInDarkTheme()
    val shadowColor = Color.Black.copy(alpha = if (isDark) 0.20f else 0.08f)
    val glassEdge = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.White.copy(alpha = if (isDark) 0.21f else 0.72f),
            0.48f to Color.White.copy(alpha = if (isDark) 0.12f else 0.52f),
            1f to Color.White.copy(alpha = if (isDark) 0.15f else 0.62f)
        )
    )
    val composerHasContent = stateHolder.messageDraft.trim().isNotEmpty() || stateHolder.preparedImages.isNotEmpty()
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
            BasicTextField(
                value = stateHolder.messageDraft,
                onValueChange = { stateHolder.messageDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp)
                    .heightIn(min = 38.dp, max = 120.dp)
                    .testTag("composer-input"),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(DshColors.Ocean),
                decorationBox = { field ->
                    Box {
                        if (stateHolder.messageDraft.isEmpty()) {
                            Text("描述你想要构建的内容", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f))
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
                Text(
                    it,
                    modifier = Modifier.background(DshColors.Purple.copy(alpha = 0.16f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
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
    Popup(
        alignment = alignment,
        offset = with(density) {
            IntOffset(
                x = horizontalCompensation.roundToPx(),
                y = 20.dp.roundToPx()
            )
        },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = false)
    ) {
        Box(Modifier.padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 48.dp)) {
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
    onRetryAttachment: (String) -> Unit
) {
    when (item.kind) {
        ConversationItemKind.USER -> UserMessage(item, thumbnails, attachmentStates, onRetryAttachment)
        ConversationItemKind.ASSISTANT -> AssistantMessage(item, thumbnails, attachmentStates, onRetryAttachment)
        ConversationItemKind.STATUS -> StatusRow(item)
        ConversationItemKind.SYSTEM -> SystemRow(item)
        else -> Unit
    }
}

@Composable
private fun ConversationProcessRow(group: ConversationProcessGroup) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                group.title,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            ProcessChevron(expanded)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 2.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
        iconRes = R.drawable.ic_process_context,
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
        iconRes = R.drawable.ic_process_reasoning,
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
        iconRes = R.drawable.ic_process_terminal,
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
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = tint
            )
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

@Composable
private fun UserMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bubbleFill = DshColors.Ocean.copy(alpha = if (isDark) 0.24f else 0.11f)
    val bubbleEdge = DshColors.Ocean.copy(alpha = if (isDark) 0.34f else 0.08f)
    Column(
        Modifier.fillMaxWidth().padding(start = 34.dp, top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Column(
            Modifier.background(bubbleFill, RoundedCornerShape(15.dp))
                .border(0.7.dp, bubbleEdge, RoundedCornerShape(15.dp))
                .padding(10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AttachmentGrid(item.images, thumbnails, states, onRetry)
            if (item.text.isNotEmpty()) Text(item.text, fontSize = 16.sp)
        }
        CopyButton(item.text)
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            WhaleIcon(Modifier.width(26.dp).height(20.dp))
            Text(item.title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        AttachmentGrid(item.images, thumbnails, states, onRetry)
        if (item.text.isNotEmpty()) MarkdownLikeText(item.text)
        CopyButton(item.text)
    }
}

@Composable
private fun StatusRow(item: ConversationItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.25f))
        Text(listOf(item.title, item.text).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(horizontal = 8.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        HorizontalDivider(Modifier.weight(1f), color = Color.Gray.copy(alpha = 0.25f))
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

@Composable
private fun TrajectoryPage(nodes: List<TrajectoryNode>) {
    if (nodes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无轨迹", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        items(nodes, key = { "trajectory:${it.id}" }) { node ->
            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(12.dp).background(trajectoryColor(node.kind), CircleShape))
                    Box(Modifier.width(2.dp).weight(1f).background(trajectoryColor(node.kind).copy(alpha = 0.25f)))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                    Text(node.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(node.subtitle.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f), fontSize = 12.sp)
                }
            }
        }
    }
}

private fun trajectoryColor(kind: TrajectoryNodeKind) = when (kind) {
    TrajectoryNodeKind.INPUT -> DshColors.Ocean
    TrajectoryNodeKind.ASSISTANT -> DshColors.Success
    TrajectoryNodeKind.REQUEST -> DshColors.Purple
    TrajectoryNodeKind.TOOL, TrajectoryNodeKind.SUBTOOL -> DshColors.Orange
    else -> Color.Gray
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionStatsSheet(stateHolder: AndroidSharedStateHolder, onDismiss: () -> Unit) {
    val snapshot = stateHolder.snapshot.statsSnapshot
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("会话统计", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            StatRow("Turns", snapshot?.stats?.turns?.toString() ?: "—")
            StatRow("Steps", snapshot?.stats?.steps?.toString() ?: "—")
            StatRow("LLM", snapshot?.stats?.llmMs?.let { "${it.toInt()} ms" } ?: "—")
            StatRow("Tools", snapshot?.stats?.toolMs?.let { "${it.toInt()} ms" } ?: "—")
            StatRow("TTFT", snapshot?.stats?.ttftMs?.let { "${it.toInt()} ms" } ?: "—")
            HorizontalDivider()
            StatRow("Input tokens", snapshot?.tokenUsage?.totals?.inputTokens?.toString() ?: "—")
            StatRow("Output tokens", snapshot?.tokenUsage?.totals?.outputTokens?.toString() ?: "—")
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.weight(1f))
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}

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
    "ask" -> "每次询问"
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
