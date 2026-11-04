package com.clarklevis.dsh.android.ui

import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import java.util.Locale

internal enum class WorkspacePreviewKind { IMAGE, VIDEO, CODE, PDF, EXTERNAL }

internal fun workspacePreviewKind(name: String, mediaType: String): WorkspacePreviewKind {
    val type = mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        type.startsWith("image/") || extension in imageExtensions -> WorkspacePreviewKind.IMAGE
        type.startsWith("video/") || extension in videoExtensions -> WorkspacePreviewKind.VIDEO
        type == "application/pdf" || extension == "pdf" -> WorkspacePreviewKind.PDF
        WorkspaceCodePreviewSupport.isSupported(name, type) -> WorkspacePreviewKind.CODE
        else -> WorkspacePreviewKind.EXTERNAL
    }
}

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg")
private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3gpp", "avi", "mpeg", "mpg")
