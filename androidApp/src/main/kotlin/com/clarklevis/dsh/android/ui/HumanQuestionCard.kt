package com.clarklevis.dsh.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.shared.protocol.GatewayPendingQuestionRequest
import com.clarklevis.dsh.shared.protocol.GatewayQuestion
import com.clarklevis.dsh.shared.protocol.GatewayQuestionAnswer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val QuestionCardShape = RoundedCornerShape(24.dp)
private val QuestionPanelTransformOrigin = TransformOrigin(0.5f, 1f)
private val QuestionConfirmationShape = RoundedCornerShape(26.dp)
private val QuestionConfirmationArrowShape = GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width / 2f, size.height)
    close()
}

@Composable
internal fun AnimatedHumanQuestionPanel(
    request: GatewayPendingQuestionRequest?,
    onAnswer: (GatewayPendingQuestionRequest, List<GatewayQuestionAnswer>) -> Unit,
    onCancel: (GatewayPendingQuestionRequest) -> Unit,
    emptyContent: @Composable () -> Unit
) {
    AnimatedContent(
        targetState = request,
        modifier = Modifier.fillMaxWidth(),
        transitionSpec = {
            val transform = if (targetState != null) {
                val enter = slideInVertically(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    initialOffsetY = { height -> height / 2 }
                ) + fadeIn(tween(220)) + scaleIn(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    initialScale = 0.96f,
                    transformOrigin = QuestionPanelTransformOrigin
                )
                enter togetherWith fadeOut(tween(120))
            } else {
                val exit = slideOutVertically(
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    targetOffsetY = { height -> height / 2 }
                ) + fadeOut(tween(180)) + scaleOut(
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    targetScale = 0.96f,
                    transformOrigin = QuestionPanelTransformOrigin
                )
                fadeIn(tween(durationMillis = 180, delayMillis = 100)) togetherWith exit
            }
            transform.using(
                SizeTransform(clip = false) { _, _ ->
                    tween(durationMillis = 320, easing = FastOutSlowInEasing)
                }
            )
        },
        contentAlignment = Alignment.BottomCenter,
        contentKey = { target -> target?.rpcId ?: "question-panel-empty" },
        label = "human-question-panel-visibility"
    ) { animatedRequest ->
        if (animatedRequest == null) {
            emptyContent()
        } else {
            HumanQuestionCard(
                request = animatedRequest,
                onAnswer = { answers -> onAnswer(animatedRequest, answers) },
                onCancel = { onCancel(animatedRequest) }
            )
        }
    }
}

