package com.clarklevis.dsh.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMediaPreviewTest {
    @Test
    fun `downloaded media with generic MIME types still uses an inline preview`() {
        assertEquals(
            WorkspacePreviewKind.VIDEO,
            workspacePreviewKind("AI软件功能宣传片_BV126aPzWEpp_720p.MP4", "application/octet-stream")
        )
        assertEquals(WorkspacePreviewKind.IMAGE, workspacePreviewKind("照片.JPEG", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.VIDEO, workspacePreviewKind("download", "Video/MP4; codecs=avc1"))
        assertEquals(WorkspacePreviewKind.IMAGE, workspacePreviewKind("download", "image/png"))
    }

    @Test
    fun `image formats take precedence over code but source files remain readable`() {
        assertEquals(WorkspacePreviewKind.IMAGE, workspacePreviewKind("diagram.svg", "image/svg+xml"))
        assertEquals(WorkspacePreviewKind.CODE, workspacePreviewKind("index.ts", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.PDF, workspacePreviewKind("report.PDF", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.EXTERNAL, workspacePreviewKind("archive.zip", "application/zip"))
    }
}
