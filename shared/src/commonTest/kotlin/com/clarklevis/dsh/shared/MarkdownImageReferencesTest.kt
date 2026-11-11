package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.MarkdownImageReferences
import com.clarklevis.dsh.shared.protocol.*
import kotlin.test.*

class MarkdownImageReferencesTest {
    private fun parse(value: String) = JsonValue.fromJsonElement(kotlinx.serialization.json.Json.parseToJsonElement(value))
    @Test fun exactSavedPathsProvideDimensionsWithoutGuessingByFilename() {
        val raw = parse("""{"meta":{"kind":"dsh-image-gen-batch","images":[
            {"kind":"dsh-image-gen","savedTo":"images/a.png","attachment":{"attachmentId":"one","mediaType":"image/png","bytes":20,"width":800,"height":400}},
            {"kind":"dsh-image-gen","savedTo":"other/a.png","attachment":{"attachmentId":"two","mediaType":"image/png","bytes":20,"width":400,"height":800}}
        ]}}""")
        val references = MarkdownImageReferences.from(listOf(SessionEvent("s", 1, 0.0, GatewayEvent("tool/result", raw = raw))))
        assertEquals("one", references["images/a.png"]?.attachmentId)
        assertEquals(800, references["images/a.png"]?.width)
        assertEquals("two", references["other/a.png"]?.attachmentId)
        assertNull(references["a.png"])
    }
    @Test fun missingOrInvalidMetadataDoesNotInventAnAttachment() {
        val raw = parse("""{"meta":{"kind":"dsh-image-gen","savedTo":"a.png","attachment":{"attachmentId":"bad","mediaType":"image/png","bytes":20,"width":0,"height":400}}}""")
        assertTrue(MarkdownImageReferences.from(listOf(SessionEvent("s", 1, 0.0, GatewayEvent("tool/result", raw = raw)))).isEmpty())
    }
}
