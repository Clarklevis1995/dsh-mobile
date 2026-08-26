package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedRouteFingerprint
import com.clarklevis.dsh.shared.facade.SharedPlatformEffect
import com.clarklevis.dsh.shared.facade.SharedShadowFacade
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedShadowFacadeTest {
    private val facade = SharedShadowFacade()
    private val context =
        """{"selectedSessionId":"selected","pendingHistorySessionId":"history-session","pendingModelsSessionId":"models-session","pendingGlobalModelsRequest":false,"pendingModelSelectionSessionId":"selection-session","pendingPermissionOptionsSessionId":"permission-session"}"""

    @Test
    fun routesEveryKnownFrameAndMalformedVariantsWithoutThrowing() {
        GatewayProtocolFixtures.ALL_ROUTES.forEach { fixture ->
            val result = facade.routeFrame(fixture.json, context)
            assertTrue(result.isSuccess, "${fixture.route}: ${result.errorMessage}")
            assertNull(result.errorCode)
            val route = wireJson.decodeFromString<SharedRouteFingerprint>(assertNotNull(result.routeJson))
            assertEquals(fixture.category, route.category, fixture.route)
            assertEquals(fixture.route, route.route, fixture.route)
            assertTrue(result.effectsJson.startsWith("["), fixture.route)
        }
    }

    @Test
    fun invalidJsonReturnsExplicitError() {
        val result = facade.routeFrame("abc", context)

        assertFalse(result.isSuccess)
        assertEquals("invalid-input", result.errorCode)
        assertNull(result.routeJson)
        assertEquals("[]", result.effectsJson)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun invalidContextReturnsExplicitError() {
        val result = facade.routeFrame("""{"kind":"hello"}""", "[]")

        assertFalse(result.isSuccess)
        assertEquals("invalid-input", result.errorCode)
    }

    @Test
    fun userIntentProducesDescriptorWithoutExecutingPlatformWork() {
        val result = facade.routeIntent(
            """{"kind":"answer-question","sessionId":"s1","rpcId":"rpc-1"}"""
        )

        assertTrue(result.isSuccess)
        val route = wireJson.decodeFromString<SharedRouteFingerprint>(assertNotNull(result.routeJson))
        val effects = wireJson.decodeFromString<List<SharedPlatformEffect>>(result.effectsJson)
        assertEquals("intent", route.category)
        assertEquals("answer-question", route.route)
        assertEquals("rpc-1", route.rpcId)
        assertEquals(listOf("answer-question"), effects.map(SharedPlatformEffect::kind))
    }
}
