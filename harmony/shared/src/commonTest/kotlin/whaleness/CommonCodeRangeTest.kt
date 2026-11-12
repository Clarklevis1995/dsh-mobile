package whaleness

import com.clarklevis.dsh.shared.facade.WorkspaceCodePreviewSupport
import com.clarklevis.dsh.shared.facade.WorkspaceCodeTokenKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CommonCodeRangeTest {
    @Test
    fun repeatedCssSelectorsAndIndentedPropertiesExcludeTheirPrefixes() {
        val document = WorkspaceCodePreviewSupport.prepare("  .item, .item {\n    color: red;\n}\n", "sample.css", "text/css")
        val selectors = document.tokens.filter { it.kind == WorkspaceCodeTokenKind.TAG }
        assertEquals(listOf(".item", ".item"), selectors.map { document.text.substring(it.start, it.endExclusive) })
        assertEquals(listOf(2, 9), selectors.map { it.start })
        val property = document.tokens.single { it.kind == WorkspaceCodeTokenKind.ATTRIBUTE }
        assertEquals("color", document.text.substring(property.start, property.endExclusive))
        assertEquals(document.text.indexOf("color"), property.start)
    }

    @Test
    fun configKeysExcludeIndentationAndListPrefixes() {
        val document = WorkspaceCodePreviewSupport.prepare("  key = key\n- other: true\n", "sample.yaml", "text/yaml")
        val attributes = document.tokens.filter { it.kind == WorkspaceCodeTokenKind.ATTRIBUTE }
        assertEquals(listOf("key", "other"), attributes.map { document.text.substring(it.start, it.endExclusive) })
        assertEquals(listOf(document.text.indexOf("key"), document.text.indexOf("other")), attributes.map { it.start })
    }
}
