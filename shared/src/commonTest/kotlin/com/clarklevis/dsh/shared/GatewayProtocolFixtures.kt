package com.clarklevis.dsh.shared

/**
 * 与 `DeepSeekHarnessMobileTests/GatewayProtocolTests.swift` 中
 * `GatewayProtocolParityFixtures` 逐字保持一致的跨端协议样本。
 */
object GatewayProtocolFixtures {
    const val LIVE_EVENT_WITHOUT_KIND =
        """{"sessionId":"s1","seq":7,"time":1001,"event":{"type":"assistant/chunk","turn":1,"step":1,"chunkType":"reasoning-delta","text":"thinking"}}"""

    const val REPLAYED_QUESTION_REQUEST =
        """{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"direction","header":"研究方向","question":"你想研究哪个方向？","detail":"请选择最感兴趣的方向","options":[{"label":"核心架构 (推荐)","description":"了解插件分层"},{"label":"移动端"}],"multiSelect":true},{"id":"notes","question":"还有什么要求？","multiSelect":false}]}"""

    const val REPLAYED_APPROVAL_REQUEST =
        """{"kind":"approval-requested","rpcId":"rpc-approval-1","sessionId":"s1","approvalId":"approval-1","toolName":"Bash","callId":"call-1","reason":"需要读取系统版本","replay":true}"""

    const val IMAGE_ATTACHMENT =
        """{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":8,"width":1,"height":1},"data":"iVBORw0K"}"""

    const val HISTORY_IMAGE =
        """{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-history","mediaType":"image/webp","bytes":42,"width":100,"height":80,"name":"image.webp"}}],"source":{"kind":"user"}}}],"hasMore":false}"""

    data class RouteFixture(
        val json: String,
        val category: String,
        val route: String
    )

    /** 与 Swift `GatewayShadowRouteFixtures.all` 逐项保持一致。 */
    val ALL_ROUTES = listOf(
        RouteFixture("""{"kind":"paired"}""", "connection", "paired"),
        RouteFixture("""{"kind":"hello","protocol":3,"capabilities":["images"],"authenticated":true,"clients":2}""", "connection", "hello"),
        RouteFixture("""{"kind":"pong","at":1000}""", "connection", "pong"),
        RouteFixture("""{"kind":"subscribed","sessionId":"s1"}""", "connection", "subscribed"),
        RouteFixture("""{"kind":"sent","sessionId":"s1"}""", "content", "sent"),
        RouteFixture("""{"kind":"event","sessionId":"s1","seq":1,"time":1000,"event":{"type":"assistant/message","text":"done"}}""", "content", "live-event"),
        RouteFixture("""{"kind":"workspaces","items":[],"archivedSessionIds":[]}""", "content", "workspaces"),
        RouteFixture("""{"kind":"sessions","items":[]}""", "content", "sessions"),
        RouteFixture("""{"kind":"history","events":[],"hasMore":false}""", "content", "history"),
        RouteFixture("""{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"a1","mediaType":"image/png","bytes":1,"width":1,"height":1}}""", "content", "attachment"),
        RouteFixture("""{"kind":"search","items":[],"hasMore":true}""", "content", "search"),
        RouteFixture("""{"kind":"host","version":"1.0"}""", "content", "host"),
        RouteFixture("""{"kind":"agent-presets","presets":[],"authorable":false,"hasDocument":false}""", "control", "agent-presets"),
        RouteFixture("""{"kind":"defaults","agentPresetDefault":"standard","permissionDefault":"workspace-write"}""", "control", "defaults"),
        RouteFixture("""{"kind":"default-model","selection":{"provider":"openai","model":"gpt-5"}}""", "control", "default-model"),
        RouteFixture("""{"kind":"save-default-model","saved":{"provider":"openai","model":"gpt-5"}}""", "control", "save-default-model"),
        RouteFixture("""{"kind":"set-default","applied":true,"target":"permission","value":"workspace-write"}""", "control", "set-default"),
        RouteFixture("""{"kind":"models","groups":[],"routable":true}""", "control", "models"),
        RouteFixture("""{"kind":"select-model","selected":{"provider":"openai","model":"gpt-5"}}""", "control", "select-model"),
        RouteFixture("""{"kind":"permission-options","sessionPermissions":{"options":[]}}""", "control", "permission-options"),
        RouteFixture("""{"kind":"permission","set":"workspace-write"}""", "control", "permission"),
        RouteFixture("""{"kind":"context-usage","asOfSeq":8}""", "control", "context-usage"),
        RouteFixture("""{"kind":"session-stats","asOfSeq":8}""", "control", "session-stats"),
        RouteFixture("""{"kind":"directories","entries":[],"crumbs":[]}""", "workspace", "directories"),
        RouteFixture("""{"kind":"directory-create","path":"/tmp/new"}""", "workspace", "directory-create"),
        RouteFixture("""{"kind":"workspace-create","created":false}""", "workspace", "workspace-create"),
        RouteFixture("""{"kind":"question-requested","rpcId":"rpc-1","sessionId":"s1","replay":true,"questions":[{"id":"q1","question":"继续？"}]}""", "question", "requested"),
        RouteFixture("""{"kind":"question-response","rpcId":"rpc-1","action":"cancel","accepted":false,"reason":"not-pending"}""", "question", "response"),
        RouteFixture("""{"kind":"question-resolved","rpcId":"rpc-1","sessionId":"s1","outcome":"cancelled"}""", "question", "resolved"),
        RouteFixture("""{"kind":"approval-requested","rpcId":"rpc-a1","sessionId":"s1","approvalId":"approval-1","toolName":"Bash","callId":"call-1","reason":"需要执行命令","replay":true}""", "approval", "requested"),
        RouteFixture("""{"kind":"approval-response","rpcId":"rpc-a1","sessionId":"s1","approvalId":"approval-1","outcome":"allowed-once","accepted":true}""", "approval", "response"),
        RouteFixture("""{"kind":"approval-resolved","rpcId":"rpc-a1","sessionId":"s1","approvalId":"approval-1","outcome":"rejected"}""", "approval", "resolved"),
        RouteFixture("""{"kind":"error","requestType":"history","code":"failed","sessionId":"s1","rpcId":"rpc-1"}""", "failure", "error"),
        RouteFixture("""{"kind":"future-frame"}""", "unknown", "future-frame"),
        RouteFixture("""{"kind":"sent"}""", "ignored", "sent"),
        RouteFixture("""{"kind":"event"}""", "ignored", "event"),
        RouteFixture("""{"kind":"attachment"}""", "ignored", "attachment"),
        RouteFixture("""{"kind":"question-requested","sessionId":"s1","questions":[]}""", "question", "invalid-request"),
        RouteFixture("""{"kind":"question-response"}""", "ignored", "question-response"),
        RouteFixture("""{"kind":"question-resolved"}""", "ignored", "question-resolved"),
        RouteFixture("""{"kind":"approval-requested","sessionId":"s1","approvalId":"approval-1","toolName":"Bash"}""", "approval", "invalid-request"),
        RouteFixture("""{"kind":"approval-response"}""", "ignored", "approval-response"),
        RouteFixture("""{"kind":"approval-resolved"}""", "ignored", "approval-resolved")
    )
}
