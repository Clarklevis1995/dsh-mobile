package com.clarklevis.dsh.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64

/**
 * 保存远端工作区文件与系统文档 URI 的对应关系。
 *
 * URI 仅在文件确实写入成功后登记；读取状态时会实际打开文件校验，用户在系统文件
 * 应用中删除文件后，失效记录也会随之移除。
 */
class AndroidWorkspaceDownloadRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun record(sessionId: String, remotePath: String, localUri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(
                localUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        preferences.edit()
            .putString(preferenceKey(sessionId, remotePath), localUri.toString())
            .apply()
    }

    fun existingRemotePaths(sessionId: String, remotePaths: Collection<String>): Set<String> =
        remotePaths.filterTo(linkedSetOf()) { remotePath ->
            val key = preferenceKey(sessionId, remotePath)
            val uri = preferences.getString(key, null)?.let(Uri::parse) ?: return@filterTo false
            val exists = runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            if (!exists) preferences.edit().remove(key).apply()
            exists
        }

    private fun preferenceKey(sessionId: String, remotePath: String): String {
        val identity = "$sessionId\u0000$remotePath".toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(identity, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private companion object {
        const val PREFERENCES_NAME = "workspace_download_locations"
    }
}
