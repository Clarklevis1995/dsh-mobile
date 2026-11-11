package com.clarklevis.dsh.android.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.commonmark.node.Link
import org.commonmark.node.Paragraph
import org.commonmark.node.Text as MarkdownText
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal data class ConnectorAppCard(
    val url: String, val title: String, val platform: String,
    val artist: String? = null, val album: String? = null, val artwork: String? = null,
    val duration: Double? = null, val latitude: Double? = null, val longitude: Double? = null,
    val transport: String? = null
) {
    val isMap get() = latitude != null && longitude != null
    fun open(context: Context) {
        val target = when {
            isMap && transport != null -> Uri.Builder().scheme("https").authority("www.google.com").path("/maps/dir/")
                .appendQueryParameter("api", "1").appendQueryParameter("destination", "$latitude,$longitude")
                .appendQueryParameter("travelmode", transport).build()
            isMap -> Uri.parse("geo:$latitude,$longitude?q=${Uri.encode("$latitude,$longitude($title)")}")
            platform == "local" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, URI(url).path.substringAfterLast('/').toLong())
            else -> Uri.parse(url)
        }
        val intent = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (platform == "local") intent.setDataAndType(target, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { context.startActivity(intent) }
        catch (failure: android.content.ActivityNotFoundException) {
            if (!isMap) throw failure
            val fallback = Uri.Builder().scheme("https").authority("www.google.com").path("/maps/search/")
                .appendQueryParameter("api", "1").appendQueryParameter("query", "$latitude,$longitude").build()
            context.startActivity(Intent(Intent.ACTION_VIEW, fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun queryPairs(query: String?): Map<String, String>? {
    if (query.isNullOrEmpty()) return emptyMap()
    val values = query.split('&').map { pair ->
        val parts = pair.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    if (values.map { it.first }.distinct().size != values.size) return null
    return values.toMap()
}

internal fun validConnectorArtwork(value: String, local: Boolean): Boolean = runCatching {
    if (value.length > 2048) return false
    val uri = URI(value)
    if (uri.userInfo != null || uri.port != -1 || uri.fragment != null) return false
    if (local && value.matches(Regex("content://media/external/audio/albumart/[0-9]{1,19}"))) return true
    val host = uri.host?.lowercase() ?: return false
    uri.scheme == "https" && listOf("mzstatic.com", "music.126.net", "y.gtimg.cn").any { host == it || host.endsWith(".$it") }
}.getOrDefault(false)

/** 卡片仅提升已知资源链接，不执行任意 Intent 或接受模型给出的包名/组件名。 */
internal fun connectorAppCard(link: String, title: String): ConnectorAppCard? = runCatching {
    if (link.length > 12288 || title.length > 1000) return null
    val original = URI(link)
    if (original.userInfo != null || original.port != -1) return null
    if (original.scheme == "https" && original.host == "www.google.com" && original.path == "/maps/search/") {
        if (original.fragment != null) return null
        val query = queryPairs(original.rawQuery) ?: return null
        if (query.keys != setOf("api", "query") || query["api"] != "1") return null
        val pair = query["query"]?.split(',') ?: return null
        if (pair.size != 2) return null
        val latitude = pair[0].toDoubleOrNull() ?: return null; val longitude = pair[1].toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return ConnectorAppCard(link, title.trim().takeIf { it.isNotEmpty() && it != link } ?: appText("地图地点"), "maps", latitude = latitude, longitude = longitude)
    }
    if (original.host?.lowercase() == "maps.apple.com" && original.scheme in listOf("https", "maps")) {
        if (original.path !in listOf("", "/") || original.fragment != null) return null
        val query = queryPairs(original.rawQuery) ?: return null
        if (query.keys.any { it !in listOf("ll", "q", "daddr", "dirflg") }) return null
        if (query.containsKey("ll") && query.containsKey("daddr")) return null
        val route = query.containsKey("daddr")
        if (!route && query.containsKey("dirflg")) return null
        val coords = query[if (route) "daddr" else "ll"]?.split(',') ?: return null
        if (coords.size != 2) return null
        val latitude = coords[0].toDoubleOrNull() ?: return null; val longitude = coords[1].toDoubleOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val transport = if (route) when (query["dirflg"] ?: "d") { "d" -> "driving"; "w" -> "walking"; "r" -> "transit"; else -> return null } else null
        return ConnectorAppCard(link, title.trim().takeIf { it.isNotEmpty() && it != link } ?: query["q"].orEmpty().ifEmpty { appText("地图地点") },
            "maps", latitude = latitude, longitude = longitude, transport = transport)
    }
    var metadata: JSONObject? = null
    var target = original
    if (original.rawFragment?.startsWith("dsh-music-card=") == true) {
        val raw = original.rawFragment.removePrefix("dsh-music-card=")
        if (!raw.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val bytes = Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        if (bytes.size > 4096) return null
        metadata = JSONObject(bytes.toString(Charsets.UTF_8))
        target = URI(link.substringBefore('#'))
        if (metadata.opt("v") != 1 || metadata.opt("title") !is String || metadata.getString("title").isBlank() || metadata.getString("title").length > 200) return null
        for (key in listOf("artist", "album")) if (metadata.has(key) && (metadata.opt(key) !is String || metadata.getString(key).length > 200)) return null
        if (metadata.has("durationSeconds") && (metadata.opt("durationSeconds") !is Number || !metadata.getDouble("durationSeconds").isFinite() || metadata.getDouble("durationSeconds") !in 0.0..86400.0)) return null
    }
    val host = target.host?.lowercase() ?: return null
    val platform = when {
        target.scheme == "whaleness-music" && host == "local" && target.path.matches(Regex("/songs/[0-9]{1,19}")) && target.query == null && target.fragment == null -> "local"
        target.scheme != "https" -> return null
        host == "music.apple.com" -> {
            if (target.fragment != null) return null
            val path = target.path.split('/').filter(String::isNotEmpty).let { if (it.firstOrNull()?.matches(Regex("[a-z]{2}")) == true) it.drop(1) else it }
            if (path.size !in 2..3 || path[0] !in listOf("song", "album", "playlist", "artist") ||
                !path.last().matches(if (path[0] == "playlist") Regex("pl\\.[A-Za-z0-9._-]{1,200}") else Regex("[0-9]{1,20}"))) return null
            val query = queryPairs(target.rawQuery) ?: return null
            if (query["i"]?.matches(Regex("[0-9]{1,20}")) == false) return null
            "Apple Music"
        }
        host == "music.163.com" -> {
            val route = if (target.path == "/" && target.fragment?.startsWith('/') == true) URI(target.fragment) else target
            if (target.fragment != null && route === target) return null
            if (route.path !in listOf("/song", "/playlist", "/album", "/artist") || queryPairs(route.rawQuery)?.get("id")?.matches(Regex("[0-9]{1,20}")) != true) return null
            "netease"
        }
        host == "y.qq.com" && target.fragment == null && target.path.matches(Regex("/n/ryqq/(songDetail|playlist|albumDetail|singer)/[A-Za-z0-9]{1,50}/?")) -> "qq"
        else -> return null
    }
    val artwork = metadata?.optString("artworkURL")?.takeIf(String::isNotEmpty)
    if (artwork != null && !validConnectorArtwork(artwork, platform == "local")) return null
    ConnectorAppCard(target.toString(), metadata?.getString("title") ?: title.trim().takeIf { it.isNotEmpty() && it != link } ?: if (platform == "local") appText("本机音乐") else musicPlatformTitle(platform),
        platform, metadata?.optString("artist"), metadata?.optString("album"), artwork, metadata?.let { if (it.has("durationSeconds")) it.getDouble("durationSeconds") else null })
}.getOrNull()

private fun musicPlatformTitle(platform: String) = when (platform) {
    "netease" -> appText("网易云音乐")
    "qq" -> appText("QQ 音乐")
    else -> platform
}

internal class ConnectorAppCardPlugin : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(Link::class.java) { visitor, node ->
            val start = visitor.length()
            io.noties.markwon.core.CoreProps.LINK_DESTINATION.set(visitor.renderProps(), node.destination)
            visitor.visitChildren(node)
            val parent = node.parent
            val standalone = parent is Paragraph && parent.parent is org.commonmark.node.Document && parent.firstChild === node && parent.lastChild === node && textOnlyLinkLabel(node)
            val title = visitor.builder().subSequence(start, visitor.length()).toString()
            val card = if (standalone) connectorAppCard(node.destination, title) else null
            if (card != null) {
                if (visitor.length() == start) visitor.builder().append('\uFFFC')
                visitor.setSpans(start, card)
            } else visitor.setSpansForNodeOptional(node, start)
        }
    }
}

private fun textOnlyLinkLabel(node: org.commonmark.node.Node): Boolean = generateSequence(node.firstChild) { it.next }.all { child ->
    when (child) {
        is MarkdownText -> true
        is org.commonmark.node.Emphasis, is org.commonmark.node.StrongEmphasis -> textOnlyLinkLabel(child)
        else -> false
    }
}

private val artworkClient by lazy { OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build() }

private fun java.io.InputStream.readArtwork(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > 512 * 1024) throw IllegalArgumentException("artwork_too_large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

@Composable
internal fun ConnectorAppCardView(card: ConnectorAppCard) {
    val context = LocalContext.current
    var failed by remember(card) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, card.artwork) {
        value = card.artwork?.let { url -> withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (url.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readArtwork() }
                else artworkClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful || response.body?.contentType()?.type != "image") null
                    else response.body?.byteStream()?.use { it.readArtwork() }
                }
                if (bytes == null || bytes.size > 512 * 1024) null else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth !in 1..8192 || options.outHeight !in 1..8192) null else {
                        options.inJustDecodeBounds = false; options.inSampleSize = 1
                        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 256) options.inSampleSize *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        } }
    }
    Surface(onClick = { failed = runCatching { card.open(context) }.isFailure }, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().testTag(if (card.isMap) "maps-app-card" else "music-app-card")) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon = if (card.isMap) R.drawable.ic_connector_location else R.drawable.ic_connector_music
            if (image != null) Image(image!!, null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            else Icon(painterResource(icon), null, Modifier.size(if (card.isMap) 48.dp else 64.dp).padding(8.dp), tint = DshColors.Ocean)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                card.artist?.takeIf(String::isNotEmpty)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                val details = listOfNotNull(card.album?.takeIf(String::isNotEmpty), card.duration?.let { seconds -> "%d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60) })
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (card.isMap) appText("在地图应用中打开") else if (card.platform == "local") appText("在音乐应用中打开") else appText("在 %1\$s 中打开", musicPlatformTitle(card.platform)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (failed) AlertDialog(onDismissRequest = { failed = false }, title = { Text(appText("无法打开应用")) },
        text = { Text(appText("请检查是否已安装对应应用并允许访问。")) }, confirmButton = { TextButton(onClick = { failed = false }) { Text(appText("好")) } })
}
