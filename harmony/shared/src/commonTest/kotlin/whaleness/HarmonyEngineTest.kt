package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarmonyEngineTest {
    private val engine = HarmonyEngine()

    private fun command(command: HarmonyCommand): HarmonyResult =
        wireJson.decodeFromString(engine.dispatch(wireJson.encodeToString(command)))

    private fun frame(
        json: String,
        channel: String = "control",
    ) = command(HarmonyCommand("frame", frame = json, channel = channel, nowMillis = 100_000))

    private fun connected(split: Boolean = false) {
        assertTrue(command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8:3080/ws/mobile")).ok)
        val capabilities = if (split) "[\"split-channels\"]" else "[]"
        assertTrue(frame("""{"kind":"hello","authenticated":true,"capabilities":$capabilities}""").ok)
        if (split) assertTrue(frame("""{"kind":"hello","authenticated":true}""", "conversation").ok)
        frame("""{"kind":"sessions","items":[]}""")
        frame("""{"kind":"workspaces","items":[]}""")
    }

    @Test fun hostDetailsFollowOnlyControlFramesAndClearOnDisconnect() {
        command(HarmonyCommand("prepare", endpoint = "ws://127.0.0.1/ws/mobile"))
        val hello = frame("""{"kind":"hello","authenticated":true}""")
        assertTrue(hello.outbound.any { it.channel == "control" && "\"type\":\"host\"" in it.text })
        val raw = """{"kind":"host","version":"fallback","dshVersion":"0.2.2-rc.1","cwd":"/owned/workspace","attachedSessions":7}"""
        val current = frame(raw).state!!.hostSnapshot!!
        assertEquals("0.2.2-rc.1", current.dshVersion)
        assertEquals("/owned/workspace", current.cwd)
        assertEquals(7, current.attachedSessions)
        assertEquals(current, frame("""{"kind":"host","dshVersion":"wrong-channel"}""", "conversation").state!!.hostSnapshot)
        assertNull(command(HarmonyCommand("disconnect")).state!!.hostSnapshot)
        assertNull(frame("""{"kind":"hello","authenticated":true}""").state!!.hostSnapshot)
    }

    @Test fun rejectsUnencryptedPublicEndpoint() {
        val result = command(HarmonyCommand("prepare", endpoint = "ws://example.com/ws/mobile"))
        assertFalse(result.ok)
    }

    @Test fun rejectsUnexpectedGatewayBeforeCatalogRequests() {
        command(
            HarmonyCommand(
                "prepare",
                endpoint = "wss://example.com/ws/mobile",
                expectedGatewayId = "12345678-1234-4123-8123-123456789abc",
            ),
        )
        val result = frame("""{"kind":"hello","gatewayId":"12345678-1234-4123-8123-123456789abd"}""")
        assertFalse(result.ok)
        assertTrue(result.fatal)
        assertTrue(result.outbound.isEmpty())
        assertFalse(result.state!!.connected)
    }

    @Test fun splitConnectionWaitsForConversationHandshake() {
        command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8/ws/mobile"))
        val control = frame("""{"kind":"hello","capabilities":["split-channels"]}""")
        assertTrue(control.openConversation)
        assertFalse(control.state!!.connected)
        assertEquals(setOf("control"), control.outbound.map { it.channel }.toSet())
        val conversation = frame("""{"kind":"hello"}""", "conversation")
        assertTrue(conversation.state!!.connected)
        val selection = command(HarmonyCommand("select", sessionId = "s"))
        assertEquals(setOf("conversation"), selection.outbound.map { it.channel }.toSet())
    }

    @Test fun legacySameSequenceDeltasPreserveAllText() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"history","sessionId":"s","events":[],"hasMore":false}""")
        val first =
            frame(
                """{"kind":"event","sessionId":"s","seq":1,"time":100,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":"Hello"}}""",
            )
        val second =
            frame(
                """{"kind":"event","sessionId":"s","seq":1,"time":101,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"text-delta","text":" world"}}""",
            )
        assertTrue(first.ok)
        assertTrue(second.ok)
        assertTrue(first.patches.flatMap { it.operations }.any { it.item?.text == "Hello" })
        assertTrue(second.patches.flatMap { it.operations }.any { it.kind == "append-text" && it.delta == " world" })
    }

    @Test fun independentAssistantStreamRejectsRetiredFrames() {
        command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8/ws/mobile"))
        frame("""{"kind":"hello","capabilities":["assistant-stream-v1"],"historyFormatVersion":3}""")
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub"}""")
        val snapshot =
            frame(
                """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":41,"replace":true,"events":[],"assistantStream":{"revision":2,"activeAttempt":{"attemptId":"s:1","turn":2,"step":3,"startedAfterSeq":41,"nextIndex":1,"stream":[{"type":"text-chunks","index":0,"time0":100,"texts":["Hello"],"dt":[]}]}}}""",
            )
        assertTrue(snapshot.ok)
        val old =
            frame(
                """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"old","streamId":"stream","frame":{"type":"chunk","attemptId":"s:1","revision":3,"index":1,"time":101,"chunk":{"type":"text-delta","index":0,"text":"BAD"}}}""",
            )
        assertTrue(old.patches.isEmpty())
        val delta =
            frame(
                """{"kind":"assistant-stream","sessionId":"s","subscriptionId":"sub","streamId":"stream","frame":{"type":"chunk","attemptId":"s:1","revision":3,"index":1,"time":101,"turn":2,"step":3,"chunk":{"type":"text-delta","index":0,"text":" world"}}}""",
            )
        assertTrue(delta.ok)
        assertTrue(delta.patches.flatMap { it.operations }.any { it.delta == " world" })
    }

    @Test fun requestIsNotAcknowledgedByAnotherSession() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        val send = command(HarmonyCommand("send", text = "hello"))
        assertTrue(send.ok)
        assertTrue(send.state!!.pendingRequest)
        val other = frame("""{"kind":"sent","sessionId":"other"}""")
        assertFalse(other.sent)
        assertFalse(command(HarmonyCommand("send", text = "again")).ok)
        val ack = frame("""{"kind":"sent","sessionId":"s"}""")
        assertTrue(ack.sent)
    }

    @Test fun changingHostsClearsPreviousCatalogAndSelection() {
        connected()
        frame("""{"kind":"sessions","items":[{"sessionId":"old","updatedAt":100,"blank":false}]}""")
        command(HarmonyCommand("select", sessionId = "old"))
        val next = command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.9/ws/mobile"))
        assertTrue(next.state!!.sessions.isEmpty())
        assertNull(next.state.selectedSessionId)
    }

    @Test fun invalidJsonDoesNotEchoSecret() {
        val result = wireJson.decodeFromString<HarmonyResult>(engine.dispatch("{\"secret\":\"super-secret"))
        assertFalse(result.ok)
        assertFalse(result.error.orEmpty().contains("super-secret"))
    }

    private fun question(id: String = "q") =
        frame(
            """{"kind":"question-requested","sessionId":"s","rpcId":"$id","replay":false,"questions":[{"id":"single","question":"选择","options":[{"label":"A"},{"label":"B"}]},{"id":"multi","question":"多选","multiSelect":true,"options":[{"label":"X"},{"label":"Y"}]},{"id":"text","question":"备注"}]}""",
        )

    private fun approval(id: String = "a") =
        frame(
            """{"kind":"approval-requested","sessionId":"s","rpcId":"$id","approvalId":"approval-1","toolName":"bash","callId":"call-1","reason":"fallback","displayReason":{"zh-CN":"运行测试命令"}}""",
        )

    private fun answer() =
        HarmonyCommand(
            "question-answer",
            rpcId = "q",
            sessionId = "s",
            answers =
                listOf(
                    com.clarklevis.dsh.shared.protocol
                        .GatewayQuestionAnswer("single", listOf("A")),
                    com.clarklevis.dsh.shared.protocol
                        .GatewayQuestionAnswer("multi", listOf("X", "Y"), "补充"),
                    com.clarklevis.dsh.shared.protocol
                        .GatewayQuestionAnswer("text", emptyList(), "备注"),
                ),
        )

    @Test fun questionUsesSharedValidationAndSingleFlight() {
        connected(true)
        question()
        val invalid =
            answer().copy(
                answers =
                    answer().answers.map {
                        if (it.id == "single") it.copy(selected = listOf("A", "B")) else it
                    },
            )
        assertFalse(command(invalid).ok)
        val sent = command(answer())
        assertTrue(sent.ok)
        assertEquals("question:q", sent.outbound.single().requestKey)
        assertEquals("control", sent.outbound.single().channel)
        assertTrue(
            sent.outbound
                .single()
                .text
                .contains("question-answer"),
        )
        assertEquals(
            "submitting",
            sent.state!!
                .questions
                .single()
                .status,
        )
        assertTrue(command(answer()).outbound.isEmpty())
        val wrong = frame("""{"kind":"question-response","rpcId":"q","sessionId":"other","action":"answer","accepted":true}""")
        assertEquals(
            "submitting",
            wrong.state!!
                .questions
                .single()
                .status,
        )
        val accepted = frame("""{"kind":"question-response","rpcId":"q","sessionId":"s","action":"answer","accepted":true}""")
        assertEquals(
            "accepted",
            accepted.state!!
                .questions
                .single()
                .status,
        )
        assertTrue(command(HarmonyCommand("question-cancel", rpcId = "q", sessionId = "s")).outbound.isEmpty())
        assertTrue(frame("""{"kind":"question-resolved","rpcId":"q","sessionId":"s"}""").state!!.questions.isEmpty())
        assertFalse(command(answer()).ok)
    }

    @Test fun questionCanBeCancelledAndExpiredRequestIsRemoved() {
        connected()
        question()
        val cancel = command(HarmonyCommand("question-cancel", rpcId = "q", sessionId = "s"))
        assertTrue(
            cancel.outbound
                .single()
                .text
                .contains("question-cancel"),
        )
        val expired = frame("""{"kind":"question-response","rpcId":"q","action":"cancel","accepted":false,"reason":"not-pending"}""")
        assertEquals(0, expired.state!!.pendingQuestionCount)
        assertFalse(command(answer()).ok)
    }

    @Test fun approvalHasLocalizedDetailsAndOnlyExplicitSingleUseDecision() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"history","sessionId":"s","events":[]}""")
        frame(
            """{"kind":"event","sessionId":"s","seq":1,"time":100,"event":{"type":"tool/call","toolName":"bash","callId":"call-1","arguments":{"command":"echo test"}}}""",
        )
        val pending = approval().state!!.approvals.single()
        assertEquals("运行测试命令", pending.reason)
        assertTrue(pending.arguments.orEmpty().contains("echo test"))
        assertFalse(command(HarmonyCommand("approval-decision", rpcId = "a", sessionId = "other", outcome = "allowed-once")).ok)
        assertFalse(command(HarmonyCommand("approval-decision", rpcId = "a", sessionId = "s", outcome = "always-allow")).ok)
        val decision = HarmonyCommand("approval-decision", rpcId = "a", sessionId = "s", outcome = "allowed-once")
        val send = command(decision)
        assertTrue(send.ok)
        assertEquals("approval:a", send.outbound.single().requestKey)
        assertTrue(
            send.outbound
                .single()
                .text
                .contains("allowed-once"),
        )
        assertTrue(command(decision.copy(outcome = "rejected")).outbound.isEmpty())
        val accepted = frame("""{"kind":"approval-response","rpcId":"a","sessionId":"s","outcome":"allowed-once","accepted":true}""")
        assertEquals(
            "accepted",
            accepted.state!!
                .approvals
                .single()
                .status,
        )
        assertTrue(frame("""{"kind":"approval-resolved","rpcId":"a","sessionId":"s"}""").state!!.approvals.isEmpty())
        assertFalse(command(decision).ok)
    }

    @Test fun approvalRejectAndRequestScopedTimeoutDoNotAffectMessages() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"history","sessionId":"s","events":[]}""")
        val message = command(HarmonyCommand("send", text = "hello"))
        val messageKey = message.state!!.pendingOperations.single()
        approval()
        val decision = HarmonyCommand("approval-decision", rpcId = "a", sessionId = "s", outcome = "rejected")
        command(decision)
        val timeout = command(HarmonyCommand("request-failed", requestKey = "approval:a"))
        assertEquals(
            "rejected",
            timeout.state!!
                .approvals
                .single()
                .status,
        )
        assertEquals(listOf(messageKey), timeout.state.pendingOperations)
        val retry = command(decision)
        assertEquals(1, retry.outbound.size)
        val rejected = frame("""{"kind":"approval-response","rpcId":"a","outcome":"rejected","accepted":false,"reason":"not-pending"}""")
        assertTrue(rejected.state!!.approvals.isEmpty())
        assertEquals(listOf(messageKey), rejected.state.pendingOperations)
    }

    @Test fun disconnectedInteractionsRequireServerReplayBeforeResubmission() {
        connected()
        question()
        command(answer())
        approval()
        command(HarmonyCommand("disconnect"))
        assertFalse(command(answer()).ok)
        assertFalse(command(HarmonyCommand("approval-decision", rpcId = "a", sessionId = "s", outcome = "allowed-once")).ok)
        val reconnect = frame("""{"kind":"hello","capabilities":[]}""")
        assertTrue(reconnect.state!!.questions.isEmpty())
        assertTrue(reconnect.state.approvals.isEmpty())
        assertFalse(command(answer()).ok)
        question()
        assertTrue(command(answer()).ok)
    }

    @Test fun sentReceiptRemainsBoundToOriginalSessionAfterSwitch() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        command(HarmonyCommand("send", text = "hello"))
        command(HarmonyCommand("select", sessionId = "other"))
        val wrong = frame("""{"kind":"sent","sessionId":"other"}""")
        assertFalse(wrong.sent)
        val receipt = frame("""{"kind":"sent","sessionId":"s"}""")
        assertTrue(receipt.sent)
        assertEquals("s", receipt.sentSessionId)
        assertNull(receipt.state!!.pendingMessageSessionId)
        assertTrue(receipt.state.loadingHistory)
    }

    @Test fun resumePreservesVisibleConversationUntilFreshBaseline() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"history","sessionId":"s","events":[{"seq":1,"time":100,"type":"user/message","data":{"text":"keep"}}]}""")
        val resume = command(HarmonyCommand("resume"))
        assertTrue(resume.ok)
        assertTrue(resume.patches.isEmpty())
        assertTrue(resume.state!!.loadingHistory)
        assertEquals(4, resume.outbound.size)
        val timer = resume.state.pendingOperations.first { it.startsWith("history:") }
        val timeout = command(HarmonyCommand("operation-timeout", requestKey = timer))
        assertFalse(timeout.ok)
        assertFalse(timeout.state!!.loadingHistory)
        assertTrue(timeout.patches.isEmpty())
    }

    @Test fun lateHistoryCannotReplaceStreamBaselineAndExplicitOlderPageCanPrepend() {
        command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8/ws/mobile"))
        frame("""{"kind":"hello","capabilities":["assistant-stream-v1"],"historyFormatVersion":3}""")
        command(HarmonyCommand("select", sessionId = "s"))
        frame("""{"kind":"subscribed","sessionId":"s","subscriptionId":"sub"}""")
        val baseline =
            frame(
                """{"kind":"session-snapshot","sessionId":"s","subscriptionId":"sub","streamId":"stream","historyFormatVersion":3,"cursor":20,"replace":true,"hasMore":true,"nextBeforeSeq":20,"events":[{"seq":20,"time":100,"type":"assistant/message","data":{"turn":20,"step":1,"message":{"content":[{"type":"text","text":"baseline"}]}}}],"assistantStream":{"revision":0,"activeAttempt":null}}""",
            )
        assertTrue(baseline.ok)
        assertTrue(baseline.patches.any { patch -> patch.replacementItems?.any { it.text == "baseline" } == true })
        assertTrue(frame("""{"kind":"history","sessionId":"s","historyFormatVersion":3,"events":[],"hasMore":false}""").patches.isEmpty())
        val older = command(HarmonyCommand("older"))
        assertTrue(older.ok)
        val page =
            frame(
                """{"kind":"history","sessionId":"s","historyFormatVersion":3,"hasMore":false,"events":[{"seq":9,"time":90,"type":"assistant/message","data":{"turn":9,"step":1,"message":{"content":[{"type":"text","text":"older"}]}}}]}""",
            )
        assertTrue(page.ok)
        val text = page.patches.flatMap { it.replacementItems.orEmpty() }.map { it.text }
        assertEquals(listOf("older", "baseline"), text)
        assertFalse(page.state!!.hasMore)
        assertFalse(page.state.loadingHistory)
    }

    @Test fun expiredResponseRemovesInteractionEvenAfterTimeout() {
        connected()
        question()
        command(answer())
        command(HarmonyCommand("request-failed", requestKey = "question:q"))
        val expired =
            frame("""{"kind":"question-response","rpcId":"q","sessionId":"s","action":"answer","accepted":false,"reason":"not-pending"}""")
        assertTrue(expired.state!!.questions.isEmpty())
        approval()
        command(HarmonyCommand("approval-decision", rpcId = "a", sessionId = "s", outcome = "rejected"))
        command(HarmonyCommand("request-failed", requestKey = "approval:a"))
        assertTrue(
            frame(
                """{"kind":"approval-response","rpcId":"a","sessionId":"s","outcome":"rejected","accepted":false,"reason":"not-pending"}""",
            ).state!!.approvals.isEmpty(),
        )
    }

    @Test fun profileValidationNormalizesMetadataAndRejectsUnsafeAddresses() {
        val profile =
            com.clarklevis.dsh.shared.gateway.GatewayProfile(
                localId = "host-a",
                gatewayName = " Desktop ",
                endpoints = listOf("ws://192.168.1.8/ws/mobile", "ws://192.168.1.8/ws/mobile"),
                alias = " Work ",
                remoteDeviceId = "secret-device",
            )
        val valid = command(HarmonyCommand("validate-profile", profile = profile))
        assertTrue(valid.ok)
        assertEquals("Desktop", valid.profile!!.gatewayName)
        assertEquals("Work", valid.profile.alias)
        assertNull(valid.profile.remoteDeviceId)
        assertEquals(1, valid.profile.endpoints.size)
        assertFalse(
            command(HarmonyCommand("validate-profile", profile = profile.copy(endpoints = listOf("ws://example.com/ws/mobile")))).ok,
        )
        assertFalse(command(HarmonyCommand("validate-profile", profile = profile.copy(localId = "../other"))).ok)
        assertFalse(command(HarmonyCommand("validate-profile", profile = profile.copy(gatewayId = "invalid"))).ok)
    }

    @Test fun searchCoalescesQueriesAndIgnoresStaleResults() {
        connected()
        val first = command(HarmonyCommand("search", query = "a"))
        assertEquals(1, first.outbound.size)
        assertTrue(command(HarmonyCommand("search", query = "abc")).outbound.isEmpty())
        val old = frame("""{"kind":"search","query":"a","items":[{"sessionId":"old","snippet":"old"}]}""")
        assertTrue(old.state!!.searchSessionIds.isEmpty())
        assertEquals(1, old.outbound.size)
        val fresh = frame("""{"kind":"search","query":"abc","items":[{"sessionId":"new","snippet":"new"}]}""")
        assertEquals(listOf("new"), fresh.state!!.searchSessionIds)
        assertFalse(fresh.state.searching)
        assertTrue(fresh.outbound.isEmpty())
    }

    @Test fun directoriesAreSingleFlightAndLateResponseCannotOverwriteNewHost() {
        connected()
        assertTrue(command(HarmonyCommand("directories", path = "/home")).ok)
        assertFalse(command(HarmonyCommand("directories", path = "/other")).ok)
        command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.9/ws/mobile"))
        frame("""{"kind":"hello"}""")
        val stale = frame("""{"kind":"directories","path":"/home","home":"/home","crumbs":[],"entries":[]}""")
        assertEquals("", stale.state!!.directory.path)
    }

    @Test fun manualReconnectOnlyRetainsStateForSameGatewayIdentity() {
        connected()
        command(HarmonyCommand("select", sessionId = "s"))
        val restored = command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8:3080/ws/mobile", keepState = true))
        assertTrue(restored.ok)
        assertEquals("s", restored.state!!.selectedSessionId)
        assertFalse(command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.9/ws/mobile", keepState = true)).ok)
    }

    @Test fun renameAndArchiveWaitForMatchingHostAcknowledgement() {
        connected()
        frame(
            """{"kind":"sessions","items":[{"sessionId":"s","blank":false,"running":false,"updatedAt":100,"projections":{"values":{"title":"old"}}}]}""",
        )
        assertTrue(command(HarmonyCommand("rename", sessionId = "s", title = "new")).ok)
        assertFalse(command(HarmonyCommand("rename", sessionId = "s", title = "double")).ok)
        val other = frame("""{"kind":"session-renamed","sessionId":"other","title":"x","seq":2}""")
        assertTrue("session-rename" in other.state!!.catalogBusy)
        val renamed = frame("""{"kind":"session-renamed","sessionId":"s","title":"new","seq":3}""")
        assertEquals(
            "new",
            renamed.state!!
                .sessions
                .first { it.id == "s" }
                .title,
        )
        assertTrue(renamed.state.catalogBusy.isEmpty())
        command(HarmonyCommand("select", sessionId = "s"))
        command(HarmonyCommand("archive", sessionId = "s"))
        val archived = frame("""{"kind":"session-archived","sessionId":"s","archivedSessionIds":["s"]}""")
        assertTrue(archived.state!!.sessions.none { it.id == "s" })
        assertNull(archived.state.selectedSessionId)
    }

    @Test fun searchTimeoutDoesNotAutomaticallyResendSameQuery() {
        connected()
        val started = command(HarmonyCommand("search", query = "one"))
        val result = command(HarmonyCommand("operation-timeout", requestKey = started.state!!.pendingOperations.single()))
        assertFalse(result.ok)
        assertTrue(result.outbound.isEmpty())
        assertFalse(result.state!!.searching)
    }

    @Test fun workspaceCreationRequiresAcknowledgementAndPublishesReturnedWorkspace() {
        connected()
        command(HarmonyCommand("workspace-create", path = "/smoke/project"))
        val result =
            frame(
                """{"kind":"workspace-create","workspace":{"workspaceId":"created","path":"/smoke/project","title":"project","sessionIds":[],"createdAt":"2026-10-30","updatedAt":"2026-10-30"}}""",
            )
        assertTrue(result.ok)
        assertEquals(
            "created",
            result.state!!
                .workspaces
                .single()
                .workspaceId,
        )
        assertTrue(result.state.catalogBusy.isEmpty())
        assertEquals(1, result.outbound.size)
    }

    @Test fun catalogLoadingEndsAfterBothSnapshotsAndTimeoutKeepsKnownRows() {
        command(HarmonyCommand("prepare", endpoint = "ws://192.168.1.8/ws/mobile"))
        val hello = frame("""{"kind":"hello"}""")
        assertTrue(hello.state!!.catalogLoading)
        val first = frame("""{"kind":"sessions","items":[{"sessionId":"s","blank":false,"running":false,"updatedAt":100}]}""")
        assertTrue(first.state!!.catalogLoading)
        assertFalse(frame("""{"kind":"workspaces","items":[]}""").state!!.catalogLoading)
        val refresh = command(HarmonyCommand("refresh"))
        assertEquals(2, refresh.outbound.size)
        assertTrue(command(HarmonyCommand("refresh")).outbound.isEmpty())
        val timeout = command(HarmonyCommand("operation-timeout", requestKey = refresh.state!!.pendingOperations.single()))
        assertFalse(timeout.state!!.catalogLoading)
        assertEquals(
            "s",
            timeout.state.sessions
                .single()
                .id,
        )
    }

    @Test fun approvalKeepsBothLocalizedReasonsAndReplayWithoutTranslatingHostFallback() {
        connected()
        val localized =
            frame(
                """{"kind":"approval-requested","sessionId":"s","rpcId":"locale","approvalId":"a1","toolName":"write","reason":"host %1${'$'}s 原文","displayReason":{"zh-CN":"写入本轮文件","en":"Write owned test file"},"replay":true}""",
            ).state!!.approvals.single()
        assertEquals("写入本轮文件", localized.reason)
        assertEquals("Write owned test file", localized.reasonEnglish)
        assertTrue(localized.replay)
        val raw =
            frame(
                """{"kind":"approval-requested","sessionId":"s","rpcId":"raw","approvalId":"a2","toolName":"write","reason":"host 原文"}""",
            ).state!!.approvals.last()
        assertEquals("host 原文", raw.reason)
        assertEquals(raw.reason, raw.reasonEnglish)
    }
}
