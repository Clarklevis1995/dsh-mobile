package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.BotCreationRules
import com.clarklevis.dsh.shared.facade.SharedBotStore
import com.clarklevis.dsh.shared.facade.SharedBotTransition
import com.clarklevis.dsh.shared.protocol.GatewayWireDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedBotStoreTest {
    private fun conversationStore(readOnly: Boolean = false): SharedBotStore {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.payload.contains("bot-subscribe") }.correlationId
        store.acceptJson("""{"kind":"bot-subscribed","requestId":"$subscription","hello":{"capabilities":["bot-conversations"]}}""")
        response(store, start, "state", """{"revision":1,"readOnly":$readOnly,"bots":[
            {"id":"a","name":"Chief","role":"主管"},{"id":"b","name":"研究员","hidden":true},
            {"id":"c","name":"Studio"}],"rooms":[]}""")
        return store
    }

    @Test
    fun groupSelectionPreservesOrderPromotesAdminAndIncludesHiddenBots() {
        val store = conversationStore()
        assertEquals("b", store.searchConversationBots("研究").single().id)
        assertEquals("a", store.searchConversationBots("主管").single().id)
        listOf("b", "a", "c", "b", "b").forEach { store.toggleConversationMember(it) }
        assertEquals(listOf("a", "c", "b"), store.snapshot().conversation.memberIds)
        assertEquals("a", store.snapshot().conversation.adminId)
        val submit = store.createConversationRoom()
        val payload = Json.parseToJsonElement(submit.requests.single().payload).jsonObject["payload"]!!.jsonObject
        assertEquals("a", payload["admin"]!!.jsonPrimitive.content)
        assertEquals("""["a","c","b"]""", payload["members"].toString())
        assertEquals("true", payload["greet"].toString())
        assertTrue(store.createConversationRoom().requests.isEmpty())
        store.toggleConversationMember("a")
        assertEquals("a", store.snapshot().conversation.adminId)
        response(store, submit, "create-room", """{"id":"new","name":"主机命名","members":["a","c","b"]}""")
        assertEquals("new", store.snapshot().conversation.openedId)
    }

    @Test
    fun acceptedConversationNavigationIsConsumedOnceAndNewGroupStartsClean() {
        val store = conversationStore()
        store.toggleConversationMember("a")
        store.toggleConversationMember("b")
        response(store, store.createConversationRoom(), "create-room",
            """{"id":"new","members":["a","b"]}""")
        store.consumeConversationNavigation("stale")
        assertEquals("new", store.snapshot().conversation.openedId)
        store.consumeConversationNavigation("new")
        assertNull(store.snapshot().conversation.openedId)
        assertTrue(store.snapshot().conversation.supported)
        assertTrue(store.snapshot().chats.any { it.id == "new" })
        store.beginConversation()
        assertTrue(store.snapshot().conversation.memberIds.isEmpty())
        assertNull(store.snapshot().conversation.createdId)
        store.openConversationBot("a")
        assertEquals("a", store.snapshot().conversation.openedId)
        store.consumeConversationNavigation("a")
        assertNull(store.snapshot().conversation.openedId)
        store.openConversationBot("a")
        assertEquals("a", store.snapshot().conversation.openedId)
    }

    @Test
    fun groupFirstMessageWaitsForRoomAndNeverCreatesTwiceOnFailure() {
        val store = conversationStore()
        store.toggleConversationMember("a"); store.toggleConversationMember("b")
        store.updateConversationMessage("  请汇总  ")
        val submit = store.createConversationRoom()
        assertFalse(submit.requests.single().payload.contains("greet"))
        val created = response(store, submit, "create-room", """{"id":"new","name":"主机命名","members":["a","b"]}""")
        assertNull(store.snapshot().conversation.openedId)
        val send = created.requests.single { it.payload.contains("\"endpoint\":\"send\"") }
        assertTrue(send.payload.contains("请汇总"))
        store.acceptJson("""{"kind":"error","requestId":"${send.correlationId}","code":"bot/denied","message":"主机错误"}""")
        assertEquals("主机错误", store.snapshot().conversation.errorDetail)
        assertEquals("  请汇总  ", store.snapshot().conversation.message)
        assertEquals("new", store.snapshot().conversation.createdId)
        assertTrue(store.createConversationRoom().requests.isEmpty())
        val retry = store.retryConversationMessage()
        assertTrue(retry.requests.single().payload.contains("\"endpoint\":\"send\""))
        response(store, retry, "send", "true")
        assertEquals("new", store.snapshot().conversation.openedId)
    }

    @Test
    fun preCreateStateReplyCannotRemoveNewRoomDuringNavigation() {
        val store = conversationStore()
        val earlier = store.refresh()
        store.toggleConversationMember("a"); store.toggleConversationMember("b")
        val created = response(store, store.createConversationRoom(), "create-room",
            """{"id":"new","name":"自动群名","members":["a","b"]}""")
        response(store, earlier, "state", """{"revision":2,"bots":[],"rooms":[]}""")
        assertTrue(store.snapshot().chats.any { it.id == "new" })
        assertEquals("new", store.snapshot().conversation.openedId)
        assertTrue(created.requests.any { it.payload.contains("\"endpoint\":\"state\"") })
    }

    @Test
    fun groupTimeoutRetainsDraftAndBlocksDuplicateCreateAcrossReconnect() {
        val store = conversationStore()
        store.toggleConversationMember("a"); store.toggleConversationMember("b")
        store.updateConversationMessage("草稿")
        val request = store.createConversationRoom().requests.single()
        store.requestFailed(requireNotNull(request.correlationId), "bot-timeout")
        assertTrue(store.snapshot().conversation.uncertain)
        assertEquals("草稿", store.snapshot().conversation.message)
        assertTrue(store.createConversationRoom().requests.isEmpty())
        store.connectionChanged(true, false)
        store.connectionChanged(true, true)
        assertTrue(store.snapshot().conversation.uncertain)
        store.confirmConversationNotCreated()
        assertFalse(store.snapshot().conversation.uncertain)
        assertEquals("草稿", store.snapshot().conversation.message)
        assertEquals(listOf("a", "b"), store.snapshot().conversation.memberIds)
    }

    @Test
    fun openingHiddenBotRestoresOnlyItsVisibilityAndUsesExistingHistory() {
        val store = conversationStore()
        val restored = store.openConversationBot("b")
        assertTrue(store.snapshot().conversation.saving)
        val request = restored.requests.single()
        assertTrue(request.payload.contains("set-flags"))
        assertFalse(request.payload.contains("session-create"))
        response(store, restored, "set-flags", """{"id":"b"}""")
        assertEquals("b", store.snapshot().conversation.openedId)
        assertFalse(store.snapshot().chats.single { it.id == "b" }.hidden)
        assertTrue(store.selectChat("b").requests.any { it.payload.contains("chat") })
    }

    @Test
    fun groupCreationRequiresWritableHostCapabilityAndTwoLiveBots() {
        val store = conversationStore()
        store.toggleConversationMember("a")
        assertTrue(store.createConversationRoom().requests.isEmpty())
        val readOnly = conversationStore(true)
        readOnly.toggleConversationMember("a"); readOnly.toggleConversationMember("b")
        assertTrue(readOnly.createConversationRoom().requests.isEmpty())
        assertTrue(readOnly.openConversationBot("b").requests.isEmpty())
        store.connectionChanged(true, false)
        assertTrue(store.createConversationRoom().requests.isEmpty())
    }

    @Test
    fun sidebarHidingPreservesGroupMembersAvatarsAndSelectedGroup() {
        val store = SharedBotStore()
        fun team(revision: Int, hidden: Boolean) = """{"revision":$revision,"mainBotId":"chief",
            "bots":[{"id":"chief","name":"Chief"},
                {"id":"researcher","name":"研究员","hidden":$hidden,"avatar":{"imageRef":"research-avatar"}}],
            "rooms":[{"id":"room","name":"项目组","members":["chief","researcher"]}]}"""
        response(store, store.activate(true, true), "state", team(1, false))
        response(store, store.selectChat("room"), "room-chat",
            """{"items":[{"id":"post","kind":"bot","botId":"researcher","name":"研究员","text":"研究结论"}]}""")
        response(store, store.refresh(), "state", team(2, true))

        val snapshot = store.snapshot()
        assertEquals("room", snapshot.selectedChatId)
        assertEquals("researcher", snapshot.messages.single().botId)
        assertEquals(listOf("chief", "researcher"), snapshot.roster.map { it.id })
        assertEquals(listOf("chief", "researcher"), snapshot.chats.single { it.room }.memberIds)
        assertTrue(snapshot.chats.single { it.id == "researcher" }.hidden)
        assertEquals(listOf("room"), store.searchChats("").map { it.id })
        assertEquals("room", store.searchChats("项目组").single().id)
        assertTrue(store.searchChats("研究员").isEmpty())

        val restored = SharedBotStore()
        restored.restoreCacheJson(store.exportCacheJson())
        assertEquals(listOf("chief", "researcher"), restored.snapshot().roster.map { it.id })
        assertEquals(listOf("room"), restored.searchChats("").map { it.id })

        response(store, store.refresh(), "state", team(3, false))
        assertEquals(setOf("researcher", "room"), store.searchChats("").map { it.id }.toSet())
    }

    @Test
    fun initiallyHiddenMemberStillLoadsItsGroupAvatar() {
        val store = SharedBotStore()
        val loaded = response(store, store.activate(true, true), "state", """{"revision":1,
            "bots":[{"id":"researcher","name":"研究员","hidden":true,"avatar":{"imageRef":"avatar-v1"}}],
            "rooms":[{"id":"room","name":"项目组","members":["researcher"]}]}""")
        val image = "data:image/png;base64,aGVsbG8="
        response(store, loaded, "avatar",
            """{"botId":"researcher","ref":"avatar-v1","image":"$image"}""")
        val room = store.searchChats("").single()
        assertEquals("room", room.id)
        val member = store.snapshot().chats.single { it.id == room.memberIds.single() }
        assertEquals("研究员", member.name)
        assertEquals(image, member.image)
        assertTrue(member.hidden)
    }

    @Test
    fun hiddenGroupHasItsOwnVisibilityAndMainBotRemainsAvailable() {
        val store = SharedBotStore()
        response(store, store.activate(true, true), "state", """{"revision":1,"mainBotId":"chief",
            "mainBotIds":["chief","deputy"],
            "bots":[{"id":"chief","name":"Chief","hidden":true},
                {"id":"deputy","name":"Deputy","hidden":true},{"id":"worker","name":"Worker"}],
            "rooms":[{"id":"hidden-group","name":"Hidden","hidden":true,"members":["worker"]},
                {"id":"visible-group","name":"Visible","members":["worker"]}]}""")
        assertEquals(setOf("deputy", "worker", "visible-group"), store.searchChats("").map { it.id }.toSet())
        assertTrue(store.searchChats("Hidden").isEmpty())
        assertEquals("chief", store.searchChats("Chief").single().id)
        assertEquals(5, store.snapshot().chats.size)
        assertTrue(store.selectChat("hidden-group").requests.any { it.payload.contains("room-chat") })
    }

    @Test
    fun homeSearchMatchesNamesAndCachedMessagesWithoutSelectingAChat() {
        val store = SharedBotStore()
        store.restoreCacheJson("""
            {"version":1,"snapshot":{"mainBotId":"chief",
              "chats":[{"id":"chief","name":"Chief"},
                       {"id":"room","name":"Project","room":true,"preview":"季度汇总"},
                       {"id":"worker","name":"研究员"}],
              "unreadCounts":{"room":2}},
             "histories":{"chief":[{"id":"old","kind":"bot","text":"验证消息同步"}],
                          "room":[{"id":"raw","kind":"event","text":"内部事件"}]},
             "optimistic":{}}
        """.trimIndent())

        assertEquals(listOf("room", "worker"), store.searchChats("  ").map { it.id })
        assertEquals("room", store.searchChats(" PROJECT ").single().id)
        assertEquals("room", store.searchChats("季度汇总").single().id)
        assertEquals("chief", store.searchChats("消息同步").single().id)
        assertTrue(store.searchChats("内部事件").isEmpty())
        assertNull(store.snapshot().selectedChatId)
        assertEquals(2, store.snapshot().unreadCount("room"))
    }

    @Test
    fun unreadCountsOnlyNewMessagesAndDeduplicatesLiveAndReplay() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        fun emit(kind: String, data: String) =
            store.acceptJson("""{"kind":"bot-event","subscriptionId":"$subscription","botEvent":"$kind","botData":$data}""")

        emit("hello", """{"boot":"first","seq":10}""")
        emit("notice", """{"seq":12,"kind":"group","roomId":"room","botId":"chief","text":"组员消息"}""")
        assertEquals(0, store.snapshot().unreadCount("room"), "实时流先到时等待缺失的通知")
        val replay = """{"boot":"first","seq":12,"notices":[
          {"seq":10,"kind":"message","botId":"chief","text":"首次同步前的旧消息"},
          {"seq":11,"kind":"message","botId":"worker","text":"新私聊"},
          {"seq":12,"kind":"group","roomId":"room","botId":"chief","text":"组员消息"}
        ]}"""
        response(store, start, "notices", replay)
        emit("notice", """{"seq":12,"kind":"group","roomId":"room","botId":"chief"}""")
        emit("notice", """{"seq":13,"kind":"done","roomId":"room","botId":"chief"}""")
        emit("notice", """{"seq":14,"kind":"approval","botId":"worker"}""")
        emit("notice", """{"seq":15,"kind":"group","roomId":"room","botId":"worker"}""")
        assertEquals(mapOf("room" to 2, "worker" to 1), store.snapshot().unreadCounts)

        store.selectChat("room")
        emit("notice", """{"seq":16,"kind":"group","roomId":"room","botId":"chief"}""")
        assertEquals(0, store.snapshot().unreadCount("room"))
        store.setForeground(false)
        emit("notice", """{"seq":17,"kind":"group","roomId":"room","botId":"chief"}""")
        assertEquals(1, store.snapshot().unreadCount("room"))
        store.setForeground(true)
        assertEquals(0, store.snapshot().unreadCount("room"))
        store.selectChat(null)
        emit("notice", """{"seq":18,"kind":"group","roomId":"room","botId":"chief"}""")
        assertEquals(1, store.snapshot().unreadCount("room"))
    }

    @Test
    fun runningIndicatorTakesPriorityAndRevealsUnreadAfterCompletion() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        fun emit(kind: String, data: String) =
            store.acceptJson("""{"kind":"bot-event","subscriptionId":"$subscription","botEvent":"$kind","botData":$data}""")

        response(store, start, "state", """
            {"revision":1,"bots":[{"id":"chief","name":"Chief"},{"id":"worker","name":"Worker"}],
             "rooms":[{"id":"room","sessionId":"group-session","name":"Team","members":["worker"]}]}
        """.trimIndent())
        val refresh = emit("hello", """{"boot":"run","seq":0}""")
        val working = response(store, refresh, "state", """
            {"revision":1,"unchanged":true,"work":{
               "bots":{"worker":{"roomId":"room","since":1000,"tool":"web_search"}},
               "rooms":{"room":[{"botId":"worker","since":1000,"tool":"web_search"}]},
               "turns":{"chief":1000}}}
        """.trimIndent()).snapshot
        assertTrue(working.chatIndicator("room")?.running == true)
        assertTrue(working.chatIndicator("chief")?.running == true)
        assertNull(working.chatIndicator("worker"), "群聊中的工作不应标记为同一成员的私聊")

        var afterNotice = emit("notice", """{"seq":1,"kind":"group","roomId":"room","botId":"worker"}""")
        val refreshAfterNotice = afterNotice
        for (seq in 2..99) {
            afterNotice = emit("notice", """{"seq":$seq,"kind":"group","roomId":"room","botId":"worker"}""")
        }
        assertTrue(afterNotice.snapshot.chatIndicator("room")?.running == true)
        assertEquals(99, afterNotice.snapshot.unreadCount("room"))
        val finished = response(store, refreshAfterNotice, "state", """{"revision":1,"unchanged":true,"work":{}}""").snapshot
        assertEquals(false, finished.chatIndicator("room")?.running)
        assertEquals("99", finished.chatIndicator("room")?.unreadText)
        assertNull(finished.chatIndicator("chief"), "运行结束且无未读时隐藏角标")

        val overflow = emit("notice", """{"seq":100,"kind":"group","roomId":"room","botId":"worker"}""").snapshot
        assertEquals("99+", overflow.chatIndicator("room")?.unreadText)
        assertEquals(100, overflow.chatIndicator("room")?.unreadCount)
        assertNull(store.selectChat("room").snapshot.chatIndicator("room"))
    }

    @Test
    fun unreadCountsAndReplayCursorSurviveLocalCacheAndHostRestart() {
        val first = SharedBotStore()
        val start = first.activate(true, true)
        val sub = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        first.acceptJson("""{"kind":"bot-event","subscriptionId":"$sub","botEvent":"hello","botData":{"boot":"old","seq":4}}""")
        first.acceptJson("""{"kind":"bot-event","subscriptionId":"$sub","botEvent":"notice","botData":{"seq":5,"kind":"message","botId":"chief"}}""")
        val restored = SharedBotStore()
        restored.restoreCacheJson(first.exportCacheJson())
        assertEquals(1, restored.snapshot().unreadCount("chief"))
        val connect = restored.activate(true, true)
        val requestBodies = connect.requests.map { Json.parseToJsonElement(it.payload).jsonObject }
        val request = requestBodies.first { it["endpoint"]?.jsonPrimitive?.content == "notices" }
        val cursor = request.getValue("payload").jsonObject
        assertEquals("old", cursor.getValue("boot").jsonPrimitive.content)
        assertEquals("5", cursor.getValue("since").jsonPrimitive.content)
        val newSub = connect.requests.first { it.requestType == "bot-subscribe" }.correlationId
        restored.acceptJson("""{"kind":"bot-event","subscriptionId":"$newSub","botEvent":"hello","botData":{"boot":"new","seq":2}}""")
        restored.selectChat("room")
        restored.selectChat(null)
        response(restored, connect, "notices", """
            {"boot":"new","seq":3,"reset":true,"notices":[
              {"seq":1,"kind":"group","roomId":"room","botId":"chief"},
              {"seq":2,"kind":"message","botId":"worker"},
              {"seq":3,"kind":"group","roomId":"room","botId":"chief"}
            ]}
        """.trimIndent())
        assertEquals(mapOf("chief" to 1, "worker" to 1, "room" to 1), restored.snapshot().unreadCounts)
    }

    @Test
    fun liveWorkIsScopedToTheChatAndClearsWhenTheTurnEnds() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val running = response(store, start, "state", """
            {"revision":2,"bots":[{"id":"chief","name":"Chief","sessionId":"current"},{"id":"worker","name":"Worker"}],
             "rooms":[{"id":"room","name":"Team","members":["worker"]}],
             "work":{"bots":{"chief":{"since":1000,"tool":"web_search"},
                             "worker":{"roomId":"room","since":2000,"tool":"web_fetch"}},
                     "rooms":{"room":[{"botId":"worker","since":2000,"tool":"web_fetch"}]},
                     "turns":{"current":1000},"steered":{"current":1500}}}
        """.trimIndent()).snapshot
        assertEquals("web-search", running.visibleLiveWork("chief")?.kind)
        assertEquals(1500L, running.visibleLiveWork("chief")?.startedAtMs)
        assertEquals("worker", running.visibleLiveWork("room")?.botId)
        assertEquals("web-read", running.visibleLiveWork("room")?.kind)
        assertNull(running.visibleLiveWork("worker"))

        val unchanged = response(store, store.refresh(), "state", """{"revision":2,"unchanged":true}""")
        assertEquals(running.liveWork, unchanged.snapshot.liveWork)
        val thinking = response(store, store.refresh(), "state",
            """{"revision":2,"unchanged":true,"work":{"turns":{"current":1000}}}""")
        assertEquals("thinking", thinking.snapshot.visibleLiveWork("chief")?.kind)
        assertNull(thinking.snapshot.visibleLiveWork("room"))

        val restored = SharedBotStore().restoreCacheJson(store.exportCacheJson())
        assertTrue(restored.snapshot.liveWork.isEmpty())
        val stopped = response(store, store.refresh(), "state", """{"revision":2,"unchanged":true,"work":{}}""")
        assertTrue(stopped.snapshot.liveWork.isEmpty())
        response(store, store.refresh(), "state", """{"revision":2,"unchanged":true,"work":{"turns":{"current":3000}}}""")
        assertTrue(store.connectionChanged(supported = true, connected = false).snapshot.liveWork.isEmpty())
    }

    @Test
    fun messageQuoteUsesExistingSendAndRestoresFromPrivateAndGroupHistory() {
        val quote = "接下来需要核对这份报告。"
        val text = "> $quote\n\n请继续"
        for ((chat, endpoint) in listOf("chief" to "chat", "room" to "room-chat")) {
            val store = connected()
            val history = store.selectChat(chat)
            val sent = store.sendMessage(text)
            val request = Json.parseToJsonElement(sent.requests.single().payload).jsonObject
            assertEquals("send", request.getValue("endpoint").jsonPrimitive.content)
            val body = request.getValue("payload").jsonObject
            assertEquals(chat, body.getValue("sessionId").jsonPrimitive.content)
            assertEquals(text, body.getValue("text").jsonPrimitive.content)
            val optimistic = sent.snapshot.messages.last()
            assertEquals(quote, optimistic.quoteParts.first().text)
            response(store, sent, "send", "true")
            response(store, history, endpoint, """
                {"items":[{"kind":"user","seq":1,"text":"> $quote\n\n请继续"}]}
            """.trimIndent())
            val restored = SharedBotStore()
            restored.restoreCacheJson(store.exportCacheJson())
            val message = restored.selectChat(chat).snapshot.messages.single()
            assertEquals(optimistic.quoteParts, message.quoteParts)
        }
    }

    @Test
    fun presentFileReplyUsesStandardMetadataAndKeepsItsSourceSessionInCache() {
        val store = connected()
        val result = response(store, store.selectChat("chief"), "chat", """
            {"sessionId":"current","items":[
                {"kind":"file","seq":3,"sessionId":"archived-part","files":[{"path":"/work/report.md","description":"调研报告"},{"path":"/work/report.md"}]},
                {"kind":"file","seq":4,"files":[{"name":"unknown.md"},{"path":"/work/folder/"}]}
            ]}
        """.trimIndent())
        val reply = result.snapshot.messages.single()
        assertEquals("archived-part", reply.sessionId)
        assertEquals("/work/report.md", reply.files.single().path)
        assertEquals("调研报告", reply.files.single().description)
        val reopened = SharedBotStore()
        reopened.restoreCacheJson(store.exportCacheJson())
        assertEquals(reply, reopened.selectChat("chief").snapshot.messages.single())
    }

    @Test
    fun deliveryFromAnOlderPageAppearsAfterTheLastReplyInItsOwnSessionAndTurn() {
        val store = connected()
        val newest = """{"items":[
          {"kind":"bot","seq":5,"turn":4,"sessionId":"archived-part","text":"本轮最后一段"},
          {"kind":"user","seq":6,"turn":5,"sessionId":"archived-part","text":"继续"},
          {"kind":"bot","seq":7,"turn":4,"sessionId":"current-part","text":"另一会话同编号轮次"}
        ],"cursor":{"part":0,"beforeSeq":5}}"""
        response(store, store.selectChat("chief"), "chat", newest)
        assertTrue(store.snapshot().messages.all { it.files.isEmpty() })

        val older = """{"items":[
          {"kind":"bot","seq":2,"turn":4,"sessionId":"archived-part","text":"前一段"},
          {"kind":"file","seq":3,"turn":4,"sessionId":"archived-part","files":[{"path":"reports/report.md","description":"调研报告"},{"path":"app.apk"}]},
          {"kind":"file","seq":4,"turn":4,"sessionId":"archived-part","files":[{"path":"reports/report.md"}]}
        ],"cursor":null}"""
        val result = response(store, store.loadEarlier(), "chat", older).snapshot.messages
        assertEquals(listOf("前一段", "本轮最后一段", "继续", "另一会话同编号轮次"), result.map { it.text })
        assertEquals(listOf("reports/report.md", "app.apk"), result[1].files.map { it.path })
        assertEquals("调研报告", result[1].files.first().description)
        assertTrue(result.filterIndexed { index, _ -> index != 1 }.all { it.files.isEmpty() })
        assertEquals(result, response(store, store.selectChat("chief"), "chat", newest).snapshot.messages)
        val reopened = SharedBotStore()
        reopened.restoreCacheJson(store.exportCacheJson())
        assertEquals(result, reopened.selectChat("chief").snapshot.messages)
    }

    @Test
    fun botHistoryRetainsTimestampsMemberUpdatesAndOriginalQuestionLetters() {
        val store = connected()
        val select = store.selectChat("chief")
        assertTrue(select.requests.any { it.payload.contains("\"historyDetails\":true") })
        val result = response(store, select, "chat", """
            {"items":[
              {"kind":"bot","seq":1,"text":"先确认一下","time":1794794580000},
              {"kind":"event","seq":2,"event":"bot-created","botId":"researcher","name":"研究员"},
              {"kind":"answer","seq":3,"questions":[
                  {"id":"topic","question":"你最关心哪一面？","options":["定位",{"label":"架构和技术实现"},"风险","跟别的方案比"]},
                  {"id":"other","question":"还有什么？","options":["没有"]}
                ],"answers":[{"id":"other","custom":"关注交付"},{"id":"topic","selected":["架构和技术实现","跟别的方案比"]}]},
              {"kind":"event","seq":4,"event":"bot-messaged","botId":"researcher","name":"研究员"},
              {"kind":"bot","seq":5,"text":"收到","time":{}}
            ]}
        """.trimIndent())
        val messages = result.snapshot.messages
        assertEquals(1794794580000L, messages[0].timeMs)
        assertEquals("bot-created", messages[1].activity)
        assertEquals("researcher", messages[1].botId)
        val answers = messages[2].questionAnswers
        assertEquals("你最关心哪一面？", answers.first().question)
        assertEquals(listOf("B", "D"), answers.first().choices.map { it.letter })
        assertEquals(listOf("架构和技术实现", "跟别的方案比"), answers.first().choices.map { it.label })
        assertEquals("B", answers[1].choices.single().letter)
        assertEquals("关注交付", answers[1].choices.single().label)
        assertEquals("bot-messaged", messages[3].activity)
        assertNull(messages[4].timeMs)
        val reopened = SharedBotStore()
        reopened.restoreCacheJson(store.exportCacheJson())
        assertEquals(messages, reopened.selectChat("chief").snapshot.messages)
    }

    @Test
    fun agentImagesRetainOriginAndSurviveCacheAndRepeatedHistory() {
        val store = connected()
        val page = """{"sessionId":"old-part","items":[{"kind":"bot","seq":8,"text":"","imageRefs":[{"attachmentId":"sha256:plot","mime":"image/png","name":"plot.png","width":640,"height":360}]}],"cursor":null}"""
        response(store, store.selectChat("chief"), "chat", page)
        response(store, store.selectChat("chief"), "chat", page)
        val message = store.snapshot().messages.single()
        assertEquals("old-part", message.sessionId)
        assertEquals("sha256:plot", message.images.single().attachmentId)
        assertEquals("old-part", message.images.single().sessionId)
        val restored = SharedBotStore()
        restored.restoreCacheJson(store.exportCacheJson())
        assertEquals(message.images, restored.selectChat("chief").snapshot.messages.single().images)
    }

    @Test
    fun groupImagesKeepTheirAuthorAndInvalidReferencesDoNotCrashHistory() {
        val store = connected()
        response(store, store.selectChat("room"), "room-chat", """{"sessionId":"room-part","items":[{"id":"post","kind":"bot","botId":"researcher","name":"研究员","imageRefs":[{"attachmentId":"photo","sessionId":"member-part","mime":"image/jpeg","width":{}},{"attachmentId":"blocked","mime":"text/html"},{"attachmentId":"photo","sessionId":"member-part","mime":"image/jpeg"}]}]}""")
        val message = store.snapshot().messages.single()
        assertEquals("researcher", message.botId)
        assertEquals(1, message.images.size)
        assertEquals("member-part", message.images.single().sessionId)
        assertEquals(0, message.images.single().width)
    }

    @Test
    fun runtimeUpdatesDoNotBypassBoundedFeedRecovery() {
        val store = SharedBotStore()
        store.connectionChanged(true, true)
        val activated = store.activate(true, true)
        val subscription = activated.requests.first { it.requestType == "bot-subscribe" }.correlationId
        store.acceptJson("""{"kind":"bot-stream-reset","subscriptionId":"$subscription"}""")
        repeat(5) { assertTrue(store.connectionChanged(true, true).requests.isEmpty()) }
        assertTrue(store.refresh().requests.any { it.requestType == "bot-subscribe" })
    }

    @Test
    fun changedAvatarReferenceRefreshesTheCachedImage() {
        val store = SharedBotStore()
        val initial = response(store, store.activate(true, true), "state",
            """{"revision":1,"bots":[{"id":"chief","name":"Chief","avatar":{"imageRef":"a"}}]}""")
        response(store, initial, "avatar", """{"botId":"chief","ref":"a","image":"data:image/png;base64,YQ=="}""")
        val refreshed = response(store, store.refresh(), "state",
            """{"revision":2,"bots":[{"id":"chief","name":"Chief","avatar":{"imageRef":"b"}}]}""")
        response(store, refreshed, "avatar", """{"botId":"chief","ref":"b","image":"data:image/png;base64,Yg=="}""")
        assertEquals("data:image/png;base64,Yg==", store.snapshot().chats.first().image)
        val reopened = SharedBotStore()
        reopened.restoreCacheJson(store.exportCacheJson())
        val restored = response(reopened, reopened.activate(true, true), "state",
            """{"revision":2,"bots":[{"id":"chief","name":"Chief","avatar":{"imageRef":"b"}}]}""")
        assertEquals("data:image/png;base64,Yg==", restored.snapshot.chats.first().image)
        assertFalse(restored.requests.any { it.payload.contains("\"endpoint\":\"avatar\"") })
    }

    @Test
    fun attachmentDraftIsAcknowledgedOnlyByItsOwnSuccessfulResult() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        store.acceptJson("""{"kind":"bot-subscribed","requestId":"$subscription","hello":{"capabilities":["bot-attachments"]}}""")
        response(store, start, "state", """{"revision":1,"bots":[{"id":"chief","name":"Chief","sessionId":"current"}]}""")
        store.selectChat("chief")
        val sent = store.sendMessageWithAttachments("请看附件", "[]", """["receipt"]""")
        val request = sent.requests.single()
        assertTrue(sent.snapshot.attachmentSending)
        assertEquals("current", Json.parseToJsonElement(request.payload).jsonObject["payload"]
            ?.jsonObject?.get("sessionId")?.jsonPrimitive?.content)
        val rejected = store.requestFailed(assertNotNull(request.correlationId), "bot-timeout")
        assertFalse(rejected.snapshot.attachmentSending)
        assertNull(rejected.snapshot.lastAttachmentAck)
        val retry = store.sendMessageWithAttachments("请看附件", "[]", """["fresh-receipt"]""")
        val confirmed = response(store, retry, "send-attachments", "true")
        assertFalse(confirmed.snapshot.attachmentSending)
        assertEquals(retry.requests.single().correlationId, confirmed.snapshot.lastAttachmentAck)
    }

    @Test
    fun newerReferenceQueryReplacesAnInFlightResult() {
        val store = SharedBotStore()
        val activation = store.activate(supported = true, connected = true)
        val subscription = activation.requests.first { it.requestType == "bot-subscribe" }.correlationId
        store.acceptJson("""{"kind":"bot-subscribed","requestId":"$subscription","hello":{"capabilities":["reference-candidates"]}}""")
        response(store, activation, "state", """{"revision":1,"bots":[{"id":"chief","name":"Chief","sessionId":"current"}]}""")
        store.selectChat("chief")
        val first = store.searchReferences("dr", false)
        val queued = store.searchReferences("draft/", true)
        assertTrue(queued.requests.isEmpty())
        assertEquals("draft/", queued.snapshot.referenceSearch?.query)
        val second = response(store, first, "reference-candidates",
            """{"items":[{"id":"dir","label":"draft/","kind":"directory","path":"draft/","wire":"@draft/"}]}""")
        assertTrue(second.snapshot.referenceSearch?.items.orEmpty().isEmpty())
        assertTrue(second.requests.single().payload.contains("draft/"))
        val result = response(store, second, "reference-candidates",
            """{"items":[{"id":"guide","label":"guide.md","kind":"file","wire":"@draft/guide.md"}]}""")
        val search = assertNotNull(result.snapshot.referenceSearch)
        assertFalse(search.loading)
        assertEquals("@draft/guide.md", search.items.single().wire)
    }

    @Test
    fun createBotUsesTheTeamModelAndRefreshesTheRosterWithoutRepeatingTheWrite() {
        val store = creationStore()
        assertEquals("provider/team", store.snapshot().defaultCreationModel)
        assertEquals(listOf("provider/host", "provider/team"), store.snapshot().creationModels.map { it.id })
        val roster = store.snapshot().roster
        assertEquals(listOf("chief", "second"), roster.map { it.id })
        assertEquals("New Bot 2", BotCreationRules.freshName("New Bot", roster))
        assertEquals("duplicate", BotCreationRules.nameProblem("new bot", roster))
        assertEquals("reserved", BotCreationRules.nameProblem("everyone", roster))
        assertEquals("characters", BotCreationRules.nameProblem("two@names", roster))
        assertTrue(store.createBot("New Bot", "star", "violet", null, null, null, false).requests.isEmpty())

        val first = store.createBot("New Bot 2", "star", "violet", null, "provider/team", null, false)
        assertTrue(first.snapshot.creation.saving)
        assertTrue(store.createBot("New Bot 2", "star", "violet", null, null, null, false).requests.isEmpty())
        val saved = response(store, first, "create-bot", """{"id":"new-role"}""")
        assertEquals("new-role", saved.snapshot.creation.createdId)
        assertFalse(saved.snapshot.creation.saving)
        assertEquals(listOf("state"), saved.requests.map {
            Json.parseToJsonElement(it.payload).jsonObject["endpoint"]?.jsonPrimitive?.content
        })
        val after = response(store, saved, "state",
            """{"revision":2,"mainBotId":"chief","bots":[{"id":"chief","name":"Chief"},{"id":"second","name":"New Bot"},{"id":"new-role","name":"New Bot 2","lastAt":100}]}""")
        assertEquals(listOf("chief", "second", "new-role"), after.snapshot.roster.map { it.id })
    }

    @Test
    fun localCreationKeepsTheAgentRouteAndDoesNotRetryAnUncertainWrite() {
        val store = creationStore()
        val listing = store.loadCreationAgents()
        response(store, listing, "local-agents", """{"items":[
            {"id":"local-codex-cli","title":"Codex CLI","available":true},
            {"id":"local-claude-code","title":"Claude Code","available":false}
        ]}""")
        assertTrue(store.createBot("Local demo", "star", "violet", null, "local-claude-code/default", null, true).requests.isEmpty())
        val modelRead = store.loadCreationAgentModels("local-codex-cli")
        response(store, modelRead, "local-agent-models", """{"items":[{"id":"local-codex-cli/verified","title":"Verified"}]}""")
        val write = store.createBot("Local demo", "star", "violet", null, "local-codex-cli/verified", "high", true)
        val request = write.requests.single()
        val payload = Json.parseToJsonElement(request.payload).jsonObject.getValue("payload").jsonObject
        assertEquals("local-codex-cli/verified", payload.getValue("model").jsonPrimitive.content)
        assertEquals("true", payload.getValue("agentAppearance").jsonPrimitive.content)
        assertNull(payload["effort"])
        val lost = store.requestFailed(requireNotNull(request.correlationId), "bot-timeout")
        assertEquals("uncertain", lost.snapshot.creation.error)
        assertFalse(lost.snapshot.creation.saving)
        assertTrue(store.createBot("Local demo", "star", "violet", null, "local-codex-cli/default", null, true).requests.isEmpty())
        val restored = SharedBotStore().restoreCacheJson(store.exportCacheJson()).snapshot
        assertNull(restored.creation.appearance)
        assertFalse(restored.canCreateBot)
    }

    private fun creationStore(): SharedBotStore {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        response(store, start, "state", """{
          "revision":1,"mainBotId":"chief","bots":[{"id":"chief","name":"Chief"},{"id":"second","name":"New Bot"}],
          "models":[{"ref":"provider/host"},{"ref":"provider/team"},{"ref":"provider/unavailable","needsKey":true},{"ref":"local-codex-cli/default"}],
          "defaultModel":"provider/host","prefs":{"defaultBotModel":"provider/team"}
        }""")
        store.acceptJson("""{"kind":"bot-subscribed","requestId":"$subscription","hello":{"capabilities":["bot-creation"]}}""")
        response(store, store.openCreation(), "creation-appearance", """{
          "shapes":[{"id":"star","images":{"violet":"data:image/svg+xml;base64,AA=="}}],
          "colors":[{"id":"violet","stops":["#8766FF"]}]
        }""")
        return store
    }

    private fun response(store: SharedBotStore, transition: SharedBotTransition, endpoint: String, value: String): SharedBotTransition {
        val request = transition.requests.first { Json.parseToJsonElement(it.payload).jsonObject["endpoint"]?.jsonPrimitive?.content == endpoint }
        return store.acceptJson("""{"kind":"bot-result","requestId":"${request.correlationId}","endpoint":"$endpoint","value":$value}""")
    }

    private fun connected(): SharedBotStore {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        response(store, start, "state", """{"revision":1,"mainBotId":"chief","bots":[{"id":"chief","name":"Chief"},{"id":"researcher","name":"研究员"}],"rooms":[{"id":"room","name":"项目组"}]}""")
        return store
    }

    @Test
    fun distinctTextBlocksAndRepeatedHistoryDoNotLoseMessages() {
        val store = connected()
        val selected = store.selectChat("chief")
        val page = """{"sessionId":"part-a","items":[{"kind":"bot","seq":7,"text":"第一块"},{"kind":"bot","seq":7,"text":"第二块"}],"cursor":null}"""
        response(store, selected, "chat", page)
        assertEquals(listOf("第一块", "第二块"), store.snapshot().messages.map { it.text })
        response(store, store.selectChat("chief"), "chat", page)
        assertEquals(2, store.snapshot().messages.size)
    }

    @Test
    fun messageArrivingDuringSnapshotCausesAnotherRead() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val sub = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        response(store, start, "state", """{"revision":1,"bots":[{"id":"chief","name":"Chief"}]}""")
        val read = store.selectChat("chief")
        store.acceptJson("""{"kind":"bot-event","subscriptionId":"$sub","botEvent":"message","botData":{"botId":"chief","item":{"seq":9}}}""")
        val next = response(store, read, "chat", """{"items":[{"kind":"bot","seq":7,"text":"旧回复"}],"cursor":null}""")
        assertTrue(next.requests.any { it.payload.contains("\"endpoint\":\"chat\"") })
        response(store, next, "chat", """{"items":[{"kind":"bot","seq":7,"text":"旧回复"},{"kind":"bot","seq":9,"text":"新回复"}],"cursor":null}""")
        assertEquals(listOf("旧回复", "新回复"), store.snapshot().messages.map { it.text })
    }

    @Test
    fun deletingRoleRequiresCapabilityAndProtectsEveryMainBot() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val roster = """{"revision":1,"mainBotId":"chief","mainBotIds":["chief","deputy"],
            "bots":[{"id":"chief","name":"Chief"},{"id":"deputy","name":"Deputy"},{"id":"worker","name":"Worker"}],
            "rooms":[{"id":"group","name":"Group","members":["worker","chief"]}]}"""
        response(store, start, "state", roster)
        assertTrue(store.deleteRole("worker").requests.isEmpty())
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        store.acceptJson("""{"kind":"bot-subscribed","requestId":"$subscription","hello":{"capabilities":["role-settings","bot-deletion"]}}""")
        for (id in listOf("chief", "deputy", "missing", "group")) {
            assertTrue(store.deleteRole(id).requests.isEmpty(), id)
        }

        val history = store.selectChat("worker")
        response(store, history, "chat", """{"items":[{"kind":"bot","seq":1,"text":"hello"}]}""")
        val staleRead = store.refresh()
        val deletion = store.deleteRole("worker")
        assertTrue(deletion.snapshot.roleSaving)
        assertTrue(deletion.snapshot.roster.any { it.id == "worker" })
        assertTrue(store.deleteRole("worker").requests.isEmpty())
        val rejected = store.requestFailed(deletion.requests.single().correlationId!!, "bot-timeout")
        assertEquals("bot-delete-unconfirmed", rejected.snapshot.roleError)
        assertTrue(rejected.snapshot.roster.any { it.id == "worker" })
        assertFalse(rejected.snapshot.roleSaving)

        val retry = store.deleteRole("worker")
        val confirmed = response(store, retry, "delete-bot", """{"id":"worker"}""")
        assertEquals(listOf("chief", "deputy"), confirmed.snapshot.roster.map { it.id })
        assertNull(confirmed.snapshot.selectedChatId)
        assertTrue(confirmed.snapshot.messages.isEmpty())
        assertFalse(confirmed.snapshot.roleSaving)
        assertNull(confirmed.snapshot.roleError)
        assertTrue(confirmed.requests.any { it.payload.contains("\"endpoint\":\"state\"") })
        assertTrue(response(store, staleRead, "state", roster).snapshot.roster.none { it.id == "worker" })
    }

    @Test
    fun disconnectPreservesUncertainBubbleAndNeverResends() {
        val store = connected()
        store.selectChat("chief")
        val send = store.sendMessage("你好")
        assertEquals("sending", send.snapshot.messages.last().delivery)
        store.connectionChanged(true, false)
        assertEquals("unknown", store.snapshot().messages.last().delivery)
        val reconnect = store.connectionChanged(true, true)
        assertFalse(reconnect.requests.any { it.payload.contains("\"endpoint\":\"send\"") })
    }

    @Test
    fun switchingChatRejectsOldSelectionAndOldSubscriptionEvents() {
        val store = connected()
        val chief = store.selectChat("chief")
        val researcher = store.selectChat("researcher")
        response(store, chief, "chat", """{"items":[{"kind":"bot","seq":1,"text":"Chief"}]}""")
        assertTrue(store.snapshot().messages.isEmpty())
        response(store, researcher, "chat", """{"items":[{"kind":"bot","seq":1,"text":"研究员"}]}""")
        store.acceptJson("""{"kind":"bot-event","subscriptionId":"old","botEvent":"stream","botData":{"botId":"researcher","attemptId":"a","phase":"start"}}""")
        assertEquals(listOf("研究员"), store.snapshot().messages.map { it.text })
    }

    @Test
    fun botEnvelopeDoesNotConflictWithSessionOrAttachmentDecoder() {
        val frame = GatewayWireDecoder.decode("""{"kind":"bot-event","subscriptionId":"s","botEvent":"message","botData":{"item":{"kind":"bot","text":"你好"}}}""")
        assertEquals("message", frame.botEvent)
        assertNotNull(frame.botData)
        assertNull(frame.event)
        assertNull(frame.data)
    }

    @Test
    fun cachedUnknownSendSurvivesRestartWithoutSubmittingAgain() {
        val source = connected()
        source.selectChat("chief")
        source.sendMessage("需要核对的消息")
        val restored = SharedBotStore()
        restored.restoreCacheJson(source.exportCacheJson())
        val opened = restored.selectChat("chief")
        assertEquals("unknown", opened.snapshot.messages.single().delivery)
        assertEquals("需要核对的消息", opened.snapshot.messages.single().text)
        assertTrue(opened.requests.isEmpty())
        val reconnect = restored.activate(true, true)
        assertFalse(reconnect.requests.any { it.payload.contains("\"endpoint\":\"send\"") })
    }

    @Test
    fun stateChangeDuringReadGetsAnotherSnapshot() {
        val store = SharedBotStore()
        val start = store.activate(true, true)
        val subscription = start.requests.first { it.requestType == "bot-subscribe" }.correlationId
        store.acceptJson("""{"kind":"bot-event","subscriptionId":"$subscription","botEvent":"revision","botData":{"revision":2}}""")
        val next = response(store, start, "state", """{"revision":1,"bots":[]}""")
        assertTrue(next.requests.any { it.payload.contains("\"endpoint\":\"state\"") })
        response(store, next, "state", """{"revision":2,"bots":[{"id":"new","name":"新助理"}]}""")
        assertEquals("new", store.snapshot().chats.single().id)
    }
}
