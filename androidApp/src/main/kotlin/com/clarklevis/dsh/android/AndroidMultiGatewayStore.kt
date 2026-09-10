package com.clarklevis.dsh.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.android.platform.AndroidGatewayCredentialStore
import com.clarklevis.dsh.android.platform.AndroidGatewayPreferences
import com.clarklevis.dsh.android.platform.AndroidNetworkMonitor
import com.clarklevis.dsh.android.platform.OkHttpGatewayTransport
import com.clarklevis.dsh.shared.gateway.GatewayConnectionState
import com.clarklevis.dsh.shared.gateway.GatewayIdentity
import com.clarklevis.dsh.shared.gateway.GatewayPairingPayloadParser
import com.clarklevis.dsh.shared.gateway.GatewayProfile
import com.clarklevis.dsh.shared.platform.GatewayConnectionSpec
import com.clarklevis.dsh.shared.platform.GatewayCredentialStore
import com.clarklevis.dsh.shared.platform.GatewayTransportEvent
import com.clarklevis.dsh.shared.platform.GatewayTransportState
import com.clarklevis.dsh.shared.protocol.GatewayFrame
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_ENDPOINTS = 16
private const val MAX_ALIAS_LENGTH = 80
private const val PAIRING_TIMEOUT_MS = 20_000L
private const val PROBE_INTERVAL_MS = 30_000L
private const val PROBE_TIMEOUT_MS = 3_000L
private const val PROBE_CONCURRENCY = 2

/** Application 拥有资料目录；每个激活代次独立创建 Runtime 和所有业务投影。 */
class AndroidMultiGatewayStore(private val application: Application) {
    private val profileJson = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = application.getSharedPreferences("gateway_profiles_v1", Context.MODE_PRIVATE)
    private val credentials = AndroidGatewayCredentialStore(application)
    private val network = AndroidNetworkMonitor(application)
    private val legacyPreferences = AndroidGatewayPreferences(application)
    private val scopedPreferences = mutableMapOf<String, AndroidGatewayPreferences>()
    private val switching = Mutex()
    private var pairingJob: Job? = null
    private var presenceJob: Job? = null
    private var activeObservation: Job? = null
    var profiles by mutableStateOf<List<GatewayProfile>>(emptyList())
        private set
    var activeGraph by mutableStateOf(graph(null))
        private set
    var activeId by mutableStateOf<String?>(null)
        private set
    var onlineIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var error by mutableStateOf<String?>(null)
    var pairing by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    val activeProfile: GatewayProfile? get() = profiles.firstOrNull { it.localId == activeId }

