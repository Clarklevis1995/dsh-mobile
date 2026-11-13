package com.clarklevis.dsh.shared.facade

/** v1 的坐标统一为 WGS-84；供应商通过其 URI 参数转换，禁止猜测未声明坐标系。 */
data class SharedMapDestination(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val mode: String = "show",
    val transport: String = "driving",
    val coordinateSystem: String = "wgs84"
)

/** 两端共用解析和供应商协议；安装检测及启动留给平台适配器。 */
class SharedMapLinks {
    fun parse(link: String, title: String): SharedMapDestination? = runCatching {
        if (link.length > 4096 || title.length > 1000 || '#' in link) return null
        val parts = link.split('?', limit = 2)
        val base = parts[0].lowercase()
        val query = linkedMapOf<String, String>()
        for (pair in parts.getOrElse(1) { "" }.split('&').filter(String::isNotEmpty)) {
            val kv = pair.split('=', limit = 2)
            val key = decode(kv[0]); val value = decode(kv.getOrElse(1) { "" })
            if (query.put(key, value) != null) return null
        }
        var mode = "show"
        var transport = "driving"
        val coords: List<String>
        var name = query["name"] ?: query["q"] ?: ""
        when (base) {
            "whaleness-map://place" -> {
                if (query.keys.any { it !in setOf("v", "latitude", "longitude", "name", "mode", "transport", "coordinateSystem") } ||
                    query["v"] != "1" || query["coordinateSystem"] != "wgs84") return null
                coords = listOf(query["latitude"] ?: return null, query["longitude"] ?: return null)
                mode = query["mode"] ?: "show"; transport = query["transport"] ?: "driving"
            }
            "https://maps.apple.com", "https://maps.apple.com/", "maps://maps.apple.com", "maps://maps.apple.com/" -> {
                if (query.keys.any { it !in setOf("ll", "q", "daddr", "dirflg") } ||
                    (query.containsKey("ll") && query.containsKey("daddr"))) return null
                if (query.containsKey("daddr")) {
                    mode = "directions"
                    transport = when (query["dirflg"] ?: "d") { "d" -> "driving"; "w" -> "walking"; "r" -> "transit"; else -> return null }
                } else if (query.containsKey("dirflg")) return null
                coords = (query[if (mode == "directions") "daddr" else "ll"] ?: return null).split(',')
            }
            "https://www.google.com/maps/search/", "https://www.google.com/maps/dir/" -> {
                mode = if (base.endsWith("/dir/")) "directions" else "show"
                if (query["api"] != "1" || query.keys.any { it !in if (mode == "show") setOf("api", "query") else setOf("api", "destination", "travelmode") }) return null
                coords = (query[if (mode == "show") "query" else "destination"] ?: return null).split(',')
                transport = query["travelmode"] ?: "driving"; name = ""
            }
            else -> return null
        }
        if (mode !in setOf("show", "directions") || transport !in setOf("driving", "walking", "transit") || coords.size != 2) return null
        val lat = coords[0].toDoubleOrNull() ?: return null; val lon = coords[1].toDoubleOrNull() ?: return null
        val label = title.trim().takeIf { it.isNotEmpty() && it != link } ?: name
        SharedMapDestination(lat, lon, label, mode, transport).takeIf(::valid)
    }.getOrNull()

    fun encode(destination: SharedMapDestination): String? = if (!valid(destination)) null else url("whaleness-map://place", linkedMapOf(
        "v" to "1", "latitude" to destination.latitude.toString(), "longitude" to destination.longitude.toString(),
        "coordinateSystem" to destination.coordinateSystem, "name" to destination.name, "mode" to destination.mode, "transport" to destination.transport))

    /** 点击地图卡片的默认动作是从当前位置查看路线，已有出行方式保持不变。 */
    fun navigationDestination(destination: SharedMapDestination): SharedMapDestination = destination.copy(mode = "directions")

