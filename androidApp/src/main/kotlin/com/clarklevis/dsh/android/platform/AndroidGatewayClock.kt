package com.clarklevis.dsh.android.platform

import com.clarklevis.dsh.shared.platform.GatewayClock
import kotlin.time.Duration.Companion.milliseconds

object AndroidGatewayClock : GatewayClock {
    override fun nowEpochMilliseconds(): Long = System.currentTimeMillis()

    override suspend fun delay(milliseconds: Long) {
        kotlinx.coroutines.delay(milliseconds.milliseconds)
    }
}
