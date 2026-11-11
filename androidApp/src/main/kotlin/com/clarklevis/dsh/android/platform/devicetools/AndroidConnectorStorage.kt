package com.clarklevis.dsh.android.platform.devicetools

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

internal fun connectorDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun canonicalConnectorJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalConnectorJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalConnectorJson(value.get(it)) }
    else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
}

internal fun JSONObject.text(key: String, max: Int, empty: Boolean = false): String {
    val value = get(key)
    require(value is String && value.length <= max && !value.contains('\u0000') && (empty || value.isNotBlank()))
    return value
}
internal fun JSONObject.instant(key: String): Instant = OffsetDateTime.parse(text(key, 40)).toInstant()
internal fun JSONObject.zone(): ZoneId = ZoneId.of(text("timeZone", 80))
internal fun JSONObject.uuid(key: String): String = text(key, 36).also {
    require(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").matches(it))
}
internal fun JSONObject.range(): Pair<Instant, Instant> {
    zone()
    val start = instant("start"); val end = instant("end")
    require(start < end && java.time.Duration.between(start, end).toMillis() <= 8 * 86_400_000L)
    return start to end
}
internal fun JSONObject.coordinates(lat: String = "latitude", lon: String = "longitude"): Pair<Double, Double> {
    val a = get(lat); val b = get(lon)
    require(a is Number && b is Number)
    val latitude = a.toDouble(); val longitude = b.toDouble()
    require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
    return latitude to longitude
}
internal fun JSONObject.integer(key: String, default: Int, range: IntRange): Int {
    if (!has(key)) return default
    val number = get(key)
    require(number is Number && number.toDouble() == number.toInt().toDouble() && number.toInt() in range)
    return number.toInt()
}

/** 原子私有文件，不备份凭据或用户数据；不确定写入的 operationId 永不自动重放。 */
internal class AndroidConnectorStorage(context: Context, hostId: String) {
    private val file = AtomicFile(File(directory(context), connectorDigest(hostId) + ".json"))
    private val lock = locks.getOrPut(file.baseFile.absolutePath) { Mutex() }

    private fun load(): JSONObject = if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) JSONObject()
        else try { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }
        catch (_: Exception) { throw ConnectorFailure("storage_unavailable") }

    private fun save(data: JSONObject) {
        val output = try { file.startWrite() } catch (_: Exception) { throw ConnectorFailure("storage_unavailable") }
        try { output.write(data.toString().toByteArray()); file.finishWrite(output) }
        catch (_: Exception) { file.failWrite(output); throw ConnectorFailure("storage_unavailable") }
    }

    suspend fun <T> read(block: (JSONObject) -> T): T = withContext(Dispatchers.IO) { lock.withLock { block(load()) } }

    suspend fun write(request: JSONObject, action: suspend (JSONObject) -> JSONObject): JSONObject = withContext(Dispatchers.IO) {
        lock.withLock {
            val args = request.getJSONObject("arguments")
            val operation = args.uuid("operationId")
            val session = request.text("sessionId", 512)
            val identity = connectorDigest("${session.length}:$session:${request.text("tool", 128)}:$operation")
            val signature = connectorDigest(canonicalConnectorJson(args))
            val data = load()
            val receipts = data.optJSONObject("receipts") ?: JSONObject().also { data.put("receipts", it) }
            receipts.optJSONObject(identity)?.let { previous ->
                if (previous.optString("signature") != signature) throw ConnectorFailure("operation_conflict")
                previous.optString("error").takeIf { it.isNotEmpty() }?.let { throw ConnectorFailure(it) }
                val result = previous.optJSONObject("result") ?: throw ConnectorFailure("outcome_unknown")
                return@withLock JSONObject(result.toString()).also { if (it.has("replayed")) it.put("replayed", true) }
            }
            if (receipts.length() >= 4096) throw ConnectorFailure("operation_limit_reached")
            receipts.put(identity, JSONObject().put("signature", signature).put("attemptedAt", Instant.now().toString()))
            save(data)
            // 系统写入开始后，即使连接被取消，也必须保存回执；下次复用 operationId 不会重复创建。
            withContext(NonCancellable) {
                val transaction = JSONObject(data.toString())
                val result = try { action(transaction) } catch (error: Exception) {
                    if (error is ConnectorFailure && error.code != "outcome_unknown") {
                        receipts.getJSONObject(identity).put("error", error.code)
                        save(data)
                        throw error
                    }
                    throw ConnectorFailure("outcome_unknown")
                }
                transaction.getJSONObject("receipts").getJSONObject(identity).put("result", result)
                try { save(transaction) } catch (_: Exception) { throw ConnectorFailure("outcome_unknown") }
                result
            }
        }
    }

    suspend fun once(request: JSONObject, action: suspend () -> JSONObject): JSONObject {
        val session = request.text("sessionId", 512); val call = request.text("toolCallId", 512)
        val copy = JSONObject(request.toString())
        val args = JSONObject(request.getJSONObject("arguments").toString())
        args.put("operationId", UUID.nameUUIDFromBytes("${session.length}:$session:$call".toByteArray()).toString())
        copy.put("arguments", args)
        return write(copy) { action() }
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        fun directory(context: Context) = File(context.noBackupFilesDir, "phone-connectors").apply { mkdirs() }
    }
}