    fun openURL(destination: SharedMapDestination, provider: String, platform: String): String? {
        if (!valid(destination) || platform !in setOf("ios", "android")) return null
        val d = destination; val pair = "${d.latitude},${d.longitude}"; val route = d.mode == "directions"
        return when (provider) {
            "apple" -> if (platform != "ios") null else url("maps://maps.apple.com/", linkedMapOf(
                (if (route) "daddr" else "ll") to pair, "q" to d.name).apply {
                    if (route) put("dirflg", when (d.transport) { "walking" -> "w"; "transit" -> "r"; else -> "d" })
                })
            "google" -> if (platform == "android") browserURL(d) else url("comgooglemaps://", linkedMapOf(
                (if (route) "daddr" else "q") to pair).apply { if (route) put("directionsmode", d.transport) })
            "amap" -> {
                val base = if (platform == "ios") "iosamap://" else "androidamap://"
                url(base + (if (route) { if (platform == "ios") "path" else "route" } else "viewMap"), linkedMapOf(
                    "sourceApplication" to "Whaleness", "dev" to "1").apply {
                    if (route) {
                        put("m", "0"); put("dlat", d.latitude.toString()); put("dlon", d.longitude.toString()); put("dname", d.name)
                        put("t", when (d.transport) { "walking" -> "2"; "transit" -> "1"; else -> "0" })
                    } else { put("lat", d.latitude.toString()); put("lon", d.longitude.toString()); put("poiname", d.name) }
                })
            }
            "baidu" -> url("baidumap://map/" + if (route) "direction" else "marker", linkedMapOf(
                "coord_type" to "wgs84", "src" to "${if (platform == "ios") "ios" else "andr"}.whaleness.app").apply {
                if (route) { put("origin", "我的位置"); put("destination", "latlng:$pair|name:${d.name.replace('|', ' ')}"); put("mode", d.transport) } // i18n-ignore: 百度 URI 当前定位起点的协议值，不是界面文案，必须保持原值。
                else { put("location", pair); put("title", d.name); put("content", d.name) }
            })
            else -> null
        }
    }

    fun browserURL(d: SharedMapDestination): String? = if (!valid(d)) null else url(
        "https://www.google.com/maps/${if (d.mode == "directions") "dir" else "search"}/", linkedMapOf(
            "api" to "1", (if (d.mode == "directions") "destination" else "query") to "${d.latitude},${d.longitude}").apply {
            if (d.mode == "directions") put("travelmode", d.transport)
        })

    private fun valid(d: SharedMapDestination) = d.latitude.isFinite() && d.longitude.isFinite() &&
        d.latitude in -90.0..90.0 && d.longitude in -180.0..180.0 && d.name.length <= 1000 &&
        d.name.none { it.code < 32 } && d.coordinateSystem == "wgs84" &&
        d.mode in setOf("show", "directions") && d.transport in setOf("driving", "walking", "transit")

    private fun url(base: String, query: Map<String, String>) = base + "?" + query.entries.joinToString("&") { "${encodePart(it.key)}=${encodePart(it.value)}" }
    private fun encodePart(s: String) = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 255
            if (v in 65..90 || v in 97..122 || v in 48..57 || v in listOf(45, 46, 95, 126)) append(v.toChar())
            else { append('%'); append("0123456789ABCDEF"[v / 16]); append("0123456789ABCDEF"[v % 16]) }
        }
    }
    private fun decode(s: String): String {
        val bytes = mutableListOf<Byte>(); var i = 0
        while (i < s.length) {
            when (s[i]) {
                '%' -> { require(i + 2 < s.length); bytes += s.substring(i + 1, i + 3).toInt(16).toByte(); i += 3 }
                '+' -> { bytes += 32.toByte(); i++ }
                else -> { val end = s.indexOfAny(charArrayOf('%', '+'), i).let { if (it < 0) s.length else it }; bytes += s.substring(i, end).encodeToByteArray().toList(); i = end }
            }
        }
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
}
