package com.clarklevis.dsh.android.ui

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.android.platform.LocalAppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
internal fun LogExportActions() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val failure = appText("日志导出失败，请重试")

    fun report() {
        Toast.makeText(context, failure, Toast.LENGTH_LONG).show()
    }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val source = pendingFile
            pendingFile = null
            if (uri != null && source != null) {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) {
                            checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                                source.inputStream().use { it.copyTo(output) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        report()
                    } finally {
                        busy = false
                    }
                }
            }
        }
    val chooserTitle = appText("分享日志")

    fun export(share: Boolean) {
        scope.launch {
            busy = true
            try {
                val file = withContext(Dispatchers.IO) { LocalAppLog.export() }
                if (share) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("logs", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(intent, chooserTitle))
                } else {
                    pendingFile = file
                    save.launch(file.name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                report()
            } finally {
                busy = false
            }
        }
    }
    SettingsActionRow(appText("导出日志"), enabled = !busy) { export(false) }
    SettingsDivider()
    SettingsActionRow(chooserTitle, enabled = !busy) { export(true) }
}
