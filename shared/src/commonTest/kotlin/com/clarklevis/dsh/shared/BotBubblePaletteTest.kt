package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.BotBubblePalette
import com.clarklevis.dsh.shared.facade.BotChat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BotBubblePaletteTest {
    @Test
    fun darkModeUsesTheFigmaPaletteInTheSameMemberOrder() {
        val members = (0..4).map { BotChat("bot-$it", "Agent $it") }
        val palette = BotBubblePalette(members.reversed() + BotChat("chief", "Chief"), "chief")

        assertEquals(0x352D3D, palette.rgb("chief", dark = true))
        assertEquals(listOf(0x2C3D34, 0x402F3A, 0x40382E, 0x2A3A43, 0x42322F),
            members.map { palette.rgb(it.id, dark = true) })
        assertEquals(0xF3EFF6, palette.rgb("chief"))
    }

    @Test
    fun botColorsFollowStableIdsAndExcludeRooms() {
        val roster = listOf(BotChat("writer", "助手"), BotChat("chief", "Chief"), BotChat("research", "助手"))
        val original = BotBubblePalette(roster, "chief")
        val reordered = BotBubblePalette(listOf(BotChat("a-room", "群聊", room = true)) + roster.reversed(), "chief")

        assertEquals(3, roster.map { original.rgb(it.id) }.toSet().size)
        for (bot in roster) {
            assertEquals(original.rgb(bot.id), reordered.rgb(bot.id))
        }
        assertEquals(original.rgb(null), reordered.rgb("a-room"))
    }

    @Test
    fun mainBotColorMovesWithTheRole() {
        val roster = listOf(BotChat("chief", "Chief"), BotChat("research", "研究员"))
        val previous = BotBubblePalette(roster, "chief")
        val changed = BotBubblePalette(roster, "research")

        assertEquals(previous.rgb("chief"), changed.rgb("research"))
        assertNotEquals(changed.rgb("chief"), changed.rgb("research"))
    }
}
