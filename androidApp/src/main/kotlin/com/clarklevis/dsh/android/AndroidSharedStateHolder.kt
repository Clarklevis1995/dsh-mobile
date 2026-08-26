package com.clarklevis.dsh.android

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clarklevis.dsh.shared.facade.SharedMobileSnapshot
import com.clarklevis.dsh.shared.facade.SharedMobileStore

@Stable
class AndroidSharedStateHolder(
    private val store: SharedMobileStore = SharedMobileStore()
) {
    var snapshot: SharedMobileSnapshot by mutableStateOf(store.snapshot())
        private set

    var wirePayload: String by mutableStateOf(DEFAULT_WIRE_PAYLOAD)

    fun loadFixture() {
        snapshot = store.loadManualTestFixture()
    }

    fun submitWirePayload() {
        snapshot = store.acceptFrame(wirePayload)
    }

    fun selectSession(sessionId: String) {
        snapshot = store.selectSession(sessionId)
    }

    fun reset() {
        snapshot = store.reset()
    }

    companion object {
        const val DEFAULT_WIRE_PAYLOAD =
            """{"sessionId":"android-demo","seq":4,"time":1786937355,"event":{"type":"assistant/message","turn":1,"step":1,"text":"最终消息会替换流式临时消息。"}}"""
    }
}
