package com.margelo.nitro.persistentbackgroundlocation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

/**
 * `FusedLocationProviderClient`-backed engine — the battery-optimal default.
 *
 * The caller is responsible for holding the location runtime permission; this
 * class suppresses the lint check because permission is enforced (and surfaced
 * with a friendly error) before the service is ever started.
 */
@SuppressLint("MissingPermission")
internal class FusedLocationEngine(context: Context) : LocationEngine {

  override val name: String = "fused"

  private val client: FusedLocationProviderClient =
    LocationServices.getFusedLocationProviderClient(context.applicationContext)

  private var callback: LocationCallback? = null
  private var currentLocationCts: CancellationTokenSource? = null

  override fun start(config: LocationConfig, looper: Looper, onLocation: (Location) -> Unit) {
    stop()
    val request = buildRequest(config)
    val cb = object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        result.lastLocation?.let(onLocation)
      }
    }
    callback = cb
    client.requestLocationUpdates(request, cb, looper)
  }

  override fun stop() {
    callback?.let { client.removeLocationUpdates(it) }
    callback = null
    currentLocationCts?.cancel()
    currentLocationCts = null
  }

  override fun getCurrentLocation(
    accuracy: String,
    timeoutMs: Long,
    maxAgeMs: Long,
    looper: Looper,
    onResult: (Location?) -> Unit
  ) {
    val cts = CancellationTokenSource()
    currentLocationCts = cts
    val request = CurrentLocationRequest.Builder()
      .setPriority(priorityFor(accuracy))
      .setDurationMillis(timeoutMs)
      .setMaxUpdateAgeMillis(maxAgeMs)
      .build()

    client.getCurrentLocation(request, cts.token)
      .addOnSuccessListener { onResult(it) }
      .addOnFailureListener {
        Log.w(Constants.TAG, "getCurrentLocation failed", it)
        onResult(null)
      }
  }

  private fun buildRequest(config: LocationConfig): LocationRequest {
    val builder = LocationRequest.Builder(priorityFor(config.accuracy), config.interval)
      .setMinUpdateIntervalMillis(config.fastestInterval.coerceAtMost(config.interval))
      .setWaitForAccurateLocation(config.accuracy == "highest")
    if (config.distanceFilter > 0.0) {
      builder.setMinUpdateDistanceMeters(config.distanceFilter.toFloat())
    }
    return builder.build()
  }

  private fun priorityFor(accuracy: String): Int = when (accuracy) {
    "lowest" -> Priority.PRIORITY_PASSIVE
    "low" -> Priority.PRIORITY_LOW_POWER
    "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    "high", "highest" -> Priority.PRIORITY_HIGH_ACCURACY
    else -> Priority.PRIORITY_HIGH_ACCURACY
  }
}
