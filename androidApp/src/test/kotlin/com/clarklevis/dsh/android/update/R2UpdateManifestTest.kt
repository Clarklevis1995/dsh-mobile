package com.clarklevis.dsh.android.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class R2UpdateManifestTest {
    private fun manifest(
        build: Int = 22,
        url: String = "https://download.whaleness.cc/DshMobile-android-v1.7.4.apk",
        hash: String = "a".repeat(64),
        fallback: String = "https://github.com/Clarklevis1995/dsh-mobile/releases/download/v1.7.4/DshMobile-android-v1.7.4.apk"
    ) = """{"schemaVersion":1,"packageName":"com.clarklevis.dsh.android","version":"1.7.4",
        "versionCode":$build,"size":37627206,"sha256":"$hash","downloadUrl":"$url",
        "githubDownloadUrl":"$fallback","releaseNotes":"新功能说明"}"""

    private fun parse(source: String, installed: String = "1.7.3", build: Long = 21) =
        parseUpdateManifest(source, "com.clarklevis.dsh.android", installed, build)

    @Test fun selectsR2WithVerifiedFallbackAndNotes() {
        val release = requireNotNull(parse(manifest()))
        assertEquals(22L, release.versionCode)
        assertEquals("sha256:${"a".repeat(64)}", release.digest)
        assertEquals("新功能说明", release.releaseNotes)
        assertTrue(requireNotNull(release.fallbackUrl).startsWith("https://github.com/"))
    }

    @Test fun selectsPreferredCompatibleAbiAndUsesItsOwnChecksum() {
        val variant = """"abiApks":{"arm64-v8a":{"size":123,"sha256":"${"b".repeat(64)}",
            "downloadUrl":"https://download.whaleness.cc/arm64.apk"},
            "armeabi-v7a":{"size":456,"sha256":"${"c".repeat(64)}",
            "downloadUrl":"https://download.whaleness.cc/arm.apk"}},"""
        val source = manifest().replace("\"schemaVersion\":1,", "\"schemaVersion\":1,$variant")
        val release = requireNotNull(parseUpdateManifest(source, "com.clarklevis.dsh.android", "1.7.3", 21,
            listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("sha256:${"b".repeat(64)}", release.digest)
        assertNull(release.fallbackUrl)
        assertEquals("sha256:${"a".repeat(64)}", requireNotNull(parse(source)).digest)
        assertEquals("sha256:${"a".repeat(64)}", requireNotNull(parseUpdateManifest(source,
            "com.clarklevis.dsh.android", "1.7.3", 21, listOf("unknown"))).digest)
        assertTrue(runCatching { parseUpdateManifest(source.replace("https://download.whaleness.cc/arm64.apk",
            "https://evil.example/arm64.apk"), "com.clarklevis.dsh.android", "1.7.3", 21,
            listOf("arm64-v8a")) }.isFailure)
    }

    @Test fun handlesSameVersionRebuildAndDoesNotDowngrade() {
        assertNotNull(parse(manifest(), "1.7.4", 21))
        assertNull(parse(manifest(), "1.7.4", 22))
        assertNull(parse(manifest(build = 50), "1.8.0", 21))
        assertNull(parse(manifest(build = 20)))
    }

    @Test fun rejectsInvalidHashPackageSchemaAndUrls() {
        val invalid = listOf(
            manifest(hash = "broken"),
            manifest(url = "https://download.whaleness.cc.evil.example/app.apk"),
            manifest(url = "http://download.whaleness.cc/app.apk"),
            manifest(fallback = "https://github.com/someone/other/releases/download/v1.7.4/app.apk"),
            manifest().replace("com.clarklevis.dsh.android", "another.package"),
            manifest().replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        )
        invalid.forEach { source ->
            assertTrue(runCatching { parse(source) }.isFailure)
        }
    }

    @Test fun fallsBackOnFailureButNotForAnUpToDateManifest() = runTest {
        assertEquals("GitHub", withUpdateFallback({ error("R2 unavailable") }, { "GitHub" }))
        var called = false
        assertNull(withUpdateFallback<String?>({ null }, { called = true; "GitHub" }))
        assertFalse(called)
    }

    @Test fun cancellationNeverStartsAnotherDownload() = runTest {
        var called = false
        try {
            withUpdateFallback<String>({ throw CancellationException() }, { called = true; "GitHub" })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(called)
        }
    }
}
