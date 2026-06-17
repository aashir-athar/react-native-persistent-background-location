package com.margelo.nitro.persistentbackgroundlocation

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide pub/sub bridge between the [BackgroundLocationService] (which may
 * outlive any React context) and the [HybridPersistentBackgroundLocation]
 * (which comes and goes with the JS runtime).
 *
 * The service always publishes here; if a HybridObject is attached it forwards
 * events to JS, and if not, the fixes were already persisted to SQLite so
 * nothing is lost. The bus also holds the small `running / lastFix / isMoving`
 * snapshot that backs `getStatus()` and `isRunning()` without a round-trip to
 * the service.
 *
 * [CopyOnWriteArraySet] gives lock-free reads on the hot `emit*` path while
 * tolerating concurrent register/unregister from the JS thread.
 */
internal object LocationEventBus {

  interface Listener {
    fun onLocation(fix: LocationFixModel)
    fun onMotionChange(isMoving: Boolean, activity: String, fix: LocationFixModel?)
    fun onProviderChange(enabled: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean)
    fun onSync(result: SyncResult)
    fun onError(code: String, message: String, fatal: Boolean)
  }

  private val listeners = CopyOnWriteArraySet<Listener>()

  @Volatile var running: Boolean = false
    private set

  @Volatile var lastFix: LocationFixModel? = null
    private set

  @Volatile var isMoving: Boolean = false
    private set

  @Volatile var trackingSince: Long = 0L
    private set

  fun register(listener: Listener) {
    listeners.add(listener)
  }

  fun unregister(listener: Listener) {
    listeners.remove(listener)
  }

  fun setRunning(value: Boolean, since: Long) {
    running = value
    trackingSince = if (value) since else 0L
    if (!value) isMoving = false
  }

  fun emitLocation(fix: LocationFixModel) {
    lastFix = fix
    isMoving = fix.isMoving
    listeners.forEach { runCatching { it.onLocation(fix) } }
  }

  fun emitMotionChange(moving: Boolean, activity: String, fix: LocationFixModel?) {
    isMoving = moving
    listeners.forEach { runCatching { it.onMotionChange(moving, activity, fix) } }
  }

  fun emitProviderChange(enabled: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean) {
    listeners.forEach { runCatching { it.onProviderChange(enabled, gpsEnabled, networkEnabled) } }
  }

  fun emitSync(result: SyncResult) {
    listeners.forEach { runCatching { it.onSync(result) } }
  }

  fun emitError(code: String, message: String, fatal: Boolean) {
    listeners.forEach { runCatching { it.onError(code, message, fatal) } }
  }
}
