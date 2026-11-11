package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedDeviceConnectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceConnectorsTest {
    private val connectors = SharedDeviceConnectors()

    @Test
    fun deviceFramesWithNumericProtocolVersionReachNativeValidator() {
        for (kind in listOf("device-tools-registered", "device-tools-status", "device-tools-permissions", "device-tool-invoke", "device-tool-cancel")) {
            val frame = com.clarklevis.dsh.shared.protocol.GatewayWireDecoder.decode("""{"kind":"$kind","version":1,"requestId":"reg","modules":[],"connectionLeaseId":"lease","capabilityRevision":1}""")
            assertEquals(kind, frame.kind)
            assertEquals("reg", frame.requestId)
        }
    }

    @Test
    fun rejectsMalformedInvocationWithoutThrowing() {
        val base = """{"version":1,"invocationId":"i","connectionLeaseId":"l","tool":"calendar.busy","arguments":{}}"""
        assertEquals("", connectors.validateInvocationJson(base))
        for (value in listOf("{}", "[]", "true", "\"1\"", "null")) {
            assertEquals("invalid_request", connectors.validateInvocationJson(base.replace("\"version\":1", "\"version\":$value")))
        }
        assertEquals("invalid_request", connectors.validateInvocationJson(base.replace("\"i\"", "42")))
        assertEquals("invalid_request", connectors.validateInvocationJson(base.replace("\"arguments\":{}", "\"arguments\":[]")))
        assertEquals("invalid_request", connectors.validateInvocationJson("x".repeat(16385)))
    }

    @Test
    fun contactsAreExcludedFromEveryToolBoundary() {
        assertNull(connectors.toolModule("contacts.list"))
        assertEquals("tool_unsupported", connectors.validateInvocationJson("""{"version":1,"invocationId":"i","connectionLeaseId":"l","tool":"contacts.list","arguments":{}}"""))
        assertEquals("health", connectors.toolModule("health.daily-summary"))
        assertEquals("photos", connectors.toolModule("photos.select"))
        for (tool in listOf("location.precise", "maps.search", "maps.geocode", "maps.reverse-geocode", "maps.directions", "maps.open", "maps.markers.create", "maps.markers.list", "maps.markers.update", "maps.markers.delete")) {
            assertEquals("location", connectors.toolModule(tool))
        }
        assertEquals("music", connectors.toolModule("music.library"))
        assertEquals("music", connectors.toolModule("music.catalog-search"))
        assertEquals("music", connectors.toolModule("music.play-library"))
        assertEquals("calendar", connectors.toolModule("calendar.create"))
        assertEquals("reminders", connectors.toolModule("reminders.create"))
        for (tool in listOf("alarms.create", "alarms.list", "alarms.cancel", "alarms.open-create")) {
            assertEquals("alarms", connectors.toolModule(tool))
        }
    }
}
