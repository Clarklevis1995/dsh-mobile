package com.clarklevis.dsh.android

import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingShareDeviceTest {
    @Test
    fun copiesGrantedFilesDeduplicatesClipDataAndRestoresAfterRestart() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = folder
        }
        val source = File(app.cacheDir, "workspace-files/share-test-${UUID.randomUUID()}.txt")
        source.parentFile!!.mkdirs()
        source.writeText("shared file content")
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.workspace-files", source)
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, "https://example.com")
            intent.clipData = ClipData.newUri(app.contentResolver, "file", uri)
            val inbox = IncomingShareInbox(context)
            inbox.receive(context, intent)
            assertNull(inbox.error)
            val pending = requireNotNull(inbox.pending)
            assertEquals(1, pending.files.size)
            source.delete()
            val restored = IncomingShareInbox(context)
            assertEquals(pending, restored.pending)
            val copied = File(requireNotNull(pending.files.single().path))
            assertEquals("shared file content", copied.readText())
            restored.pending = null
            assertNull(IncomingShareInbox(context).pending)
            assertFalse(copied.exists())
        } finally { source.delete(); folder.deleteRecursively() }
    }

    @Test
    fun rejectsPrivateFileUrisWithoutDiscardingExistingDraft() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(app.cacheDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = folder }
        try {
            val inbox = IncomingShareInbox(context)
            inbox.receive(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("file:///private/secret")))
            assertNull(inbox.pending)
            assertNotNull(inbox.error)
            inbox.receive(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "https://example.com/first"))
            val first = inbox.pending
            inbox.receive(context, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "second"))
            assertEquals(first, inbox.pending)
        } finally { folder.deleteRecursively() }
    }
}
