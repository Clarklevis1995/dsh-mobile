package com.clarklevis.dsh.android.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import com.clarklevis.dsh.android.AndroidSharedStateHolder
import com.clarklevis.dsh.android.localization.appText
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private object ComposerTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent =
        super.createIntent(context, input).apply {
            clipData = ClipData.newRawUri("photo", input)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
}

@Composable
internal fun rememberComposerCamera(stateHolder: AndroidSharedStateHolder): () -> Unit {
    val context = LocalContext.current
    val directory = File(context.cacheDir, "composer-camera")
    // 相机在外部 Activity 中运行。保存请求来源，旋转后仍可处理返回结果。
    var pending by rememberSaveable { mutableStateOf<Bundle?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    error?.let { message ->
        DshAlertDialog(title = appText("相机"), message = message, onDismissRequest = { error = null },
            confirmLabel = appText("好"), onConfirm = { error = null })
    }

    fun reportPermissionDenied() {
        error = appText("未获得相机权限。请在系统设置中允许 DeepSeek Harness 使用相机后重试。")
    }

    val capture = rememberLauncherForActivityResult(ComposerTakePicture) { success ->
        val request = pending
        pending = null
        val name = request?.getString("name") ?: return@rememberLauncherForActivityResult
        val file = File(directory, name)
        if (!success || request.getString("gateway") != stateHolder.gatewayLocalId ||
            request.getString("session") != stateHolder.snapshot.selectedSessionId) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
        val job = stateHolder.prepareImages(listOf(uri), request.getString("session"))
        if (job == null) file.delete() else job.invokeOnCompletion { file.delete() }
    }

    fun openCamera() {
        val request = pending ?: return
        if (request.getString("gateway") != stateHolder.gatewayLocalId ||
            request.getString("session") != stateHolder.snapshot.selectedSessionId) {
            pending = null
            return
        }
        val name = requireNotNull(request.getString("name"))
        val file = File(directory, name)
        fun fail(message: String) {
            pending = null
            file.delete()
            error = message
        }
        try {
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException()
            val staleBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            directory.listFiles()?.forEach { cached ->
                if (cached.isFile && cached.lastModified() < staleBefore) cached.delete()
            }
            if (!file.createNewFile()) throw IOException()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
            capture.launch(uri)
        } catch (_: ActivityNotFoundException) {
            fail(appText("此设备没有可用相机。"))
        } catch (_: SecurityException) {
            pending = null
            file.delete()
            reportPermissionDenied()
        } catch (_: IOException) {
            fail(appText("相机启动失败，请稍后重试。"))
        } catch (_: IllegalArgumentException) {
            fail(appText("相机启动失败，请稍后重试。"))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else {
            val gateway = pending?.getString("gateway")
            pending = null
            if (gateway == stateHolder.gatewayLocalId) reportPermissionDenied()
        }
    }
    return launch@{
        if (pending != null) return@launch
        pending = bundleOf(
            "name" to "${UUID.randomUUID()}.jpg",
            "gateway" to stateHolder.gatewayLocalId,
            "session" to stateHolder.snapshot.selectedSessionId
        )
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
}
