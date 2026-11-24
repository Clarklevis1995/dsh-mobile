package com.clarklevis.dsh.android.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/** Bot 页面共用真实导航栈；推入时旧页视差后退，返回时反向还原。 */
@Composable
internal fun BotNavigationHost(
    controller: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val motion = tween<androidx.compose.ui.unit.IntOffset>(350, easing = CubicBezierEasing(.32f, .72f, 0f, 1f))
    NavHost(controller, startDestination, modifier.clipToBounds(),
        enterTransition = { slideInHorizontally(motion) { it * direction } },
        exitTransition = { slideOutHorizontally(motion) { -it * direction / 3 } },
        popEnterTransition = { slideInHorizontally(motion) { -it * direction / 3 } },
        popExitTransition = { slideOutHorizontally(motion) { it * direction } },
        builder = builder)
}

/** 仅接受当前页完成进入后的点击，避免双击产生重复页面或误退两层。 */
internal fun NavHostController.pushBotPage(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState != Lifecycle.State.RESUMED) return
    navigate(route) { launchSingleTop = true }
}

internal fun NavHostController.popBotPage() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}
