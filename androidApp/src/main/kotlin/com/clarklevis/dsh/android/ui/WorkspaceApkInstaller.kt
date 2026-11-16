package com.clarklevis.dsh.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import com.clarklevis.dsh.android.localization.appText

private const val APK_MEDIA_TYPE = "application/vnd.android.package-archive"

internal fun AndroidWorkspaceLocalFile.isApk(): Boolean =
    name.endsWith(".apk", ignoreCase = true) ||
        mediaType.substringBefore(';').trim().equals(APK_MEDIA_TYPE, ignoreCase = true)

internal fun workspaceApkInstallIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, APK_MEDIA_TYPE)
    clipData = ClipData.newRawUri("APK", uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
internal fun rememberWorkspaceApkInstaller(stateHolder: AndroidSharedStateHolder): (AndroidWorkspaceLocalFile) -> Unit {
    val context = LocalContext.current
    var pendingApkUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun canInstallApks(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

    fun launchApkInstaller(uri: Uri) {
        runCatching {
            context.startActivity(workspaceApkInstallIntent(uri))
        }.onFailure {
            stateHolder.showPlatformError(
                if (it is ActivityNotFoundException) appText("设备上没有可用的 APK 安装程序")
                else appText("打开安装程序失败：%1\$s", it.localizedMessage)
            )
        }
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val uri = pendingApkUri?.let(Uri::parse)
        pendingApkUri = null
        if (uri != null) {
            if (canInstallApks()) launchApkInstaller(uri)
            else stateHolder.showPlatformError(appText("尚未允许安装未知应用，点击 APK 文件可重新尝试。"))
        }
    }

    return { file ->
        runCatching {
            stateHolder.retainExternalWorkspaceFile(file.file)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file.file)
            if (canInstallApks()) {
                launchApkInstaller(uri)
            } else {
                pendingApkUri = uri.toString()
                installPermissionLauncher.launch(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ))
            }
        }.onFailure {
            pendingApkUri = null
            stateHolder.showPlatformError(appText("无法准备 APK 安装：%1\$s", it.localizedMessage))
        }
    }
}
