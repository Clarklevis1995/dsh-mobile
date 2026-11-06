package com.clarklevis.dsh.android.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private val LocalScheduleInputBounds = staticCompositionLocalOf<MutableMap<Any, Rect>?> { null }

/** 在 Initial pass 旁观完整轻触，不消费事件；编辑区内点击和拖动都保留原来的处理。 */
@Composable
internal fun ScheduleKeyboardDismissHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val inputs = remember { mutableMapOf<Any, Rect>() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    CompositionLocalProvider(LocalScheduleInputBounds provides inputs) {
        Box(modifier.onGloballyPositioned { coordinates = it }.pointerInput(inputs, focus, keyboard) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val position = coordinates?.takeIf { it.isAttached }?.localToWindow(down.position)
                val outside = position != null && inputs.values.none { it.contains(position) }
                var dragged = false
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                    if (!change.pressed) {
                        if (outside && !dragged) { focus.clearFocus(force = true); keyboard?.hide() }
                        break
                    }
                }
            }
        }) { content() }
    }
}

@Composable
internal fun Modifier.scheduleInputRegion(): Modifier {
    val inputs = LocalScheduleInputBounds.current ?: return this
    val key = remember { Any() }
    DisposableEffect(inputs, key) { onDispose { inputs.remove(key) } }
    return onGloballyPositioned { inputs[key] = it.boundsInWindow() }
}
