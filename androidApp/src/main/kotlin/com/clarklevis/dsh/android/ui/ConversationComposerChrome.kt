package com.clarklevis.dsh.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 回底按钮悬浮在输入区上方，其显隐不参与输入区高度和列表底部留白计算。 */
@Composable
internal fun ConversationComposerChrome(
    modifier: Modifier = Modifier,
    composerModifier: Modifier = Modifier,
    jumpVisible: Boolean,
    jumpButton: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier) {
        AnimatedVisibility(
            visible = jumpVisible,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.85f),
            exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f)
        ) { jumpButton() }
        Column(composerModifier) { content() }
    }
}
