package com.clarklevis.dsh.shared.cache

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.clarklevis.dsh.shared.facade.CachedHistoryWindow
import com.clarklevis.dsh.shared.facade.SharedHistoryStore
import com.clarklevis.dsh.shared.facade.SharedMviDispatchResult
import com.clarklevis.dsh.shared.facade.SharedOfflineCatalogCodec
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import com.clarklevis.dsh.shared.protocol.SessionEvent
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
private data class ChatSearchResult(val sessionId: String, val seq: Int, val time: Double, val snippet: String)

@Serializable
private data class ChatSearchResponse(
    val coverage: String = "cached-only",
    val hasMore: Boolean = false,
    val results: List<ChatSearchResult> = emptyList()
)

@Serializable
private data class ChatCacheStatistics(
    val logicalBytes: Long,
    val sessionCount: Long,
    val eventCount: Long,
    val memoryBytes: Long,
    val memorySessions: Int,
    val memoryHits: Long,
    val diskHits: Long,
    val misses: Long,
    val epoch: Long,
    val writeAdmissionRefusals: Long,
    val minimumFreeReserveBytes: Long
)

/** L1 保存已校验的类型窗口，L2 使用共用 Room。网络权威和 late-cache 仲裁由 HistoryStore 负责。 */
// 对外提供跨平台缓存门面；SQL 与容量事务仍集中在 DAO。
@Suppress("TooManyFunctions", "LargeClass")
class SharedChatHistoryCache internal constructor(
    private val database: ChatCacheDatabase,
    private val maximumBytes: Long,
    private val targetBytes: Long,
    private val availableBytesProvider: () -> Long? = { Long.MAX_VALUE }
) {
    private val metadata = Mutex()
    private val writer = Mutex()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = ChatMemoryCache()
    private val protected = mutableMapOf<String, Set<String>>()
    private val generations = mutableMapOf<ChatCacheKey, Long>()
    private val revisions = mutableMapOf<ChatCacheKey, Long>()
    private val touches = mutableMapOf<ChatCacheKey, Long>()
    private val pendingTouches = mutableMapOf<ChatCacheKey, Long>()
    private var accessClock = 0L
    private var accessInitialized = false
    private val loads = mutableMapOf<ChatCacheKey, CompletableDeferred<CachedHistoryWindow?>>()
    private var closed = false
    private var cacheEpoch = 0L
    private var readRevision = 0L
    private var memoryTrimRevision = 0L
    private var writeAdmissionRefusals = 0L
    private var memoryHits = 0L
    private var diskHits = 0L
    private var misses = 0L
    private val dao = database.cacheDao()

    init {
        require(targetBytes > 0 && maximumBytes >= targetBytes)
        chatCacheLog("database-configured", "maximumBytes=$maximumBytes targetBytes=$targetBytes journal=truncate")
    }

    /** 在平台捕获目录快照时获取；clear/forget 后旧快照不能重新落盘。 */
    @Throws(Exception::class)
    suspend fun offlineCatalogTicket(gatewayId: String): String = metadata.withLock {
        if (closed || !ChatPrefetchPolicy.validIdentifier(gatewayId)) return@withLock ""
        rollMetadataIfNeeded()
        val key = ChatCacheKey(gatewayId, CATALOG_KEY)
        val revision = (revisions[key] ?: 0L) + 1L
        revisions[key] = revision
        wireJson.encodeToString(ChatPrefetchTicket(gatewayId, CATALOG_KEY, cacheEpoch, revision))
    }

    @Throws(Exception::class)
    suspend fun saveOfflineCatalog(gatewayId: String, catalogJson: String, ticket: String): Boolean =
        recover("catalog-save", TimeSource.Monotonic.markNow(), false) {
            val canonical = SharedOfflineCatalogCodec().normalize(catalogJson) ?: return@recover false
            val token = wireJson.decodeFromString<ChatPrefetchTicket>(ticket)
            if (token.gatewayId != gatewayId || token.sessionId != CATALOG_KEY) return@recover false
            writer.withLock {
                val valid = metadata.withLock {
                    !closed && cacheEpoch == token.epoch && revisions[ChatCacheKey(gatewayId, CATALOG_KEY)] == token.revision
                }
                if (!valid || !admitCacheWrite(canonical.utf8Size())) return@withLock false
                val time = nextAccessTime()
                dao.replaceCatalog(OfflineCatalogEntity(gatewayId, canonical, canonical.utf8Size(), time))
                chatCacheLog("catalog-saved", "bytes=${canonical.utf8Size()}")
                true
            }
        }

    @Throws(Exception::class)
    suspend fun loadOfflineCatalog(gatewayId: String): String? = recover("catalog-load", TimeSource.Monotonic.markNow(), null) {
        if (!ChatPrefetchPolicy.validIdentifier(gatewayId)) return@recover null
        val startingEpoch = metadata.withLock { cacheEpoch }
        writer.withLock {
            if (metadata.withLock { closed || cacheEpoch != startingEpoch }) return@withLock null
            val row = dao.offlineCatalog(gatewayId) ?: return@withLock null
            val canonical = SharedOfflineCatalogCodec().normalize(row.catalogJson) ?: return@withLock null
            dao.touchCatalog(gatewayId, nextAccessTime())
            canonical
        }
    }

    /** 忘记网关必须同时调用 clear(gatewayId) 与此方法；普通清正文缓存保留导航。 */
    @Throws(Exception::class)
    suspend fun forgetOfflineCatalog(gatewayId: String) = writer.withLock {
        metadata.withLock { cacheEpoch += 1 }
        try {
            dao.removeCatalog(gatewayId)
        } finally {
            withContext(NonCancellable) { metadata.withLock { cacheEpoch += 1 } }
        }
        chatCacheLog("catalog-forgotten")
    }

    @Throws(Exception::class)
    suspend fun epoch(): Long = metadata.withLock { cacheEpoch }

    /** 仅由用户实际进入会话的入口调用，预加载、恢复和后台查询不得调用。 */
    @Throws(Exception::class)
    suspend fun recordSessionOpen(gatewayId: String, sessionId: String) =
        recordSessionOpenAt(gatewayId, sessionId, Clock.System.now().toEpochMilliseconds() / 1000.0)

    internal suspend fun recordSessionOpenAt(gatewayId: String, sessionId: String, time: Double) = writer.withLock {
        if (!ChatPrefetchPolicy.validIdentifier(gatewayId) ||
            !ChatPrefetchPolicy.validIdentifier(sessionId) ||
            !time.isFinite()
        ) {
            return@withLock
        }
        recover("heat-record", TimeSource.Monotonic.markNow(), Unit) {
            if (!metadata.withLock { closed } && admitCacheWrite(HEAT_WRITE_BYTES)) dao.recordOpen(gatewayId, sessionId, time)
        }
    }

    @Throws(Exception::class)
    suspend fun planPrefetch(gatewayId: String, candidatesJson: String, selectedSessionId: String?): String =
        planPrefetchAt(gatewayId, candidatesJson, selectedSessionId, Clock.System.now().toEpochMilliseconds() / 1000.0)

    internal suspend fun planPrefetchAt(gatewayId: String, candidatesJson: String, selectedSessionId: String?, time: Double): String =
        recover("prefetch-plan", TimeSource.Monotonic.markNow(), "[]") {
            if (!ChatPrefetchPolicy.validIdentifier(gatewayId) ||
                candidatesJson.utf8Size() > MAXIMUM_CANDIDATE_BYTES ||
                !time.isFinite() ||
                metadata.withLock { closed }
            ) {
                return@recover "[]"
            }
            val candidates = wireJson.decodeFromString<List<ChatPrefetchCandidate>>(candidatesJson).take(MAXIMUM_CANDIDATES)
            val heat = writer.withLock { dao.sessionHeat(gatewayId) }
            val result = ChatPrefetchPolicy.rank(candidates, heat, selectedSessionId, time)
            chatCacheLog("prefetch-planned", "candidates=${candidates.size} selected=${result.size}")
            wireJson.encodeToString(result)
        }

    @Throws(Exception::class)
    suspend fun prefetchTicket(gatewayId: String, sessionId: String): String = metadata.withLock {
        if (closed || !ChatPrefetchPolicy.validIdentifier(gatewayId) || !ChatPrefetchPolicy.validIdentifier(sessionId)) return@withLock ""
        rollMetadataIfNeeded()
        val key = ChatCacheKey(gatewayId, sessionId)
        // 登记有界 revision，取消可精确拒绝正在解析/等待 writer 的响应。
        val revision = (revisions[key] ?: 0L) + 1L
        revisions[key] = revision
        wireJson.encodeToString(ChatPrefetchTicket(gatewayId, sessionId, cacheEpoch, revision))
    }

    @Throws(Exception::class)
    suspend fun cancelPrefetch(gatewayId: String, sessionId: String, ticket: String) {
        val decoded = runCatching { wireJson.decodeFromString<ChatPrefetchTicket>(ticket) }.getOrNull() ?: return
        if (decoded.gatewayId != gatewayId || decoded.sessionId != sessionId) return
        writer.withLock {
            metadata.withLock {
                val key = ChatCacheKey(gatewayId, sessionId)
                if (cacheEpoch == decoded.epoch && revisions[key] == decoded.revision) revisions[key] = decoded.revision + 1L
            }
        }
    }

    /** 隔离的最新页：不接入正常历史基线、不建立订阅、不占 L1，不从后台访问增加用户热度。 */
    @Throws(Exception::class)
    suspend fun acceptPrefetchHistory(gatewayId: String, sessionId: String, rawJson: String, ticket: String): Boolean =
        withContext(Dispatchers.Default) {
            recover("prefetch-accept", TimeSource.Monotonic.markNow(), false) {
                if (rawJson.utf8Size() > ChatPrefetchPolicy.MAXIMUM_RESPONSE_BYTES) return@recover false
                val decoded = wireJson.decodeFromString<ChatPrefetchTicket>(ticket)
                if (decoded.gatewayId != gatewayId || decoded.sessionId != sessionId) return@recover false
                val root = wireJson.parseToJsonElement(rawJson) as? JsonObject ?: return@recover false
                if (listOf("beforeSeq", "beforeSequence").any { root[it] != null && root[it] != JsonNull }) return@recover false
                val frame = GatewayWireDecoder.decode(rawJson)
                val rawEvents = frame.events ?: return@recover false
                if (frame.kind != "history" ||
                    frame.sessionId != sessionId ||
                    rawEvents.size > ChatPrefetchPolicy.MAXIMUM_RESPONSE_EVENTS ||
                    frame.historyFormatVersion?.let { it !in SUPPORTED_HISTORY_FORMATS } == true
                ) {
                    return@recover false
                }
                val records = rawEvents.map { it.normalized(sessionId) }
                val window = CachedHistoryWindow(
                    sessionId = sessionId,
                    historyFormatVersion = frame.historyFormatVersion,
                    persistentCursor = frame.cursor ?: records.lastOrNull()?.seq,
                    hasMore = frame.hasMore ?: false,
                    nextBeforeSequence = frame.nextBeforeSeq,
                    events = records.filter { it.event.type != "assistant/chunk" }
                )
                window.validate(sessionId)
                val json = ChatCacheWindowPolicy.exportTail(
                    window,
                    window.events,
                    ChatPrefetchPolicy.MAXIMUM_RESPONSE_BYTES,
                    ChatPrefetchPolicy.MAXIMUM_RESPONSE_EVENTS
                ) ?: return@recover false
                val accepted = saveWindowAtEpoch(gatewayId, sessionId, json, null, decoded.epoch, decoded.revision, false)
                chatCacheLog("prefetch-completed", "accepted=$accepted events=${records.size}")
                accepted
            }
        }

    @Throws(Exception::class)
    suspend fun protect(gatewayId: String, sessionIds: List<String>) = metadata.withLock {
        if (sessionIds.isEmpty()) protected.remove(gatewayId) else protected[gatewayId] = sessionIds.toSet()
        chatCacheLog("protection-changed", "sessions=${sessionIds.size}")
    }

    @Throws(Exception::class)
    suspend fun load(gatewayId: String, sessionId: String): String? = loadWindow(gatewayId, sessionId)?.let { wireJson.encodeToString(it) }

    /** 端上须在自己的 history actor/主线程上调用，避免热路径的窗口 JSON encode/decode。 */
    @Throws(Exception::class)
    suspend fun hydrateToStore(gatewayId: String, sessionId: String, store: SharedHistoryStore): SharedMviDispatchResult? {
        val window = loadWindow(gatewayId, sessionId) ?: return null
        return store.hydrateTypedCachedWindow(sessionId, window)
    }

    @Throws(Exception::class)
    suspend fun loadTypedWindow(gatewayId: String, sessionId: String): CachedHistoryWindow? = loadWindow(gatewayId, sessionId)

    // 将 single-flight 的登记、读与提交校验放在一起，便于核对取消/epoch/revision 的完整生命周期。
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun loadWindow(gatewayId: String, sessionId: String): CachedHistoryWindow? {
        if (metadata.withLock { closed }) return null
        val key = ChatCacheKey(gatewayId, sessionId)
        var owner = false
        var ticket = 0L
        var revision = 0L
        var readTicket = 0L
        var hot: CachedHistoryWindow? = null
        val deferred = metadata.withLock {
            ticket = cacheEpoch
            revision = revisions[key] ?: 0L
            readTicket = readRevision
            hot = memory.get(key)
            if (hot != null) {
                memoryHits += 1
                null
            } else {
                loads[key] ?: if (loads.size >= MAXIMUM_PENDING_LOADS) {
                    CompletableDeferred<CachedHistoryWindow?>().also { it.complete(null) }
                } else {
                    CompletableDeferred<CachedHistoryWindow?>().also {
                        loads[key] = it
                        owner = true
                    }
                }
            }
        }
        hot?.let {
            touchEventually(key, ticket)
            chatCacheLog("memory-hit", "events=${it.events.size}")
            return metadata.withLock {
                if (closed || cacheEpoch != ticket || readRevision != readTicket) {
                    null
                } else if ((revisions[key] ?: 0L) == revision) {
                    it
                } else {
                    memory.get(key)
                }
            }
        }
        if (!owner) return deferred!!.await().takeIf { epoch() == ticket }
        val started = TimeSource.Monotonic.markNow()
        try {
            val window = recover("load", started, null) {
                val rows = dao.load(gatewayId, sessionId, nextAccessTime()) ?: return@recover null
                val header = wireJson.decodeFromString<CachedHistoryWindow>(rows.header.headerJson)
                header.copy(events = rows.events.map { wireJson.decodeFromString<SessionEvent>(it.eventJson) })
                    .also { it.validate(sessionId) }
            }
            val estimate = window?.let(::estimateWindowBytes) ?: 0
            val accepted = metadata.withLock {
                if (closed || cacheEpoch != ticket || readRevision != readTicket) {
                    null
                } else if ((revisions[key] ?: 0L) != revision) {
                    memory.get(key)
                } else {
                    window.also {
                        if (it == null) {
                            misses += 1
                        } else {
                            diskHits += 1
                            memory.put(key, it, estimate)
                        }
                    }
                }
            }
            deferred!!.complete(accepted)
            chatCacheLog(if (accepted == null) "load-miss" else "disk-hit", "elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
            return accepted
        } catch (cancelled: CancellationException) {
            deferred!!.complete(null)
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                metadata.withLock { if (loads[key] === deferred) loads.remove(key) }
                if (deferred?.isCompleted == false) deferred.complete(null)
            }
        }
    }

    @Throws(Exception::class)
    suspend fun save(gatewayId: String, sessionId: String, json: String, generation: Long): Boolean =
        saveAtEpoch(gatewayId, sessionId, json, generation, epoch())

    /** 在安排 debounce/后台快照时取得 epoch，清缓存前排队的旧快照不能重新落盘。 */
    // 预处理在锁外，提交和 finally 失效必须保持可见的连续事务边界。
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Throws(Exception::class)
    suspend fun saveAtEpoch(gatewayId: String, sessionId: String, json: String, generation: Long, expectedEpoch: Long): Boolean =
        saveWindowAtEpoch(gatewayId, sessionId, json, generation, expectedEpoch)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun saveWindowAtEpoch(
        gatewayId: String,
        sessionId: String,
        json: String,
        generation: Long?,
        expectedEpoch: Long,
        expectedRevision: Long? = null,
        warmMemory: Boolean = true
    ): Boolean {
        val started = TimeSource.Monotonic.markNow()
        val key = ChatCacheKey(gatewayId, sessionId)
        return recover("save", started, false) {
            val size = json.encodeToByteArray().size.toLong()
            if (gatewayId.isBlank() || size > minOf(ChatCacheWindowPolicy.DEFAULT_MAXIMUM_BYTES, targetBytes)) return@recover false
            // 解析/构建索引不占用 writer 和 metadata 锁。
            val window = wireJson.decodeFromString<CachedHistoryWindow>(json).also { it.validate(sessionId) }
            val index = searchIndex(gatewayId, window)
            val logicalBytes = size + index.bytes
            if (logicalBytes > targetBytes) return@recover false
            val events = window.events.map { CachedEventEntity(gatewayId, sessionId, it.seq, wireJson.encodeToString(it)) }
            writer.withLock {
                if (expectedRevision != null) {
                    val old = dao.session(gatewayId, sessionId)?.let { wireJson.decodeFromString<CachedHistoryWindow>(it.headerJson) }
                    if (old?.persistentCursor != null &&
                        old.historyFormatVersion == window.historyFormatVersion &&
                        window.persistentCursor != null &&
                        old.persistentCursor >= window.persistentCursor
                    ) {
                        return@withLock false
                    }
                }
                if (!admitCacheWrite(logicalBytes)) return@withLock false
                var memoryTicket = 0L
                val protection = metadata.withLock {
                    rollMetadataIfNeeded()
                    if (closed ||
                        cacheEpoch != expectedEpoch ||
                        (generation != null && generation < (generations[key] ?: Long.MIN_VALUE)) ||
                        (expectedRevision != null && revisions[key] != expectedRevision)
                    ) {
                        return@withLock null
                    }
                    if (generation != null) generations[key] = generation
                    revisions[key] = (revisions[key] ?: 0L) + 1
                    memory.remove(key)
                    memoryTicket = memoryTrimRevision
                    protected.toMap()
                } ?: return@withLock false
                val header =
                    CachedSessionEntity(
                        gatewayId,
                        sessionId,
                        wireJson.encodeToString(window.copy(events = emptyList())),
                        logicalBytes,
                        nextAccessTime()
                    )
                flushPendingTouches()
                var committed: CacheWriteResult? = null
                val result = try {
                    dao.replace(CacheWindowWrite(header, events, index.texts, index.grams), protection, maximumBytes, targetBytes).also {
                        committed =
                            it
                    }
                } finally {
                    withContext(NonCancellable) {
                        metadata.withLock {
                            // 写期间开始的读可能看到旧行；提交或取消后都必须使其失效。
                            revisions[key] = (revisions[key] ?: 0L) + 1
                            memory.remove(key)
                            loads.remove(key)
                            if (committed == null || committed!!.evictedSessions > 0) {
                                memory.clear()
                                readRevision += 1
                                loads.clear()
                            }
                        }
                    }
                }
                metadata.withLock {
                    // 磁盘淘汰必须同步使 L1 失效，否则可继续命中已删除的全文。
                    if (result.evictedSessions > 0) {
                        memory.clear()
                        readRevision += 1
                        loads.clear()
                    }
                    if (warmMemory && memoryTrimRevision == memoryTicket) memory.put(key, window, size)
                }
                chatCacheLog(
                    "save-committed",
                    "events=${events.size} bytes=$logicalBytes totalBytes=${result.totalBytes} evicted=${result.evictedSessions}"
                )
                true
            }
        }
    }

    /** 最近一页按 seq 返回，hasMore 同时反映本地剩余页和网关历史缺口。 */
    @Throws(Exception::class)
    suspend fun loadPage(gatewayId: String, sessionId: String, beforeSequence: Int?, limit: Int): String? =
        loadTypedPage(gatewayId, sessionId, beforeSequence, limit)?.let { wireJson.encodeToString(it) }

    @Suppress("CyclomaticComplexMethod")
    @Throws(Exception::class)
    suspend fun loadTypedPage(gatewayId: String, sessionId: String, beforeSequence: Int?, limit: Int): CachedHistoryWindow? =
        recover("load-page", TimeSource.Monotonic.markNow(), null) {
            if (metadata.withLock { closed }) return@recover null
            val ticket = epoch()
            val key = ChatCacheKey(gatewayId, sessionId)
            val revision = metadata.withLock { revisions[key] ?: 0L }
            val readTicket = metadata.withLock { readRevision }
            val count = limit.coerceIn(1, MAXIMUM_PAGE_EVENTS)
            if (beforeSequence == null) {
                val hot = metadata.withLock { memory.get(key)?.also { memoryHits += 1 } }
                if (hot != null) {
                    touchEventually(key, ticket)
                    val tail = hot.events.takeLast(count)
                    return@recover hot.copy(
                        events = tail,
                        hasMore = hot.events.size > count || hot.hasMore,
                        nextBeforeSequence = if (hot.events.size > count) tail.firstOrNull()?.seq else hot.nextBeforeSequence
                    )
                        .takeIf {
                            metadata.withLock {
                                !closed && cacheEpoch == ticket && readRevision == readTicket && (revisions[key] ?: 0L) == revision
                            }
                        }
                }
            }
            val rows = dao.loadPage(gatewayId, sessionId, beforeSequence, count + 1)
            if (rows == null) {
                metadata.withLock { if (cacheEpoch == ticket && !closed) misses += 1 }
                chatCacheLog("load-page-miss", "reason=not-cached")
                return@recover null
            }
            val hasLocalMore = rows.events.size > count
            val records = rows.events.take(count).asReversed().map { wireJson.decodeFromString<SessionEvent>(it.eventJson) }
            val header = wireJson.decodeFromString<CachedHistoryWindow>(rows.header.headerJson)
            val result = header.copy(
                events = records,
                hasMore = hasLocalMore || header.hasMore,
                nextBeforeSequence = if (hasLocalMore) records.firstOrNull()?.seq else header.nextBeforeSequence
            )
            result.validate(sessionId)
            val accepted = metadata.withLock {
                if (cacheEpoch != ticket || readRevision != readTicket || (revisions[key] ?: 0L) != revision) {
                    false
                } else {
                    diskHits += 1
                    true
                }
            }
            if (accepted) {
                touchEventually(key, ticket)
                chatCacheLog("disk-page-hit", "events=${records.size} hasMore=${result.hasMore}")
                result
            } else {
                null
            }
        }

    /** 三字符及以上走索引候选；一/二字符保留包含语义。所有用户输入均通过 Room 参数绑定。 */
    @Throws(Exception::class)
    suspend fun search(gatewayId: String, query: String, sessionId: String?, limit: Int): String =
        recover("search", TimeSource.Monotonic.markNow(), wireJson.encodeToString(ChatSearchResponse())) {
            val ticket = epoch()
            val normalized = query.trim().lowercase()
            if (normalized.isEmpty() ||
                normalized.length > MAXIMUM_QUERY_LENGTH
            ) {
                return@recover wireJson.encodeToString(ChatSearchResponse())
            }
            rebuildLegacySearchIndex()
            val count = limit.coerceIn(1, MAXIMUM_SEARCH_RESULTS)
            val units = unicodeUnits(normalized)
            val rows = if (units.size < TRIGRAM_LENGTH) {
                dao.searchShort(gatewayId, sessionId, normalized, count + 1)
            } else {
                dao.searchLong(gatewayId, sessionId, units.take(TRIGRAM_LENGTH).joinToString(""), normalized, count + 1)
            }
            // 极低磁盘余量下旧版本索引可能无法重建；仍检索其正文，保证迁移不造成漏搜。
            val fallback = dao.unindexedSessions().filter {
                it.gatewayId == gatewayId && (sessionId == null || it.sessionId == sessionId)
            }.flatMap { legacy ->
                dao.events(legacy.gatewayId, legacy.sessionId).mapNotNull { row ->
                    val event = wireJson.decodeFromString<SessionEvent>(row.eventJson)
                    val text = event.event.text
                    if (event.event.type in setOf("user/message", "assistant/message") && text != null && normalized in text.lowercase()) {
                        CachedSearchText(gatewayId, legacy.sessionId, event.seq, event.time, text, text.lowercase())
                    } else {
                        null
                    }
                }
            }
            val matches = (rows + fallback).distinctBy {
                it.sessionId to it.seq
            }.sortedWith(compareByDescending<CachedSearchText> { it.time }.thenBy { it.sessionId }.thenByDescending { it.seq })
            val results = matches.take(count).map { row ->
                val offset = row.text.indexOf(query.trim(), ignoreCase = true).coerceAtLeast(0)
                val start = (offset - SNIPPET_LEADING_CHARACTERS).coerceAtLeast(0)
                val end = (offset + normalized.length + SNIPPET_TRAILING_CHARACTERS).coerceAtMost(row.text.length)
                ChatSearchResult(row.sessionId, row.seq, row.time, row.text.substring(start.coerceAtMost(end), end))
            }
            if (epoch() != ticket) {
                wireJson.encodeToString(ChatSearchResponse())
            } else {
                wireJson.encodeToString(ChatSearchResponse(hasMore = matches.size > count, results = results))
            }
        }

    private suspend fun rebuildLegacySearchIndex() = writer.withLock {
        for (header in dao.unindexedSessions()) {
            recover("search-migrate", TimeSource.Monotonic.markNow(), Unit) {
                val metadata = wireJson.decodeFromString<CachedHistoryWindow>(header.headerJson)
                val window = metadata.copy(
                    events = dao.events(header.gatewayId, header.sessionId).map {
                        wireJson.decodeFromString<SessionEvent>(it.eventJson)
                    }
                )
                window.validate(header.sessionId)
                val index = searchIndex(header.gatewayId, window)
                if (admitCacheWrite(index.bytes)) dao.indexLegacy(header, index.texts, index.grams, index.bytes, maximumBytes)
            }
        }
    }

    @Throws(Exception::class)
    suspend fun statistics(): String = writer.withLock {
        val bytes = dao.totalBytes()
        val sessions = dao.sessionCount()
        val events = dao.eventCount()
        metadata.withLock {
            wireJson.encodeToString(
                ChatCacheStatistics(
                    bytes, sessions, events, memory.bytes, memory.size, memoryHits, diskHits, misses, cacheEpoch,
                    writeAdmissionRefusals, MINIMUM_FREE_RESERVE_BYTES
                )
            )
        }
    }

    /** OS 内存压力只释放可重建 L1；隔离已在读的旧回填，也不允许进行中的写再次暖入 L1。 */
    @Throws(Exception::class)
    suspend fun trimMemory() = metadata.withLock {
        memory.clear()
        readRevision += 1
        memoryTrimRevision += 1
        loads.clear()
        chatCacheLog("memory-trimmed", "reason=pressure")
    }

    @Throws(Exception::class)
    suspend fun clear(gatewayId: String?) = writer.withLock {
        metadata.withLock {
            cacheEpoch += 1
            memory.clear(gatewayId)
            loads.clear()
            touches.clear()
            pendingTouches.clear()
            generations.keys.filter { gatewayId == null || it.gatewayId == gatewayId }.forEach(generations::remove)
        }
        try {
            dao.clearCacheAndHeat(gatewayId)
        } finally {
            withContext(NonCancellable) {
                metadata.withLock {
                    cacheEpoch += 1
                    memory.clear(gatewayId)
                    loads.clear()
                }
            }
        }
        chatCacheLog("clear-completed", "scope=${if (gatewayId == null) "all" else "gateway"}")
    }

    @Throws(Exception::class)
    suspend fun trimToBytes(targetBytes: Long): Long = writer.withLock {
        val protection = metadata.withLock { protected.toMap() }
        flushPendingTouches()
        val reclaimed = try {
            dao.trim(targetBytes.coerceAtLeast(0), protection)
        } finally {
            withContext(NonCancellable) {
                metadata.withLock {
                    memory.clear()
                    readRevision += 1
                    loads.clear()
                }
            }
        }
        if (reclaimed > 0) {
            metadata.withLock {
                memory.clear()
                readRevision += 1
                loads.clear()
            }
        }
        chatCacheLog("trim-completed", "reclaimedBytes=$reclaimed")
        reclaimed
    }

    @Throws(Exception::class)
    suspend fun remove(gatewayId: String, sessionId: String, generation: Long) = writer.withLock {
        val key = ChatCacheKey(gatewayId, sessionId)
        val allowed = metadata.withLock {
            rollMetadataIfNeeded()
            if (generation < (generations[key] ?: Long.MIN_VALUE)) {
                false
            } else {
                generations[key] = generation
                revisions[key] = (revisions[key] ?: 0L) + 1
                memory.remove(key)
                true
            }
        }
        if (allowed) {
            try {
                recover("remove", TimeSource.Monotonic.markNow(), Unit) {
                    dao.removeCacheAndHeat(gatewayId, sessionId)
                }
            } finally {
                withContext(NonCancellable) {
                    metadata.withLock {
                        revisions[key] = (revisions[key] ?: 0L) + 1
                        memory.remove(key)
                        loads.remove(key)
                    }
                }
            }
        }
    }

    @Throws(Exception::class)
    suspend fun close() = writer.withLock {
        metadata.withLock {
            closed = true
            cacheEpoch += 1
            memory.clear()
            loads.clear()
            touches.clear()
            pendingTouches.clear()
        }
        maintenance.cancel()
        database.close()
        chatCacheLog("database-closed")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun <T> recover(operation: String, started: TimeMark, fallback: T, action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val reason = when (error) {
            is CacheCapacityExceeded -> "capacity-protected"
            is SerializationException -> "invalid-payload"
            is IllegalArgumentException -> "invalid-window"
            else -> "storage-error"
        }
        chatCacheLog("$operation-failed", "reason=$reason elapsedMs=${started.elapsedNow().inWholeMilliseconds}", warning = true)
        fallback
    }

    /** 清除 tombstone 前滚动 epoch，所有尚未提交的旧票据都会失效，不能复活已删除窗口。 */
    private fun rollMetadataIfNeeded() {
        if (generations.size < MAXIMUM_METADATA_KEYS && revisions.size < MAXIMUM_METADATA_KEYS) return
        cacheEpoch += 1
        generations.clear()
        revisions.clear()
        loads.clear()
        memory.clear()
        touches.clear()
        pendingTouches.clear()
        chatCacheLog("metadata-epoch-rolled", "limit=$MAXIMUM_METADATA_KEYS")
    }

    private suspend fun touchEventually(key: ChatCacheKey, expectedEpoch: Long) {
        val time = nextAccessTime()
        val shouldTouch = metadata.withLock {
            if (closed || cacheEpoch != expectedEpoch) return@withLock false
            pendingTouches[key] = time
            if (time - (touches[key] ?: 0L) < TOUCH_INTERVAL_MILLIS) {
                false
            } else {
                if (touches.size > MAXIMUM_PENDING_LOADS) touches.clear()
                touches[key] = time
                true
            }
        }
        if (!shouldTouch) return
        maintenance.launch {
            recover("maintenance", TimeSource.Monotonic.markNow(), Unit) {
                writer.withLock { flushPendingTouches() }
            }
        }
    }

    // writer 内先同步最新 read recency，再决定 LRU；热路径无需等磁盘。
    private suspend fun flushPendingTouches() {
        val pending = metadata.withLock { pendingTouches.toMap().also { pendingTouches.clear() } }
        for ((key, time) in pending) {
            recover("touch", TimeSource.Monotonic.markNow(), Unit) {
                dao.touch(key.gatewayId, key.sessionId, time)
            }
        }
    }

    // 这是当前时刻的写入准入阈值，其他进程仍可能同时消耗空间，并不是文件系统的硬预留。
    private suspend fun admitCacheWrite(logicalBytes: Long): Boolean {
        val free = withContext(Dispatchers.IO) { runCatching(availableBytesProvider).getOrNull() }
        val required = MINIMUM_FREE_RESERVE_BYTES + maxOf(
            MINIMUM_WRITE_HEADROOM_BYTES,
            logicalBytes * WRITE_SPACE_MULTIPLIER + maximumBytes / TRANSACTION_HEADROOM_DIVISOR
        )
        if (free != null && free >= required) return true
        metadata.withLock { writeAdmissionRefusals += 1 }
        chatCacheLog("save-admission-refused", "reason=${if (free == null) "space-unavailable" else "low-space"} requiredBytes=$required")
        return false
    }

    private suspend fun nextAccessTime(): Long {
        val latest = if (metadata.withLock { accessInitialized }) 0L else maxOf(dao.latestAccess(), dao.latestCatalogAccess())
        return metadata.withLock {
            accessInitialized = true
            maxOf(now(), accessClock + 1, latest + 1).also { accessClock = it }
        }
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun estimateWindowBytes(window: CachedHistoryWindow): Long =
        window.events.sumOf { wireJson.encodeToString(it).encodeToByteArray().size.toLong() } + ESTIMATED_WINDOW_HEADER_BYTES

    companion object {
        private const val CATALOG_KEY = "\u0000offline-catalog"
        private const val HEAT_WRITE_BYTES = 256L
        private const val MAXIMUM_CANDIDATE_BYTES = 1024L * 1024
        private const val MAXIMUM_CANDIDATES = 8192
        private val SUPPORTED_HISTORY_FORMATS = 1..4
        const val DEFAULT_MAXIMUM_BYTES: Long = 96L * 1024 * 1024
        const val DEFAULT_TARGET_BYTES: Long = 72L * 1024 * 1024
        private const val MAXIMUM_PAGE_EVENTS = 200
        private const val MAXIMUM_QUERY_LENGTH = 512
        private const val MAXIMUM_SEARCH_RESULTS = 100
        private const val TRIGRAM_LENGTH = 3
        private const val SNIPPET_LEADING_CHARACTERS = 48
        private const val SNIPPET_TRAILING_CHARACTERS = 96
        private const val TOUCH_INTERVAL_MILLIS = 10_000L
        private const val ESTIMATED_WINDOW_HEADER_BYTES = 256L
        private const val MAXIMUM_METADATA_KEYS = 4096
        private const val MAXIMUM_PENDING_LOADS = 64
        const val MINIMUM_FREE_RESERVE_BYTES: Long = 128L * 1024 * 1024
        private const val MINIMUM_WRITE_HEADROOM_BYTES = 8L * 1024 * 1024
        private const val WRITE_SPACE_MULTIPLIER = 3L
        private const val TRANSACTION_HEADROOM_DIVISOR = 4L
    }
}

/** 所有平台使用相同 SQLite 引擎，并限制物理文件与事务日志的增长。 */
internal class BoundedCacheDriver(private val maximumBytes: Long) : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName).also { connection ->
        val started = TimeSource.Monotonic.markNow()
        connection.execSQL("PRAGMA auto_vacuum=FULL")
        connection.execSQL("PRAGMA journal_size_limit=0")
        connection.execSQL("PRAGMA busy_timeout=2000")
        val pageSize = connection.prepare("PRAGMA page_size").use {
            it.step()
            it.getLong(0)
        }
        connection.prepare("PRAGMA max_page_count=${(maximumBytes + PHYSICAL_OVERHEAD_BYTES) / pageSize}")
            .use { it.step() }
        chatCacheLog("sqlite-opened", "elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
    }

    companion object {
        private const val PHYSICAL_OVERHEAD_BYTES = 32L * 1024 * 1024
    }
}

internal fun RoomDatabase.Builder<ChatCacheDatabase>.buildCache(
    maximumBytes: Long,
    targetBytes: Long,
    availableBytesProvider: () -> Long? = { Long.MAX_VALUE }
): SharedChatHistoryCache {
    val database = addMigrations(CHAT_CACHE_MIGRATION_1_2, CHAT_CACHE_MIGRATION_2_3, CHAT_CACHE_MIGRATION_3_4)
        .setDriver(BoundedCacheDriver(maximumBytes))
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return SharedChatHistoryCache(database, maximumBytes, targetBytes, availableBytesProvider)
}
