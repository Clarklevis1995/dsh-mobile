package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedBotImageTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedBotImageTransferTest {
    @Test fun imageRequestsReuseGatewayEnvelopeAndIndependentCorrelation() {
        val transfer = SharedBotImageTransfer("archived-member", "photo", preview = false)
        val request = com.clarklevis.dsh.shared.gateway.GatewayRequests.botCall(transfer.endpoint, transfer.payloadJson(), "bot-image-1")
        assertEquals(request.payload, transfer.requestJson("bot-image-1"))
        assertEquals("bot-image-1", request.correlationId)
        assertEquals(com.clarklevis.dsh.shared.gateway.GatewayRequestLanePolicy.REJECT_IF_BUSY, request.lanePolicy)
        assertTrue(request.payload.contains("archived-member"))
        kotlin.test.assertFails { transfer.requestJson("ordinary-history") }
    }

    @Test fun chunksReassembleAndRetainTheOwnedSessionTarget() {
        val transfer = SharedBotImageTransfer("archived-part", "sha256:image", preview = false)
        assertTrue(transfer.payloadJson().contains("archived-part"))
        assertTrue(transfer.acceptJson("""{"mime":"image/png","data":"TWFu","offset":0,"nextOffset":3,"size":6,"done":false}"""))
        assertFalse(transfer.finished)
        assertEquals(3, transfer.offset)
        assertTrue(transfer.acceptJson("""{"mime":"image/png","data":"TWFu","offset":3,"nextOffset":6,"size":6,"done":true}"""))
        assertEquals("TWFuTWFu", transfer.dataBase64)
        assertTrue(transfer.finished)
    }

    @Test fun legacyHostsAndMarkdownImagesRemainReadable() {
        val transfer = SharedBotImageTransfer("room", path = "charts/a.png", author = "researcher")
        assertEquals("markdown-image", transfer.endpoint)
        assertTrue(transfer.payloadJson().contains("researcher"))
        assertTrue(transfer.acceptJson("""{"mime":"image/png","bytes":"TWFu","rev":"v1"}"""))
        assertTrue(transfer.finished)
        assertEquals("TWFu", transfer.dataBase64)
    }

    @Test fun corruptOffsetsAndChangingImagesNeverProduceAnOriginal() {
        for (bad in listOf(
            """{"mime":"image/png","data":"TWFu","offset":2,"nextOffset":6,"size":6,"done":true,"rev":"v1"}""",
            """{"mime":"image/png","data":"TWFu","offset":3,"nextOffset":6,"size":6,"done":true,"rev":"v2"}""",
            """{"mime":"image/png","data":"TWFu","offset":3,"nextOffset":6,"size":6,"done":false,"rev":"v1"}"""
        )) {
            val transfer = SharedBotImageTransfer("chief", "photo")
            transfer.acceptJson("""{"mime":"image/png","data":"TWFu","offset":0,"nextOffset":3,"size":6,"done":false,"rev":"v1"}""")
            assertFalse(transfer.acceptJson(bad))
            assertEquals(null, transfer.dataBase64)
        }
    }

    @Test fun unsupportedMimeInvalidBase64AndOversizedMetadataAreRejected() {
        for (bad in listOf(
            """{"mime":"text/html","data":"TWFu"}""",
            """{"mime":"image/png","data":"!!!!"}""",
            """{"mime":"image/png","data":"TWFu","offset":0,"nextOffset":3,"size":16777217,"done":false}"""
        )) assertFalse(SharedBotImageTransfer("chief", "photo").acceptJson(bad))
    }
}
