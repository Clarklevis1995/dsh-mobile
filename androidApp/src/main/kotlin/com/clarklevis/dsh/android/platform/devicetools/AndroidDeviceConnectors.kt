package com.clarklevis.dsh.android.platform.devicetools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.shared.facade.SharedDeviceConnectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ConnectorRegistrationState {
    DISCONNECTED, UNSUPPORTED, REGISTERING, REGISTERED, UNVERIFIED, FAILED, TIMED_OUT, NOT_REGISTERED
}

data class RegisteredConnectorModule(val id: String, val enabled: Boolean, val available: Boolean, val tools: List<String>)

/** 系统实现与控制连接隔离，登记只包含这台 Android 真正实现的工具。 */
interface AndroidConnectorPlatform {
    fun supported(module: String): Boolean
    fun authorized(module: String): Boolean
    fun permissions(module: String): Array<String>
    fun tools(module: String): List<String>
    suspend fun execute(request: JSONObject): JSONObject
    fun completeHandoff(invocationId: String) {}
    fun cancelHandoff(invocationId: String) {}
}

class ConnectorFailure(val code: String) : Exception(code)

/** 开关按主机保存；聊天在线、控制租约和网关确认分别建模。 */
class AndroidDeviceConnectors(
    context: Context,
    hostId: String,
    private val scope: CoroutineScope,
    val platform: AndroidConnectorPlatform = AndroidConnectorServices(context.applicationContext, hostId),
    private val registrationWaitMs: Long = 10_000,
    private val statusWaitMs: Long = 5_000,
    private val send: suspend (String) -> Boolean
) {
    val modules = listOf("calendar", "reminders", "location", "photos", "music", "alarms")
    private val preferences = context.getSharedPreferences("device-connectors-$hostId", Context.MODE_PRIVATE).also { saved ->
        // Android 已移除健康能力；清理旧开关，并在下次连接时撤销该主机的旧授权。
        if (saved.contains("health") || saved.contains("pending:health")) {
            saved.edit().remove("health").putBoolean("pending:health", false).apply()
        }
    }
    var enabled by mutableStateOf(modules.associateWith { preferences.getBoolean(it, false) }); private set
    var hostPermissions by mutableStateOf(modules.associateWith { false }); private set
    var connected by mutableStateOf(false); private set
    var foreground by mutableStateOf(false); private set
    var pending by mutableStateOf<JSONObject?>(null); private set
    var selectingPhoto by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    var syncNotice by mutableStateOf<String?>(null); private set
    var permissionSyncSupported by mutableStateOf<Boolean?>(null); private set
    var registrationState by mutableStateOf(ConnectorRegistrationState.DISCONNECTED); private set
    var registeredModules by mutableStateOf(emptyList<RegisteredConnectorModule>()); private set
    var registrationConfirmedAt by mutableStateOf<Long?>(null); private set
    var checkingRegistration by mutableStateOf(false); private set
    val registeredToolCount get() = registeredModules.sumOf { it.tools.size }
    val canRetryRegistration get() = connected && negotiated && registration == null && pending == null
    val canCheckRegistration get() = connected && negotiated && statusQuerySupported && registration == null
    private var negotiated = false
    private var statusQuerySupported = false
    private var lease: String? = null
    private var revision = 0
    private var registration: String? = null
    private var registrationChanges = emptyMap<String, Boolean>()
    private val queuedPermissionChanges = modules.mapNotNull { id ->
        if (preferences.contains("pending:$id")) id to preferences.getBoolean("pending:$id", false) else null
    }.toMap().toMutableMap().apply {
        if (preferences.contains("pending:health")) put("health", false)
    }
    private var registrationTimeout: Job? = null
    private var statusRequest: String? = null
    private var statusTimeout: Job? = null
    private var executing: Job? = null
    private var deadline: Job? = null
    private var selectedPhotoResult: JSONObject? = null
    private var lastAuthorization = modules.associateWith(platform::authorized)
    private val shared = SharedDeviceConnectors()
    private val completed = linkedSetOf<String>()

    fun supported(module: String) = module in modules && platform.supported(module)
    fun available(module: String) = supported(module) && platform.authorized(module)
    fun isOn(module: String): Boolean = enabled[module] == true &&
        (queuedPermissionChanges[module] == true || registrationChanges[module] == true || permissionSyncSupported == null || hostPermissions[module] == true)

    fun setEnabled(module: String, value: Boolean) {
        if (module !in modules || (value && !available(module))) return
        preferences.edit().putBoolean(module, value).putBoolean("pending:$module", value).apply()
        enabled = enabled + (module to value)
        queuedPermissionChanges[module] = value
        syncNotice = null
        clearPending("tool_disabled")
        register()
    }

    fun visibility(active: Boolean) {
        foreground = active
        if (!active && !selectingPhoto) clearPending("user_action_required")
        if (active) {
            val authorization = modules.associateWith(platform::authorized)
            if (authorization != lastAuthorization) {
                lastAuthorization = authorization
                register()
            }
            selectedPhotoResult?.let { result -> pending?.let { finish(it, result) } }
        }
    }

    fun connection(active: Boolean, capabilities: Collection<String> = listOf("device-tools-v1")) {
        val supportsTools = active && "device-tools-v1" in capabilities
        if (active == connected && supportsTools == negotiated) return
        clearPending("device_disconnected")
        registrationTimeout?.cancel()
        cancelStatusCheck()
        registration = null; registrationChanges = emptyMap(); syncing = false
        lease = null; permissionSyncSupported = null; statusQuerySupported = false
        hostPermissions = modules.associateWith { false }; completed.clear(); clearSnapshot()
        connected = active; negotiated = supportsTools
        registrationState = if (!active) ConnectorRegistrationState.DISCONNECTED else ConnectorRegistrationState.UNSUPPORTED
        if (supportsTools) register()
    }

    fun register() {
        if (!connected || !negotiated) return
        clearPending("stale_lease")
        lease = null; registrationTimeout?.cancel(); cancelStatusCheck(); clearSnapshot()
        statusQuerySupported = false; registrationState = ConnectorRegistrationState.REGISTERING
        val id = UUID.randomUUID().toString(); registration = id
        val changes = queuedPermissionChanges.toMap()
        queuedPermissionChanges.clear(); registrationChanges = changes; syncing = changes.isNotEmpty()
        // 已发出的开启补丁不在断线后重放，避免覆盖网关刚撤销的权限。
        preferences.edit().also { edit -> changes.keys.forEach { edit.remove("pending:$it") } }.apply()
        val frame = JSONObject().put("type", "device-tools-register").put("version", 1).put("platform", "android").put("requestId", id)
            .put("capabilityRevision", ++revision).put("modules", JSONArray(modules.map { module ->
                JSONObject().put("id", module).put("enabled", enabled[module] == true)
                    .put("available", available(module)).put("tools", JSONArray(platform.tools(module)))
            }))
        if (changes.isNotEmpty()) frame.put("permissionChanges", JSONObject(changes))
        scope.launch {
            if (!send(frame.toString()) && registration == id) registrationFailed(ConnectorRegistrationState.FAILED, "send_failed")
        }
        registrationTimeout = scope.launch {
            delay(registrationWaitMs)
            if (registration == id) registrationFailed(ConnectorRegistrationState.TIMED_OUT, "registration_timeout")
        }
    }

    fun refreshRegistrationStatus() {
        if (!foreground || !canCheckRegistration || statusRequest != null) return
        val id = UUID.randomUUID().toString(); statusRequest = id; checkingRegistration = true
        scope.launch {
            val sent = send(JSONObject().put("type", "device-tools-status").put("version", 1).put("requestId", id).toString())
            if (!sent && statusRequest == id) statusFailed()
        }
        statusTimeout = scope.launch {
            delay(statusWaitMs)
            if (statusRequest == id) statusFailed()
        }
    }

    private fun statusFailed() { cancelStatusCheck(); clearSnapshot(); registrationState = ConnectorRegistrationState.UNVERIFIED }
    private fun registrationFailed(state: ConnectorRegistrationState, code: String) {
        registrationTimeout?.cancel(); registrationTimeout = null
        registration = null; registrationChanges = emptyMap(); syncing = false
        clearSnapshot(); registrationState = state; syncNotice = code
    }
    private fun cancelStatusCheck() {
        statusTimeout?.cancel(); statusTimeout = null; statusRequest = null; checkingRegistration = false
    }
    private fun clearSnapshot() { registeredModules = emptyList(); registrationConfirmedAt = null }

    private fun snapshot(frame: JSONObject): Boolean {
        val rows = frame.optJSONArray("modules") ?: return false
        if (rows.length() > modules.size) return false
        val accepted = mutableListOf<RegisteredConnectorModule>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: return false
            val id = row.optString("id")
            val tools = row.optJSONArray("tools") ?: return false
            if (id !in modules || accepted.any { it.id == id } || row.opt("enabled") !is Boolean || row.opt("available") !is Boolean) return false
            val names = (0 until tools.length()).map { tools.opt(it) }
            if (names.any { it !is String || it !in platform.tools(id) } || names.distinct().size != names.size) return false
            accepted += RegisteredConnectorModule(id, row.getBoolean("enabled"), row.getBoolean("available"), names.filterIsInstance<String>())
        }
        registeredModules = accepted; registrationConfirmedAt = System.currentTimeMillis()
        return true
    }

    fun receive(raw: String) {
        if (!negotiated) return
        val frame = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (frame.optString("kind") == "error") {
            if (frame.optString("requestType") == "device-tools-status" && frame.optString("requestId") == statusRequest) statusFailed()
            if (frame.optString("requestType") == "device-tools-register" && frame.optString("requestId") == registration) registrationFailed(ConnectorRegistrationState.FAILED, "registration_failed")
            return
        }
        if (frame.optInt("version") != 1) return
        when (frame.optString("kind")) {
            "device-tools-registered" -> {
                if (registration == null || frame.optString("requestId") != registration || frame.optInt("capabilityRevision") != revision) return
                val owner = frame.optString("connectionLeaseId").takeIf { it.isNotBlank() } ?: return
                val attemptedSync = registrationChanges.isNotEmpty()
                registrationTimeout?.cancel(); registrationTimeout = null
                registration = null; registrationChanges = emptyMap(); syncing = false; lease = owner
                permissionSyncSupported = frame.optBoolean("permissionSync", false)
                statusQuerySupported = frame.optBoolean("statusQuery", false)
                syncNotice = if (attemptedSync && permissionSyncSupported != true) "gateway_update_required" else null
                permissions(frame)
                registrationState = if (snapshot(frame)) ConnectorRegistrationState.REGISTERED else ConnectorRegistrationState.UNVERIFIED
                if (registrationState != ConnectorRegistrationState.REGISTERED) clearSnapshot()
            }
            "device-tools-status" -> {
                if (statusRequest == null || frame.optString("requestId") != statusRequest) return
                if (frame.optString("status") == "not-registered") {
                    cancelStatusCheck(); clearPending("stale_lease"); lease = null; clearSnapshot()
                    registrationState = ConnectorRegistrationState.NOT_REGISTERED
                } else if (frame.optString("status") == "registered" && lease != null && frame.optString("connectionLeaseId") == lease && frame.optInt("capabilityRevision") == revision) {
                    cancelStatusCheck(); permissions(frame)
                    registrationState = if (snapshot(frame)) ConnectorRegistrationState.REGISTERED else ConnectorRegistrationState.UNVERIFIED
                    if (registrationState != ConnectorRegistrationState.REGISTERED) clearSnapshot()
                }
            }
            "device-tools-permissions" -> if (lease != null && frame.optString("connectionLeaseId") == lease) permissions(frame)
            "device-tool-invoke" -> {
                if (!connected || lease == null || frame.optString("connectionLeaseId") != lease) return
                val validation = shared.validateInvocationJson(raw)
                if (validation.isNotEmpty()) { reply(frame, validation); return }
                val tool = frame.getString("tool")
                val module = shared.toolModule(tool) ?: return reply(frame, "tool_unsupported")
                val id = frame.getString("invocationId")
                if (id in completed || pending?.optString("invocationId") == id) return
                if (pending != null) { reply(frame, "busy"); return }
                if (enabled[module] != true || hostPermissions[module] != true) { reply(frame, "tool_disabled"); return }
                if (tool !in platform.tools(module) || !supported(module)) { reply(frame, "tool_unsupported"); return }
                if (!available(module)) { reply(frame, "permission_denied"); return }
                if (!foreground) { reply(frame, "user_action_required"); return }
                pending = frame
                deadline = scope.launch {
                    delay(frame.optLong("timeoutMs", 120_000).coerceIn(1, 120_000))
                    if (pending === frame) clearPending("invocation_timeout")
                }
                if (tool == "photos.select") { selectingPhoto = true; return }
                executing = scope.launch {
                    try {
                        val result = withTimeout(15_000) { platform.execute(frame) }
                        finish(frame, result)
                    } catch (cancelled: CancellationException) {
                        if (cancelled is TimeoutCancellationException && pending === frame) clearPending("invocation_timeout") else throw cancelled
                    } catch (error: Exception) {
                        if (pending === frame) clearPending(when (error) {
                            is ConnectorFailure -> error.code
                            is SecurityException -> "permission_denied"
                            is IllegalArgumentException, is org.json.JSONException -> "invalid_request"
                            else -> "data_unavailable"
                        })
                    }
                }
            }
            "device-tool-cancel" -> if (lease != null && frame.optString("connectionLeaseId") == lease && frame.optString("invocationId") == pending?.optString("invocationId")) clearPending("cancelled")
        }
    }

    private fun permissions(frame: JSONObject) {
        val values = frame.optJSONObject("permissions") ?: JSONObject()
        hostPermissions = modules.associateWith { values.optBoolean(it, false) }
        pending?.let { if (hostPermissions[shared.toolModule(it.optString("tool"))] != true) clearPending("tool_disabled") }
    }

    fun photoSelected(result: JSONObject?) {
        val request = pending ?: return
        if (!selectingPhoto) return
        selectingPhoto = false
        if (result == null) clearPending("cancelled") else {
            selectedPhotoResult = result
            if (foreground) finish(request, result)
        }
    }

    private fun finish(request: JSONObject, result: JSONObject) {
        val module = shared.toolModule(request.optString("tool"))
        if (pending !== request || !foreground || request.optString("connectionLeaseId") != lease || enabled[module] != true || hostPermissions[module] != true) return
        pending = null; selectingPhoto = false; selectedPhotoResult = null
        deadline?.cancel(); deadline = null; executing = null
        remember(request)
        scope.launch {
            val sent = send(envelope(request).put("status", "success").put("result", result).toString())
            val id = request.getString("invocationId")
            if (sent && connected && foreground && request.optString("connectionLeaseId") == lease) platform.completeHandoff(id)
            else platform.cancelHandoff(id)
        }
    }

    fun clearPending(code: String = "cancelled") {
        val request = pending
        val outcome = if (executing != null && request?.optString("tool") in setOf("calendar.create", "reminders.create", "alarms.open-create", "maps.markers.create", "maps.markers.update", "maps.markers.delete", "music.play", "music.pause", "music.seek", "music.play-library") && code in setOf("cancelled", "user_action_required", "device_disconnected", "tool_disabled", "stale_lease", "invocation_timeout")) "outcome_unknown" else code
        pending = null; selectingPhoto = false; selectedPhotoResult = null
        deadline?.cancel(); deadline = null; executing?.cancel(); executing = null
        if (request != null) { platform.cancelHandoff(request.optString("invocationId")); remember(request); reply(request, outcome) }
    }
    private fun remember(request: JSONObject) {
        completed.add(request.optString("invocationId"))
        while (completed.size > 1024) completed.remove(completed.first())
    }
    private fun envelope(request: JSONObject) = JSONObject().put("type", "device-tool-result").put("version", 1)
        .put("invocationId", request.optString("invocationId")).put("connectionLeaseId", request.optString("connectionLeaseId"))
    private fun reply(request: JSONObject, code: String) {
        scope.launch { send(envelope(request).put("status", "error").put("error", JSONObject().put("code", code)).toString()) }
    }
}
