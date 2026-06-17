package com.margelo.nitro.persistentbackgroundlocation

import kotlin.math.max

/**
 * Decides whether the device is *moving* or *stationary* so the service can
 * throttle the GPS request when nothing is happening — the single biggest
 * battery lever available without OEM-specific tricks.
 *
 * The gate is primarily **heuristic**: it fuses the reported GNSS speed with a
 * displacement-over-time estimate, which works on every device with zero extra
 * permissions. When [ActivityRecognitionHelper] is active it additionally feeds
 * a hardware activity label in via [applyActivity], sharpening the `activity`
 * classification (`walking` vs `in_vehicle`, etc.).
 *
 * This is the "basic motion gating" of the v1 scope — deliberately not the
 * multi-year ML model that commercial SDKs ship.
 */
internal class MotionGate(private val stationaryTimeoutMs: Long) {

  @Volatile var isMoving: Boolean = false
    private set

  @Volatile var activity: String = "unknown"
    private set

  private var lastMovingAtMs: Long = 0L
  private var lastLat: Double? = null
  private var lastLon: Double? = null
  private var lastFixAtMs: Long = 0L

  /** Hardware-confirmed activity (from ActivityRecognition) overrides the heuristic when present. */
  @Volatile private var hardwareActivity: String? = null

  /**
   * Fold a new fix into the gate.
   *
   * @return `true` when the moving/stationary state flipped as a result.
   */
  fun consider(fix: LocationFixModel): Boolean {
    val now = fix.timestamp
    val speed = estimateSpeed(fix, now)

    val movingBySpeed = speed >= MOVING_SPEED_MPS
    val classified = hardwareActivity ?: classifyBySpeed(speed)
    activity = classified

    val moving = when {
      movingBySpeed -> true
      classified == "still" || classified == "unknown" -> false
      // A hardware "walking/running/in_vehicle" label keeps us moving even if a
      // momentary speed dip would otherwise read as stationary.
      else -> true
    }

    val wasMoving = isMoving
    if (moving) {
      lastMovingAtMs = now
      isMoving = true
    } else if (now - lastMovingAtMs >= stationaryTimeoutMs) {
      isMoving = false
    }

    lastLat = fix.latitude
    lastLon = fix.longitude
    lastFixAtMs = now
    return wasMoving != isMoving
  }

  /** Apply a hardware activity label from ActivityRecognition. */
  fun applyActivity(activityLabel: String) {
    hardwareActivity = activityLabel
    activity = activityLabel
    if (activityLabel != "still" && activityLabel != "unknown") {
      lastMovingAtMs = max(lastMovingAtMs, nowGuess())
      isMoving = true
    }
  }

  fun reset() {
    isMoving = false
    activity = "unknown"
    hardwareActivity = null
    lastMovingAtMs = 0L
    lastLat = null
    lastLon = null
    lastFixAtMs = 0L
  }

  private fun estimateSpeed(fix: LocationFixModel, now: Long): Double {
    fix.speed?.let { if (it >= 0) return it }
    // No GNSS speed → derive from displacement since the previous fix.
    val pLat = lastLat
    val pLon = lastLon
    val dtMs = now - lastFixAtMs
    if (pLat == null || pLon == null || dtMs <= 0) return 0.0
    val meters = haversineMeters(pLat, pLon, fix.latitude, fix.longitude)
    return meters / (dtMs / 1000.0)
  }

  private fun classifyBySpeed(speed: Double): String = when {
    speed < MOVING_SPEED_MPS -> "still"
    speed < 2.2 -> "walking"      // < ~8 km/h
    speed < 4.5 -> "running"      // < ~16 km/h
    speed < 50.0 -> "in_vehicle"  // < ~180 km/h
    else -> "unknown"
  }

  private fun nowGuess(): Long = max(lastFixAtMs, lastMovingAtMs)

  companion object {
    /** ~1.8 km/h — above sensor noise, below a slow walk. */
    private const val MOVING_SPEED_MPS = 0.5

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
      val dLat = Math.toRadians(lat2 - lat1)
      val dLon = Math.toRadians(lon2 - lon1)
      val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
        Math.sin(dLon / 2) * Math.sin(dLon / 2)
      return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
  }
}
