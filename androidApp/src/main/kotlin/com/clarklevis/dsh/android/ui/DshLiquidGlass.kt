package com.clarklevis.dsh.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.shadow.Shadow as ComposeShadow
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.exposureAdjustment
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 页面内玻璃控件只采样背景层，避免递归渲染和重影；独立窗口的弹层采样整个页面。
 *
 * API 31+ 使用真实背景采样与模糊，API 33+ 额外启用 AGSL lens 折射；
 * API 24～30 保持与原设计一致的半透明玻璃降级。
 */
@Composable
internal fun DshLiquidGlassHost(
    modifier: Modifier = Modifier,
    frostOnOlderDevices: Boolean = false,
    workspaceScrollState: ScrollableState? = null,
    background: @Composable (Modifier) -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val effectsReduced = LocalUiEffectsReduced.current
    val cardGlassApi = LocalDshCardGlassApi.current
    val backdrop = rememberLayerBackdrop()
    val cacheWorkspaceBackdrop =
        workspaceScrollState != null && !effectsReduced && cardGlassApi >= Build.VERSION_CODES.S
    SideEffect {
        // 所有卡片采样同一份离屏背景，滚动时继续绘制各自的模糊和折射。
        backdrop.graphicsLayer.compositingStrategy =
            if (cacheWorkspaceBackdrop) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    val sheetBackdrop = rememberLayerBackdrop()
    val frameSignal = remember { mutableLongStateOf(0L) }
    val pointerDown = remember(workspaceScrollState) { mutableStateOf(false) }
    val waitingForScrollToSettle = remember(workspaceScrollState) { mutableStateOf(false) }
    val workspaceMoving = remember(workspaceScrollState) {
        derivedStateOf {
            pointerDown.value || workspaceScrollState?.isScrollInProgress == true ||
                waitingForScrollToSettle.value
        }
    }
    LaunchedEffect(workspaceScrollState) {
        if (workspaceScrollState == null) return@LaunchedEffect
        snapshotFlow { pointerDown.value || workspaceScrollState.isScrollInProgress }.collectLatest { moving ->
            if (moving) {
                waitingForScrollToSettle.value = true
            } else if (waitingForScrollToSettle.value) {
                // 连续滑动之间保持背景采样帧，避免短暂松手后又重启背景动画。
                delay(WORKSPACE_SCROLL_SETTLE_MS)
                waitingForScrollToSettle.value = false
            }
        }
    }
    val workspaceModifier = if (workspaceScrollState == null || effectsReduced) {
        modifier
    } else {
        // 在列表越过拖动阈值前暂停背景动画。点击、长按和滚动仍由原控件处理。
        modifier.pointerInput(workspaceScrollState) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pointerDown.value = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally {
                    pointerDown.value = false
                }
            }
        }
    }
    val legacyBackdrop = if (frostOnOlderDevices && cardGlassApi < Build.VERSION_CODES.S && !effectsReduced) {
        remember { LegacyFrostedBackdrop() }
    } else null
    val backgroundCapture = when {
        legacyBackdrop != null -> Modifier.captureFrostedBackdrop(legacyBackdrop)
        !effectsReduced -> Modifier.layerBackdrop(backdrop)
        else -> Modifier
    }
    CompositionLocalProvider(
        LocalDshBackdrop provides backdrop.takeUnless { effectsReduced || legacyBackdrop != null },
        LocalDshSheetBackdrop provides sheetBackdrop.takeUnless { effectsReduced },
        LocalDshGlassFrameSignal provides frameSignal.takeUnless { effectsReduced },
        LocalLegacyFrostedBackdrop provides legacyBackdrop,
        LocalWorkspaceMoving provides workspaceMoving,
    ) {
        // 弹层位于独立窗口，可采样整个页面；页面内的玻璃控件仍只采样背景。
        Box(if (effectsReduced) workspaceModifier else workspaceModifier.layerBackdrop(sheetBackdrop)) {
            val backgroundLayer = if (cacheWorkspaceBackdrop) Modifier.graphicsLayer() else Modifier
            background(
                if (effectsReduced) Modifier else backgroundLayer
                    .drawWithContent {
                        frameSignal.longValue
                        drawContent()
                    }
                    .then(backgroundCapture)
            )
            content()
        }
    }
}

