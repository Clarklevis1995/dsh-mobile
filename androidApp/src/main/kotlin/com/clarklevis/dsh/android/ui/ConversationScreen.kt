package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", fontSize = 31.sp) } },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        SmallConnectionDot(stateHolder.gatewayState.connection)
                        Text(
                            session?.agentPreset ?: stateHolder.snapshot.agentPresetDefault ?: "Agent",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                        TextButton(onClick = { showStats = true }) { Text("•••") }
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
private fun SegmentedControl(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 66.dp, vertical = 12.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(9.dp))
            .padding(2.dp)
    ) {
        listOf("对话", "轨迹").forEachIndexed { index, title ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (selected == index) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(title, fontSize = 13.sp, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ConversationPage(stateHolder: AndroidSharedStateHolder, onPickImage: () -> Unit) {
    val listState = rememberLazyListState()
    val items = stateHolder.snapshot.conversation
    val itemsById = remember(items) { items.associateBy(ConversationItem::id) }
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
    LaunchedEffect(items.size, items.lastOrNull()?.text?.length) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LaunchedEffect(listState, stateHolder.snapshot.selectedHistoryHasMore) {
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
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
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
            items(items, key = { it.id }) { item ->
                ConversationRow(
                    item = item,
                    thumbnails = stateHolder.attachmentThumbnails,
                    attachmentStates = stateHolder.attachmentStates,
                    onRetryAttachment = stateHolder::retryAttachment
                )
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding()) {
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
    val shadowColor = Color.Black.copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.08f)
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
        border = androidx.compose.foundation.BorderStroke(0.7.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp, max = 120.dp).testTag("composer-input"),
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
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(DshColors.Ocean)
                        .alpha(if (stateHolder.canSend) 1f else 0.48f)
                        .clickable(enabled = stateHolder.canSend, onClick = stateHolder::sendMessage)
                        .semantics { contentDescription = "发送" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
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
        else -> DisclosureRow(item)
    }
}

@Composable
private fun UserMessage(
    item: ConversationItem,
    thumbnails: Map<String, ImageBitmap>,
    states: Map<String, AttachmentLoadState>,
    onRetry: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 34.dp, top = 12.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Column(
            Modifier.background(DshColors.Ocean.copy(alpha = 0.24f), RoundedCornerShape(15.dp))
                .border(0.7.dp, DshColors.Ocean.copy(alpha = 0.34f), RoundedCornerShape(15.dp))
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
private fun DisclosureRow(item: ConversationItem) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val tint = when (item.kind) {
        ConversationItemKind.REASONING -> DshColors.Purple
        ConversationItemKind.TOOL, ConversationItemKind.JSON_TOOL, ConversationItemKind.TOOL_RESULT -> DshColors.Orange
        ConversationItemKind.CONTEXT -> DshColors.Success
        else -> DshColors.Ocean
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .background(tint.copy(alpha = 0.07f), RoundedCornerShape(11.dp))
            .clickable { expanded = !expanded }.padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (expanded) "⌄" else "›", color = tint, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(9.dp))
            Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (!expanded) Text(item.text.replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.widthIn(max = 180.dp))
        }
        AnimatedVisibility(expanded) {
            Text(item.text, modifier = Modifier.padding(start = 20.dp, top = 9.dp), fontSize = 13.sp, fontFamily = if (item.kind == ConversationItemKind.JSON_TOOL) FontFamily.Monospace else FontFamily.Default)
        }
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
    Text(
        if (copied) "✓" else "▣",
        modifier = Modifier.clickable {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DeepSeek", text))
            copied = true
        }.semantics { contentDescription = if (copied) "已复制" else "复制正文" },
        color = if (copied) DshColors.Ocean else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        fontSize = 14.sp
    )
}

@Composable
private fun MarkdownLikeText(text: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        markdownBlocks(text).forEach { block ->
            when (block) {
                is MarkdownBlock.Code -> Text(
                    block.value,
                    modifier = Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                        RoundedCornerShape(10.dp)
                    ).padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
                is MarkdownBlock.Heading -> Text(
                    inlineMarkdown(block.value),
                    fontSize = when (block.level) { 1 -> 24.sp; 2 -> 21.sp; else -> 18.sp },
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp
                )
                is MarkdownBlock.Bullet -> Row {
                    Text("•  ", color = DshColors.Ocean)
                    Text(inlineMarkdown(block.value), fontSize = 16.sp, lineHeight = 24.sp)
                }
                is MarkdownBlock.Paragraph -> Text(
                    inlineMarkdown(block.value),
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Code(val value: String) : MarkdownBlock
    data class Heading(val level: Int, val value: String) : MarkdownBlock
    data class Bullet(val value: String) : MarkdownBlock
    data class Paragraph(val value: String) : MarkdownBlock
}

private fun markdownBlocks(source: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()
    val code = mutableListOf<String>()
    var inCode = false
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            result += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    source.lines().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                result += MarkdownBlock.Code(code.joinToString("\n"))
                code.clear()
            } else flushParagraph()
            inCode = !inCode
        } else if (inCode) {
            code += line
        } else when {
            line.isBlank() -> flushParagraph()
            line.startsWith("### ") -> { flushParagraph(); result += MarkdownBlock.Heading(3, line.drop(4)) }
            line.startsWith("## ") -> { flushParagraph(); result += MarkdownBlock.Heading(2, line.drop(3)) }
            line.startsWith("# ") -> { flushParagraph(); result += MarkdownBlock.Heading(1, line.drop(2)) }
            line.startsWith("- ") || line.startsWith("* ") -> { flushParagraph(); result += MarkdownBlock.Bullet(line.drop(2)) }
            else -> paragraph += line
        }
    }
    if (code.isNotEmpty()) result += MarkdownBlock.Code(code.joinToString("\n"))
    flushParagraph()
    return result
}

@Composable
private fun inlineMarkdown(source: String): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    return buildAnnotatedString {
        var index = 0
        val token = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
        token.findAll(source).forEach { match ->
            append(source.substring(index, match.range.first))
            val raw = match.value
            if (raw.startsWith("**")) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.drop(2).dropLast(2)) }
            } else {
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                ) { append(raw.drop(1).dropLast(1)) }
            }
            index = match.range.last + 1
        }
        append(source.substring(index))
    }
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
