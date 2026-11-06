package com.clarklevis.dsh.android.update

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clarklevis.dsh.android.localization.appText
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class AndroidRelease(
    val version: String,
    val url: String,
    val size: Long,
    val digest: String?,
    val versionCode: Long? = null,
    val fallbackUrl: String? = null,
    val releaseNotes: String? = null
)
internal data class UpdateState(
    val busy: Boolean = false,
    val status: String? = null,
    val release: AndroidRelease? = null,
    val apk: File? = null,
    val downloading: Boolean = false
)

internal fun isSkippedVersion(version: String, skipped: String?): Boolean =
    skipped != null && version.removePrefix("v") == skipped.removePrefix("v")

internal fun compareVersions(first: String, second: String): Int {
    fun parts(value: String) = value.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(first)
    val b = parts(second)
    return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
        (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 }).takeIf { it != 0 }
    } ?: 0
}

private val releaseAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

private fun apkPreference(name: String, supportedAbis: List<String>): Int? {
    val normalized = name.lowercase()
    if (!normalized.endsWith(".apk") || normalized.contains("debug")) return null
    val abi = releaseAbis.firstOrNull { normalized.endsWith("-$it.apk") }
    if (abi != null) return supportedAbis.indexOf(abi).takeIf { it >= 0 }
    if (listOf("arm64", "armeabi", "x86").any(normalized::contains)) return null
    return supportedAbis.size + if (normalized.contains("universal")) 0 else 1
}

internal fun selectAndroidRelease(json: String, installed: String, supportedAbis: List<String> = emptyList()): AndroidRelease? =
    Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val release = element.jsonObject
        if (release["draft"]?.jsonPrimitive?.boolean == true ||
            release["prerelease"]?.jsonPrimitive?.boolean == true) return@mapNotNull null
        val version = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
        if (!Regex("v?\\d+\\.\\d+\\.\\d+").matches(version) || compareVersions(version, installed) <= 0) {
            return@mapNotNull null
        }
        val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
        // 发布流程提供的 ABI APK 是独立安装包，优先选择设备原生架构。
        val apk = assets.mapNotNull { asset ->
            apkPreference(asset["name"]!!.jsonPrimitive.content, supportedAbis)?.let { it to asset }
        }.minByOrNull { it.first }?.second ?: return@mapNotNull null
        val url = apk["browser_download_url"]!!.jsonPrimitive.content
        if (!url.startsWith("https://github.com/Clarklevis1995/dsh-mobile/releases/download/")) return@mapNotNull null
        AndroidRelease(version, url, apk["size"]!!.jsonPrimitive.long, apk["digest"]?.jsonPrimitive?.contentOrNull)
    }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }

