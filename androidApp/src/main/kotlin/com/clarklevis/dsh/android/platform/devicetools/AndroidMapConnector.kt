package com.clarklevis.dsh.android.platform.devicetools

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal class AndroidMapConnector(private val context: Context, private val storage: AndroidConnectorStorage) {
    suspend fun geocode(tool: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) throw ConnectorFailure("geocoder_unavailable")
        val coder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val rows = if (tool == "maps.geocode") coder.getFromLocationName(args.text("address", 200), args.integer("limit", 5, 1..5))
            else args.coordinates().let { coder.getFromLocation(it.first, it.second, 5) }
        val items = rows ?: throw ConnectorFailure("geocoder_unavailable")
        JSONObject().put("items", JSONArray(items.filter { it.hasLatitude() && it.hasLongitude() }.take(5).map { row ->
            JSONObject().put("name", (row.featureName ?: row.locality ?: row.countryName ?: "").take(200))
                .put("latitude", row.latitude).put("longitude", row.longitude)
                .put("address", (0..row.maxAddressLineIndex).joinToString(" ") { row.getAddressLine(it) }.take(400))
        })).put("source", "android-geocoder").put("truncated", false)
    }

    fun open(request: JSONObject): JSONObject {
        val args = request.getJSONObject("arguments")
        val (latitude, longitude) = args.coordinates()
        val name = if (args.has("name")) args.text("name", 200) else ""
        val mode = args.optString("mode", "show"); val transport = args.optString("transport", "driving")
        require(mode in listOf("show", "directions") && transport in listOf("driving", "walking", "transit"))
        // 工具只准备地点数据；外部应用只能由用户点击聊天卡片后选择打开。
        // 不读取旧跳转回执，避免把历史 handoff-requested 当作本次已安排跳转。
        return JSONObject().put("status", "ready").put("latitude", latitude).put("longitude", longitude).put("name", name).put("mode", mode)
            .also { if (mode == "directions") it.put("transport", transport) }
    }

    suspend fun markers(request: JSONObject): JSONObject {
        val tool = request.getString("tool"); val args = request.getJSONObject("arguments")
        if (tool == "maps.markers.list") {
            val query = if (args.has("query")) args.text("query", 200) else ""
            val offset = args.integer("offset", 0, 0..Int.MAX_VALUE); val limit = args.integer("limit", 20, 1..20)
            return storage.read { data ->
                val rows = data.optJSONObject("markers") ?: JSONObject()
                val matches = rows.keys().asSequence().map { rows.getJSONObject(it) }.filter { row ->
                    query.isEmpty() || listOf("name", "address", "notes").any { row.optString(it).contains(query, true) }
                }.sortedBy { it.getString("createdAt") }.toList()
                val items = matches.drop(offset).take(limit); val hasMore = offset.toLong() + items.size < matches.size
                JSONObject().put("items", JSONArray(items)).put("total", matches.size).put("offset", offset).put("limit", limit)
                    .put("hasMore", hasMore).put("nextOffset", if (hasMore) offset + items.size else JSONObject.NULL).put("source", "whaleness-host-markers")
            }
        }
        args.uuid("operationId")
        if (tool != "maps.markers.create") args.uuid("markerId")
        if (tool == "maps.markers.create" || args.has("name")) args.text("name", 200)
        if (tool == "maps.markers.create" || args.has("latitude") || args.has("longitude")) args.coordinates()
        if (args.has("address")) args.text("address", 400, true)
        if (args.has("notes")) args.text("notes", 1000, true)
        if (args.has("expectedRevision")) args.integer("expectedRevision", 1, 1..Int.MAX_VALUE)
        if (tool == "maps.markers.update") require(listOf("name", "latitude", "longitude", "address", "notes").any(args::has))
        return storage.write(request) { data ->
            val rows = data.optJSONObject("markers") ?: JSONObject().also { data.put("markers", it) }
            val id = if (tool == "maps.markers.create") UUID.randomUUID().toString() else args.getString("markerId")
            val previous = rows.optJSONObject(id)
            if (args.has("expectedRevision") && (previous == null || previous.getInt("revision") != args.getInt("expectedRevision"))) throw ConnectorFailure("revision_conflict")
            val result = JSONObject().put("operationId", args.getString("operationId")).put("replayed", false).put("source", "whaleness-host-markers")
            if (tool == "maps.markers.delete") {
                rows.remove(id); result.put("markerId", id).put("status", "deleted").put("existed", previous != null)
            } else {
                if (tool == "maps.markers.create" && rows.length() >= 2000) throw ConnectorFailure("marker_limit_reached")
                if (tool == "maps.markers.update" && previous == null) throw ConnectorFailure("marker_not_found")
                val row = previous ?: JSONObject().put("id", id).put("createdAt", Instant.now().toString()).put("address", "").put("notes", "")
                listOf("name", "latitude", "longitude", "address", "notes").filter(args::has).forEach { row.put(it, args.get(it)) }
                row.put("revision", row.optInt("revision") + 1).put("updatedAt", Instant.now().toString()); rows.put(id, row)
                result.put("marker", row).put("status", if (tool == "maps.markers.create") "created" else "updated")
            }
        }
    }
}