internal enum class DshGlassStyle(
    val blurRadiusDp: Float,
    val refractionHeightDp: Float,
    val refractionAmountDp: Float,
    val surfaceAlpha: Float,
    val borderAlpha: Float,
    val shadowRadiusDp: Float,
    val saturation: Float,
    val navyTintAlpha: Float = 0f
) {
    CONTROL(4f, 11f, 18f, 0.050f, 0.25f, 15f, saturation = 0.92f),
    CARD(5f, 14f, 23f, 0.005f, 0.20f, 20f, saturation = 0.72f),
    ACTION(5f, 16f, 26f, 0.012f, 0.26f, 22f, saturation = 0.74f, navyTintAlpha = 0.06f),
    FIELD(5f, 10f, 16f, 0.060f, 0.10f, 14f, saturation = 0.72f),
    SUBTLE(4f, 8f, 13f, 0.040f, 0.10f, 12f, saturation = 0.72f),
    APP_CARD(8f, 3f, 5f, 0.020f, 0.20f, 6f, saturation = 1f)
}

/** 聊天应用卡片使用中性着色和轻折射，避免首页的深蓝底色影响正文。 */
@Composable
internal fun Modifier.dshAppCardGlass(shape: Shape, dark: Boolean, shadowStrength: Float = 1f): Modifier {
    val surface = if (dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val rim = Color(0xFF8E8E93)
    if (LocalUiEffectsReduced.current) {
        return background(surface, shape)
            .border(0.5.dp, rim.copy(alpha = 0.30f), shape)
            .clip(shape)
    }
    return dshOuterShadow(shape, ComposeShadow(
        radius = 6.dp, color = Color.Black.copy(alpha = (if (dark) 0.20f else 0.07f) * shadowStrength),
        offset = DpOffset(0.dp, 2.dp)
    ))
        .dshLiquidGlass(shape, DshGlassStyle.APP_CARD, shadowsEnabled = false)
        .background(surface.copy(alpha = 0.72f), shape)
        .border(0.5.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) 0.24f else 0.95f),
            rim.copy(alpha = if (dark) 0.26f else 0.20f),
            rim.copy(alpha = if (dark) 0.36f else 0.32f)
        )), shape)
        .clip(shape)
}

