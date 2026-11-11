package com.clarklevis.dsh.android.ui

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.clarklevis.dsh.android.platform.devicetools.AndroidDeviceConnectors

internal const val CONNECTORS_ROUTE = "connectors"
internal const val CONNECTOR_STATUS_ROUTE = "connectors/status"

/** 使用产品主导航栈，继承统一的 push/pop 转场和目的地状态恢复。 */
internal fun NavGraphBuilder.connectorDestinations(
    connectors: AndroidDeviceConnectors?,
    navController: NavHostController
) {
    composable(CONNECTORS_ROUTE) {
        ConnectorsContent(
            connectors = connectors,
            onBack = { navController.popBackStack() },
            onOpenStatus = {
                navController.navigate(CONNECTOR_STATUS_ROUTE) { launchSingleTop = true }
            }
        )
    }
    composable(CONNECTOR_STATUS_ROUTE) {
        ConnectorStatusScreen(connectors, onBack = { navController.popBackStack() })
    }
}