@Composable
internal fun HumanQuestionCard(
    request: GatewayPendingQuestionRequest,
    onAnswer: (List<GatewayQuestionAnswer>) -> Unit,
    onCancel: () -> Unit
) {
    var currentIndex by remember(request.rpcId) { mutableIntStateOf(0) }
    var collapsed by remember(request.rpcId) { mutableStateOf(false) }
    var showsCancelConfirmation by remember(request.rpcId) { mutableStateOf(false) }
    var validationMessage by remember(request.rpcId) { mutableStateOf<String?>(null) }
    var panelTopInWindowPx by remember(request.rpcId) { mutableStateOf(0f) }
    val selections = remember(request.rpcId) { mutableStateMapOf<String, Set<String>>() }
    val customAnswers = remember(request.rpcId) { mutableStateMapOf<String, String>() }
    val question = request.questions[currentIndex]

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("question-panel")
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val cardMaxHeight = minOf(maxHeight, 500.dp)
        val isDark = isSystemInDarkTheme()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = cardMaxHeight)
                .testTag("question-card-surface")
                .onGloballyPositioned { coordinates ->
                    panelTopInWindowPx = coordinates.positionInWindow().y
                }
                .dropShadow(
                    shape = QuestionCardShape,
                    shadow = Shadow(
                        radius = 20.dp,
                        color = Color.Black.copy(alpha = if (isDark) 0.30f else 0.12f),
                        offset = DpOffset(0.dp, 9.dp)
                    )
                )
                .clip(QuestionCardShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(
                    0.8.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    QuestionCardShape
                )
        ) {
            QuestionHeader(
                collapsed = collapsed,
                question = question,
                replay = request.replay,
                onToggle = { collapsed = !collapsed },
                onCancel = { showsCancelConfirmation = true }
            )
            AnimatedVisibility(
                visible = !collapsed,
                modifier = Modifier.weight(1f, fill = false),
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    clip = false
                ) + fadeIn(tween(durationMillis = 180, delayMillis = 40)),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    clip = false
                ) + fadeOut(tween(120))
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    val scrollState = rememberScrollState()
                    LaunchedEffect(currentIndex) { scrollState.scrollTo(0) }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 17.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        QuestionHeading(question)
                        AnswerControls(
                            question = question,
                            selections = selections[question.id].orEmpty(),
                            customAnswer = customAnswers[question.id].orEmpty(),
                            onSelectionChange = { updated ->
                                selections[question.id] = updated
                                if (!question.allowsMultipleSelections && updated.isNotEmpty()) {
                                    customAnswers[question.id] = ""
                                }
                                validationMessage = null
                            },
                            onCustomAnswerChange = { value ->
                                customAnswers[question.id] = value
                                if (!question.allowsMultipleSelections && value.isNotBlank()) {
                                    selections[question.id] = emptySet()
                                }
                                validationMessage = null
                            }
                        )
                        validationMessage?.let { message ->
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    QuestionFooter(
                        currentIndex = currentIndex,
                        questionCount = request.questions.size,
                        onPrevious = { currentIndex-- },
                        onNext = { currentIndex++ },
                        onSkip = { showsCancelConfirmation = true },
                        onContinue = {
                            if (!answered(question, selections, customAnswers)) {
                                validationMessage = "请选择一个选项或填写自定义答案。"
                                return@QuestionFooter
                            }
                            if (currentIndex < request.questions.lastIndex) {
                                currentIndex++
                                validationMessage = null
                                return@QuestionFooter
                            }
                            val firstMissing = request.questions.indexOfFirst {
                                !answered(it, selections, customAnswers)
                            }
                            if (firstMissing >= 0) {
                                currentIndex = firstMissing
                                validationMessage = "请先完成这道问题。"
                                return@QuestionFooter
                            }
                            onAnswer(
                                request.questions.map { item ->
                                    GatewayQuestionAnswer(
                                        item.id,
                                        selections[item.id].orEmpty().toList(),
                                        customAnswers[item.id]
                                    )
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    if (showsCancelConfirmation) {
        QuestionCancelConfirmation(
            anchorTopInWindowPx = panelTopInWindowPx,
            onDismiss = { showsCancelConfirmation = false },
            onConfirm = {
                showsCancelConfirmation = false
                onCancel()
            }
        )
    }
}

@Composable
private fun QuestionCancelConfirmation(
    anchorTopInWindowPx: Float,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            val density = LocalDensity.current
            var popoverHeightPx by remember { mutableIntStateOf(0) }
            val verticalGapPx = with(density) { 2.dp.roundToPx() }
            val minimumTopPx = with(density) { 48.dp.roundToPx() }
            val maximumTopPx = with(density) {
                (maxHeight - 48.dp).roundToPx() - popoverHeightPx
            }
            val requestedTopPx = anchorTopInWindowPx.toInt() - popoverHeightPx - verticalGapPx
            val popoverTopPx = requestedTopPx.coerceIn(
                minimumValue = minimumTopPx,
                maximumValue = maximumTopPx.coerceAtLeast(minimumTopPx)
            )
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }

            AnimatedVisibility(
                visible = visible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, popoverTopPx) }
                    .padding(horizontal = 28.dp),
                enter = fadeIn(tween(140)) + scaleIn(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    initialScale = 0.94f,
                    transformOrigin = QuestionPanelTransformOrigin
                )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .fillMaxWidth()
                        .testTag("question-cancel-popover")
                        .onSizeChanged { size -> popoverHeightPx = size.height }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = QuestionConfirmationShape,
                        color = surfaceColor,
                        shadowElevation = 18.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "放弃这组问题？",
                                fontSize = 18.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "当前填写的答案不会提交。",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f))
                                    .clickable(onClick = onConfirm)
                                    .testTag("question-cancel-confirm"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "跳过并让 Agent 继续",
                                    color = Color(0xFFFF3B30),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Box(modifier = Modifier.size(width = 28.dp, height = 14.dp)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                // Let the tail cover the card edge by 2dp so anti-aliasing
                                // cannot reveal a seam between the separately drawn layers.
                                .offset(y = (-2).dp)
                                .size(width = 28.dp, height = 16.dp)
                                .dropShadow(
                                    shape = QuestionConfirmationArrowShape,
                                    shadow = Shadow(
                                        radius = 4.dp,
                                        color = Color.Black.copy(alpha = 0.10f),
                                        offset = DpOffset(0.dp, 2.dp)
                                    )
                                )
                                .background(surfaceColor, QuestionConfirmationArrowShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionHeader(
    collapsed: Boolean,
    question: GatewayQuestion,
    replay: Boolean,
    onToggle: () -> Unit,
    onCancel: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) 180f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "question-chevron-rotation"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (collapsed) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 17.dp, end = 9.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(
                if (collapsed) R.drawable.ic_question_bubble_filled
                else R.drawable.ic_question_bubble
            ),
            contentDescription = null,
            tint = DshColors.Ocean,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(11.dp))
        AnimatedContent(
            targetState = collapsed,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(durationMillis = 180, delayMillis = 30)) togetherWith
                    fadeOut(tween(100))
            },
            label = "question-header-content"
        ) { targetCollapsed ->
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    if (targetCollapsed) "Agent 正在等待回答" else "需要你的回答",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (targetCollapsed || replay) {
                    Text(
                        if (targetCollapsed) question.question else "已从断线前恢复",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                }
            }
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                painterResource(R.drawable.ic_question_chevron_down),
                contentDescription = if (collapsed) "展开问题卡片" else "收起问题卡片",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                modifier = Modifier.size(20.dp).rotate(chevronRotation)
            )
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandHorizontally(
                expandFrom = Alignment.End,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            ) + fadeIn(tween(160)),
            exit = shrinkHorizontally(
                shrinkTowards = Alignment.End,
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) + fadeOut(tween(100))
        ) {
            HeaderIconButton(R.drawable.ic_close, "放弃整组问题", onCancel)
        }
    }
}

