package com.clarklevis.dsh.android.ui

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.clarklevis.dsh.android.AndroidWorkspaceLocalFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.unit.Density
import org.junit.Rule

class WorkspaceApkInstallerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun apkPreviewKeepsInstallButtonVisibleAndWaitsForClickAtLargeFontSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("apk-preview-glass-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appearance = AppearanceSettings(preferences)
        val scale = mutableFloatStateOf(1f)
        var installs = 0
        val file = local("app-debug-long-application-name.APK", "application/octet-stream")
        compose.setContent {
            DshTheme(appearance) {
                WorkspaceFilePreviewSheet(file, onDismiss = {}) { padding ->
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
                        WorkspaceFilePreviewContent(file, {}, padding, onInstall = { installs++ })
                    }
                }
            }
        }
        for (style in listOf(InterfaceStyle.LIGHT, InterfaceStyle.DARK)) {
            compose.runOnIdle { appearance.selectInterfaceStyle(style) }
            for (fontScale in listOf(1f, 2f)) {
                compose.runOnIdle { scale.floatValue = fontScale }
                compose.onNodeWithTag("workspace-apk-install").assertIsDisplayed()
                compose.onNodeWithTag("workspace-apk-name").assertIsDisplayed()
                val button = compose.onNodeWithTag("workspace-apk-install").fetchSemanticsNode().boundsInRoot
                val sheet = compose.onNodeWithTag("workspace-file-preview-sheet").fetchSemanticsNode().boundsInRoot
                assertEquals(sheet.center.x, button.center.x, 2f)
                assertTrue(button.top > sheet.top && button.bottom < sheet.bottom)
                compose.onNodeWithTag("workspace-file-preview-sheet").captureToImage()
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "apk-preview-${context.resources.configuration.orientation}-$fontScale-${style.name}.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        assertEquals(0, installs)
        compose.onNodeWithTag("workspace-apk-install").performClick()
        assertEquals(1, installs)
    }

    @Test
    fun apkWithGenericMimeAndUppercaseExtensionIsRecognized() {
        assertTrue(local("app-debug.APK", "application/octet-stream").isApk())
        assertTrue(local("download", "application/vnd.android.package-archive; charset=binary").isApk())
        assertFalse(local("README.md", "text/markdown").isApk())
        assertFalse(local("archive.zip", "application/zip").isApk())
    }

    @Test
    fun installerGetsReadableContentUriAndApkMimeRegardlessOfDownloadMime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "workspace-previews/apk-installer-test/app-debug.apk")
        file.parentFile!!.mkdirs()
        try {
            file.writeBytes(byteArrayOf(0x50, 0x4b, 3, 4))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.workspace-files", file)
            val intent = workspaceApkInstallIntent(uri)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertEquals("content", intent.data!!.scheme)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use {
                assertEquals(0x50, it.read())
            }
        } finally {
            file.delete()
        }
    }

    private fun local(name: String, mime: String) =
        AndroidWorkspaceLocalFile(File(name), "s1", name, name, mime, "preview")
}