@Composable
internal fun Modifier.dshLiquidGlass(
    shape: Shape,
    style: DshGlassStyle = DshGlassStyle.CARD,
    shadowsEnabled: Boolean = true,
    shadowStrength: Float = 1f,
): Modifier {
    val backdrop = LocalDshBackdrop.current
    val frameSignal = LocalDshGlassFrameSignal.current
    if (LocalUiEffectsReduced.current) {
        return clip(shape)
            .background(Color(0xFF17304D).copy(alpha = 0.90f), shape)
            .border(0.8.dp, Color.White.copy(alpha = style.borderAlpha), shape)
    }
    if (backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        return clip(shape)
            .background(Color.White.copy(alpha = style.surfaceAlpha), shape)
            .border(0.8.dp, Color.White.copy(alpha = style.borderAlpha), shape)
    }
    val frameInvalidatingModifier = drawWithContent {
        frameSignal?.longValue
        drawContent()
    }

    return frameInvalidatingModifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(brightness = 0.005f, contrast = 1.03f, saturation = style.saturation)
            exposureAdjustment(0.015f)
            blur(style.blurRadiusDp.dp.toPx())
            if (style.refractionHeightDp > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = style.refractionHeightDp.dp.toPx(),
                    refractionAmount = style.refractionAmountDp.dp.toPx(),
                    depthEffect = true,
                    chromaticAberration = true
                )
            }
        },
        highlight = {
            Highlight.Default.copy(
                width = 0.90.dp,
                blurRadius = 0.75.dp,
                alpha = style.borderAlpha.coerceAtLeast(0.10f)
            )
        },
        shadow = if (shadowsEnabled) {
            {
                Shadow(radius = style.shadowRadiusDp.dp, color = Color.Black.copy(alpha = 0.09f * shadowStrength))
            }
        } else null,
        innerShadow = if (shadowsEnabled) {
            { InnerShadow.Default.copy(radius = 4.dp, alpha = style.borderAlpha * 0.22f) }
        } else null,
        onDrawSurface = {
            if (style.navyTintAlpha > 0f) {
                drawRect(Color(0xFF031B3D).copy(alpha = style.navyTintAlpha), blendMode = BlendMode.SrcOver)
            }
            drawRect(Color.White.copy(alpha = style.surfaceAlpha), blendMode = BlendMode.SrcOver)
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = style.borderAlpha * 0.20f),
                    0.34f to Color.White.copy(alpha = style.surfaceAlpha * 0.08f),
                    0.68f to Color.Transparent,
                    1f to Color.White.copy(alpha = style.surfaceAlpha * 0.03f)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = style.borderAlpha * 0.12f),
                    0.22f to Color.White.copy(alpha = style.surfaceAlpha * 0.06f),
                    0.58f to Color.Transparent,
                    1f to Color.White.copy(alpha = style.borderAlpha * 0.02f)
                )
            )
        }
    )
}

/** 两种首页共用材质和滚动策略，配色由各自的背景提供。 */
@Composable
internal fun Modifier.dshWorkspaceGlass(shape: Shape, style: DshGlassStyle = DshGlassStyle.CARD): Modifier {
    val legacyBackdrop = LocalLegacyFrostedBackdrop.current
    if (legacyBackdrop == null) {
        return dshLiquidGlass(shape, style, shadowStrength = WORKSPACE_GLASS_SHADOW_STRENGTH)
    }
    return clip(shape)
        .drawFrostedBackdrop(legacyBackdrop)
        .background(Color(0xFF031B3D).copy(alpha = style.navyTintAlpha), shape)
        .background(Color.White.copy(alpha = style.surfaceAlpha), shape)
        .border(0.8.dp, Color.White.copy(alpha = style.borderAlpha), shape)
}

internal val LocalDshCardGlassApi = staticCompositionLocalOf { Build.VERSION.SDK_INT }
private val LocalLegacyFrostedBackdrop = staticCompositionLocalOf<LegacyFrostedBackdrop?> { null }
private const val WORKSPACE_GLASS_SHADOW_STRENGTH = 0.5f
private const val WORKSPACE_SCROLL_SETTLE_MS = 220L
internal val LocalWorkspaceMoving = staticCompositionLocalOf<State<Boolean>?> { null }

private val LocalDshBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalDshSheetBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalDshGlassFrameSignal = staticCompositionLocalOf<MutableLongState?> { null }

/** 弹层只模糊已录制的背景，文字和操作控件保持清晰。 */
@Composable
internal fun Modifier.dshFrostedSheet(shape: Shape): Modifier {
    val backdrop = LocalDshSheetBackdrop.current
    val frameSignal = LocalDshGlassFrameSignal.current
    val dark = isSystemInDarkTheme()
    val tint = if (dark) Color(0xFF22252C) else Color(0xFFE8EDF5)
    val surface = if (LocalUiEffectsReduced.current || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        background(tint.copy(alpha = 0.92f), shape)
    } else {
        drawWithContent {
            frameSignal?.longValue
            drawContent()
        }.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(24.dp.toPx()) },
            onDrawSurface = { drawRect(tint.copy(alpha = if (dark) 0.76f else 0.68f)) }
        )
    }
    return surface.clip(shape).border(0.8.dp, Color.White.copy(alpha = if (dark) 0.12f else 0.35f), shape)
}
