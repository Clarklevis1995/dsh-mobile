package whaleness

import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/** 仅编入临时诊断库；读取已完成GC记录，不请求GC、不设置调度器参数。 */
@OptIn(NativeRuntimeApi::class, ExperimentalStdlibApi::class)
internal fun nativeMemoryProbe(): String {
    val info = GC.lastGCInfo
    val before = info?.memoryUsageBefore?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
    val after = info?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1L
    val values =
        linkedMapOf(
            "lastGcEpoch" to (info?.epoch ?: -1L),
            "gcStartNanos" to (info?.startTimeNs ?: -1L),
            "gcEndNanos" to (info?.endTimeNs ?: -1L),
            "gcHeapBeforeBytes" to before,
            "gcHeapAfterBytes" to after,
            "gcFirstPauseNanos" to if (info == null) -1L else info.firstPauseEndTimeNs - info.firstPauseStartTimeNs,
            "targetHeapBytes" to GC.targetHeapBytes,
            "minHeapBytes" to GC.minHeapBytes,
            "maxHeapBytes" to GC.maxHeapBytes,
            "regularGcIntervalMilliseconds" to GC.regularGCInterval.inWholeMilliseconds,
        )
    return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\":\"$value\"" }
}
