package com.margelo.nitro.persistentbackgroundlocation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * `LocationManager`-backed fallback engine for devices without Google Play
 * Services. Less battery-optimal than the fused engine, but it keeps the package
 * fully functional (and genuinely OSS-friendly) on de-Googled / AOSP devices.
 */
@SuppressLint("MissingPermission")
internal class PlatformLocationEngine(context: Context) : LocationEngine {

  override val name: String = "platform"

  private val manager =
    context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

  private var listener: LocationListener? = null

  override fun start(config: LocationConfig, looper: Looper, onLocation: (Location) -> Unit) {
    stop()
    val provider = providerFor(config.accuracy)
    val l = object : LocationListener {
      override fun onLocationChanged(location: Location) = onLocation(location)
      // Required no-op overrides for older API levels.
      override fun onProviderEnabled(provider: String) {}
      override fun onProviderDisabled(provider: String) {}
      @Deprecated("Deprecated in API 29")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }
    listener = l
    try {
      manager.requestLocationUpdates(
        provider,
        config.interval,
        config.distanceFilter.toFloat(),
        l,
        looper
      )
    } catch (t: Throwable) {
      Log.e(Constants.TAG, "LocationManager.requestLocationUpdates failed for $provider", t)
      // Last-ditch: try the network provider if GPS isn't present.
      runCatching {
        manager.requestLocationUpdates(
          LocationManager.NETWORK_PROVIDER,
          config.interval,
          config.distanceFilter.toFloat(),
          l,
          looper
        )
      }
    }
  }

  override fun stop() {
    listener?.let { manager.removeUpdates(it) }
    listener = null
  }

  override fun getCurrentLocation(
    accuracy: String,
    timeoutMs: Long,
    maxAgeMs: Long,
    looper: Looper,
    onResult: (Location?) -> Unit
  ) {
    // A recent enough last-known fix satisfies a non-zero maxAge immediately.
    val cached = bestLastKnown()
    if (cached != null && maxAgeMs > 0 &&
      System.currentTimeMillis() - cached.time <= maxAgeMs
    ) {
      onResult(cached)
      return
    }

    val handler = Handler(looper)
    var settled = false
    val l = object : LocationListener {
      override fun onLocationChanged(location: Location) {
        if (settled) return
        settled = true
        manager.removeUpdates(this)
        onResult(location)
      }
      override fun onProviderEnabled(provider: String) {}
      override fun onProviderDisabled(provider: String) {}
      @Deprecated("Deprecated in API 29")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    try {
      manager.requestLocationUpdates(providerFor(accuracy), 0L, 0f, l, looper)
    } catch (t: Throwable) {
      onResult(cached)
      return
    }

    handler.postDelayed({
      if (settled) return@postDelayed
      settled = true
      manager.removeUpdates(l)
      onResult(cached)
    }, timeoutMs)
  }

  private fun bestLastKnown(): Location? {
    val providers = listOf(
      LocationManager.GPS_PROVIDER,
      LocationManager.NETWORK_PROVIDER,
      LocationManager.PASSIVE_PROVIDER
    )
    return providers
      .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
      .maxByOrNull { it.time }
  }

  private fun providerFor(accuracy: String): String {
    val wantsGps = accuracy == "high" || accuracy == "highest" || accuracy == "balanced"
    val gpsEnabled = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    val networkEnabled = runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    return when {
      wantsGps && gpsEnabled -> LocationManager.GPS_PROVIDER
      networkEnabled -> LocationManager.NETWORK_PROVIDER
      gpsEnabled -> LocationManager.GPS_PROVIDER
      else -> LocationManager.PASSIVE_PROVIDER
    }
  }
}
