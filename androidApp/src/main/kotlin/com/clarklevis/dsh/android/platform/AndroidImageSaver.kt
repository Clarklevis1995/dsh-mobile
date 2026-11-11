package com.clarklevis.dsh.android.platform

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 保存附件原始字节；失败时移除尚未完成的相册记录。 */
internal suspend fun saveImageToPhotos(context: Context, bytes: ByteArray, mediaType: String) =
    withContext(Dispatchers.IO) {
        val extension = when (mediaType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> error("Unsupported image format")
        }
        val name = "DshMobile-${UUID.randomUUID()}.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mediaType)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DshMobile")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DshMobile")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create photo directory" }
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0)
            }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
