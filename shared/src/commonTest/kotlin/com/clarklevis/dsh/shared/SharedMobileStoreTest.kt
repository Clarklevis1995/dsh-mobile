package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMobileFacade
import com.clarklevis.dsh.shared.facade.SharedMobileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedMobileStoreTest {
    @Test
    fun facadeNormalizesWireKindForPlatformAdapters() {
        assertEquals(
            "event",
            SharedMobileFacade().decodeFrameKind(GatewayProtocolFixtures.LIVE_EVENT_WITHOUT_KIND)
        )
    }

    @Test
    fun manualFixtureUsesSharedReducersAndProjection() {
        val store = SharedMobileStore()
        val snapshot = store.loadManualTestFixture()
        assertEquals("android-demo", snapshot.selectedSessionId)
        assertEquals(1, snapshot.sessions.size)
        assertTrue(snapshot.sessions.single().isRunning)
        assertEquals(2, snapshot.conversation.size)
        assertEquals("共享协议解码、Reducer 与投影已接入。", snapshot.conversation.last().text)
        assertEquals(1, snapshot.pendingQuestionCount)
        assertEquals("question-requested", snapshot.lastFrameKind)
        assertNull(snapshot.lastError)
    }

    @Test
    fun invalidFrameIsReportedWithoutDestroyingCurrentState() {
        val store = SharedMobileStore()
        store.loadManualTestFixture()
        val snapshot = store.acceptFrame("not-json")
        assertEquals(1, snapshot.sessions.size)
        assertTrue(!snapshot.lastError.isNullOrBlank())
    }

    @Test
    fun historyAndStreamCreateSessionsWithReliableTimestamps() {
        val store = SharedMobileStore(nowEpochSeconds = { 1_800_000_000.0 })

        var snapshot = store.acceptFrame(
            """{"kind":"history","sessionId":"empty-history","events":[]}"""
        )
        assertEquals(1_800_000_000.0, snapshot.sessions.single().lastActivityEpochSeconds)

        snapshot = store.acceptFrame(
            """{"kind":"event","sessionId":"stream","seq":1,"time":1800000001000,"event":{"type":"assistant/chunk","text":"delta"}}"""
        )
        assertEquals(
            1_800_000_001.0,
            snapshot.sessions.first { it.id == "stream" }.lastActivityEpochSeconds
        )
    }

    @Test
    fun productFramesPopulateWorkspaceSearchAndControlUiState() {
        val store = SharedMobileStore()
        var snapshot = store.acceptFrame(
            """{"kind":"workspaces","items":[{"workspaceId":"w1","path":"/work","title":"Work","sessionIds":["s1"],"createdAt":"now","updatedAt":"now"}]}"""
        )
        assertEquals("Work", snapshot.workspaces.single().title)

        snapshot = store.acceptFrame(
            """{"kind":"search","items":[{"sessionId":"s1","snippet":"match"}]}"""
        )
        assertEquals(listOf("s1"), snapshot.searchResultSessionIds)

        snapshot = store.acceptFrame(
            """{"kind":"agent-presets","agentPresetDefault":"standard","presets":[{"id":"standard","isDefault":true,"name":"Standard"}]}"""
        )
        assertEquals("standard", snapshot.agentPresetDefault)
        assertEquals("Standard", snapshot.agentPresets.single().name)

        snapshot = store.acceptFrame(
            """{"kind":"set-default","target":"agent-preset","value":"cordis","applied":true}"""
        )
        assertEquals("cordis", snapshot.agentPresetDefault)

        snapshot = store.acceptFrame(
            """{"kind":"set-default","target":"permission","value":"workspace-write","applied":true}"""
        )
        assertEquals("workspace-write", snapshot.permissionDefault)

        snapshot = store.acceptFrame(
            """{"kind":"models","current":{"provider":"deepseek","model":"deepseek-chat"},"routable":true,"groups":[{"id":"deepseek","name":"DeepSeek","models":[{"id":"deepseek-chat","name":"DeepSeek Chat"}]}]}"""
        )
        assertEquals("deepseek-chat", snapshot.modelCatalog?.current?.model)

        snapshot = store.acceptFrame(
            """{"kind":"session-stats","asOfSeq":4,"sessionStats":{"turns":2,"steps":3},"contextPressure":{"pressureTokens":100,"contextWindow":1000}}"""
        )
        assertEquals(2, snapshot.statsSnapshot?.stats?.turns)
        assertEquals(1_000, snapshot.statsSnapshot?.contextPressure?.contextWindow)

        snapshot = store.acceptFrame(
            """{"kind":"host","version":"0.1.11","cwd":"/work","provider":"deepseek-official","model":"deepseek-v4","attachedSessions":12,"canOpenPath":true}"""
        )
        assertEquals("0.1.11", snapshot.hostSnapshot?.version)
        assertEquals("deepseek-official", snapshot.hostSnapshot?.provider)
        assertEquals(12, snapshot.hostSnapshot?.attachedSessions)
    }
}
