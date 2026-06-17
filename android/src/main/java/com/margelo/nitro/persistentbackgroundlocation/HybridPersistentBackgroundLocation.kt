package com.margelo.nitro.persistentbackgroundlocation

import android.location.Location
import android.os.Looper
import android.util.Log
import com.margelo.nitro.core.Promise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * JS ↔ Kotlin bridge (Nitro HybridObject). Thin by design: every method either
 * configures the long-lived [BackgroundLocationService], reads the
 * [LocationEventBus] / [LocationBufferStore] snapshot, or delegates to
 * [PermissionHelper]. The heavy lifting (the actual GPS stream + survival) lives
 * in the service so it can keep running after this object — and the whole JS
 * runtime — is gone.
 *
 * A Nitro HybridObject is constructed by the native layer with no Context, so
 * every Context use here goes through [AppContextHolder] (populated by
 * [LocationContextProvider] at process start).
 *
 * Surface and event names are the contract in `src/types.ts`; keep them in sync.
 */
class HybridPersistentBackgroundLocation : HybridPersistentBackgroundLocationSpec() {

  // ── JS callbacks (set via the set* methods). Volatile: written from JS, read
  //    from the bus listener which may fire on a service thread. ──────────────
  @Volatile private var onLocation: ((NativeLocationFix) -> Unit)? = null
  @Volatile private var onMotionChange: ((NativeMotionChangeEvent) -> Unit)? = null
  @Volatile private var onProviderChange: ((NativeProviderChangeEvent) -> Unit)? = null
  @Volatile private var onSync: ((NativeSyncResult) -> Unit)? = null
  @Volatile private var onError: ((NativeLocationErrorEvent) -> Unit)? = null

  /** Forwards bus events to the stored JS callbacks. Registered for this
   *  HybridObject's lifetime; unregistered when the object is disposed. */
  private val busListener = object : LocationEventBus.Listener {
    override fun onLocation(fix: LocationFixModel) {
      runCatching { this@HybridPersistentBackgroundLocation.onLocation?.invoke(fix.toNitro()) }
    }

    override fun onMotionChange(isMoving: Boolean, activity: String, fix: LocationFixModel?) {
      runCatching {
        onMotionChange?.invoke(
          NativeMotionChangeEvent(isMoving = isMoving, activity = activity, fix = fix?.toNitro())
        )
      }
    }

    override fun onProviderChange(enabled: Boolean, gpsEnabled: Boolean, networkEnabled: Boolean) {
      runCatching {
        onProviderChange?.invoke(
          NativeProviderChangeEvent(
            enabled = enabled,
            gpsEnabled = gpsEnabled,
            networkEnabled = networkEnabled,
            authorization = PermissionHelper.currentState().status
          )
        )
      }
    }

    override fun onSync(result: SyncResult) {
      runCatching { onSync?.invoke(result.toNitro()) }
    }

    override fun onError(code: String, message: String, fatal: Boolean) {
      runCatching {
        onError?.invoke(NativeLocationErrorEvent(code = code, message = message, fatal = fatal))
      }
    }
  }

  init {
    LocationEventBus.register(busListener)
  }

  /**
   * Called by the Nitro runtime when the JS-side instance is garbage-collected.
   * Unregister the bus listener so a destroyed HybridObject doesn't keep
   * receiving events (and leak through the bus's strong reference).
   */
  override fun dispose() {
    LocationEventBus.unregister(busListener)
    onLocation = null
    onMotionChange = null
    onProviderChange = null
    onSync = null
    onError = null
    super.dispose()
  }

  private fun requireContext() = AppContextHolder.appContext
    ?: throw IllegalStateException(
      "${Constants.ERR_NO_CONTEXT}: Android application context is unavailable."
    )

  // ─────────────────────────────────────────────────────────────────────────
  // Lifecycle
  // ─────────────────────────────────────────────────────────────────────────

  override fun start(config: NativeStartConfig): Promise<Unit> = Promise.async {
    val ctx = requireContext()
    if (!PermissionHelper.hasForeground(ctx)) {
      throw IllegalStateException(
        "${Constants.ERR_PERMISSION_DENIED}: Location permission not granted. " +
          "Call requestPermissions() before start()."
      )
    }
    BackgroundLocationService.start(ctx, config.toLocationConfig())
  }

  override fun stop(): Promise<Unit> = Promise.async {
    BackgroundLocationService.stop(requireContext())
  }

  override fun isRunning(): Boolean = BackgroundLocationService.isRunning()

  override fun getStatus(): Promise<NativeTrackingStatus> = Promise.async {
    val ctx = requireContext()
    val perm = PermissionHelper.currentState()
    val bufferedCount = withContext(Dispatchers.IO) {
      runCatching { LocationBufferStore.get(ctx).count() }.getOrDefault(0)
    }
    NativeTrackingStatus(
      running = BackgroundLocationService.isRunning(),
      lastFix = LocationEventBus.lastFix?.toNitro(),
      bufferedCount = bufferedCount.toDouble(),
      authorization = perm.status,
      locationServicesEnabled = PermissionHelper.locationServicesEnabled(ctx),
      isMoving = LocationEventBus.isMoving,
      trackingSince = LocationEventBus.trackingSince.takeIf { it > 0L }?.toDouble()
    )
  }

