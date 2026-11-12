package com.clarklevis.dsh.android.platform.devicetools

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AndroidDeviceConnectorsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private class Platform : AndroidConnectorPlatform {
        var executions = 0
        var completions = 0
        var authorized = true
        var waiting: CompletableDeferred<JSONObject>? = null
        override fun supported(module: String) = module in listOf("calendar", "photos")
        override fun authorized(module: String) = supported(module) && authorized
        override fun permissions(module: String) = emptyArray<String>()
        override fun tools(module: String) = when (module) { "calendar" -> listOf("calendar.busy", "calendar.create"); "photos" -> listOf("photos.select"); else -> emptyList() }
        override suspend fun execute(request: JSONObject): JSONObject { executions++; return waiting?.await() ?: JSONObject().put("busyIntervals", JSONArray()) }
        override fun completeHandoff(invocationId: String) { completions++ }
    }
    private class Fixture(context: android.content.Context, val host: String = "connector-test-" + UUID.randomUUID(), wait: Long = 1000) {
        val platform = Platform()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val frames = mutableListOf<JSONObject>()
        var acceptResults = true
        val model = AndroidDeviceConnectors(context, host, scope, platform, wait, wait) {
            val frame = JSONObject(it); frames += frame
            frame.optString("type") != "device-tool-result" || acceptResults
        }
        fun ack(includeModules: Boolean = true, tools: JSONArray? = null) {
            val sent = frames.last { it.optString("type") == "device-tools-register" }
            val modules = JSONArray(sent.getJSONArray("modules").toString())
            if (tools != null) modules.getJSONObject(0).put("tools", tools)
            val ack = JSONObject().put("kind", "device-tools-registered").put("version", 1).put("requestId", sent.getString("requestId"))
                .put("capabilityRevision", sent.getInt("capabilityRevision")).put("connectionLeaseId", "lease-${sent.getInt("capabilityRevision")}")
                .put("statusQuery", true).put("permissionSync", true).put("permissions", JSONObject().put("calendar", true).put("photos", true))
            if (includeModules) ack.put("modules", modules)
            model.receive(ack.toString())
        }
        fun connect() { model.visibility(true); model.connection(true); ack() }
        fun invoke(tool: String = "calendar.busy", id: String = "call"): JSONObject {
            val reg = frames.last { it.optString("type") == "device-tools-register" }
            return JSONObject().put("kind", "device-tool-invoke").put("version", 1).put("invocationId", id)
                .put("connectionLeaseId", "lease-${reg.getInt("capabilityRevision")}").put("tool", tool).put("arguments", JSONObject())
        }
        fun close() { scope.cancel() }
    }

    @Test fun removedHealthIsAbsentFromUiCatalogRegistrationAndManifestAndOldPermissionIsRevoked() {
        val host = "health-removal-" + UUID.randomUUID()
        val saved = context.getSharedPreferences("device-connectors-$host", android.content.Context.MODE_PRIVATE)
        saved.edit().putBoolean("health", true).putBoolean("pending:health", true).commit()
        val f = Fixture(context, host)
        try {
            assertFalse(f.model.modules.contains("health"))
            assertFalse(f.model.enabled.containsKey("health"))
            assertFalse(saved.contains("health"))
            f.model.setEnabled("health", true)
            assertFalse(f.model.enabled.containsKey("health"))
            f.connect()
            val registration = f.frames.first { it.optString("type") == "device-tools-register" }
            assertFalse(registration.getJSONObject("permissionChanges").getBoolean("health"))
            val modules = registration.getJSONArray("modules")
            assertEquals(6, modules.length())
            assertTrue((0 until modules.length()).none { modules.getJSONObject(it).getString("id") == "health" })
            f.model.connection(false); f.model.connection(true)
            assertFalse(f.frames.last().has("permissionChanges"))
            val native = AndroidConnectorServices(context, host)
            assertFalse(native.supported("health"))
            assertTrue(native.tools("health").isEmpty())
            assertTrue(native.permissions("health").isEmpty())
            val permissions = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            assertTrue(permissions.none { it.startsWith("android.permission.health.") })
        } finally { f.close(); saved.edit().clear().commit() }
    }

    @Test fun stateUsesGatewayAcknowledgementAndReadOnlyStatusDoesNotReregister() {
        val f = Fixture(context)
        try {
            f.model.visibility(true); f.model.connection(true)
            assertEquals(ConnectorRegistrationState.REGISTERING, f.model.registrationState)
            assertEquals("android", f.frames.last().getString("platform"))
            f.ack(); assertEquals(ConnectorRegistrationState.REGISTERED, f.model.registrationState)
            val registrationCount = f.frames.count { it.optString("type") == "device-tools-register" }
            f.model.refreshRegistrationStatus()
            assertEquals("device-tools-status", f.frames.last().getString("type"))
            val request = f.frames.last().getString("requestId")
            f.model.receive(JSONObject().put("kind", "device-tools-status").put("version", 1).put("requestId", "old")
                .put("status", "not-registered").toString())
            assertEquals(ConnectorRegistrationState.REGISTERED, f.model.registrationState)
            f.model.receive(JSONObject().put("kind", "device-tools-status").put("version", 1).put("requestId", request)
                .put("status", "not-registered").toString())
            assertTrue(f.model.connected)
            assertEquals(ConnectorRegistrationState.NOT_REGISTERED, f.model.registrationState)
            assertEquals(0, f.model.registeredToolCount)
            assertEquals(registrationCount, f.frames.count { it.optString("type") == "device-tools-register" })
        } finally { f.close() }
    }

    @Test fun missingOrForeignCapabilitiesNeverProduceGreenRegistration() {
        val f = Fixture(context)
        try {
            f.model.connection(true); f.ack(includeModules = false)
            assertEquals(ConnectorRegistrationState.UNVERIFIED, f.model.registrationState)
            f.model.register(); f.ack(tools = JSONArray().put("music.catalog-search"))
            assertEquals(ConnectorRegistrationState.UNVERIFIED, f.model.registrationState)
            f.model.connection(false)
            assertEquals(ConnectorRegistrationState.DISCONNECTED, f.model.registrationState)
            f.model.connection(true, emptyList())
            assertEquals(ConnectorRegistrationState.UNSUPPORTED, f.model.registrationState)
        } finally { f.close() }
    }

    @Test fun switchChangesAreHostScopedAndNotReplayedAfterSending() {
        val f = Fixture(context)
        val other = Fixture(context)
        try {
            f.model.setEnabled("calendar", true)
            assertTrue(f.model.isOn("calendar")); assertFalse(other.model.isOn("calendar"))
            f.connect()
            assertTrue(f.frames.first().getJSONObject("permissionChanges").getBoolean("calendar"))
            f.model.connection(false); f.model.connection(true)
            assertFalse(f.frames.last().has("permissionChanges"))
            f.ack()
            assertTrue(f.model.isOn("calendar"))
            f.model.receive(JSONObject().put("kind", "device-tools-permissions").put("version", 1)
                .put("connectionLeaseId", f.frames.last().let { "lease-${it.getInt("capabilityRevision")}" })
                .put("permissions", JSONObject().put("calendar", false)).toString())
            assertFalse(f.model.isOn("calendar"))
            f.model.setEnabled("calendar", true)
            assertTrue(f.frames.last().getJSONObject("permissionChanges").getBoolean("calendar"))
        } finally { f.close(); other.close() }
    }

    @Test fun offlineIntentSurvivesRecreationAndTimeoutIsVisible() = runBlocking {
        val host = "offline-" + UUID.randomUUID()
        val first = Fixture(context, host)
        first.model.setEnabled("calendar", true); first.close()
        val next = Fixture(context, host, 30)
        try {
            next.model.connection(true)
            assertTrue(next.frames.last().getJSONObject("permissionChanges").getBoolean("calendar"))
            delay(90)
            assertEquals(ConnectorRegistrationState.TIMED_OUT, next.model.registrationState)
            assertTrue(next.model.canRetryRegistration)
        } finally { next.close() }
    }

    @Test fun revocationCancelsRunningReadAndRejectsStaleAndUnsupportedCalls() = runBlocking {
        val f = Fixture(context)
        try {
            f.model.setEnabled("calendar", true); f.connect()
            f.platform.waiting = CompletableDeferred()
            f.model.receive(f.invoke().toString()); assertNotNull(f.model.pending)
            f.model.refreshRegistrationStatus(); assertNotNull(f.model.pending)
            val count = f.frames.count { it.optString("type") == "device-tools-register" }
            assertEquals(1, count)
            f.model.receive(JSONObject().put("kind", "device-tools-permissions").put("version", 1)
                .put("connectionLeaseId", "lease-1").put("permissions", JSONObject()).toString())
            assertNull(f.model.pending)
            assertEquals("tool_disabled", f.frames.last().getJSONObject("error").getString("code"))
            f.model.receive(f.invoke(id = "stale").put("connectionLeaseId", "old").toString())
            assertEquals(1, f.platform.executions)
        } finally { f.close() }
    }

    @Test fun successfulReadDoesNotAskForRepeatedConsentAndDuplicateIsIgnored() {
        val f = Fixture(context)
        try {
            f.model.setEnabled("calendar", true); f.connect()
            val request = f.invoke()
            f.model.receive(request.toString())
            assertEquals("success", f.frames.last().getString("status")); assertEquals(1, f.platform.executions)
            f.model.receive(request.toString()); assertEquals(1, f.platform.executions)
            f.model.receive(f.invoke(id = "second").toString()); assertEquals(2, f.platform.executions)
        } finally { f.close() }
    }

    @Test fun photoSelectionCanReturnBeforeResumeWithoutAnotherConfirmation() {
        val f = Fixture(context)
        try {
            f.model.setEnabled("photos", true); f.connect()
            f.model.receive(f.invoke("photos.select").toString())
            f.model.visibility(false); assertNotNull(f.model.pending)
            f.model.photoSelected(JSONObject().put("images", JSONArray()))
            assertNotNull(f.model.pending)
            f.model.visibility(true)
            assertNull(f.model.pending); assertEquals("success", f.frames.last().getString("status"))
        } finally { f.close() }
    }

    @Test fun externalHandoffIsNeverLaunchedWhenResultCannotBeSent() {
        val f = Fixture(context)
        try {
            f.model.setEnabled("calendar", true); f.connect()
            f.acceptResults = false
            f.model.receive(f.invoke(id = "lost-result").toString())
            assertEquals(0, f.platform.completions)
            f.acceptResults = true
            f.model.receive(f.invoke(id = "accepted-result").toString())
            assertEquals(1, f.platform.completions)
        } finally { f.close() }
    }

    @Test fun preparingHandoffDoesNotOpenAnActivityAndCancellationDiscardsIt() {
        var opened = 0
        val wrapper = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: android.content.Intent) { opened++ }
        }
        val handoffs = AndroidConnectorHandoffs(wrapper)
        val intent = android.content.Intent(context, com.clarklevis.dsh.android.MainActivity::class.java)
        val request = JSONObject().put("invocationId", "handoff")
        handoffs.prepare(request, intent, "missing_app")
        assertEquals(0, opened)
        handoffs.cancel("handoff"); handoffs.complete("handoff")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(0, opened)
        handoffs.prepare(request, intent, "missing_app"); handoffs.complete("handoff")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(1, opened)
    }

    @Test fun mapsOpenOnlyPreparesCardDataAndNeverLaunchesAnExternalActivity() = runBlocking {
        var opened = 0
        val wrapper = object : android.content.ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int) = android.content.pm.PackageManager.PERMISSION_GRANTED
            override fun startActivity(intent: android.content.Intent) { opened++ }
        }
        val services = AndroidConnectorServices(wrapper, "map-card-" + UUID.randomUUID())
        for (mode in listOf("show", "directions")) for (transport in listOf("driving", "walking", "transit")) {
            val id = "$mode-$transport"
            val args = JSONObject().put("latitude", 38.01573).put("longitude", 114.473655)
                .put("name", "石家庄站 & 入口").put("mode", mode)
                .also { if (mode == "directions") it.put("transport", transport) }
            val request = JSONObject().put("invocationId", id).put("sessionId", "map-session").put("toolCallId", id)
                .put("tool", "maps.open").put("arguments", args)
            repeat(2) {
                val result = services.execute(request)
                assertEquals("ready", result.getString("status"))
                assertEquals(args.getString("name"), result.getString("name"))
                assertEquals(args.getDouble("latitude"), result.getDouble("latitude"), 0.0)
                assertEquals(args.getDouble("longitude"), result.getDouble("longitude"), 0.0)
                assertEquals(mode, result.getString("mode"))
                if (mode == "directions") assertEquals(transport, result.getString("transport"))
                else assertFalse(result.has("transport"))
                // 模拟回执发送成功后的生命周期，仍不能安排浏览器或地图跳转。
                services.completeHandoff(id)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(0, opened)
            }
        }
    }

    @Test fun writeReceiptsSurviveRecreationAndSeparateHostsSessionsAndArguments() = runBlocking {
        val host = "ledger-" + UUID.randomUUID(); val operation = UUID.randomUUID().toString()
        val request = JSONObject().put("sessionId", "s").put("tool", "calendar.create")
            .put("arguments", JSONObject().put("operationId", operation).put("title", "test"))
        var writes = 0
        val a = AndroidConnectorStorage(context, host)
        val result = a.write(request) { writes++; JSONObject().put("id", "saved") }
        assertEquals("saved", result.getString("id"))
        AndroidConnectorStorage(context, host).write(request) { writes++; JSONObject() }
        assertEquals(1, writes)
        try { a.write(JSONObject(request.toString()).put("arguments", JSONObject().put("operationId", operation).put("title", "other"))) { JSONObject() }; fail() }
        catch (error: ConnectorFailure) { assertEquals("operation_conflict", error.code) }
        AndroidConnectorStorage(context, host + "other").write(request) { writes++; JSONObject() }
        a.write(JSONObject(request.toString()).put("sessionId", "other")) { writes++; JSONObject() }
        assertEquals(3, writes)
        val unknown = JSONObject(request.toString()).put("sessionId", "unknown")
        try { a.write(unknown) { throw IllegalStateException("interrupted") }; fail() } catch (error: ConnectorFailure) { assertEquals("outcome_unknown", error.code) }
        try { a.write(unknown) { writes++; JSONObject() }; fail() } catch (error: ConnectorFailure) { assertEquals("outcome_unknown", error.code) }
        assertEquals(3, writes)
    }

    @Test fun markerLifecycleIsIsolatedAndUsesRevisionChecks() = runBlocking {
        val host = "marker-" + UUID.randomUUID()
        val maps = AndroidMapConnector(context, AndroidConnectorStorage(context, host))
        fun request(tool: String, args: JSONObject) = JSONObject().put("sessionId", "session").put("tool", tool).put("arguments", args)
        val args = JSONObject().put("operationId", UUID.randomUUID().toString()).put("name", "测试地点").put("latitude", 31.2).put("longitude", 121.5)
        val created = maps.markers(request("maps.markers.create", args))
        val id = created.getJSONObject("marker").getString("id")
        assertEquals(id, maps.markers(request("maps.markers.create", args)).getJSONObject("marker").getString("id"))
        val read = request("maps.markers.list", JSONObject())
        assertEquals(1, maps.markers(read).getInt("total"))
        assertEquals(0, AndroidMapConnector(context, AndroidConnectorStorage(context, host + "other")).markers(read).getInt("total"))
        val update = JSONObject().put("operationId", UUID.randomUUID().toString()).put("markerId", id).put("name", "新名称").put("expectedRevision", 1)
        assertEquals(2, maps.markers(request("maps.markers.update", update)).getJSONObject("marker").getInt("revision"))
        val delete = JSONObject().put("operationId", UUID.randomUUID().toString()).put("markerId", id).put("expectedRevision", 2)
        maps.markers(request("maps.markers.delete", delete))
        assertEquals(0, maps.markers(read).getInt("total"))
    }

    @Test fun androidRemindersAreOwnHostDataAndNeverPretendToBeSystemReminders() = runBlocking {
        val host = "reminder-" + UUID.randomUUID()
        val connector = AndroidReminderConnector(context, AndroidConnectorStorage(context, host), host)
        val request = JSONObject().put("sessionId", "s").put("tool", "reminders.create").put("arguments", JSONObject()
            .put("operationId", UUID.randomUUID().toString()).put("title", "测试待办").put("timeZone", "Asia/Shanghai").put("notify", false))
        val created = connector.create(request)
        assertEquals("created", created.getString("status")); assertTrue(created.isNull("dueDate"))
        assertEquals(created.getString("id"), connector.create(request).getString("id"))
        val range = JSONObject().put("start", "2026-11-10T00:00:00+08:00").put("end", "2026-11-11T00:00:00+08:00").put("timeZone", "Asia/Shanghai")
        val read = connector.list(range)
        assertEquals("whaleness-host-reminders", read.getString("source")); assertEquals(1, read.getJSONArray("items").length())
        val other = AndroidReminderConnector(context, AndroidConnectorStorage(context, host + "other"), host + "other")
        assertEquals(0, other.list(range).getJSONArray("items").length())
    }

    @Test fun mediaStoreSearchAndLibraryReadActualProviderWithoutClaimingCloudPlaylists() = runBlocking {
        val marker = "WhalenessConnectorTest" + UUID.randomUUID().toString().replace("-", "")
        val uri = context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "$marker.mp3")
            put(android.provider.MediaStore.Audio.Media.TITLE, marker)
            put(android.provider.MediaStore.Audio.Media.ARTIST, "Test artist")
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(android.provider.MediaStore.Audio.Media.IS_MUSIC, 1)
        })!!
        try {
            val connector = AndroidMusicConnector(context, AndroidConnectorStorage(context, marker))
            val search = connector.search(JSONObject().put("query", marker))
            assertEquals(1, search.getJSONArray("items").length())
            val library = connector.library(JSONObject().put("query", marker))
            assertEquals("android-media-store", library.getString("source"))
            assertEquals("device-shared-audio", library.getString("scope"))
            assertEquals(1, library.getJSONArray("items").length())
            assertTrue(library.getJSONArray("items").getJSONObject(0).getString("id").startsWith("library:android:songs:"))
            try { connector.library(JSONObject().put("kind", "playlists")); fail() }
            catch (error: ConnectorFailure) { assertEquals("library_kind_unsupported", error.code) }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
