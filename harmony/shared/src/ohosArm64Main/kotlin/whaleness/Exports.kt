package whaleness

private val engines = mutableMapOf<Long, HarmonyEngine>()
private var nextHandle = 0L

fun create(): Long = (++nextHandle).also { engines[it] = HarmonyEngine() }

fun dispatch(
    handle: Long,
    command: String,
): String = engines[handle]?.dispatch(command) ?: "{\"ok\":false,\"error\":\"共享引擎已释放\"}"

fun destroy(handle: Long) {
    engines.remove(handle)
}