  override fun getCurrentPosition(options: NativeCurrentPositionOptions): Promise<NativeLocationFix> =
    Promise.async {
      val ctx = requireContext()
      if (!PermissionHelper.hasForeground(ctx)) {
        throw IllegalStateException(
          "${Constants.ERR_PERMISSION_DENIED}: Location permission not granted."
        )
      }
      val engine = LocationEngineFactory.create(ctx)
      val location = suspendCancellableCoroutine<Location?> { cont ->
        engine.getCurrentLocation(
          accuracy = options.accuracy,
          timeoutMs = options.timeoutMs.toLong(),
          maxAgeMs = options.maximumAgeMs.toLong(),
          looper = Looper.getMainLooper()
        ) { result ->
          runCatching { engine.stop() }
          if (cont.isActive) cont.resume(result)
        }
        cont.invokeOnCancellation { runCatching { engine.stop() } }
      } ?: throw IllegalStateException(
        "${Constants.ERR_TIMEOUT}: Timed out acquiring a location fix."
      )

      val (battery, charging) = BatteryHelper.snapshot(ctx)
      LocationFixModel
        .fromLocation(location, isMoving = false, activity = "unknown", batteryLevel = battery, isCharging = charging)
        .toNitro()
    }

  // ─────────────────────────────────────────────────────────────────────────
  // Buffer & sync
  // ─────────────────────────────────────────────────────────────────────────

  override fun getBufferedLocations(limit: Double): Promise<Array<NativeLocationFix>> =
    Promise.async {
      val ctx = requireContext()
      withContext(Dispatchers.IO) {
        LocationBufferStore.get(ctx).recent(limit.toInt())
          .map { it.toNitro() }
          .toTypedArray()
      }
    }

  override fun clearBuffer(): Promise<Double> = Promise.async {
    val ctx = requireContext()
    withContext(Dispatchers.IO) { LocationBufferStore.get(ctx).clear() }.toDouble()
  }

  override fun flush(): Promise<NativeSyncResult> = Promise.async {
    val ctx = requireContext()
    val config = ConfigStore.load(ctx)
      ?: return@async SyncResult(false, 0, null, "not-configured").toNitro()
    if (config.syncUrl.isNullOrEmpty()) {
      return@async SyncResult(false, 0, null, "no-sync-url").toNitro()
    }
    val result = LocationSyncer(LocationBufferStore.get(ctx), config.debug).flush(config)
    LocationEventBus.emitSync(result)
    result.toNitro()
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Permissions
  // ─────────────────────────────────────────────────────────────────────────

  override fun requestPermissions(background: Boolean): Promise<NativePermissionResult> =
    Promise.async {
      PermissionHelper.request(background).toNitro()
    }

  override fun getPermissionStatus(): Promise<NativePermissionResult> = Promise.async {
    PermissionHelper.currentState().toNitro()
  }

  override fun openSettings() {
    try {
      PermissionHelper.openSettings(requireContext())
    } catch (t: Throwable) {
      Log.w(Constants.TAG, "openSettings failed", t)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Event callback registration
  // ─────────────────────────────────────────────────────────────────────────

  override fun setOnLocation(callback: ((fix: NativeLocationFix) -> Unit)?) {
    onLocation = callback
  }

  override fun setOnMotionChange(callback: ((event: NativeMotionChangeEvent) -> Unit)?) {
    onMotionChange = callback
  }

  override fun setOnProviderChange(callback: ((event: NativeProviderChangeEvent) -> Unit)?) {
    onProviderChange = callback
  }

  override fun setOnSync(callback: ((event: NativeSyncResult) -> Unit)?) {
    onSync = callback
  }

  override fun setOnError(callback: ((event: NativeLocationErrorEvent) -> Unit)?) {
    onError = callback
  }
}

/** Convert the generated start-config struct into the internal [LocationConfig]. */
private fun NativeStartConfig.toLocationConfig(): LocationConfig = LocationConfig(
  accuracy = accuracy,
  distanceFilter = distanceFilter,
  interval = interval.toLong(),
  fastestInterval = fastestInterval.toLong(),
  activityType = activityType,
  showsBackgroundLocationIndicator = showsBackgroundLocationIndicator,
  pausesUpdatesAutomatically = pausesUpdatesAutomatically,
  stopOnTerminate = stopOnTerminate,
  restartOnBoot = restartOnBoot,
  useSignificantChanges = useSignificantChanges,
  debug = debug,
  notificationTitle = notificationTitle,
  notificationBody = notificationBody,
  notificationChannelId = notificationChannelId,
  notificationChannelName = notificationChannelName,
  notificationColor = notificationColor,
  notificationIcon = notificationIcon,
  tapToOpenApp = tapToOpenApp,
  persist = persist,
  syncUrl = syncUrl,
  httpMethod = httpMethod,
  headers = headers,
  batchSize = batchSize.toInt(),
  autoSync = autoSync,
  maxRecordsToPersist = maxRecordsToPersist.toInt(),
  motionEnabled = motionEnabled,
  stationaryTimeoutMs = stationaryTimeoutMs.toLong()
)
