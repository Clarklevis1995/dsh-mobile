package com.clarklevis.dsh.android.platform.devicetools

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocationConnectorDeviceTest {
    @Test
    fun preciseCacheRequiresFiftyMeterAccuracyAndPrefersNewerTies() = runBlocking {
        val source = Source().apply {
            cached[GPS] = sample(50f, ageMillis = 2_000)
            cached[NETWORK] = sample(50f, ageMillis = 1_000, latitude = 31.23456)
        }
        val result = connector(source).location(precise = true, maxAgeSeconds = 60)
        assertEquals("precise", result.getString("precision"))
        assertEquals(50.0, result.getDouble("horizontalAccuracyMeters"), 0.0)
        assertEquals(31.23456, result.getDouble("latitude"), 0.0)
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun inaccurateCacheAndEarlyFixesWaitForAccurateNetworkFixWhileGpsHasNoGoodFix() = runBlocking {
        val source = Source().apply { cached[NETWORK] = sample(1500f) }
        val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
        source.awaitRequests()
        assertEquals(listOf(GPS, NETWORK), source.requested)
        withContext(Dispatchers.Main) {
            source.emit(sample(300f, provider = GPS))
            source.emit(sample(1500f))
        }
        assertFalse(pending.isCompleted)
        withContext(Dispatchers.Main) { source.emit(sample(25f, latitude = 31.23456)) }
        val result = pending.await()
        assertEquals("precise", result.getString("precision"))
        assertEquals(25.0, result.getDouble("horizontalAccuracyMeters"), 0.0)
        assertEquals(31.23456, result.getDouble("latitude"), 0.0)
        assertEquals(1, source.removals)
    }

    @Test
    fun malformedStaleAndFutureSamplesAreRejectedInBothCacheAndLiveUpdates() = runBlocking {
        val invalid = listOf(
            sample(50.1f),
            sample(-1f),
            sample(Float.NaN),
            sample(Float.POSITIVE_INFINITY),
            sample(1f).apply { removeAccuracy() },
            sample(1f, latitude = Double.NaN),
            sample(1f, latitude = 91.0),
            sample(1f).apply { longitude = 181.0 },
            sample(1f, ageMillis = 60_001),
            sample(1f, ageMillis = -1)
        )
        for (bad in invalid) {
            val source = Source().apply { cached[GPS] = bad }
            val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
            source.awaitRequests()
            withContext(Dispatchers.Main) { source.emit(bad) }
            assertFalse(pending.isCompleted)
            withContext(Dispatchers.Main) { source.emit(sample(10f)) }
            assertEquals(10.0, pending.await().getDouble("horizontalAccuracyMeters"), 0.0)
            assertEquals(1, source.removals)
        }
    }

    @Test
    fun noAccurateFixFailsWithDataUnavailableAndRemovesEverySubscription() = runBlocking {
        val source = Source().apply { cached[NETWORK] = sample(1500f) }
        try {
            AndroidLocationConnector(source, acquisitionTimeoutMillis = 100, now = { NOW })
                .location(precise = true, maxAgeSeconds = 300)
            throw AssertionError("不应将低精度结果标为 precise")
        } catch (failure: ConnectorFailure) {
            assertEquals("data_unavailable", failure.code)
        }
        assertEquals(listOf(GPS, NETWORK), source.requested)
        assertEquals(1, source.removals)
        assertTrue(source.listeners.isEmpty())
    }

    @Test
    fun cancellationImmediatelyRemovesAllProviderSubscriptions() = runBlocking {
        val source = Source()
        val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
        source.awaitRequests()
        pending.cancelAndJoin()
        assertEquals(1, source.removals)
        assertTrue(source.listeners.isEmpty())
    }

    @Test
    fun disappearingGpsProviderDoesNotPreventNetworkFix() = runBlocking {
        val source = Source().apply { requestErrors[GPS] = IllegalArgumentException("provider removed") }
        val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
        source.awaitRequests()
        withContext(Dispatchers.Main) { source.emit(sample(40f)) }
        assertEquals(40.0, pending.await().getDouble("horizontalAccuracyMeters"), 0.0)
        assertEquals(1, source.removals)
    }

    @Test
    fun disablingGpsDuringAcquisitionKeepsNetworkSubscriptionAlive() = runBlocking {
        val source = Source()
        val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
        source.awaitRequests()
        withContext(Dispatchers.Main) {
            source.listeners.single().onProviderDisabled(GPS)
            source.emit(sample(30f))
        }
        assertEquals(30.0, pending.await().getDouble("horizontalAccuracyMeters"), 0.0)
        assertEquals(1, source.removals)
    }

    @Test
    fun disablingAllProvidersDuringAcquisitionFailsAndRemovesSubscriptions() = runBlocking {
        supervisorScope {
            val source = Source()
            val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
            source.awaitRequests()
            withContext(Dispatchers.Main) {
                source.listeners.single().onProviderDisabled(GPS)
                source.listeners.single().onProviderDisabled(NETWORK)
            }
            try {
                pending.await()
                throw AssertionError("所有定位来源关闭后不应继续等待")
            } catch (failure: ConnectorFailure) {
                assertEquals("location_disabled", failure.code)
                assertEquals(1, source.removals)
            }
        }
    }

    @Test
    fun permissionRevocationAfterFirstRegistrationPropagatesAndCleansUp() = runBlocking {
        supervisorScope {
            val source = Source().apply { requestErrors[NETWORK] = SecurityException("permission revoked") }
            val pending = async { connector(source).location(precise = true, maxAgeSeconds = 60) }
            try {
                pending.await()
                throw AssertionError("权限撤销必须传递给连接器")
            } catch (_: SecurityException) {
                assertEquals(1, source.removals)
                assertTrue(source.listeners.isEmpty())
            }
        }
    }

    @Test
    fun allProvidersDisabledFailsWithoutStartingUpdates() = runBlocking {
        val source = Source().apply { enabled.clear() }
        try {
            connector(source).location(precise = true, maxAgeSeconds = 60)
            throw AssertionError("未启用定位服务时不能成功")
        } catch (failure: ConnectorFailure) {
            assertEquals("location_disabled", failure.code)
        }
        assertTrue(source.requested.isEmpty())
    }

    @Test
    fun coarseResultsKeepRoundingAndPrivacyAccuracyFloor() = runBlocking {
        for (accuracy in listOf(10f, 1500f, 2000f)) {
            val source = Source().apply { cached[NETWORK] = sample(accuracy) }
            val result = connector(source).location(precise = false, maxAgeSeconds = 60)
            assertEquals("coarse", result.getString("precision"))
            assertEquals(31.23, result.getDouble("latitude"), 0.0)
            assertEquals(121.47, result.getDouble("longitude"), 0.0)
            assertEquals(maxOf(1500.0, accuracy.toDouble()), result.getDouble("horizontalAccuracyMeters"), 0.0)
        }
    }

    private fun connector(source: Source) = AndroidLocationConnector(source, now = { NOW })

    private class Source : AndroidLocationSource {
        val enabled = mutableSetOf(GPS, NETWORK)
        val cached = mutableMapOf<String, Location>()
        val requested = mutableListOf<String>()
        val requestErrors = mutableMapOf<String, Exception>()
        val listeners = mutableSetOf<LocationListener>()
        var removals = 0
        private val ready = CompletableDeferred<Unit>()
        override fun isEnabled(provider: String) = provider in enabled
        override fun lastKnown(provider: String) = cached[provider]
        override fun requestUpdates(provider: String, listener: LocationListener) {
            requested += provider
            if (requested.size == enabled.size) ready.complete(Unit)
            requestErrors[provider]?.let { throw it }
            listeners += listener
        }
        override fun removeUpdates(listener: LocationListener) {
            removals++
            listeners.remove(listener)
        }
        suspend fun awaitRequests() { withTimeout(2_000) { ready.await() } }
        fun emit(location: Location) { listeners.toList().forEach { it.onLocationChanged(location) } }
    }

    private companion object {
        const val GPS = LocationManager.GPS_PROVIDER
        const val NETWORK = LocationManager.NETWORK_PROVIDER
        const val NOW = 1_800_000_000_000L
        fun sample(
            accuracy: Float,
            ageMillis: Long = 0,
            latitude: Double = 31.234567,
            provider: String = NETWORK
        ) = Location(provider).apply {
            this.latitude = latitude
            longitude = 121.467891
            this.accuracy = accuracy
            time = NOW - ageMillis
        }
    }
}
