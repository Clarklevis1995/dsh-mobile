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

    const val IMAGE_ATTACHMENT =
        """{"kind":"attachment","sessionId":"s1","attachment":{"attachmentId":"att-1","mediaType":"image/png","bytes":8,"width":1,"height":1},"data":"iVBORw0K"}"""

    const val HISTORY_IMAGE =
        """{"kind":"history","events":[{"type":"user/message","seq":1,"time":1786937352,"data":{"content":[{"type":"image","attachment":{"attachmentId":"att-history","mediaType":"image/webp","bytes":42,"width":100,"height":80,"name":"image.webp"}}],"source":{"kind":"user"}}}],"hasMore":false}"""
}
