package com.margelo.nitro.persistentbackgroundlocation

import android.location.Location
import android.os.Build
import org.json.JSONObject

/**
 * Immutable, platform-agnostic representation of a single location sample.
 *
 * Field names and nullability mirror `LocationFix` in `src/types.ts` exactly.
 * The bridge payload is produced by [toNitro] — a generated Nitro struct
 * ([NativeLocationFix]) whose optional numeric fields are Kotlin-nullable, so an
 * unreported value carries an honest `null` across to JS instead of a sentinel.
 */
internal data class LocationFixModel(
  val id: String?,
  val latitude: Double,
  val longitude: Double,
  val accuracy: Double?,
  val altitude: Double?,
  val altitudeAccuracy: Double?,
  val speed: Double?,
  val speedAccuracy: Double?,
  val heading: Double?,
  val headingAccuracy: Double?,
  val timestamp: Long,
  val isMoving: Boolean,
  val activity: String,
  val batteryLevel: Double?,
  val isCharging: Boolean?,
  val mocked: Boolean,
  val provider: String?
) {

  /** Bridge payload as the generated Nitro struct. Nullable fields pass `null`
   *  exactly where the value was unreported. */
  fun toNitro(): NativeLocationFix = NativeLocationFix(
    id = id,
    latitude = latitude,
    longitude = longitude,
    accuracy = accuracy,
    altitude = altitude,
    altitudeAccuracy = altitudeAccuracy,
    speed = speed,
    speedAccuracy = speedAccuracy,
    heading = heading,
    headingAccuracy = headingAccuracy,
    // Emit as Double so it survives the JS number bridge for the full epoch-ms range.
    timestamp = timestamp.toDouble(),
    isMoving = isMoving,
    activity = activity,
    batteryLevel = batteryLevel,
    isCharging = isCharging,
    mocked = mocked,
    provider = provider
  )

  /** Compact JSON used for the SQLite buffer body and the HTTP sync payload. */
  fun toJson(): JSONObject = JSONObject().apply {
    put("id", id ?: JSONObject.NULL)
    put("latitude", latitude)
    put("longitude", longitude)
    put("accuracy", accuracy ?: JSONObject.NULL)
    put("altitude", altitude ?: JSONObject.NULL)
    put("altitudeAccuracy", altitudeAccuracy ?: JSONObject.NULL)
    put("speed", speed ?: JSONObject.NULL)
    put("speedAccuracy", speedAccuracy ?: JSONObject.NULL)
    put("heading", heading ?: JSONObject.NULL)
    put("headingAccuracy", headingAccuracy ?: JSONObject.NULL)
    put("timestamp", timestamp)
    put("isMoving", isMoving)
    put("activity", activity)
    put("batteryLevel", batteryLevel ?: JSONObject.NULL)
    put("isCharging", isCharging ?: JSONObject.NULL)
    put("mocked", mocked)
    put("provider", provider ?: JSONObject.NULL)
  }

  companion object {
    /**
     * Convert an Android [Location] into a [LocationFixModel].
     *
     * Per-field availability is gated on the platform `has*` accessors so we
     * report `null` (not a misleading `0.0`) when the OS did not measure a
     * value — e.g. `speed`/`bearing` on a stationary cold fix.
     */
    fun fromLocation(
      location: Location,
      isMoving: Boolean,
      activity: String,
      batteryLevel: Double?,
      isCharging: Boolean?
    ): LocationFixModel {
      val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
      val altitude = if (location.hasAltitude()) location.altitude else null
      val speed = if (location.hasSpeed()) location.speed.toDouble() else null
      val heading = if (location.hasBearing()) location.bearing.toDouble() else null

      val altitudeAccuracy =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()) {
          location.verticalAccuracyMeters.toDouble()
        } else null
      val speedAccuracy =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
          location.speedAccuracyMetersPerSecond.toDouble()
        } else null
      val headingAccuracy =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
          location.bearingAccuracyDegrees.toDouble()
        } else null

      val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        location.isMock
      } else {
        @Suppress("DEPRECATION")
        location.isFromMockProvider
      }

      // Prefer the GNSS wall-clock; fall back to "now" if the provider reported 0.
      val timestamp = if (location.time > 0L) location.time else System.currentTimeMillis()

      return LocationFixModel(
        id = null,
        latitude = location.latitude,
        longitude = location.longitude,
        accuracy = accuracy,
        altitude = altitude,
        altitudeAccuracy = altitudeAccuracy,
        speed = speed,
        speedAccuracy = speedAccuracy,
        heading = heading,
        headingAccuracy = headingAccuracy,
        timestamp = timestamp,
        isMoving = isMoving,
        activity = activity,
        batteryLevel = batteryLevel,
        isCharging = isCharging,
        mocked = mocked,
        provider = location.provider
      )
    }

    /** Reconstruct a fix from its persisted JSON (buffer reads). */
    fun fromJson(json: JSONObject, id: String?): LocationFixModel = LocationFixModel(
      id = id ?: json.optStringOrNull("id"),
      latitude = json.getDouble("latitude"),
      longitude = json.getDouble("longitude"),
      accuracy = json.optDoubleOrNull("accuracy"),
      altitude = json.optDoubleOrNull("altitude"),
      altitudeAccuracy = json.optDoubleOrNull("altitudeAccuracy"),
      speed = json.optDoubleOrNull("speed"),
      speedAccuracy = json.optDoubleOrNull("speedAccuracy"),
      heading = json.optDoubleOrNull("heading"),
      headingAccuracy = json.optDoubleOrNull("headingAccuracy"),
      timestamp = json.optLong("timestamp", System.currentTimeMillis()),
      isMoving = json.optBoolean("isMoving", false),
      activity = json.optString("activity", "unknown"),
      batteryLevel = json.optDoubleOrNull("batteryLevel"),
      isCharging = if (json.isNull("isCharging")) null else json.optBoolean("isCharging"),
      mocked = json.optBoolean("mocked", false),
      provider = json.optStringOrNull("provider")
    )
  }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
  if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optStringOrNull(key: String): String? =
  if (isNull(key) || !has(key)) null else optString(key)
