package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.domain.SessionControlState
import com.clarklevis.dsh.shared.facade.SharedSessionControlEffect
import com.clarklevis.dsh.shared.facade.SharedMviEvent
import com.clarklevis.dsh.shared.facade.SharedMviEventObserver
import com.clarklevis.dsh.shared.facade.SharedSessionControlEventMetadata
import com.clarklevis.dsh.shared.facade.SharedSessionControlPatch
import com.clarklevis.dsh.shared.facade.SharedSessionControlStore
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSessionControlStoreTest {
    @Test
    fun subscriptionPushesCommittedPatchMetadataAndEffectExactlyOnce() {
        val store = SharedSessionControlStore()
        val events = mutableListOf<SharedMviEvent>()
        val subscription = store.subscribe(SharedMviEventObserver(events::add))

        val result = store.requestModels("session-push", isConnected = true)
        store.requestModels("session-push", isConnected = true)

        assertEquals(listOf("snapshot", "transition"), events.map { it.kind })
        assertEquals(listOf(0L, 1L), events.map { it.sequence })
        val transition = events.last()
        assertEquals("session-control", transition.domain)
        assertEquals(result.snapshotJson, transition.statePayloadJson)
        assertEquals(result.effectsJson, transition.effectsJson)
        val metadata = wireJson.decodeFromString<SharedSessionControlEventMetadata>(
            assertNotNull(transition.metadataJson)
        )
        assertTrue(metadata.applied)
        assertTrue(metadata.committed)
        assertEquals(1, wireJson.decodeFromString<List<SharedSessionControlEffect>>(
            transition.effectsJson
        ).size)

        subscription.cancel()
        store.setPermission("session-push", "unsupported", isConnected = true)
        assertEquals(2, events.size)
    }

    @Test
    fun sameKindRequestsAreSerializedAndLatestDifferentTargetIsQueued() {
        val store = SharedSessionControlStore()

        val first = store.requestModels("session-1", isConnected = true)
        val firstState = store.currentState()
        val effect = first.effects().single()
        assertEquals("models", effect.kind)
        assertEquals("session-1", effect.sessionId)
        assertEquals(effect.requestToken, firstState.requestTokens["models"])
        assertEquals("session-1", firstState.pendingModelsSessionId)
        assertTrue("models" in firstState.loadingKinds)

        val queued = store.requestModels("session-2", isConnected = true)
        assertTrue(queued.effects().isEmpty())
        assertEquals("session-2", store.currentState().queuedRequestTargets["models"]?.sessionId)

        // 旧 generation 的超时回调必须携带 token；结束 A 与启动 B 是同一事务。
        val timedOut = store.requestTimedOut("models", isDefault = false, effect.requestToken)
        val retryEffect = timedOut.effects().single()
        val retryState = store.currentState()
        assertTrue("models" in retryState.loadingKinds)
        assertEquals("session-2", retryState.pendingModelsSessionId)
        assertEquals(retryEffect.requestToken, retryState.requestTokens["models"])
        assertTrue(retryEffect.requestToken != effect.requestToken)
        assertFalse("models" in retryState.explicitSessionRequiredKinds)

        // 显式回显的旧 session 仍不得提交到 B。
        val late = store.modelsReceived("session-1", null, true, "[]", false)
        assertNull(late.snapshotJson)
        assertEquals(retryEffect.requestToken, store.currentState().requestTokens["models"])

        // Gateway 的 models 成功帧不回显 sessionId，绑定唯一 active generation。
        store.modelsReceived(null, null, true, "[]", false)
        val completed = store.currentState()
        assertFalse("models" in completed.loadingKinds)
        assertNull(completed.requestTokens["models"])
        assertNull(completed.pendingModelsSessionId)
    }

    @Test
    fun latestWinsCancelsQueuedTargetWhenActiveTargetIsRequestedAgain() {
        val store = SharedSessionControlStore()
        val first = store.requestModels("session-a", true)
        store.requestModels("session-b", true)

        val returnedToA = store.requestModels("session-a", true)
        assertTrue(returnedToA.effects().isEmpty())
        assertTrue(returnedToA.committed)
        assertTrue(store.currentState().queuedRequestTargets.isEmpty())

        val completedA = store.modelsReceived("session-a", null, true, "[]", false)
        assertEquals(first.effects().single().requestToken, completedA.completedRequestToken)
        assertTrue(completedA.effects().isEmpty())
        assertTrue(store.currentState().activeRequestTargets.isEmpty())
    }

    @Test
    fun normalGenerationAcceptsLegacyNilResponseAndRejectsExplicitOldSession() {
        val store = SharedSessionControlStore()
        val first = store.requestModels("session-a", true).effects().single()
        store.modelsReceived("session-a", null, true, "[]", false)

        val second = store.requestModels("session-b", true).effects().single()
        assertFalse("models" in store.currentState().explicitSessionRequiredKinds)

        // 显式旧 session 和旧 token 不能结束当前 generation。
        assertFalse(store.modelsReceived("session-a", null, true, "[]", false).applied)
        assertFalse(store.requestFailed("models", false, first.requestToken).applied)
        assertEquals(second.requestToken, store.currentState().requestTokens["models"])

        val completed = store.modelsReceived(null, null, true, "[]", false)
        assertTrue(completed.applied)
        assertTrue(completed.committed)
        assertEquals(second.requestToken, completed.completedRequestToken)
    }

    @Test
    fun normalSuccessfulDefaultRefreshAllowsSameTargetNextGeneration() {
        val store = SharedSessionControlStore()
        store.requestAgentPresets(true)
        store.agentPresetsReceived("[]", false, false)

        val repeated = store.requestAgentPresets(true)
        assertEquals("agent-presets", repeated.effects().single().kind)
        assertFalse("agent-presets" in store.currentState().quarantinedRequestKinds)
    }

    @Test
    fun normalSuccessfulTargetReuseAfterInterleavedGenerationStartsNewEffect() {
        val store = SharedSessionControlStore()
        store.requestModels("session-a", true)
        store.modelsReceived("session-a", null, true, "[]", false)
        store.requestModels("session-b", true)
        store.modelsReceived("session-b", null, true, "[]", false)

        val reused = store.requestModels("session-a", true)
        assertEquals("session-a", reused.effects().single().sessionId)
        assertFalse("models" in store.currentState().quarantinedRequestKinds)
    }

    @Test
    fun repeatedContextStatsAndDefaultsRefreshesEmitEffectsAfterNormalSuccess() {
        val store = SharedSessionControlStore()

        store.requestContextUsage("session-1", true)
        store.contextReceived("session-1", 1, null, null, null)
        assertEquals("context-usage", store.requestContextUsage("session-1", true).effects().single().kind)
        store.contextReceived("session-1", 2, null, null, null)

        store.requestSessionStats("session-1", true)
        store.statsReceived("session-1", 1, null, null, null)
        assertEquals("session-stats", store.requestSessionStats("session-1", true).effects().single().kind)
        store.statsReceived("session-1", 2, null, null, null)

        store.requestDefaults(true)
        store.defaultsReceived("standard", "workspace-write")
        assertEquals("defaults", store.requestDefaults(true).effects().single().kind)
        assertTrue(store.currentState().quarantinedRequestKinds.isEmpty())
    }

    @Test
    fun repeatedSameSessionRefreshAcceptsLegacyResponsesWithoutSessionId() {
        val store = SharedSessionControlStore()
        val permissions = """{"options":[{"value":"read-only","name":"Read"}],"currentValue":"read-only"}"""

        store.requestModels("session-1", true)
        assertTrue(store.modelsReceived(null, null, true, "[]", false).applied)
        val repeatedModels = store.requestModels("session-1", true)
        assertFalse("models" in store.currentState().explicitSessionRequiredKinds)
        assertTrue(store.modelsReceived(null, null, true, "[]", false).applied)

        store.requestPermissionOptions("session-1", true)
        assertTrue(store.permissionsReceived(null, permissions).applied)
        val repeatedPermissions = store.requestPermissionOptions("session-1", true)
        assertFalse("permission-options" in store.currentState().explicitSessionRequiredKinds)
        val completed = store.permissionsReceived(null, permissions)
        assertTrue(completed.applied)
        assertFalse("permission-options" in store.currentState().loadingKinds)
    }

    @Test
    fun abnormalTerminationClearsActiveStateAndAllowsRetryWithoutReconnect() {
        val store = SharedSessionControlStore()
        store.requestModels("session-a", true)
        store.modelsReceived("session-a", null, true, "[]", false)

        val second = store.requestModels("session-b", true)
        assertFalse("models" in store.currentState().explicitSessionRequiredKinds)
        val timedOut = store.requestTimedOut(
            "models",
            isDefault = false,
            second.effects().single().requestToken
        )

        assertTrue(timedOut.applied)
        assertFalse("models" in store.currentState().quarantinedRequestKinds)
        assertFalse("models" in store.currentState().explicitSessionRequiredKinds)
        assertFalse("models" in store.currentState().activeRequestTargets)
        assertEquals(1, store.requestModels("session-b", true).effects().size)

        val firstPermission = store.requestPermissionOptions("session-b", true).effects().single()
        store.requestTimedOut(
            "permission-options",
            isDefault = false,
            firstPermission.requestToken
        )
        val permissionTimeout = store.currentState()
        assertFalse("permission-options" in permissionTimeout.quarantinedRequestKinds)
        assertEquals(1, store.requestPermissionOptions("session-b", true).effects().size)
        val completedPermission = store.permissionsReceived(
            null,
            """{"options":[{"value":"read-only","name":"Read"}],"currentValue":"read-only"}"""
        )
        assertTrue(completedPermission.applied)
        assertFalse("permission-options" in store.currentState().loadingKinds)
    }

    @Test
    fun mergesControlResponsesAndFiltersUnsupportedPermissions() {
        val store = SharedSessionControlStore()
        store.mergeContextProjection(
            sessionId = "session-1",
            asOfSequence = 10,
            tokenUsageJson = """{"uncachedInputTokens":12}""",
            pressureJson = null,
            breakdownJson = null
        )
        store.mergeContextProjection(
            sessionId = "session-1",
            asOfSequence = null,
            tokenUsageJson = null,
            pressureJson = """{"pressureTokens":30,"contextWindow":100}""",
            breakdownJson = """{"systemTokens":4}"""
        )
        val context = store.currentState().contextSnapshots["session-1"]

        assertEquals(10, context?.asOfSeq)
        assertEquals(12, context?.tokenUsage?.uncachedInputTokens)
        assertEquals(30, context?.pressure?.pressureTokens)
        assertEquals(4, context?.breakdown?.systemTokens)

        store.mergePermissionsProjection(
            "session-1",
            """{"options":[{"value":"read-only","name":"Read"},{"value":"future","name":"Future"}],"currentValue":"read-only"}"""
        )
        val permissions = store.currentState().sessionPermissions["session-1"]
        assertEquals(listOf("read-only"), permissions?.options?.map { it.value })

        val rejected = store.mergePermissionProjection("session-1", "future")
        assertEquals("invalid-permission-state", rejected.errorCode)
        assertNull(rejected.snapshotJson)

        val invalidProjection = store.mergePermissionsProjection(
            "session-1",
            """{"options":[],"currentValue":"future"}"""
        )
        assertEquals("session-control-merge-permissions-projection-failed", invalidProjection.errorCode)
        assertNull(invalidProjection.snapshotJson)
    }

    @Test
    fun uncorrelatedInvalidPermissionResponseIsIgnoredWithoutError() {
        val store = SharedSessionControlStore()
        val withoutActive = store.permissionSelected("session-1", "future")
        assertTrue(withoutActive.isSuccess)
        assertFalse(withoutActive.applied)
        assertNull(withoutActive.snapshotJson)

        store.setPermission("session-1", "read-only", true)
        val wrongTarget = store.permissionSelected("session-2", "future")
        assertTrue(wrongTarget.isSuccess)
        assertFalse(wrongTarget.applied)
        assertNull(wrongTarget.snapshotJson)
        assertEquals("read-only", store.currentState().activeRequestTargets["permission"]?.value)
    }

    @Test
    fun defaultsAndStatsRemainKmpOwnedAcrossPartialResponses() {
        val store = SharedSessionControlStore()
        store.requestAgentPresets(isConnected = true)
        store.agentPresetsReceived(
            """[{"id":"standard","isDefault":true},{"id":"broken","isDefault":false,"broken":true}]""",
            authorable = true,
            hasDocument = true
        )
        store.requestDefaults(isConnected = true)
        store.defaultsReceived("standard", "workspace-write")
        var state = store.currentState()
        assertEquals("standard", state.agentPresetDefault)
        assertEquals("workspace-write", state.permissionDefault)
        assertTrue(state.agentPresetsAuthorable)

        val setDefault = store.setDefault("agent-preset", "standard", isConnected = true)
        assertEquals("set-default", setDefault.effects().single().kind)
        assertEquals("invalid-agent-preset", store.setDefault("agent-preset", "broken", true).errorCode)
        assertEquals(
            "unsupported-default-permission",
            store.setDefault("permission", "ask", isConnected = true).errorCode
        )

        store.requestSessionStats("session-1", true)
        store.statsReceived(
            "session-1",
            20,
            """{"turns":2,"steps":5}""",
            """{"inputTokens":100,"outputTokens":20}""",
            null
        )
        state = store.currentState()
        val stats = assertNotNull(state.sessionStatsSnapshots["session-1"])
        assertEquals(20, stats.asOfSeq)
        assertEquals(2, stats.stats?.turns)
        assertEquals(100, stats.tokenUsage?.totals?.inputTokens)
        // 同一次业务快照的第二部分通过独立 projection 合并，不伪造 request generation。
        store.mergeStatsProjection(
            "session-1", null, null, null,
            """{"projectedTokens":80,"contextWindow":1000}"""
        )
        state = store.currentState()
        assertEquals(80, state.sessionStatsSnapshots["session-1"]?.contextPressure?.projectedTokens)
    }

    @Test
    fun sequenceIsLongAndPreservesValuesAboveInt32Max() {
        val store = SharedSessionControlStore()
        val sequence = Int.MAX_VALUE.toLong() + 42L
        store.requestContextUsage("session-1", true)
        store.contextReceived("session-1", sequence, null, null, null)
        val state = store.currentState()
        assertEquals(sequence, state.contextSnapshots["session-1"]?.asOfSeq)
    }

    @Test
    fun timedOutRequestCanBeRetriedWithoutReconnect() {
        val store = SharedSessionControlStore()
        val first = store.requestAgentPresets(true).effects().single()
        store.requestTimedOut("agent-presets", true, first.requestToken)
        val timedOut = store.currentState()
        assertFalse("agent-presets" in timedOut.quarantinedRequestKinds)
        val retried = store.requestAgentPresets(true)
        assertEquals(1, retried.effects().size)
        assertTrue(store.agentPresetsReceived("[]", false, false).applied)
    }

    @Test
    fun disconnectedIntentReturnsStructuredFailureWithoutStateMutation() {
        val store = SharedSessionControlStore()
        val result = store.requestAgentPresets(isConnected = false)
        assertEquals("not-connected", result.errorCode)
        assertNull(result.snapshotJson)
        assertTrue(result.effects().isEmpty())
        assertTrue(store.currentState().defaultConfigurationLoadingKinds.isEmpty())
    }

    @Test
    fun committedPatchOnlyContainsAffectedSessionFragments() {
        val store = SharedSessionControlStore()
        store.mergeContextProjection("session-a", 1, """{"uncachedInputTokens":1}""", null, null)
        store.mergeContextProjection("session-b", 2, """{"uncachedInputTokens":2}""", null, null)
        store.mergeStatsProjection("session-b", 2, """{"turns":9}""", null, null)

        val result = store.mergeContextProjection(
            "session-a", 3, null,
            """{"pressureTokens":30,"contextWindow":100}""",
            null
        )
        val patch = result.patch()

        assertTrue(result.committed)
        assertEquals(setOf("session-a"), patch.contextSnapshotsUpsert.keys)
        assertTrue(patch.contextSnapshotsRemove.isEmpty())
        assertTrue(patch.sessionStatsSnapshotsUpsert.isEmpty())
        assertFalse(requireNotNull(result.snapshotJson).contains("session-b"))
        assertEquals(2, store.currentState().contextSnapshots["session-b"]?.asOfSeq)
    }

    @Test
    fun unchangedMutationDoesNotCrossBridgeWithSnapshotOrPatch() {
        val store = SharedSessionControlStore()
        store.mergeContextProjection("session-a", 1, """{"uncachedInputTokens":1}""", null, null)

        val unchanged = store.mergeContextProjection(
            "session-a", 1, """{"uncachedInputTokens":1}""", null, null
        )

        assertTrue(unchanged.applied)
        assertFalse(unchanged.committed)
        assertNull(unchanged.snapshotJson)
        assertTrue(unchanged.effects().isEmpty())
    }

    @Test
    fun sessionCleanupEmitsOnlyRemovalFragmentsAndPreservesOtherSessions() {
        val store = SharedSessionControlStore()
        for (sessionId in listOf("session-a", "session-b")) {
            store.mergeModelProjection(sessionId, """{"provider":"p","model":"m"}""")
            store.mergePermissionsProjection(sessionId, """{"options":[],"currentValue":"read-only"}""")
            store.mergeContextProjection(sessionId, 1, null, null, null)
            store.mergeStatsProjection(sessionId, 1, null, null, null)
        }

        val result = store.clearSessionData("session-a")
        val patch = result.patch()

        assertEquals(setOf("session-a"), patch.modelCatalogsRemove)
        assertEquals(setOf("session-a"), patch.sessionPermissionsRemove)
        assertEquals(setOf("session-a"), patch.contextSnapshotsRemove)
        assertEquals(setOf("session-a"), patch.sessionStatsSnapshotsRemove)
        assertTrue(patch.modelCatalogsUpsert.isEmpty())
        assertFalse(requireNotNull(result.snapshotJson).contains("session-b"))
        assertEquals(setOf("session-b"), store.currentState().modelCatalogs.keys)
        assertEquals(setOf("session-b"), store.currentState().sessionPermissions.keys)
    }

    @Test
    fun sessionCleanupDrainsOldNilSessionTerminalBeforeStartingQueuedTarget() {
        val store = SharedSessionControlStore()
        store.mergeContextProjection("session-a", 1, null, null, null)
        val active = store.requestModels("session-a", true).effects().single()
        store.requestModels("session-b", true)

        val cleared = store.clearSessionData("session-a")
        val state = store.currentState()
        assertTrue(cleared.effects().isEmpty())
        assertEquals(active.requestToken, state.requestTokens["models"])
        assertEquals("session-a", state.activeRequestTargets["models"]?.sessionId)
        assertEquals("session-b", state.queuedRequestTargets["models"]?.sessionId)
        assertTrue("models" in state.drainingRequestKinds)
        assertNull(state.contextSnapshots["session-a"])

        // 真实协议的 A 终态没有 sessionId：只消费 tombstone，不能回写 A payload。
        val drained = store.modelsReceived(null, null, true, "[]", false)
        val replacement = drained.effects().single()
        assertEquals("session-b", replacement.sessionId)
        assertTrue(replacement.requestToken != active.requestToken)
        assertNull(store.currentState().modelCatalogs["session-a"])
        assertFalse("models" in store.currentState().drainingRequestKinds)
        assertFalse("models" in store.currentState().explicitSessionRequiredKinds)
        assertFalse("models" in store.currentState().previousCompletedRequestTargets)

        // A 唯一终态已被消费，B 的真实 nil-session 终态可以安全投影。
        assertTrue(store.modelsReceived(null, null, true, "[]", false).applied)
        assertNotNull(store.currentState().modelCatalogs["session-b"])
    }

    @Test
    fun permissionOptionsNilSessionTerminalAlsoDrainsBeforeQueuedTarget() {
        val store = SharedSessionControlStore()
        store.requestPermissionOptions("session-a", true)
        store.requestPermissionOptions("session-b", true)
        store.clearSessionData("session-a")
        val payload = """{"options":[],"currentValue":"read-only"}"""

        val drained = store.permissionsReceived(null, payload)
        assertEquals("session-b", drained.effects().single().sessionId)
        assertNull(store.currentState().sessionPermissions["session-a"])
        assertTrue(store.permissionsReceived(null, payload).applied)
        assertEquals("read-only", store.currentState().sessionPermissions["session-b"]?.currentValue)
    }

    @Test
    fun drainingTimeoutOrTransportFailureQuarantinesWithoutStartingQueuedTarget() {
        for (terminate in listOf("timeout", "failure")) {
            val store = SharedSessionControlStore()
            val active = store.requestModels("session-a", true).effects().single()
            store.requestModels("session-b", true)
            store.clearSessionData("session-a")

            val terminated = if (terminate == "timeout") {
                store.requestTimedOut("models", false, active.requestToken)
            } else {
                store.requestFailed("models", false, active.requestToken)
            }
            val state = store.currentState()
            assertTrue(terminated.effects().isEmpty(), terminate)
            assertNull(state.activeRequestTargets["models"], terminate)
            assertNull(state.queuedRequestTargets["models"], terminate)
            assertNull(state.requestTokens["models"], terminate)
            assertFalse("models" in state.drainingRequestKinds, terminate)
            assertTrue("models" in state.quarantinedRequestKinds, terminate)
            assertFalse(store.modelsReceived(null, null, true, "[]", false).applied, terminate)
            assertFalse(store.requestModels("session-b", true).isSuccess, terminate)

            store.requestsDisconnected()
            assertTrue(store.currentState().quarantinedRequestKinds.isEmpty(), terminate)
        }
    }

    @Test
    fun batchCleanupNeverStartsQueuedTargetThatIsAlsoClearedRegardlessOfOrder() {
        for (idsJson in listOf("[\"session-a\",\"session-b\"]", "[\"session-b\",\"session-a\"]")) {
            val store = SharedSessionControlStore()
            val active = store.requestModels("session-a", true).effects().single()
            store.requestModels("session-b", true)

            val cleared = store.clearSessionsData(idsJson)
            val state = store.currentState()
            assertTrue(cleared.effects().isEmpty(), idsJson)
            assertEquals(active.requestToken, state.requestTokens["models"], idsJson)
            assertEquals("session-a", state.activeRequestTargets["models"]?.sessionId, idsJson)
            assertNull(state.queuedRequestTargets["models"], idsJson)
            assertTrue("models" in state.drainingRequestKinds, idsJson)

            val drained = store.modelsReceived(null, null, true, "[]", false)
            assertTrue(drained.effects().isEmpty(), idsJson)
            assertNull(store.currentState().activeRequestTargets["models"], idsJson)
            assertFalse("models" in store.currentState().previousCompletedRequestTargets, idsJson)
            assertNull(store.currentState().modelCatalogs["session-a"], idsJson)
            assertNull(store.currentState().modelCatalogs["session-b"], idsJson)
        }
    }

    @Test
    fun patchPayloadDoesNotGrowWithUnrelatedSessionCount() {
        fun payloadAfterPopulating(count: Int): String {
            val store = SharedSessionControlStore()
            repeat(count) { index ->
                store.mergeContextProjection("session-$index", index.toLong(), null, null, null)
            }
            return requireNotNull(
                store.mergeContextProjection(
                    "session-0", null, null,
                    """{"pressureTokens":30,"contextWindow":100}""",
                    null
                ).snapshotJson
            )
        }

        val oneHundred = payloadAfterPopulating(100)
        val oneThousand = payloadAfterPopulating(1_000)
        assertEquals(oneHundred.length, oneThousand.length)
        assertFalse(oneThousand.contains("session-999"))
        val patch = wireJson.decodeFromString<SharedSessionControlPatch>(oneThousand)
        assertEquals(setOf("session-0"), patch.contextSnapshotsUpsert.keys)
        assertTrue(patch.modelCatalogsUpsert.isEmpty())
        assertTrue(patch.sessionPermissionsUpsert.isEmpty())
        assertTrue(patch.sessionStatsSnapshotsUpsert.isEmpty())
    }

    private fun SharedSessionControlStore.currentState(): SessionControlState =
        wireJson.decodeFromString(requireNotNull(snapshot().snapshotJson))

    private fun com.clarklevis.dsh.shared.facade.SharedSessionControlResult.patch(): SharedSessionControlPatch =
        wireJson.decodeFromString(requireNotNull(snapshotJson))

    private fun com.clarklevis.dsh.shared.facade.SharedSessionControlResult.effects(): List<SharedSessionControlEffect> =
        wireJson.decodeFromString(effectsJson)
}