@Composable
private fun HeaderIconButton(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painterResource(iconRes),
            contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun QuestionHeading(question: GatewayQuestion) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        question.header?.takeIf(String::isNotEmpty)?.let { header ->
            Text(
                header,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
            )
        }
        Text(question.question, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        question.detail?.takeIf(String::isNotEmpty)?.let { detail ->
            DshMarkdownText(markdown = detail, compact = true)
        }
        if (question.allowsMultipleSelections) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(R.drawable.ic_checklist),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "可以选择多项",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                )
            }
        }
    }
}

@Composable
private fun AnswerControls(
    question: GatewayQuestion,
    selections: Set<String>,
    customAnswer: String,
    onSelectionChange: (Set<String>) -> Unit,
    onCustomAnswerChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        question.options.orEmpty().forEach { option ->
            val selected = option.label in selections
            val metadata = optionMetadata(option.label)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) DshColors.Ocean.copy(alpha = 0.09f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
                    )
                    .border(
                        1.dp,
                        if (selected) DshColors.Ocean.copy(alpha = 0.48f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable {
                        val updated = if (question.allowsMultipleSelections) {
                            if (selected) selections - option.label else selections + option.label
                        } else if (selected) emptySet() else setOf(option.label)
                        onSelectionChange(updated)
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painterResource(
                        when {
                            question.allowsMultipleSelections && selected -> R.drawable.ic_checkbox_selected
                            question.allowsMultipleSelections -> R.drawable.ic_checkbox_unselected
                            selected -> R.drawable.ic_radio_selected
                            else -> R.drawable.ic_radio_unselected
                        }
                    ),
                    contentDescription = if (selected) "已选择" else "未选择",
                    tint = if (selected) DshColors.Ocean
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                    modifier = Modifier.padding(top = 1.dp).size(19.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            metadata.title,
                            fontSize = 16.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (metadata.isRecommended) {
                            Text(
                                "推荐",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DshColors.Ocean,
                                modifier = Modifier
                                    .background(DshColors.Ocean.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                    option.description?.takeIf(String::isNotEmpty)?.let { description ->
                        Text(
                            description,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                        )
                    }
                }
            }
        }
        CustomAnswerField(
            value = customAnswer,
            onValueChange = onCustomAnswerChange,
            hasOptions = !question.options.isNullOrEmpty()
        )
    }
}

@Composable
private fun CustomAnswerField(value: String, onValueChange: (String) -> Unit, hasOptions: Boolean) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("question-custom-answer")
            .bringIntoViewRequester(bringIntoViewRequester)
            .heightIn(min = if (hasOptions) 48.dp else 92.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                RoundedCornerShape(if (hasOptions) 13.dp else 14.dp)
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = if (hasOptions) Alignment.CenterVertically else Alignment.Top
    ) {
        Icon(
            painterResource(R.drawable.ic_pencil_line),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            modifier = Modifier.padding(top = if (hasOptions) 0.dp else 2.dp).size(18.dp)
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    if (hasOptions) "或输入自定义答案" else "输入你的答案",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.34f),
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("question-custom-input")
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            coroutineScope.launch {
                                delay(280)
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                ),
                cursorBrush = SolidColor(DshColors.Ocean),
                minLines = if (hasOptions) 1 else 3,
                maxLines = 3
            )
        }
    }
}

