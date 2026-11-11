package com.clarklevis.dsh.android.platform.devicetools

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConnectorMediaListenerService : NotificationListenerService()

/** 本机公共音频库与活跃播放器各用自己的授权，不宣称能读取 QQ/网易云的私有云端曲库。 */
internal class AndroidMusicConnector(private val context: Context, private val storage: AndroidConnectorStorage, private val handoffs: AndroidConnectorHandoffs = AndroidConnectorHandoffs(context)) {
    val mediaPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun sessionAuthorized() = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    private fun controller(): MediaController {
        if (!sessionAuthorized()) throw ConnectorFailure("media_control_permission_required")
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = manager.getActiveSessions(ComponentName(context, ConnectorMediaListenerService::class.java))
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull() ?: throw ConnectorFailure("no_active_player")
    }

    suspend fun search(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val rows = songs(args.text("query", 100), 0, 21)
        JSONObject().put("source", "android-media-store").put("truncated", rows.size > 20)
            .put("items", JSONArray(rows.take(20).map { row -> JSONObject(row.toString()).apply {
                put("id", row.getString("mediaId")); remove("mediaId")
            } }))
    }

    suspend fun library(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val kind = args.optString("kind", "songs")
        val offset = args.integer("offset", 0, 0..10000); val limit = args.integer("limit", 20, 1..50)
        val query = if (args.has("query")) args.text("query", 100) else ""
        // MediaStore 无法代表第三方 App 的个人云端歌单，不能返回假的“空歌单”。
        if (kind != "songs") throw ConnectorFailure("library_kind_unsupported")
        val all = songs(query, offset, limit + 1)
        val items = all.take(limit).map { row -> JSONObject(row.toString()).apply { remove("mediaId") } }
        val more = all.size > limit; val capped = more && offset + items.size > 10000
        JSONObject().put("items", JSONArray(items)).put("kind", kind).put("offset", offset).put("limit", limit)
            .put("hasMore", more).put("nextOffset", if (more && !capped) offset + items.size else JSONObject.NULL)
            .put("pagingLimitReached", capped).put("source", "android-media-store").put("scope", "device-shared-audio")
            .put("downloadedOnly", true).put("status", if (items.isEmpty()) "empty-in-requested-scope" else "ok")
    }

    private fun songs(query: String, offset: Int, count: Int): List<JSONObject> {
        val columns = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0" + if (query.isNotEmpty()) " AND (${MediaStore.Audio.Media.TITLE} LIKE ? ESCAPE '\\' OR ${MediaStore.Audio.Media.ARTIST} LIKE ? ESCAPE '\\')" else ""
        val cursor = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, selection,
            if (query.isEmpty()) null else arrayOf("%$escaped%", "%$escaped%"), "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC, ${MediaStore.Audio.Media._ID} ASC")
            ?: throw ConnectorFailure("music_library_unavailable")
        return cursor.use { rows ->
            val items = mutableListOf<JSONObject>()
            if (offset > 0 && !rows.moveToPosition(offset - 1)) return@use items
            while (items.size < count && rows.moveToNext()) {
                val id = rows.getLong(0)
                val row = JSONObject().put("id", "library:android:songs:$id").put("mediaId", id.toString())
                    .put("title", rows.getString(1).orEmpty().take(500)).put("artist", rows.getString(2).orEmpty().take(500))
                    .put("album", rows.getString(3).orEmpty().take(500))
                val seconds = rows.getLong(4) / 1000.0
                if (seconds in 0.0..86400.0) row.put("durationSeconds", seconds)
                val album = rows.getLong(5)
                if (album > 0) row.put("artworkURL", "content://media/external/audio/albumart/$album")
                items += row
            }
            items
        }
    }

    suspend fun control(request: JSONObject): JSONObject {
        val tool = request.getString("tool"); val args = request.getJSONObject("arguments")
        if (tool == "music.state") return withContext(Dispatchers.Main) { state(controller()) }
        if (tool == "music.play-library" || (tool == "music.play" && args.has("itemId"))) {
            val raw = args.text("itemId", 208)
            val id = if (tool == "music.play-library") {
                require(args.getString("kind") == "songs" && raw.matches(Regex("library:android:songs:[0-9]{1,19}")))
                raw.substringAfterLast(':').toLong()
            } else { require(raw.matches(Regex("[0-9]{1,19}"))); raw.toLong() }
            require(id > 0)
            return storage.once(request) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, mediaPermission) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw ConnectorFailure("permission_denied")
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                handoffs.prepare(request, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "music_app_unavailable")
                JSONObject().put("playbackState", 0).put("requested", tool).put("status", "handoff-requested").put("source", "android-media-store")
            }
        }
        return storage.once(request) {
            withContext(Dispatchers.Main) {
                val player = controller()
                val playback = player.playbackState ?: throw ConnectorFailure("playback_unavailable")
                val action = when (tool) {
                    "music.play" -> PlaybackState.ACTION_PLAY
                    "music.pause" -> PlaybackState.ACTION_PAUSE
                    "music.seek" -> PlaybackState.ACTION_SEEK_TO
                    else -> throw ConnectorFailure("tool_unsupported")
                }
                if (playback.actions and action == 0L) throw ConnectorFailure("player_action_unsupported")
                when (tool) {
                    "music.play" -> player.transportControls.play()
                    "music.pause" -> player.transportControls.pause()
                    "music.seek" -> {
                        val seconds = args.getDouble("seconds"); require(seconds.isFinite() && seconds in 0.0..86400.0)
                        val duration = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                        require(duration <= 0 || seconds * 1000 <= duration)
                        player.transportControls.seekTo((seconds * 1000).toLong())
                    }
                }
                state(player).put("requested", tool)
            }
        }
    }

    private fun state(player: MediaController): JSONObject {
        val playback = player.playbackState ?: throw ConnectorFailure("playback_unavailable")
        val value = when (playback.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 1
            PlaybackState.STATE_PAUSED -> 2
            PlaybackState.STATE_FAST_FORWARDING -> 4
            PlaybackState.STATE_REWINDING -> 5
            else -> 0
        }
        return JSONObject().put("playbackState", value).put("playbackSeconds", maxOf(0L, playback.position) / 1000.0)
            .put("source", "android-media-session").put("playerPackage", player.packageName)
            .also { result -> player.metadata?.let { metadata ->
                val track = JSONObject()
                mapOf("title" to MediaMetadata.METADATA_KEY_TITLE, "artist" to MediaMetadata.METADATA_KEY_ARTIST, "album" to MediaMetadata.METADATA_KEY_ALBUM)
                    .forEach { (key, source) -> metadata.getString(source)?.let { track.put(key, it.take(500)) } }
                result.put("track", track)
            } }
    }
}
