package com.clarklevis.dsh.shared.facade

/** 基于完整成员名册分配色号，让同一成员在私聊和群聊中使用相同底色。 */
class BotBubblePalette(chats: List<BotChat>, private val mainBotId: String?) {
    private val memberIndices = chats
        .filter { !it.room && it.id != mainBotId }
        .map { it.id }
        .distinct()
        .sorted()
        .mapIndexed { index, id -> id to (index % MEMBER_COLORS.size) }
        .toMap()

    fun rgb(botId: String?, dark: Boolean = false): Int {
        if (botId != null && botId == mainBotId) return if (dark) 0x352D3D else 0xF3EFF6
        val index = memberIndices[botId] ?: return if (dark) 0x302E35 else 0xF0EFED
        return (if (dark) NIGHT_MEMBER_COLORS else MEMBER_COLORS)[index]
    }

    private companion object {
        val MEMBER_COLORS = listOf(
            0xE7EFE7, // 灰鼠尾草绿
            0xF3E7EA, // 灰玫瑰粉
            0xF3ECDF, // 灰沙米色
            0xE4ECEF, // 雾蓝灰
            0xF2E7E1, // 灰陶杏
        )
        val NIGHT_MEMBER_COLORS = listOf(0x2C3D34, 0x402F3A, 0x40382E, 0x2A3A43, 0x42322F)
    }
}
