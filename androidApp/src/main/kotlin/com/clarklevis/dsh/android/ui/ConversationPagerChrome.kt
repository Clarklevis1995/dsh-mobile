package com.clarklevis.dsh.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.localization.appText
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay

/** 两页始终常驻，顶部玻璃、提示和导航按钮都覆盖在内容上，不占分页高度。 */
@Suppress("LongParameterList") // Compose 容器显式接收状态、修饰符及两个内容插槽。
@Composable
internal fun ConversationPagerChrome(
    pagerState: PagerState,
    sessionId: String?,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit,
    page: @Composable (Int, Dp) -> Unit
) {
    val density = LocalDensity.current
    val initialTopInset = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 64.dp
    var topInset by remember(density) { mutableStateOf(initialTopInset) }
    val backdrop = rememberLayerBackdrop()
    val currentPage = pagerState.currentPage
    val pageName = appText(if (currentPage == 0) "对话" else "轨迹")
    val switchLabel = appText(if (currentPage == 0) "切换到轨迹" else "切换到对话")
    val pagerLabel = appText("会话页面")
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(
            modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        ) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
                    .testTag("conversation-pager")
                    .semantics {
                        contentDescription = pagerLabel
                        stateDescription = pageName
                        customActions = listOf(
                            CustomAccessibilityAction(switchLabel) {
                                onSelectPage(1 - currentPage)
                                true
                            }
                        )
                    }
            ) { index ->
                Box(
                    Modifier.fillMaxSize().semantics {
                        if (index != currentPage) hideFromAccessibility()
                    }
                ) {
                    page(index, topInset)
                }
            }
            Box(
                Modifier.fillMaxWidth().height(topInset + 32.dp)
                    .conversationTopGlass(backdrop)
                    .semantics { hideFromAccessibility() }
                    .testTag("conversation-top-glass")
            )
            Box(Modifier.fillMaxWidth().onSizeChanged { topInset = with(density) { it.height.toDp() } }) {
                topBar()
            }
            ConversationPageIndicator(
                sessionId,
                currentPage,
                Modifier.align(Alignment.TopCenter).padding(top = topInset + 4.dp)
            )
        }
    }
}

@Composable
internal fun ConversationPageTitle(title: String, selectedPage: Int, status: (@Composable () -> Unit)? = null) {
    val pageName = appText(if (selectedPage == 0) "对话" else "轨迹")
    Column(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics { stateDescription = pageName }
            .testTag("conversation-page-title"),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (status == null) 17.sp else 16.sp,
            lineHeight = if (status == null) TextUnit.Unspecified else 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("conversation-title-text")
        )
        status?.invoke()
    }
}

@Composable
private fun ConversationPageIndicator(sessionId: String?, selectedPage: Int, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(sessionId, selectedPage) {
        alpha.snapTo(1f)
        delay(2_000)
        alpha.animateTo(0f, tween(300))
    }
    if (alpha.value > 0f) {
        Row(
            modifier.graphicsLayer { this.alpha = alpha.value }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("conversation-page-indicator")
                .semantics { hideFromAccessibility() },
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(2) { index ->
                Box(
                    Modifier.size(5.dp).background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = if (index == selectedPage) 0.65f else 0.2f),
                        CircleShape
                    )
                )
            }
        }
    }
}
