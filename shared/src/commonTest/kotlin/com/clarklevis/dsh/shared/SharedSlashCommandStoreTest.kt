package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedSlashCommandStore
import com.clarklevis.dsh.shared.gateway.GatewayOutgoingImage
import com.clarklevis.dsh.shared.gateway.GatewayRequests
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedSlashCommandStoreTest {
    private val catalog = """
        {"kind":"commands","sessionId":"s1","groups":[{"id":"host","title":"Host 命令","items":[
          {"name":"compact","description":"Compact history","source":"host","action":"execute","ui":{"kind":"immediate","submitRequest":"command-execute"}},
          {"name":"goal","description":"Set a goal","source":"host","action":"execute","input":{"hint":"输入目标"},"ui":{"kind":"input","hint":"输入目标","displayHint":"输入目标，智能体将持续执行","images":false,"submitRequest":"command-execute"}},
          {"name":"plan","description":"Enter plan mode","source":"host","action":"execute","input":{"hint":"[off|message]"},"ui":{"kind":"input","hint":"[off|message]","displayHint":"描述你的任务以生成计划","images":true,"submitRequest":"command-execute"}},
          {"name":"model","description":"Select model","source":"client","action":"select-model","ui":{"kind":"select","optionsRequest":"command-options","selectionRequest":"command-select"}}
        ]}]}
    """.trimIndent()

    private val groupedCatalog = """
        {"kind":"commands","sessionId":"s1","groups":[
          {"id":"commands","title":"命令","items":[
            {"id":"command:compact","name":"compact","description":"Compact history","source":"host","ui":{"kind":"immediate","submitRequest":"message","submitText":"/compact"}}
          ]},
          {"id":"skills","title":"技能","items":[
            {"id":"skill:android-cli","name":"android-cli","description":"Android CLI instructions","source":"skill","action":"insert","modelInvocable":true,"whenToUse":"连接 Android 设备","ui":{"kind":"input","insertText":"/android-cli ","images":true,"submitRequest":"message"}}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun slashLookupLoadsAndFiltersCatalog() {
        val store = SharedSlashCommandStore()
        val opened = store.updateInput("s1", "/", isConnected = true, isSupported = true)
        assertEquals("commands", opened.request?.requestType)
        assertTrue(opened.snapshot.catalogVisible)

        store.acceptFrame(catalog)
        val filtered = store.updateInput("s1", "/go", isConnected = true, isSupported = true)
        assertEquals(listOf("goal"), filtered.snapshot.filteredCommands.map { it.name })
        assertNull(filtered.request)
    }

    @Test
    fun commandCatalogUsesClientLocaleAndHostExecutionUsesDedicatedRequest() {
        val store = SharedSlashCommandStore()
        val catalog = store.updateInput("s1", "/", isConnected = true, isSupported = true, locale = "zh-CN")
        assertTrue(catalog.request?.payload?.contains("\"locale\":\"zh-CN\"") == true)

        val execution = GatewayRequests.commandExecute(
            sessionId = "s1",
            line = "/plan 适配移动端",
            images = listOf(GatewayOutgoingImage("image/png", "AQID", "plan.png"))
        )
        assertEquals("command-execute", execution.requestType)
        assertEquals("command-executed", execution.responseKind)
        assertTrue(execution.payload.contains("\"line\":\"/plan 适配移动端\""))
        assertTrue(execution.payload.contains("\"images\""))
    }

    @Test
    fun commandLifecycleAcceptsObjectSourceWithoutBreakingFrameDecoding() {
        val frame = GatewayWireDecoder.decode(
            """{"kind":"event","sessionId":"s1","seq":89,"time":89,"event":{"type":"command/run","commandId":"cmd-compact","name":"compact","source":{"kind":"user"}}}"""
        )

        assertEquals("command/run", frame.event?.type)
        assertEquals("user", frame.event?.source)
        assertEquals("cmd-compact", frame.event?.commandId)
        assertEquals("compact", frame.event?.name)
    }

    @Test
    fun historyNormalizationKeepsCompactionLifecycleFields() {
        val frame = GatewayWireDecoder.decode(
            """{"kind":"history","events":[{"type":"compaction/summary","seq":90,"time":90,"data":{"compactionId":"cmp-1","sourceCommandId":"cmd-1","shadowedItemCount":11,"shadowedTokenCount":3441}}]}"""
        )
        val event = frame.events?.single()?.normalized("s1")?.event

        assertEquals("cmp-1", event?.compactionId)
        assertEquals("cmd-1", event?.sourceCommandId)
        assertEquals(11, event?.shadowedItemCount)
        assertEquals(3441, event?.shadowedTokenCount)
    }

    @Test
    fun uiDescriptorDrivesImmediateInputAndSelectBehavior() {
        val store = SharedSlashCommandStore()
        store.updateInput("s1", "/", true, true)
        store.acceptFrame(catalog)

        val immediate = store.selectCommand("compact")
        assertEquals("/compact", immediate.commandExecution?.line)
        assertNull(immediate.submitText)

        store.updateInput("s1", "/", true, true)
        val input = store.selectCommand("goal")
        assertEquals("/goal", input.snapshot.commandToken)
        assertEquals("/goal ", input.replacementText)
        assertEquals("输入目标，智能体将持续执行", input.snapshot.argumentHint)
        assertTrue(input.clearDraft)

        val withoutTrailingSpace = store.updateInput("s1", "/goal", true, true)
        assertEquals("/goal", withoutTrailingSpace.snapshot.commandToken)
        val shortened = store.updateInput("s1", "/goa", true, true)
        assertNull(shortened.snapshot.commandToken)
        assertTrue(shortened.snapshot.catalogVisible)
        assertEquals("goa", shortened.snapshot.query)

        store.updateInput("s1", "/", true, true)
        val select = store.selectCommand("model")
        assertEquals("command-options", select.request?.requestType)
        assertEquals("model", select.snapshot.optionsCommand?.name)
        assertEquals("/model", select.snapshot.commandToken)
        assertEquals("/model", select.replacementText)
    }

    @Test
    fun manuallyTypedInputCommandActivatesAfterCatalogLoads() {
        val store = SharedSlashCommandStore()

        val pendingCatalog = store.updateInput("s1", "/plan ", isConnected = true, isSupported = true)
        assertEquals("commands", pendingCatalog.request?.requestType)
        assertNull(pendingCatalog.snapshot.commandToken)

        val activated = store.acceptFrame(catalog)
        assertEquals("/plan", activated.snapshot.commandToken)
        assertEquals("描述你的任务以生成计划", activated.snapshot.argumentHint)
        assertFalse(activated.snapshot.catalogVisible)

        val withArgument = store.updateInput("s1", "/plan 重构移动端", isConnected = true, isSupported = true)
        assertEquals("/plan", withArgument.snapshot.commandToken)
        assertFalse(withArgument.snapshot.catalogVisible)
        assertEquals("/plan 重构移动端", store.commandExecutionForInput("/plan 重构移动端")?.line)
    }

    @Test
    fun genericSelectionIsCorrelatedAndReturnsModelProjection() {
        val store = SharedSlashCommandStore()
        store.updateInput("s1", "/", true, true)
        store.acceptFrame(catalog)
        store.selectCommand("model")
        store.acceptFrame(
            """{"kind":"command-options","sessionId":"s1","command":"model","options":[{"id":"opaque","label":"DeepSeek V4","detail":"DeepSeek","selected":true}]}"""
        )
        val request = store.selectOption("opaque")
        assertEquals("command-select", request.request?.requestType)
        assertEquals("", request.replacementText)
        val clearedDraft = store.updateInput("s1", "", true, true)
        assertEquals("model", clearedDraft.snapshot.optionsCommand?.name)

        val ignored = store.acceptFrame(
            """{"kind":"command-selected","sessionId":"other","command":"model","selected":{"id":"opaque","label":"Wrong","selected":true}}"""
        )
        assertTrue(ignored.snapshot.selectionLoading)

        val selected = store.acceptFrame(
            """{"kind":"command-selected","sessionId":"s1","command":"model","selected":{"id":"opaque","label":"DeepSeek V4","detail":"DeepSeek","selected":true},"value":{"provider":"deepseek","model":"v4","reasoningEffort":"high"}}"""
        )
        assertFalse(selected.snapshot.selectionLoading)
        assertNull(selected.snapshot.commandToken)
        assertEquals("DeepSeek V4", selected.snapshot.selectedLabel("model"))
        assertEquals("v4", selected.selectedModel?.model)
    }

    @Test
    fun groupedCatalogDecodesWithoutModelGroupConflictAndIncludesSkills() {
        val store = SharedSlashCommandStore()
        store.updateInput("s1", "/", true, true)

        val accepted = store.acceptFrame(groupedCatalog)

        assertNull(accepted.snapshot.lastError)
        assertEquals(listOf("命令", "技能"), accepted.snapshot.groups.map { it.title })
        assertEquals(listOf("compact", "android-cli"), accepted.snapshot.commands.map { it.name })
    }

    @Test
    fun skillSelectionUsesServerInsertTextAndCanBeFilteredByWhenToUse() {
        val store = SharedSlashCommandStore()
        store.updateInput("s1", "/", true, true)
        store.acceptFrame(groupedCatalog)

        val filtered = store.updateInput("s1", "/设备", true, true)
        assertEquals(listOf("技能"), filtered.snapshot.filteredGroups.map { it.title })
        assertEquals(listOf("android-cli"), filtered.snapshot.filteredCommands.map { it.name })

        val selected = store.selectItem("skill:android-cli")
        assertEquals("/android-cli", selected.snapshot.commandToken)
        assertEquals("/android-cli ", selected.replacementText)
        assertTrue(selected.snapshot.commandAllowsImages)
    }
}
