package whaleness

import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresentationSupportTest {
    private fun render(command: HarmonyCommand): HarmonyResult =
        wireJson.decodeFromString(HarmonyEngine().dispatch(wireJson.encodeToString(command)))

    @Test fun highlightPreservesUtf16SourceAndLanguageAliases() {
        val source = "val name = \"鲸鱼🐳\"\n\tprintln(name)"
        val result = render(HarmonyCommand("render-code", text = source, title = "kt")).presentation!!
        assertEquals("kotlin", result.language)
        assertTrue(result.spans.any { source.substring(it.start, it.end) == "val" })
        assertTrue(result.spans.all { it.start >= 0 && it.end <= source.length && it.start < it.end })
        assertTrue(result.spans.zipWithNext().all { (left, right) -> left.end <= right.start })
        assertTrue(result.spans.all { it.light.matches(Regex("#[0-9a-f]{8}")) && it.dark.matches(Regex("#[0-9a-f]{8}")) })
    }

    @Test fun linksSeparateWorkspacePathsFromSafeWebDestinations() {
        fun link(value: String) = render(HarmonyCommand("render-link", text = value)).presentation!!
        assertEquals("web", link("https://example.com/文档").linkKind)
        assertEquals("file", link("file:///workspace/%E4%B8%AD%E6%96%87.kt#L12").linkKind)
        assertEquals("/workspace/中文.kt", link("file:///workspace/%E4%B8%AD%E6%96%87.kt#L12").target)
        for (value in listOf("javascript:alert(1)", "data:text/html,test", "//evil.example", "a\u0000.txt")) {
            assertEquals("invalid", link(value).linkKind)
        }
    }

    @Test fun tableExportUsesSharedEscapingAndChineseEncoding() {
        val result = render(HarmonyCommand("render-table", rows = listOf(listOf("名称", "说明"), listOf("鲸鱼", "a,\"b\"\nc")))).presentation!!
        assertEquals("名称\t说明\n鲸鱼\ta,\"b\"\nc", result.plainText)
        assertEquals("\uFEFF名称,说明\r\n鲸鱼,\"a,\"\"b\"\"\nc\"\r\n", result.csv)
    }
}
