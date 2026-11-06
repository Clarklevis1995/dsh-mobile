package com.clarklevis.dsh.android.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun skipOnlyMatchesTheChosenVersion() {
        assertTrue(isSkippedVersion("v1.8.0", "1.8.0"))
        assertFalse(isSkippedVersion("v1.8.1", "1.8.0"))
        assertFalse(isSkippedVersion("v1.8.0", null))
    }

    @Test fun comparesNumericVersions() {
        assertTrue(compareVersions("v1.10.0", "1.9.9") > 0)
        assertEquals(0, compareVersions("v1.7.2", "1.7.2"))
        assertTrue(compareVersions("1.7.1", "1.7.2") < 0)
    }

    @Test fun selectsNewestStableAndroidApk() {
        val releases = """[
            ${release("v1.9.0", "build.ipa")},
            ${release("v1.8.0", "build.apk", prerelease = true)},
            ${release("v1.7.4", "DshMobile-android-v1.7.4.apk")},
            ${release("v1.7.3", "build.apk")}
        ]"""
        assertEquals("v1.7.4", selectAndroidRelease(releases, "1.7.2")?.version)
        assertNull(selectAndroidRelease(releases, "1.7.4"))
    }

    @Test fun rejectsUntrustedDownloadLocationAndSplitApks() {
        assertNull(selectAndroidRelease("[${release("v2.0.0", "build.apk", url = "https://example.com/build.apk")}]", "1.0.0"))
        assertNull(selectAndroidRelease("[${release("v2.0.0", "build-arm64.apk")}]", "1.0.0"))
    }

    @Test fun selectsApkFromReleasePageWithoutApiMetadata() {
        val html = """<a href="/Clarklevis1995/dsh-mobile/releases/download/v1.8.0/mobile.ipa">iOS</a>
            <a href="/Clarklevis1995/dsh-mobile/releases/download/v1.8.0/mobile.apk.sha256">Checksum</a>
            <a href="/Clarklevis1995/dsh-mobile/releases/download/v1.8.0/mobile.apk">Android</a>"""
        val selected = selectReleasePageApk("v1.8.0", html)
        assertEquals("https://github.com/Clarklevis1995/dsh-mobile/releases/download/v1.8.0/mobile.apk", selected.url)
        assertEquals(-1L, selected.size)
    }

    @Test(expected = IllegalStateException::class)
    fun releasePageWithoutApkDoesNotReportLatestVersion() {
        selectReleasePageApk("v1.8.0", """<a href="https://example.com/mobile.apk">APK</a>""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedReleaseTag() {
        selectReleasePageApk("v1.8.0-beta", "")
    }

    @Test fun independentTestBuildsCannotUpdateFromProductionManifest() {
        assertTrue(supportsInPlaceUpdates("com.clarklevis.dsh.android"))
        assertFalse(supportsInPlaceUpdates("com.clarklevis.dsh.android.reference"))
        assertFalse(supportsInPlaceUpdates("com.clarklevis.dsh.android.dev"))
    }

    @Test fun githubFallbackPrefersNativeAbiAndRetainsUniversalForUnknownAbi() {
        val json = """[{"tag_name":"v2.0.0","draft":false,"prerelease":false,"assets":[
            {"name":"app.apk","size":100,"browser_download_url":"https://github.com/Clarklevis1995/dsh-mobile/releases/download/v2.0.0/app.apk"},
            {"name":"app-arm64-v8a.apk","size":30,"browser_download_url":"https://github.com/Clarklevis1995/dsh-mobile/releases/download/v2.0.0/app-arm64-v8a.apk"}
        ]}]"""
        assertTrue(requireNotNull(selectAndroidRelease(json, "1.0.0", listOf("arm64-v8a"))).url.endsWith("app-arm64-v8a.apk"))
        assertTrue(requireNotNull(selectAndroidRelease(json, "1.0.0", listOf("x86_64"))).url.endsWith("app.apk"))
        val html = """<a href="/Clarklevis1995/dsh-mobile/releases/download/v2.0.0/app.apk">universal</a>
            <a href="/Clarklevis1995/dsh-mobile/releases/download/v2.0.0/app-arm64-v8a.apk">native</a>"""
        assertTrue(selectReleasePageApk("v2.0.0", html, listOf("arm64-v8a")).url.endsWith("app-arm64-v8a.apk"))
    }

    private fun release(
        version: String,
        name: String,
        prerelease: Boolean = false,
        url: String = "https://github.com/Clarklevis1995/dsh-mobile/releases/download/$version/$name"
    ) = """{"tag_name":"$version","draft":false,"prerelease":$prerelease,
        "assets":[{"name":"$name","browser_download_url":"$url","size":100}]}"""
}
