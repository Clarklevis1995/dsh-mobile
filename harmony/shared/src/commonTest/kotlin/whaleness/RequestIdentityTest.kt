package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestIdentityTest {
    private fun firstMessageId(): String {
        val engine = HarmonyEngine()

        fun call(command: HarmonyCommand): HarmonyResult = wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(command)))

        fun frame(value: String) = call(HarmonyCommand("frame", frame = value))
        assertTrue(call(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile")).ok)
        assertTrue(frame("""{"kind":"hello","authenticated":true}""").ok)
        frame("""{"kind":"sessions","items":[{"sessionId":"s","updatedAt":100,"blank":false,"running":false}]}""")
        frame("""{"kind":"workspaces","items":[]}""")
        call(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"history","sessionId":"s","events":[],"hasMore":false}""")
        val sent = call(HarmonyCommand("send", text = "same content after process recreation"))
        assertTrue(sent.ok, sent.error)
        return wireJson
            .parseToJsonElement(sent.outbound.single().text)
            .jsonObject
            .getValue("requestId")
            .jsonPrimitive.content
    }

    @Test fun recreatedEnginesCannotReuseMessageIdempotencyKeysForTheSameSession() {
        val ids = List(16) { firstMessageId() }
        assertEquals(ids.size, ids.toSet().size, "新实例必须与旧进程、其他客户端的消息幂等键隔离")
        assertTrue(ids.all { it.length in 1..128 })
    }
}
