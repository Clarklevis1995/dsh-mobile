package com.clarklevis.dsh.shared

import com.clarklevis.dsh.shared.facade.SharedMapDestination
import com.clarklevis.dsh.shared.facade.SharedMapLinks
import kotlin.test.*

class SharedMapLinksTest {
    private val codec = SharedMapLinks()
    @Test fun genericRoundTripPreservesLabelCoordinatesAndTravelMode() {
        for (mode in listOf("show", "directions")) for (transport in listOf("driving", "walking", "transit")) {
            val d = SharedMapDestination(31.234567, 121.456789, "咖啡 & Café + /? #店", mode, transport)
            assertEquals(d, codec.parse(assertNotNull(codec.encode(d)), ""))
        }
    }
    @Test fun historyFromBothPlatformsIsRecognized() {
        val apple = assertNotNull(codec.parse("https://maps.apple.com/?daddr=0,-180&dirflg=w&q=店", ""))
        val google = assertNotNull(codec.parse("https://www.google.com/maps/dir/?api=1&destination=0,-180&travelmode=walking", "店"))
        assertEquals(apple, google)
        assertEquals("新名称", codec.parse("https://maps.apple.com/?ll=31,121&q=旧名称", "新名称")?.name)
        assertEquals("show", codec.parse("https://www.google.com/maps/search/?api=1&query=31,121", "店")?.mode)
    }
    @Test fun rejectsUntrustedAndAmbiguousLinksAndCoordinateSystems() {
        for (s in listOf("https://maps.apple.com.evil.test/?ll=1,2", "https://user@maps.apple.com/?ll=1,2",
            "https://maps.apple.com:443/?ll=1,2", "https://maps.apple.com/?ll=NaN,1", "https://maps.apple.com/?ll=91,1",
            "https://maps.apple.com/?ll=1,2&ll=3,4", "https://maps.apple.com/?ll=1,2&daddr=3,4", "https://maps.apple.com/?ll=1,2&dirflg=w",
            "https://maps.apple.com/?ll=1,2#evil", "https://maps.apple.com/?ll=1,2&url=evil", "https://maps.apple.com/?ll=1,2&q=%FF",
            "whaleness-map://place?v=2&latitude=1&longitude=2&coordinateSystem=wgs84",
            "whaleness-map://place?v=1&latitude=1&longitude=2&coordinateSystem=gcj02",
            "whaleness-map://place?v=1&latitude=1&longitude=2", "whaleness-map://place?v=1&latitude=1&longitude=2&coordinateSystem=wgs84&provider=evil",
            "intent://anything", "baidumap://map/marker?location=1,2")) assertNull(codec.parse(s, "店"), s)
        assertNull(codec.encode(SharedMapDestination(1.0, 2.0, "店\n注入")))
        assertNull(codec.encode(SharedMapDestination(Double.NaN, 2.0, "店")))
    }
    @Test fun eachPluginUsesPlatformSchemeAndExplicitCoordinateType() {
        val d = SharedMapDestination(31.2, 121.5, "A & B", "directions", "walking")
        for (platform in listOf("android", "ios")) {
            val amap = assertNotNull(codec.openURL(d, "amap", platform))
            assertTrue(amap.startsWith(if (platform == "ios") "iosamap://path?" else "androidamap://route?"))
            assertTrue(amap.contains("dev=1")); assertTrue(amap.contains("t=2")); assertTrue(amap.contains("dname=A%20%26%20B"))
            val baidu = assertNotNull(codec.openURL(d, "baidu", platform))
            assertTrue(baidu.startsWith("baidumap://map/direction?")); assertTrue(baidu.contains("coord_type=wgs84")); assertTrue(baidu.contains("mode=walking")); assertTrue(baidu.contains("origin="))
        }
        assertNull(codec.openURL(d, "apple", "android"))
        assertNull(codec.openURL(d, "tencent", "android"))
        assertTrue(assertNotNull(codec.openURL(d, "apple", "ios")).contains("dirflg=w"))
        assertTrue(assertNotNull(codec.openURL(d, "google", "ios")).startsWith("comgooglemaps://?daddr="))
        assertEquals(d.copy(name = ""), codec.parse(assertNotNull(codec.openURL(d, "google", "android")), ""))
    }
    @Test fun tappingOrdinaryPlacePlansRouteAndKeepsExistingTravelMode() {
        val place = assertNotNull(codec.parse("https://maps.apple.com/?ll=31.2,121.5&q=Cafe", "店"))
        assertEquals("show", place.mode)
        val route = codec.navigationDestination(place)
        assertEquals(place.copy(mode = "directions"), route)
        for (platform in listOf("android", "ios")) {
            val amap = assertNotNull(codec.openURL(route, "amap", platform))
            assertTrue(amap.startsWith(if (platform == "ios") "iosamap://path?" else "androidamap://route?"))
            assertTrue(amap.contains("t=0")); assertFalse(amap.contains("viewMap"))
            assertTrue(assertNotNull(codec.openURL(route, "baidu", platform)).contains("map/direction?"))
        }
        assertTrue(assertNotNull(codec.openURL(route, "apple", "ios")).contains("daddr="))
        assertTrue(assertNotNull(codec.openURL(route, "google", "ios")).contains("daddr="))
        assertTrue(assertNotNull(codec.browserURL(route)).contains("/maps/dir/"))
        for (transport in listOf("walking", "transit")) {
            val existing = place.copy(mode = "directions", transport = transport)
            assertEquals(existing, codec.navigationDestination(existing))
        }
    }

    @Test fun webFallbackKeepsRouteInsteadOfLosingIt() {
        val d = SharedMapDestination(0.0, -180.0, "地点", "directions", "transit")
        val url = assertNotNull(codec.browserURL(d))
        assertTrue(url.contains("travelmode=transit")); assertTrue(url.contains("/maps/dir/"))
        assertEquals(d, codec.parse(url, "地点"))
    }
}