    init {
        scope.launch {
            runCatching {
                val raw = preferences.getString("profiles", null)
                if (raw != null) {
                    profiles = profileJson.decodeFromString(raw)
                    require(profiles.map { it.localId }.distinct().size == profiles.size)
                    profiles.forEach { profile ->
                        GatewayIdentity.validate(null, profile.localId)
                        require(profile.endpoints.isNotEmpty() && profile.endpoints.size <= MAX_ENDPOINTS)
                    }
                } else {
                    val old = legacyPreferences.load()
                    if (credentials.loadToken(old.endpoint) != null || old.sessionsJson != null) {
                        val profile =
                            GatewayProfile(
                                UUID.randomUUID().toString(),
                                gatewayName = GatewayIdentity.host(old.endpoint),
                                endpoints = listOf(old.endpoint)
                            )
                        scoped(profile.localId).update(old)
                        credentials.loadToken(old.endpoint)?.let { credentials.saveToken("host:${profile.localId}", it) }
                        withContext(Dispatchers.IO) {
                            val source = application.cacheDir.resolve("gateway-image-attachments")
                            val target = application.cacheDir.resolve("gateway-image-attachments/${profile.localId}")
                            // 只迁移旧缓存文件，避免递归复制目录到其自身。
                            target.mkdirs()
                            source.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(target.resolve(it.name), overwrite = false) }
                            val oldDownloads = application.getSharedPreferences("workspace_download_locations", Context.MODE_PRIVATE)
                            val newDownloads =
                                application.getSharedPreferences(
                                    "workspace_download_locations_${profile.localId}",
                                    Context.MODE_PRIVATE
                                )
                            val edit = newDownloads.edit()
                            oldDownloads.all.forEach { (key, value) ->
                                if (value is String) {
                                    edit.putString(key, value)
                                }
                            }
                            check(edit.commit())
                        }
                        profiles = listOf(profile)
                        persist()
                    }
                }
                val selected = profiles.firstOrNull { it.localId == preferences.getString("active", null) } ?: profiles.firstOrNull()
                if (selected != null) {
                    activate(selected, connect = false)
                } else {
                    activeGraph.pairingHandler = ::pair
                }
            }.onFailure { error = "主机资料读取或迁移失败：${it.message}" }
            ready = true
        }
    }

    private fun scoped(id: String) = scopedPreferences.getOrPut(id) { AndroidGatewayPreferences(application, id) }

    private fun graph(profile: GatewayProfile?, verified: suspend (GatewayFrame) -> Unit = {}): AndroidAppGraph = AndroidAppGraph(
        application,
        preferencesOverride = profile?.let { scoped(it.localId) } ?: scoped("unpaired"),
        credentialStoreOverride = profile?.let { ScopedCredentials(credentials, it.localId) } ?: ScopedCredentials(credentials, "unpaired"),
        networkMonitorOverride = network,
        gatewayLocalId = profile?.localId ?: "unpaired",
        expectedGatewayId = profile?.gatewayId,
        trustedEndpoints = profile?.endpoints.orEmpty(),
        onIdentity = { frame, endpoint ->
            if (profile != null) {
                withContext(Dispatchers.Main.immediate) {
                    require(
                        profiles.none {
                            it.localId != profile.localId && frame.gatewayId != null &&
                                it.gatewayId.equals(frame.gatewayId, true)
                        }
                    ) {
                        "网关身份已属于另一条记录"
                    }
                    verified(frame)
                    if (frame.kind == "hello" && activeId == profile.localId && !pairing) {
                        updateIdentity(profile, frame, endpoint)
                    }
                }
            }
        }
    ).also {
        it.pairingHandler = ::pair
        it.gatewayDisplayName = profile?.displayName.orEmpty()
    }

    fun select(profile: GatewayProfile) {
        if (profiles.none { it.localId == profile.localId }) return
        cancelPairing()
        scope.launch {
            switching.withLock {
                runCatching { activate(profile, connect = true) }.onFailure { error = it.message }
            }
        }
    }

    private suspend fun activate(profile: GatewayProfile, connect: Boolean, prepared: AndroidAppGraph? = null) {
        if (profiles.none { it.localId == profile.localId } && prepared == null) return
        if (prepared == null && activeId == profile.localId &&
            activeGraph.gatewayRuntime.state.value.connection == GatewayConnectionState.CONNECTED
        ) {
            return
        }
        stopPresence()
        val old = activeGraph
        withContext(old.gatewayDispatcher) { old.gatewayRuntime.disconnect() }
        old.stateHolder.close()
        old.applicationScope.cancel()
        old.gatewayScope.cancel()
        val stored = scoped(profile.localId).load()
        scoped(profile.localId).update(stored.copy(endpoint = profile.connectionEndpoints.first()))
        activeId = profile.localId
        activeGraph = prepared ?: graph(profile)
        check(preferences.edit().putString("active", profile.localId).commit()) { "无法保存当前主机" }
        activeObservation?.cancel()
        activeObservation =
            scope.launch {
                activeGraph.gatewayRuntime.state.collect { state ->
                    onlineIds =
                        if (state.connection ==
                            GatewayConnectionState.CONNECTED
                        ) {
                            onlineIds + profile.localId
                        } else {
                            onlineIds - profile.localId
                        }
                    if (state.shouldKeepAliveInBackground) {
                        GatewayConnectionService.start(application)
                    } else {
                        GatewayConnectionService.stop(application)
                    }
                }
            }
        if (connect && prepared == null) {
            withContext(activeGraph.gatewayDispatcher) {
                activeGraph.gatewayRuntime.connect(profile.connectionEndpoints.first())
            }
        }
    }

    // 聚合平台解析、存储及网络错误；协程取消必须向上传递。
    @Suppress("TooGenericExceptionCaught")
    fun pair(raw: String) {
        val previous = pairingJob
        previous?.cancel()
        pairingJob =
            scope.launch {
                previous?.join()
                var candidate: AndroidAppGraph? = null
                var committed = false
                try {
                    val payload = GatewayPairingPayloadParser.parse(raw, System.currentTimeMillis())
                    val endpoints = GatewayIdentity.endpoints(payload)
                    var profile = pairingProfile(payload, endpoints)
                    pairing = true
                    val identity = CompletableDeferred<GatewayFrame>()
                    candidate = graph(profile) { frame ->
                        if (frame.kind == "paired") {
                            profile = profile.copy(remoteDeviceId = frame.device?.id)
                            if (activeId == profile.localId) {
                                val old = activeGraph
                                withContext(old.gatewayDispatcher) { old.gatewayRuntime.disconnect() }
                            }
                        }
                        if (frame.kind == "hello") identity.complete(frame)
                    }
                    withContext(candidate.gatewayDispatcher) { candidate.gatewayRuntime.pair(raw) }
                    val state =
                        withTimeout(PAIRING_TIMEOUT_MS) {
                            candidate.gatewayRuntime.state.first {
                                it.connection == GatewayConnectionState.CONNECTED ||
                                    it.connection == GatewayConnectionState.FAILED
                            }
                        }
                    check(state.connection == GatewayConnectionState.CONNECTED) { state.lastError ?: "配对失败" }
                    val hello = identity.await()
                    profile = updated(profile, hello, endpoints.first())
                    profiles = profiles.filterNot { it.localId == profile.localId } + profile
                    persist()
                    candidate.pairingHandler = ::pair
                    candidate.gatewayDisplayName = profile.displayName
                    switching.withLock { activate(profile, connect = false, prepared = candidate) }
                    committed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = "配对失败：${failure.message}"
                } finally {
                    if (!committed) {
                        candidate?.let { graph ->
                            withContext(NonCancellable + graph.gatewayDispatcher) { graph.gatewayRuntime.disconnect() }
                            graph.applicationScope.cancel()
                            graph.gatewayScope.cancel()
                        }
                    }
                    pairing = false
                }
            }
    }

    private fun pairingProfile(payload: com.clarklevis.dsh.shared.protocol.GatewayPairingPayload, endpoints: List<String>): GatewayProfile {
        val existing = profiles.firstOrNull {
            if (payload.gatewayId != null) {
                payload.gatewayId.equals(it.gatewayId, true)
            } else {
                it.gatewayId == null && endpoints.first() in it.endpoints
            }
        }
        val profile = existing ?: GatewayProfile(
            UUID.randomUUID().toString(),
            payload.gatewayId?.lowercase(),
            payload.gatewayName ?: GatewayIdentity.host(endpoints.first()),
            endpoints = endpoints,
            server = !GatewayIdentity.isLocal(GatewayIdentity.host(endpoints.first()))
        )
        return profile.copy(endpoints = endpoints, preferredEndpoint = endpoints.first())
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
    }

    fun remove(profile: GatewayProfile) {
        remove(listOf(profile))
    }

    fun remove(selectedProfiles: List<GatewayProfile>) {
        val requestedIds = selectedProfiles.mapTo(mutableSetOf()) { it.localId }
        if (requestedIds.isEmpty()) return
        cancelPairing()
        stopPresence()
        scope.launch {
            switching.withLock {
                runCatching {
                    val knownIds = profiles.mapTo(mutableSetOf()) { it.localId }.intersect(requestedIds)
                    if (knownIds.isEmpty()) return@runCatching
                    if (activeId?.let(knownIds::contains) == true) {
                        activeObservation?.cancel()
                        val old = activeGraph
                        withContext(old.gatewayDispatcher) { old.gatewayRuntime.disconnect() }
                        old.stateHolder.close()
                        old.applicationScope.cancel()
                        old.gatewayScope.cancel()
                        activeId = null
                        activeGraph = graph(null)
                        check(preferences.edit().remove("active").commit())
                        GatewayConnectionService.stop(application)
                    }
                    knownIds.forEach { credentials.deleteToken("host:$it") }
                    profiles = profiles.filterNot { it.localId in knownIds }
                    onlineIds = onlineIds - knownIds
                    persist()
                }.onFailure { error = it.message }
            }
        }
    }

    fun edit(profile: GatewayProfile, alias: String, server: Boolean) {
        profiles =
            profiles.map {
                if (it.localId ==
                    profile.localId
                ) {
                    it.copy(alias = alias.trim().take(MAX_ALIAS_LENGTH), server = server)
                } else {
                    it
                }
            }
        activeGraph.gatewayDisplayName = activeProfile?.displayName.orEmpty()
        runCatching { persist() }.onFailure { error = it.message }
    }

    private fun updated(profile: GatewayProfile, frame: GatewayFrame, endpoint: String) = profile.copy(
        gatewayId = frame.gatewayId?.lowercase() ?: profile.gatewayId,
        gatewayName = frame.gatewayName?.takeIf { it.isNotBlank() } ?: profile.gatewayName,
        preferredEndpoint = endpoint,
        lastConnectedAt = System.currentTimeMillis()
    )

    private fun updateIdentity(profile: GatewayProfile, frame: GatewayFrame, endpoint: String) {
        profiles = profiles.map { if (it.localId == profile.localId) updated(it, frame, endpoint) else it }
        persist()
    }

    fun startPresence() {
        stopPresence()
        presenceJob =
            scope.launch {
                while (isActive) {
                    profiles.chunked(PROBE_CONCURRENCY).forEach { batch ->
                        coroutineScope {
                            batch
                                .map { profile ->
                                    async {
                                        try {
                                            probe(profile)
                                        } catch (
                                            cancelled: CancellationException
                                        ) {
                                            throw cancelled
                                        } catch (_: Throwable) {
                                            onlineIds = onlineIds - profile.localId
                                        }
                                    }
                                }.awaitAll()
                        }
                    }
                    delay(PROBE_INTERVAL_MS)
                }
            }
    }

    private suspend fun probe(profile: GatewayProfile) {
        if (profile.localId == activeId && activeGraph.gatewayRuntime.state.value.connection == GatewayConnectionState.CONNECTED) return
        onlineIds = onlineIds - profile.localId
        val token = credentials.loadToken("host:${profile.localId}") ?: return
        for (endpoint in profile.connectionEndpoints) {
            currentCoroutineContext().ensureActive()
            val transport = OkHttpGatewayTransport()
            try {
                val online =
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        coroutineScope {
                            val response =
                                async(start = CoroutineStart.UNDISPATCHED) {
                                    transport.events.first { event ->
                                        event is GatewayTransportEvent.Frame ||
                                            event is GatewayTransportEvent.State && event.value is GatewayTransportState.Failed
                                    }
                                }
                            transport.open(
                                GatewayConnectionSpec(
                                    1,
                                    endpoint,
                                    credentials.loadOrCreateDeviceId(),
                                    bearerToken = token,
                                    channel = "control"
                                )
                            )
                            val frame =
                                (response.await() as? GatewayTransportEvent.Frame)
                                    ?.value
                                    ?.text
                                    ?.let(com.clarklevis.dsh.shared.protocol.GatewayWireDecoder::decode)
                            acceptsProbe(profile, frame)
                        }
                    } == true
                if (online) {
                    onlineIds = onlineIds + profile.localId
                    return
                }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (
                _: Throwable
            ) {
                // 单个主机失败不阻塞其他主机。
            } finally {
                withContext(NonCancellable) { transport.close() }
            }
        }
    }

    private fun acceptsProbe(profile: GatewayProfile, frame: GatewayFrame?): Boolean = frame?.kind == "hello" && runCatching {
        GatewayIdentity.validate(profile.gatewayId, frame.gatewayId)
        require(
            profiles.none {
                it.localId != profile.localId && frame.gatewayId != null && it.gatewayId.equals(frame.gatewayId, true)
            }
        )
    }.isSuccess

    fun stopPresence() {
        presenceJob?.cancel()
        presenceJob = null
        onlineIds =
            if (activeGraph.gatewayRuntime.state.value.connection ==
                GatewayConnectionState.CONNECTED
            ) {
                setOfNotNull(activeId)
            } else {
                emptySet()
            }
    }

    private fun persist() {
        check(preferences.edit().putString("profiles", profileJson.encodeToString(profiles)).commit()) { "无法保存主机资料" }
    }
}

private class ScopedCredentials(private val delegate: GatewayCredentialStore, id: String) : GatewayCredentialStore {
    private val key = "host:$id"

    override suspend fun loadOrCreateDeviceId() = delegate.loadOrCreateDeviceId()

    override suspend fun loadToken(endpoint: String) = delegate.loadToken(key)

    override suspend fun saveToken(endpoint: String, token: String) = delegate.saveToken(key, token)

    override suspend fun deleteToken(endpoint: String) = delegate.deleteToken(key)
}
