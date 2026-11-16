package com.clarklevis.dsh.android.platform.devicetools

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant
import kotlin.math.round

internal interface AndroidLocationSource {
    fun isEnabled(provider: String): Boolean
    fun lastKnown(provider: String): Location?
    fun requestUpdates(provider: String, listener: LocationListener)
    fun removeUpdates(listener: LocationListener)
}

internal class AndroidLocationConnector(
    private val source: AndroidLocationSource,
    private val acquisitionTimeoutMillis: Long = 10_000,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun location(precise: Boolean, maxAgeSeconds: Int): JSONObject = withContext(Dispatchers.Main.immediate) {
        val providers = (if (precise) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }).filter { source.isEnabled(it) }
        if (providers.isEmpty()) throw ConnectorFailure("location_disabled")

        val cached = providers.mapNotNull { source.lastKnown(it) }
            .filter { acceptable(it, precise, maxAgeSeconds) }
            .minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
        // maxAgeSeconds 限制样本年龄；采集另设短超时，须早于连接器外层的 15 秒超时。
        val sample = cached ?: withTimeoutOrNull(acquisitionTimeoutMillis) {
            updates(providers).first { acceptable(it, precise, maxAgeSeconds) }
        } ?: throw ConnectorFailure("data_unavailable")

        if (!acceptable(sample, precise, maxAgeSeconds)) throw ConnectorFailure("data_unavailable")
        JSONObject()
            .put("latitude", if (precise) sample.latitude else round(sample.latitude * 100) / 100)
            .put("longitude", if (precise) sample.longitude else round(sample.longitude * 100) / 100)
            .put("precision", if (precise) "precise" else "coarse")
            .put("horizontalAccuracyMeters", if (precise) sample.accuracy.toDouble() else maxOf(1500.0, sample.accuracy.toDouble()))
            .put("sampledAt", Instant.ofEpochMilli(sample.time).toString())
    }

    private fun acceptable(sample: Location, precise: Boolean, maxAgeSeconds: Int): Boolean =
        sample.latitude.isFinite() && sample.latitude in -90.0..90.0 &&
            sample.longitude.isFinite() && sample.longitude in -180.0..180.0 &&
            sample.hasAccuracy() && sample.accuracy.isFinite() && sample.accuracy >= 0f &&
            (!precise || sample.accuracy <= GOOD_ACCURACY_METERS) &&
            now() - sample.time in 0..maxAgeSeconds * 1000L

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun updates(providers: List<String>) = callbackFlow {
        val activeProviders = providers.toMutableSet()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(Location(location)) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                activeProviders.remove(provider)
                if (activeProviders.isEmpty()) close(ConnectorFailure("location_disabled"))
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            for (provider in providers) {
                try {
                    source.requestUpdates(provider, listener)
                } catch (_: IllegalArgumentException) {
                    // provider 可能在检查后消失；继续使用其他可用来源。
                    activeProviders.remove(provider)
                }
            }
            if (activeProviders.isEmpty()) throw ConnectorFailure("data_unavailable")
            awaitClose()
        } finally {
            source.removeUpdates(listener)
        }
    }

    private companion object {
        const val GOOD_ACCURACY_METERS = 50f
    }
}

@Suppress("MissingPermission")
internal class LocationManagerSource(private val manager: LocationManager) : AndroidLocationSource {
    override fun isEnabled(provider: String) = manager.isProviderEnabled(provider)
    override fun lastKnown(provider: String): Location? = manager.getLastKnownLocation(provider)
    override fun requestUpdates(provider: String, listener: LocationListener) {
        manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
    }
    override fun removeUpdates(listener: LocationListener) { manager.removeUpdates(listener) }
}
