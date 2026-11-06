package com.clarklevis.dsh.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.update.AppUpdateViewModel
import com.clarklevis.dsh.android.update.UpdateState

@Composable
internal fun AppUpdateDialogs(model: AppUpdateViewModel, state: UpdateState) {
    val context = LocalContext.current
    fun install() {
        val file = model.state.value.apk ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            model.installationHandled()
        } catch (exception: Exception) {
            model.reportError(appText("无法打开安装程序：%1\$s", exception.message))
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) install()
        else model.reportError(appText("未允许安装未知应用，请重新检查更新后重试"))
    }
    LaunchedEffect(state.apk) {
        if (state.apk != null && (Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls())) install()
    }
    if (state.apk != null && Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        DshAlertDialog(
            title = appText("允许安装更新"),
            message = appText("安装包已下载。请在系统设置中允许本应用安装未知应用，返回后将继续安装。"),
            confirmLabel = appText("前往设置"), dismissLabel = appText("取消"),
            onDismissRequest = model::dismiss,
            onConfirm = {
                try {
                    permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } catch (exception: Exception) { model.reportError(appText("无法打开安装权限设置")) }
            }
        )
    }
    state.release?.let { release ->
        DshAlertDialog(
            title = appText("发现新版本 %1\$s", release.version),
            message = appText("当前版本 %1\$s。是否下载更新并安装？", model.currentVersion),
            confirmLabel = appText("安装新版本"), dismissLabel = appText("跳过当前版本"),
            onDismissClick = model::skipVersion,
            onDismissRequest = model::dismiss,
            onConfirm = { model.download(release) }
        )
    }
    state.status?.let { status ->
        DshAlertDialog(
            title = when {
                state.downloading -> appText("下载更新")
                state.busy -> appText("检查更新")
                else -> appText("更新提示")
            },
            message = status,
            dismissLabel = if (state.busy) appText("取消") else null,
            confirmLabel = if (state.busy) null else appText("确定"),
            onDismissRequest = model::dismiss,
            onConfirm = model::dismiss
        )
    }
}
