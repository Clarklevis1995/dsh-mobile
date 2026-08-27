package com.clarklevis.dsh.shared.facade

/**
 * KMP -> platform 的粗粒度 MVI 事件信封。
 *
 * Store 在提交业务状态后发布一个 transition event；state payload 与 effects
 * 共享同一 transactionId，平台必须在执行 effect 前完成 state payload 校验与发布。
 * 初始化 snapshot 使用当前 sequence 作为订阅基线，不占用新的事务序号。
 */
data class SharedMviEvent(
    val schema: Int = 1,
    val sequence: Long,
    val transactionId: String,
    val domain: String,
    val kind: String,
    val statePayloadJson: String?,
    val effectsJson: String = "[]",
    val errorCode: String? = null,
    val errorMessage: String? = null
)

fun interface SharedMviEventObserver {
    fun onEvent(event: SharedMviEvent)
}

/** 可幂等取消；取消后不会收到后续事件，已经开始的回调允许正常结束。 */
class SharedMviSubscription internal constructor(
    private var cancelAction: (() -> Unit)?
) {
    fun cancel() {
        val action = cancelAction ?: return
        cancelAction = null
        action()
    }
}

/** Swift dispatch Intent 后只接收确认/错误，不通过返回值拉取业务状态。 */
data class SharedMviDispatchResult(
    val accepted: Boolean,
    val transactionId: String?,
    val eventSequence: Long?,
    val errorCode: String? = null,
    val errorMessage: String? = null
)

/**
 * Store 内部使用的同步保序 emitter。
 *
 * Store 的 dispatch 入口必须串行调用（iOS 为 MainActor，Android 为状态持有者线程）。
 * 同步投递可保证 state event 与一次性 effect 的事务顺序，也避免异步 Flow collector
 * 在 Swift 生命周期结束后继续回调。重入 emit 会排队到当前事件的所有 observer 完成后。
 */
internal class SharedMviEventEmitter(
    private val domain: String
) {
    private var nextSequence = 0L
    private var nextObserverId = 0L
    private val observers = linkedMapOf<Long, SharedMviEventObserver>()
    private val pendingEvents = ArrayDeque<SharedMviEvent>()
    private var isDelivering = false

    val currentSequence: Long get() = nextSequence

    fun subscribe(
        observer: SharedMviEventObserver,
        snapshotPayloadJson: String
    ): SharedMviSubscription {
        val observerId = ++nextObserverId
        observers[observerId] = observer
        deliverSafely(
            observer,
            SharedMviEvent(
                sequence = nextSequence,
                transactionId = "snapshot:$domain:$nextSequence",
                domain = domain,
                kind = "snapshot",
                statePayloadJson = snapshotPayloadJson
            )
        )
        return SharedMviSubscription { observers.remove(observerId) }
    }

    fun emitTransition(
        transactionId: String,
        statePayloadJson: String?,
        effectsJson: String = "[]"
    ): SharedMviEvent {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        val event = SharedMviEvent(
            sequence = ++nextSequence,
            transactionId = transactionId,
            domain = domain,
            kind = "transition",
            statePayloadJson = statePayloadJson,
            effectsJson = effectsJson
        )
        enqueue(event)
        return event
    }

    fun emitError(
        transactionId: String,
        errorCode: String,
        errorMessage: String?
    ): SharedMviEvent {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(errorCode.isNotBlank()) { "errorCode must not be blank" }
        val event = SharedMviEvent(
            sequence = ++nextSequence,
            transactionId = transactionId,
            domain = domain,
            kind = "error",
            statePayloadJson = null,
            errorCode = errorCode,
            errorMessage = errorMessage
        )
        enqueue(event)
        return event
    }

    private fun enqueue(event: SharedMviEvent) {
        pendingEvents.addLast(event)
        if (isDelivering) return
        isDelivering = true
        try {
            while (pendingEvents.isNotEmpty()) {
                val next = pendingEvents.removeFirst()
                observers.values.toList().forEach { deliverSafely(it, next) }
            }
        } finally {
            isDelivering = false
        }
    }

    private fun deliverSafely(observer: SharedMviEventObserver, event: SharedMviEvent) {
        try {
            observer.onEvent(event)
        } catch (_: Throwable) {
            // 平台 observer 的异常不得越过 Kotlin/Native 边界，也不能阻断其他 observer。
        }
    }
}