internal fun selectReleasePageApk(version: String, html: String, supportedAbis: List<String> = emptyList()): AndroidRelease {
    require(Regex("v?\\d+\\.\\d+\\.\\d+").matches(version)) { appText("无法识别 GitHub 发布版本") }
    val prefix = "/Clarklevis1995/dsh-mobile/releases/download/$version/"
    val path = Regex("href=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }
        .filter { it.startsWith(prefix) }
        .mapNotNull { path -> apkPreference(path.substringAfterLast('/'), supportedAbis)?.let { it to path } }
        .minByOrNull { it.first }?.second
        ?: error(appText("最新正式发布尚未提供可安装的 Android APK，请稍后重试"))
    return AndroidRelease(version, "https://github.com$path", -1, null)
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val cacheStorage = com.clarklevis.dsh.android.AndroidCacheStorage.shared(application)
    private var updateLease: java.io.Closeable? = null
    private var operationJob: Job? = null

    private fun releaseUpdateLease() {
        updateLease?.close()
        updateLease = null
    }
    override fun onCleared() {
        operationJob?.cancel()
        releaseUpdateLease()
        super.onCleared()
    }
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).build()
    private val metadataClient = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    @Suppress("DEPRECATION")
    private val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    val currentVersion: String = installed.versionName ?: appText("未知")

    fun dismiss() {
        operationJob?.cancel()
        releaseUpdateLease()
        mutableState.value = UpdateState()
    }
    fun installationHandled() {
        val retained = runCatching { mutableState.value.apk?.let { cacheStorage.retainExternal(it) } }.isSuccess
        if (retained) releaseUpdateLease()
        mutableState.value = UpdateState()
    }
    fun reportError(message: String) { mutableState.value = UpdateState(status = message) }

    private val preferences = context.getSharedPreferences("app_updates", Application.MODE_PRIVATE)
    private var startupChecked = false
    private var apiRetryAt = 0L

    fun checkOnStartup() {
        if (startupChecked) return
        startupChecked = true
        performCheck(automatic = true)
    }

    fun skipVersion() {
        val release = mutableState.value.release ?: return
        preferences.edit().putString("skipped_version", release.version.removePrefix("v")).apply()
        dismiss()
    }

    fun check() = performCheck(automatic = false)

    private fun performCheck(automatic: Boolean) = runOperation(silent = automatic) {
        if (!supportsInPlaceUpdates(context.packageName)) {
            return@runOperation UpdateState(status = if (automatic) null else appText("当前为独立测试版本，请在正式版中检查更新"))
        }
        val release = withUpdateFallback(
            primary = {
                val json = metadataClient.newCall(Request.Builder().url(UPDATE_MANIFEST_URL)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "DSH-Mobile-Android").build()).withUpdateResponse { response ->
                    check(response.isSuccessful) { appText("R2 检查更新失败（%1\$s）", response.code) }
                    check((response.body?.contentLength() ?: -1) <= 256 * 1024) { appText("更新配置过大") }
                    response.body?.byteStream()?.use { input ->
                        val buffer = ByteArray(256 * 1024 + 1)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            count += read
                        }
                        check(count <= 256 * 1024) { appText("更新配置过大") }
                        String(buffer, 0, count, Charsets.UTF_8)
                    } ?: error(appText("更新配置为空"))
                }
                parseUpdateManifest(json, context.packageName, currentVersion,
                    PackageInfoCompat.getLongVersionCode(installed), android.os.Build.SUPPORTED_ABIS.toList())
            },
            fallback = { checkGitHubRelease() }
        )
        val visibleRelease = release?.takeUnless {
            automatic && isSkippedVersion(it.version, preferences.getString("skipped_version", null))
        }
        UpdateState(
            release = visibleRelease,
            status = if (!automatic && release == null) appText("当前已是最新 Android 版本") else null
        )
    }

    private suspend fun checkGitHubRelease(): AndroidRelease? {
        return try {
            check(System.currentTimeMillis() >= apiRetryAt) { appText("GitHub API 暂时限流") }
            val json = metadataClient.newCall(Request.Builder()
                .url("https://api.github.com/repos/Clarklevis1995/dsh-mobile/releases?per_page=100")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DSH-Mobile-Android").build()).withUpdateResponse { response ->
                if (response.code == 403 || response.code == 429) {
                    val now = System.currentTimeMillis()
                    apiRetryAt = maxOf(
                        now + (response.header("Retry-After")?.toLongOrNull() ?: 60) * 1000,
                        (response.header("X-RateLimit-Reset")?.toLongOrNull() ?: 0) * 1000
                    )
                }
                check(response.isSuccessful) { appText("GitHub API 检查失败（%1\$s）", response.code) }
                response.body?.string() ?: error(appText("GitHub 返回了空响应"))
            }
            selectAndroidRelease(json, currentVersion, Build.SUPPORTED_ABIS.toList())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            checkReleasePage()
        }
    }

    private suspend fun checkReleasePage(): AndroidRelease? {
        val version = metadataClient.newCall(Request.Builder()
            .url("https://github.com/Clarklevis1995/dsh-mobile/releases/latest")
            .header("User-Agent", "DSH-Mobile-Android").build()).withUpdateResponse { response ->
            check(response.isSuccessful) { appText("无法访问 GitHub 发布页（%1\$s），请检查网络后重试", response.code) }
            val url = response.request.url
            check(url.host == "github.com" && url.encodedPath.startsWith("/Clarklevis1995/dsh-mobile/releases/tag/")) {
                appText("无法识别 GitHub 最新发布，请稍后重试")
            }
            url.pathSegments.last()
        }
        check(Regex("v?\\d+\\.\\d+\\.\\d+").matches(version)) { appText("无法识别 GitHub 发布版本") }
        // Verify that the release really contains an Android APK before declaring it up to date.
        val html = metadataClient.newCall(Request.Builder()
            .url("https://github.com/Clarklevis1995/dsh-mobile/releases/expanded_assets/$version")
            .header("User-Agent", "DSH-Mobile-Android").build()).withUpdateResponse { response ->
            check(response.isSuccessful) { appText("无法读取 GitHub Android 发布包（%1\$s），请稍后重试", response.code) }
            response.body?.string() ?: error(appText("GitHub 发布包列表为空"))
        }
        val release = selectReleasePageApk(version, html, Build.SUPPORTED_ABIS.toList())
        return release.takeIf { compareVersions(it.version, currentVersion) > 0 }
    }

    fun download(release: AndroidRelease) = runOperation(downloading = true) {
        check(supportsInPlaceUpdates(context.packageName)) { appText("当前为独立测试版本，请在正式版中检查更新") }
        val directory = File(context.cacheDir, "updates")
        releaseUpdateLease()
        val lease = cacheStorage.access {
            check(cacheStorage.safeManagedDirectory(directory) && cacheStorage.canRemove(directory)) { appText("安装器仍在使用缓存安装包，请稍后再试") }
            cacheStorage.lease(directory)
        }
        var ready = false
        directory.mkdirs()
        val target = File(directory, "update.apk")
        val partial = File(directory, "update.part")
        try {
            suspend fun downloadAndValidate(url: String) {
                coroutineContext.ensureActive()
                publishDownloadStatus(appText("正在连接下载源 %1\$s…", url.toHttpUrl().host))
                partial.delete()
                client.newCall(Request.Builder().url(url).build()).withUpdateResponse { response ->
                    check(response.isSuccessful) { appText("下载失败（%1\$s）", response.code) }
                    val body = response.body ?: error(appText("下载内容为空"))
                    val expectedSize = release.size.takeIf { it > 0 } ?: body.contentLength()
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            var lastProgressAt = android.os.SystemClock.elapsedRealtime()
                            var lastProgressBytes = 0L
                            var spaceCheckedAt = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                coroutineContext.ensureActive()
                                total += count
                                check(expectedSize <= 0 || total <= expectedSize) { appText("安装包大小异常") }
                                if (total >= spaceCheckedAt) {
                                    check(cacheStorage.canWriteTemporary(maxOf(count.toLong(), minOf(expectedSize - total, 1024 * 1024L)))) {
                                        appText("可用存储空间不足")
                                    }
                                    spaceCheckedAt = total + 1024 * 1024L
                                }
                                output.write(buffer, 0, count)
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastProgressAt >= 500 || total == expectedSize) {
                                    val bytesPerSecond = (total - lastProgressBytes) * 1000 / maxOf(1, now - lastProgressAt)
                                    val progress = if (expectedSize > 0) appText("正在下载 %1\$s%", total * 100 / expectedSize)
                                        else appText("正在下载 %1\$s MB", total / 1024 / 1024)
                                    publishDownloadStatus("$progress · ${android.text.format.Formatter.formatShortFileSize(context, total)} / " +
                                            (if (expectedSize > 0) android.text.format.Formatter.formatShortFileSize(context, expectedSize) else "—") +
                                            "\n${android.text.format.Formatter.formatShortFileSize(context, bytesPerSecond)}/s · ${url.toHttpUrl().host}")
                                    lastProgressAt = now
                                    lastProgressBytes = total
                                }
                            }
                            check((expectedSize <= 0 || total == expectedSize) && total > 0) { appText("安装包下载不完整") }
                        }
                    }
                }
                release.digest?.takeIf { it.startsWith("sha256:") }?.let { expected ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    partial.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            hash.update(buffer, 0, count)
                        }
                    }
                    check(hash.digest().joinToString("") { "%02x".format(it) } == expected.removePrefix("sha256:")) { appText("安装包校验失败") }
                }
                validateApk(partial, release)
            }
            withUpdateFallback(
                primary = { downloadAndValidate(release.url) },
                fallback = {
                    val fallback = release.fallbackUrl ?: error(appText("下载失败，请检查网络后重试"))
                    publishDownloadStatus(appText("加速源下载失败，正在切换 GitHub…"))
                    downloadAndValidate(fallback)
                }
            )
            coroutineContext.ensureActive()
            target.delete()
            check(partial.renameTo(target)) { appText("无法保存安装包") }
            updateLease = lease
            ready = true
            UpdateState(apk = target)
        } finally {
            partial.delete()
            if (!ready) lease.close()
        }
    }

    private suspend fun publishDownloadStatus(status: String) = withContext(Dispatchers.Main.immediate) {
        mutableState.value = UpdateState(busy = true, downloading = true, status = status)
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: AndroidRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error(appText("无效的 APK 安装包"))
        check(archive.packageName == context.packageName) { appText("安装包不属于当前应用") }
        check(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(installed)) { appText("安装包构建号未高于当前版本") }
        release.versionCode?.let { expected ->
            check(PackageInfoCompat.getLongVersionCode(archive) == expected && archive.versionName == release.version) {
                appText("安装包版本与更新配置不一致")
            }
        }
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val archiveSignatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val currentSignatures = if (Build.VERSION.SDK_INT >= 28) current.signingInfo?.apkContentsSigners else current.signatures
        check(!archiveSignatures.isNullOrEmpty() && !currentSignatures.isNullOrEmpty() &&
            archiveSignatures.toSet() == currentSignatures.toSet()) { appText("安装包签名不一致，无法覆盖安装") }
    }

    private fun runOperation(
        silent: Boolean = false,
        downloading: Boolean = false,
        operation: suspend () -> UpdateState
    ) {
        // 取消后的 IO 清理完成前不启动第二个写入相同缓存文件的任务。
        if (operationJob != null) return
        mutableState.value = UpdateState(busy = true, downloading = downloading,
            status = if (silent) null else appText(if (downloading) "正在准备下载…" else "正在检查更新…"))
        operationJob = viewModelScope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) { operation() }
            } catch (exception: CancellationException) {
                releaseUpdateLease()
                mutableState.value = UpdateState()
                throw exception
            } catch (exception: Exception) {
                mutableState.value = UpdateState(status = if (silent) null else exception.message ?: appText("更新失败，请重试"))
            } finally {
                operationJob = null
            }
        }
    }
}
