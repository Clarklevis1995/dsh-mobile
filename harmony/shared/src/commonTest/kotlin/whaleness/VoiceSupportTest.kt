package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceSupportTest {
    private val support = VoiceSupport()
    private var sid = "a"
    private var draft = ""

    private fun intent(
        op: String,
        value: String = "",
        mode: String = "",
        input: String = "",
        frame: String = "",
    ): VoiceChange =
        support.command(
            HarmonyCommand(
                op,
                sessionId = sid,
                text = draft,
                value = value,
                mode = mode,
                input = input,
                frame = frame,
                nowMillis = 1000,
            ),
            sid,
            true,
            listOf("speech-input-v1"),
            false,
        )

    private fun ready() {
        val initial = intent("voice-sync")
        val request = initial.requests.first { it.requestType == "speech-catalog" }.correlationId
        intent(
            "voice-frame",
            frame =
                """
                {"kind":"speech-catalog","requestId":"$request","available":true,
                 "selection":{"providerId":"p"},"providers":[{"id":"p","preparation":{"phase":"ready"}}]}
                """.trimIndent(),
        )
        intent("voice-mode", mode = "on")
    }

    private fun transcribing(): String {
        val id = intent("voice-press").effects.first { it.action == "permission" }.recordingId
        intent("voice-permission", value = id, mode = "granted")
        intent("voice-started", value = id)
        assertEquals("stopping", intent("voice-release").view.phase)
        val audio = ByteArray(32000) { if (it % 2 == 0) 100.toByte() else 8.toByte() }
        val change = intent("voice-finished", value = id, input = Base64.encode(audio))
        assertEquals("transcribing", change.view.phase)
        assertTrue(
            change.requests
                .single()
                .payload
                .contains("audioBase64"),
        )
        return id
    }

    @Test fun transcriptUpdatesDraftWithoutSendingChat() {
        ready()
        val id = transcribing()
        val update = intent("voice-frame", frame = """{"kind":"speech-transcript","requestId":"$id","text":"识别后的中文"}""")
        assertEquals("识别后的中文", update.view.draft)
        assertTrue(update.requests.isEmpty())
        assertEquals("idle", update.view.phase)
    }

    @Test fun editedDraftKeepsTranscriptUntilExplicitInsertion() {
        ready()
        val id = transcribing()
        draft = "转写期间的新文字"
        val update = intent("voice-frame", frame = """{"kind":"speech-transcript","requestId":"$id","text":"识别文字"}""")
        assertNull(update.view.draft)
        assertEquals("识别文字", update.view.pendingTranscript)
        assertEquals("转写期间的新文字\n识别文字", intent("voice-insert").view.draft)
    }

    @Test fun switchingSessionCancelsHardwareAndRejectsOldTranscript() {
        ready()
        val id = transcribing()
        sid = "b"
        val switched = intent("voice-sync")
        assertTrue(switched.effects.any { it.action == "releaseCapture" })
        assertFalse(switched.view.voiceMode)
        val late = intent("voice-frame", frame = """{"kind":"speech-transcript","requestId":"$id","text":"旧会话文字"}""")
        assertNull(late.view.draft)
        assertNull(late.view.pendingTranscript)
    }

    @Test fun deniedPermissionReleasesCaptureAndNeverTranscribes() {
        ready()
        val id = intent("voice-press").effects.first { it.action == "permission" }.recordingId
        val denied = intent("voice-permission", value = id, mode = "denied")
        assertEquals("idle", denied.view.phase)
        assertNotNull(denied.view.error)
        assertTrue(denied.effects.any { it.action == "stop" && it.discard })
        assertTrue(
            denied.requests.none {
                it.requestType ==
                    "speech-transcribe"
            },
        )
    }

    @Test fun voiceBridgeReturnsNoHistoryOrStateMirror() {
        val reply = wireJson.decodeFromString<HarmonyResult>(HarmonyEngine().dispatch("""{"op":"voice-sync","text":"草稿"}"""))
        assertTrue(reply.ok)
        assertNull(reply.state)
        assertTrue(reply.patches.isEmpty())
        assertNotNull(reply.voice)
    }
}
