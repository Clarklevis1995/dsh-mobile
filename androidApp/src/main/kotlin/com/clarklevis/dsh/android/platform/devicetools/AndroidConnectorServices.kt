package com.clarklevis.dsh.android.platform.devicetools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.coroutines.resume

class AndroidConnectorServices(private val context: Context, hostId: String) : AndroidConnectorPlatform {
    private val storage = AndroidConnectorStorage(context, hostId)
    private val handoffs = AndroidConnectorHandoffs(context)
    private val calendar = AndroidCalendarConnector(context, storage)
    private val reminders = AndroidReminderConnector(context, storage, hostId)
    private val maps = AndroidMapConnector(context, storage)
    private val music = AndroidMusicConnector(context, storage, handoffs)
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override fun completeHandoff(invocationId: String) = handoffs.complete(invocationId)
    override fun cancelHandoff(invocationId: String) = handoffs.cancel(invocationId)

    override fun supported(module: String): Boolean = when (module) {
        "calendar" -> context.packageManager.resolveContentProvider("com.android.calendar", 0) != null
        "alarms" -> context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_DEFAULT_ONLY) != null
        "location" -> context.getSystemService(Context.LOCATION_SERVICE) != null
        "reminders", "photos", "music" -> true
        else -> false
    }
    override fun authorized(module: String): Boolean = when (module) {
        "calendar" -> granted(Manifest.permission.READ_CALENDAR)
        "location" -> granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        "music" -> granted(music.mediaPermission) || music.sessionAuthorized()
        else -> supported(module)
    }
    override fun permissions(module: String): Array<String> = when (module) {
        "calendar" -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        "location" -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        "music" -> arrayOf(music.mediaPermission)
        "reminders" -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        else -> emptyArray()
    }
    override fun tools(module: String): List<String> {
        if (!supported(module)) return emptyList()
        return when (module) {
            "calendar" -> listOf("calendar.busy") + if (granted(Manifest.permission.WRITE_CALENDAR)) listOf("calendar.create") else emptyList()
            "reminders" -> listOf("reminders.list", "reminders.create")
            "location" -> listOf("location.current", "maps.open", "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete") +
                (if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) listOf("location.precise") else emptyList()) +
                (if (Geocoder.isPresent()) listOf("maps.geocode", "maps.reverse-geocode") else emptyList())
            "photos" -> listOf("photos.select")
            "music" -> (if (granted(music.mediaPermission)) listOf("music.search", "music.library", "music.play-library", "music.play") else emptyList()) +
                (if (music.sessionAuthorized()) listOf("music.state", "music.pause", "music.seek", "music.play") else emptyList())
            "alarms" -> listOf("alarms.open-create")
            else -> emptyList()
        }.distinct()
    }

    override suspend fun execute(request: JSONObject): JSONObject {
        val tool = request.getString("tool"); val args = request.getJSONObject("arguments")
        val module = com.clarklevis.dsh.shared.facade.SharedDeviceConnectors().toolModule(tool) ?: throw ConnectorFailure("tool_unsupported")
        if (tool !in tools(module)) throw ConnectorFailure("tool_unsupported")
        if (!authorized(module)) throw ConnectorFailure("permission_denied")
        return when (tool) {
            "calendar.busy" -> calendar.busy(args)
            "calendar.create" -> calendar.create(request)
            "reminders.list" -> reminders.list(args)
            "reminders.create" -> reminders.create(request)
            "location.current", "location.precise" -> location(tool, args)
            "maps.geocode", "maps.reverse-geocode" -> maps.geocode(tool, args)
            "maps.open" -> maps.open(request)
            "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete" -> maps.markers(request)
            "music.search" -> music.search(args)
            "music.library" -> music.library(args)
            "music.play", "music.state", "music.pause", "music.seek", "music.play-library" -> music.control(request)
            "alarms.open-create" -> openAlarm(request)
            else -> throw ConnectorFailure("tool_unsupported")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private suspend fun location(tool: String, args: JSONObject): JSONObject {
        val precise = tool == "location.precise"
        if (!precise) require(args.optString("requestedPrecision", "coarse") == "coarse")
        val maxAge = args.integer("maxAgeSeconds", 60, 1..300)
        if (precise && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) throw ConnectorFailure("precise_location_required")
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = (if (precise) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER))
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw ConnectorFailure("location_disabled")
        val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter {
            it.latitude.isFinite() && it.longitude.isFinite() && System.currentTimeMillis() - it.time in 0..maxAge * 1000L
        }.minByOrNull { it.accuracy }
        val sample = cached ?: suspendCancellableCoroutine<Location> { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(value: Location) { manager.removeUpdates(this); if (continuation.isActive) continuation.resume(value) }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            manager.requestSingleUpdate(providers.first(), listener, Looper.getMainLooper())
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
        }
        require(System.currentTimeMillis() - sample.time in 0..maxAge * 1000L)
        return JSONObject().put("latitude", if (precise) sample.latitude else kotlin.math.round(sample.latitude * 100) / 100)
            .put("longitude", if (precise) sample.longitude else kotlin.math.round(sample.longitude * 100) / 100)
            .put("precision", if (precise) "precise" else "coarse")
            .put("horizontalAccuracyMeters", if (precise) maxOf(0.0, sample.accuracy.toDouble()) else maxOf(1500.0, sample.accuracy.toDouble()))
            .put("sampledAt", Instant.ofEpochMilli(sample.time).toString())
    }

    private suspend fun openAlarm(request: JSONObject): JSONObject {
        val args = request.getJSONObject("arguments")
        val title = args.text("title", 500); val zone = args.zone(); val fire = args.instant("fireDate")
        args.uuid("operationId")
        val local = fire.atZone(zone); val deviceZone = java.time.ZoneId.systemDefault()
        val onDevice = fire.atZone(deviceZone)
        val now = ZonedDateTime.now(deviceZone)
        // ACTION_SET_ALARM 只有时分，没有任意日期；只接受设备下一次该时分，且不静默改写秒数。
        require(fire > Instant.now() && onDevice.second == 0 && onDevice.nano == 0)
        val time = LocalTime.of(onDevice.hour, onDevice.minute)
        var next = now.toLocalDate().atTime(time).atZone(deviceZone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        if (next.toInstant() != fire) throw ConnectorFailure("alarm_date_unsupported")
        return storage.write(request) {
            handoffs.prepare(request, Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, onDevice.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, onDevice.minute).putExtra(AlarmClock.EXTRA_MESSAGE, title)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "clock_app_unavailable")
            JSONObject().put("status", "handoff-requested").put("skipUIRequested", true).put("title", title)
                .put("fireDate", local.toOffsetDateTime().toString()).put("timeZone", zone.id).put("operationId", args.getString("operationId"))
        }
    }
}
