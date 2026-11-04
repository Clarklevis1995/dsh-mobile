package com.clarklevis.dsh.android.update

import com.clarklevis.dsh.android.localization.appText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val UPDATE_MANIFEST_URL = "https://download.whaleness.cc/latest.json"

internal fun parseUpdateManifest(
    source: String,
    packageName: String,
    installedVersion: String,
    installedBuild: Long,
    supportedAbis: List<String> = emptyList()
): AndroidRelease? {
    val manifest = Json.parseToJsonElement(source).jsonObject
    require(manifest.getValue("schemaVersion").jsonPrimitive.int == 1) { appText("不支持的更新配置格式") }
    require(manifest.getValue("packageName").jsonPrimitive.content == packageName) { appText("更新配置包名不一致") }
    val version = manifest.getValue("version").jsonPrimitive.content
    require(Regex("\\d+\\.\\d+\\.\\d+").matches(version)) { appText("无效的更新版本") }
    val build = manifest.getValue("versionCode").jsonPrimitive.long
    val variants = manifest["abiApks"]?.jsonObject
    val selected = supportedAbis.firstNotNullOfOrNull { variants?.get(it)?.jsonObject } ?: manifest
    val size = selected.getValue("size").jsonPrimitive.long
    val hash = selected.getValue("sha256").jsonPrimitive.content.lowercase()
    require(build > 0 && size > 0 && Regex("[0-9a-f]{64}").matches(hash)) { appText("无效的安装包校验信息") }
    val url = selected.getValue("downloadUrl").jsonPrimitive.content.toHttpUrl()
    require(url.scheme == "https" && url.host == "download.whaleness.cc" &&
        url.username.isEmpty() && url.password.isEmpty() && url.encodedPath.endsWith(".apk")) { appText("不可信的 APK 下载地址") }
    val fallback = selected["githubDownloadUrl"]?.jsonPrimitive?.contentOrNull?.toHttpUrl()
    require(fallback == null || (fallback.scheme == "https" && fallback.host == "github.com" &&
        fallback.username.isEmpty() && fallback.password.isEmpty() &&
        fallback.encodedPath.startsWith("/Clarklevis1995/dsh-mobile/releases/download/v$version/") &&
        fallback.encodedPath.endsWith(".apk"))) { appText("不可信的 GitHub 备用地址") }
    // 同一版本重新打包时也以构建号判断；禁止降级和无法覆盖安装的构建。
    if (build <= installedBuild || compareVersions(version, installedVersion) < 0) return null
    return AndroidRelease(version, url.toString(), size, "sha256:$hash", build,
        fallback?.toString(), manifest["releaseNotes"]?.jsonPrimitive?.contentOrNull)
}

internal suspend fun <T> withUpdateFallback(primary: suspend () -> T, fallback: suspend () -> T): T =
    try {
        primary()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        try {
            fallback()
        } catch (fallbackError: Exception) {
            fallbackError.addSuppressed(exception)
            throw fallbackError
        }
    }
