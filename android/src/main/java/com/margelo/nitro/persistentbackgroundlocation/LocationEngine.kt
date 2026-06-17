package com.margelo.nitro.persistentbackgroundlocation

import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Abstraction over the device's location source so the service is agnostic to
 * *how* fixes are produced. Two implementations ship:
 *
 *  - [FusedLocationEngine] — Google Play Services `FusedLocationProviderClient`,
 *    the battery-optimal path used on the vast majority of devices.
 *  - [PlatformLocationEngine] — raw AOSP `LocationManager`, the fallback for
 *    de-Googled / Play-less devices so the package stays genuinely free of a
 *    hard Play Services requirement.
 *
 * Every callback delivers a raw [Location]; enrichment (motion, battery) and
 * persistence happen one layer up in [BackgroundLocationService].
 */
internal interface LocationEngine {
  /** Begin continuous updates. [onLocation] is invoked on [looper]'s thread. */
  fun start(config: LocationConfig, looper: Looper, onLocation: (Location) -> Unit)

  /** Stop continuous updates. Safe to call when not started. */
  fun stop()

  /** Resolve one fresh fix. [onResult] receives `null` on timeout / failure. */
  fun getCurrentLocation(
    accuracy: String,
    timeoutMs: Long,
    maxAgeMs: Long,
    looper: Looper,
    onResult: (Location?) -> Unit
  )

  /** Human-readable engine name, surfaced as `LocationFix.provider` fallback. */
  val name: String
}

internal object LocationEngineFactory {
  /**
   * Prefer the fused engine when Play Services are present and usable; otherwise
   * fall back to the platform engine. The check is defensive on both axes —
   * availability *and* class linkage — so a stripped APK never crashes here.
   */
  fun create(context: Context): LocationEngine {
    return if (isFusedAvailable(context)) {
      runCatching { FusedLocationEngine(context) as LocationEngine }
        .getOrElse { PlatformLocationEngine(context) }
    } else {
      PlatformLocationEngine(context)
    }
  }

  private fun isFusedAvailable(context: Context): Boolean = runCatching {
    Class.forName("com.google.android.gms.location.LocationServices")
    val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    status == ConnectionResult.SUCCESS
  }.getOrElse {
    Log.i(Constants.TAG, "Play Services unavailable — using LocationManager engine.")
    false
  }
}
