package com.clarklevis.dsh.android.platform.devicetools

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

internal class AndroidCalendarConnector(private val context: Context, private val storage: AndroidConnectorStorage) {
    suspend fun busy(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val (start, end) = args.range()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start.toEpochMilli()); ContentUris.appendId(builder, end.toEpochMilli())
        val rows = mutableListOf<Pair<Long, Long>>()
        val cursor = context.contentResolver.query(builder.build(), arrayOf(CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.AVAILABILITY, CalendarContract.Events.STATUS),
            null, null, CalendarContract.Instances.BEGIN + " ASC") ?: throw ConnectorFailure("calendar_unavailable")
        cursor.use {
            while (it.moveToNext()) {
                if (it.getInt(2) == CalendarContract.Events.AVAILABILITY_FREE || it.getInt(3) == CalendarContract.Events.STATUS_CANCELED) continue
                if (rows.size >= 2000) throw ConnectorFailure("result_too_large")
                rows += maxOf(start.toEpochMilli(), it.getLong(0)) to minOf(end.toEpochMilli(), it.getLong(1))
            }
        }
        val merged = mutableListOf<Pair<Long, Long>>()
        rows.filter { it.first < it.second }.forEach { row ->
            val last = merged.lastOrNull()
            if (last != null && row.first <= last.second) merged[merged.lastIndex] = last.first to maxOf(last.second, row.second)
            else merged += row
        }
        JSONObject().put("timeZone", args.zone().id).put("coverage", "authorized-device-calendars")
            .put("source", "android-calendar-provider").put("collectedAt", Instant.now().toString()).put("truncated", false)
            .put("busyIntervals", JSONArray(merged.map { JSONObject().put("start", Instant.ofEpochMilli(it.first).toString()).put("end", Instant.ofEpochMilli(it.second).toString()) }))
    }

    suspend fun create(request: JSONObject): JSONObject {
        val args = request.getJSONObject("arguments")
        val title = args.text("title", 500); val start = args.instant("start"); val end = args.instant("end"); val zone = args.zone()
        require(start < end && java.time.Duration.between(start, end).toDays() <= 31 && java.time.Duration.between(start, end).toMillis() <= 31 * 86_400_000L)
        val allDay = if (args.has("allDay")) args.getBoolean("allDay") else false
        if (allDay) require(listOf(start, end).all { it.atZone(zone).toLocalTime() == java.time.LocalTime.MIDNIGHT })
        val alert = args.integer("alertMinutesBefore", -1, 0..10080)
        val notes = if (args.has("notes")) args.text("notes", 2000, true) else ""
        val location = if (args.has("location")) args.text("location", 500, true) else ""
        args.uuid("operationId")
        val calendarId = withContext(Dispatchers.IO) {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ALLOWED_REMINDERS),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC")?.use { cursor ->
                var found: Long? = null
                while (cursor.moveToNext()) {
                    if (alert < 0 || cursor.getString(1).orEmpty().split(',').contains(CalendarContract.Reminders.METHOD_ALERT.toString())) { found = cursor.getLong(0); break }
                }
                found
            } ?: throw ConnectorFailure("writable_calendar_unavailable")
        }
        return storage.write(request) {
            val operations = arrayListOf(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId).withValue(CalendarContract.Events.TITLE, title)
                .withValue(CalendarContract.Events.DESCRIPTION, notes).withValue(CalendarContract.Events.EVENT_LOCATION, location)
                .withValue(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id)
                .withValue(CalendarContract.Events.DTSTART, if (allDay) start.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else start.toEpochMilli())
                .withValue(CalendarContract.Events.DTEND, if (allDay) end.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else end.toEpochMilli())
                .withValue(CalendarContract.Events.HAS_ALARM, if (alert >= 0) 1 else 0).build())
            if (alert >= 0) operations += ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0).withValue(CalendarContract.Reminders.MINUTES, alert)
                .withValue(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT).build()
            val result = context.contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            val id = result.firstOrNull()?.uri?.lastPathSegment ?: throw ConnectorFailure("outcome_unknown")
            JSONObject().put("id", id).put("title", title).put("start", args.getString("start")).put("end", args.getString("end"))
                .put("timeZone", zone.id).put("allDay", allDay).put("operationId", args.getString("operationId")).put("status", "created")
        }
    }
}