@Composable
private fun QuestionFooter(
    currentIndex: Int,
    questionCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("question-footer")
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FooterIconButton(
                R.drawable.ic_back_chevron,
                "上一题",
                currentIndex > 0,
                onPrevious
            )
            Text(
                "${currentIndex + 1} / $questionCount",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                maxLines = 1
            )
            FooterIconButton(
                R.drawable.ic_chevron_right,
                "下一题",
                currentIndex < questionCount - 1,
                onNext
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .heightIn(min = 42.dp)
                    .widthIn(min = 92.dp)
                    .testTag("question-skip"),
                shape = RoundedCornerShape(21.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(
                    "跳过问题",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .heightIn(min = 42.dp)
                    .widthIn(min = 68.dp)
                    .testTag("question-submit"),
                shape = RoundedCornerShape(21.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DshColors.Ocean,
                    contentColor = Color.White
                ),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(
                    if (currentIndex == questionCount - 1) "提交" else "下一题",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun FooterIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            painterResource(iconRes),
            contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.86f else 0.24f),
            modifier = Modifier.size(20.dp)
        )
    }
}

private data class OptionMetadata(val title: String, val isRecommended: Boolean)

private fun optionMetadata(label: String): OptionMetadata {
    val suffixes = listOf(" (recommended)", "（recommended）", " (推荐)", "（推荐）")
    val suffix = suffixes.firstOrNull { label.lowercase().endsWith(it.lowercase()) }
    return OptionMetadata(
        title = suffix?.let { label.dropLast(it.length) } ?: label,
        isRecommended = suffix != null
    )
}

private fun answered(
    question: GatewayQuestion,
    selections: Map<String, Set<String>>,
    customAnswers: Map<String, String>
): Boolean = selections[question.id].orEmpty().isNotEmpty() ||
    customAnswers[question.id]?.isNotBlank() == true
