package com.margelo.nitro.persistentbackgroundlocation

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The heart of the package's Android survival story.
 *
 * A `location`-typed foreground service that:
 *  - owns the [LocationEngine] and streams fixes off a dedicated [HandlerThread];
 *  - enriches each fix with motion + battery context, persists it to the
 *    [LocationBufferStore], publishes it on the [LocationEventBus], and lets the
 *    [LocationSyncer] drain it to the network — **all without any JS running**;
 *  - survives swipe-to-kill three ways: the FGS keeps the process alive, the
 *    sticky restart contract re-arms it after a low-memory kill (reloading its
 *    config from disk), and [onTaskRemoved] schedules a best-effort restart;
 *  - re-arms after reboot via [BootBroadcastReceiver].
 */
class BackgroundLocationService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private lateinit var locationThread: HandlerThread

  private var engine: LocationEngine? = null
  private var motionGate: MotionGate? = null
  private var syncer: LocationSyncer? = null
  private var config: LocationConfig? = null
  private var syncJob: Job? = null
  private var trackingSince: Long = 0L

  /** Tracks the engine cadence so motion throttling doesn't restart it needlessly. */
  private var lowPowerActive = false

  private var providerReceiver: BroadcastReceiver? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    instance = this
    locationThread = HandlerThread("rn-bg-location").apply { start() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == Constants.ACTION_STOP) {
      // Always honor the startForegroundService() 5s contract before tearing
      // down — even if the saved config is gone, fall back to defaults so a
      // config-less STOP delivery can't trigger a "did not call startForeground"
      // crash. Then shut everything down.
      startInForeground(ConfigStore.load(this) ?: LocationConfig.defaults())
      shutdown(markStopped = true)
      return START_NOT_STICKY
    }

    // Config comes from the intent on a fresh start, or from disk on a
    // system-initiated (null intent) sticky restart / boot restart.
    val resolved = intent?.getStringExtra(EXTRA_CONFIG_JSON)
      ?.let { runCatching { LocationConfig.fromJson(it) }.getOrNull() }
      ?: ConfigStore.load(this)

    if (resolved == null) {
      Log.w(Constants.TAG, "Service started without a config — stopping.")
      promoteTypelessThenStop()
      return START_NOT_STICKY
    }

    // A null intent (system sticky restart) or an explicit RESTART action both
    // mean "resume the existing session" — preserve the original trackingSince.
    val restarted = intent == null || intent.action == Constants.ACTION_RESTART

    // A location FGS started from the background (boot / sticky / alarm restart)
    // additionally requires ACCESS_BACKGROUND_LOCATION on API 29+ — without it,
    // startForeground(TYPE_LOCATION) throws. Foreground starts only need the
    // fine/coarse grant.
    val missingForeground = !PermissionHelper.hasForeground(this)
    val missingBackground = restarted &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      !PermissionHelper.hasBackground(this)
    if (missingForeground || missingBackground) {
      LocationEventBus.emitError(
        Constants.ERR_PERMISSION_DENIED,
        if (missingBackground) {
          "Resuming background tracking needs the \"Allow all the time\" location permission."
        } else {
          "Location permission is not granted; cannot run the tracking service."
        },
        fatal = true
      )
      promoteTypelessThenStop()
      return START_NOT_STICKY
    }

    if (!startInForeground(resolved)) return START_NOT_STICKY
    reconfigure(resolved, restarted)
    return START_STICKY
  }

  /** Promote to a typed location FGS. @return `false` (after stopping) if the OS
   *  refused — in which case we fall back to a typeless FGS purely to satisfy the
   *  startForegroundService() contract and avoid a "did not call startForeground" crash. */
  private fun startInForeground(cfg: LocationConfig): Boolean {
    val notification = NotificationFactory.build(this, cfg)
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceCompat.startForeground(
          this,
          Constants.NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
      } else {
        startForeground(Constants.NOTIFICATION_ID, notification)
      }
      true
    } catch (t: Throwable) {
      Log.e(Constants.TAG, "startForeground(location) failed", t)
      LocationEventBus.emitError(Constants.ERR_SERVICE, t.message ?: "startForeground failed", fatal = true)
      runCatching {
        ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID, notification, 0)
      }
      stopSelf()
      false
    }
  }

  /** Satisfy the foreground-service start contract with a minimal typeless
   *  notification, then stop — for the early-return error paths. */
  private fun promoteTypelessThenStop() {
    runCatching {
      val cfg = ConfigStore.load(this) ?: LocationConfig.defaults()
      val notification = NotificationFactory.build(this, cfg)
      ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID, notification, 0)
    }
    shutdown(markStopped = false)
  }

  private fun reconfigure(cfg: LocationConfig, restarted: Boolean) {
    config = cfg
    trackingSince = if (restarted && ConfigStore.trackingSince(this) > 0) {
      ConfigStore.trackingSince(this)
    } else {
      System.currentTimeMillis()
    }
    // Persist (so a future sticky restart / boot can reload).
    ConfigStore.save(this, cfg, trackingSince)

    val gate = MotionGate(cfg.stationaryTimeoutMs)
    motionGate = gate
    syncer = LocationSyncer(LocationBufferStore.get(this), cfg.debug)

    val locationEngine = LocationEngineFactory.create(this)
    engine = locationEngine
    lowPowerActive = false
    locationEngine.start(cfg, locationThread.looper) { onRawLocation(it) }

    // Tear down any prior activity-recognition subscription before deciding
    // whether to re-arm it, so a reconfigure that turns motion off doesn't leak
    // a stale PendingIntent that keeps firing.
    runCatching { ActivityRecognitionHelper.remove(this) }
    if (cfg.motionEnabled && ActivityRecognitionHelper.isPermissionGranted(this)) {
      ActivityRecognitionHelper.request(this)
    }

    registerProviderReceiver()
    LocationEventBus.setRunning(true, trackingSince)
    startAutoSyncLoop(cfg)

    if (cfg.debug) {
      Log.d(Constants.TAG, "Tracking started via ${locationEngine.name} engine (restarted=$restarted).")
    }
  }

  private fun onRawLocation(location: Location) {
    val cfg = config ?: return
    val gate = motionGate ?: return

    val (batteryLevel, isCharging) = BatteryHelper.snapshot(this)
    val provisional = LocationFixModel.fromLocation(
      location = location,
      isMoving = gate.isMoving,
      activity = gate.activity,
      batteryLevel = batteryLevel,
      isCharging = isCharging
    )

    // Motion gating is opt-in. When off, honor the contract (`activity: unknown`,
    // a stable `isMoving: false`) and emit no motion-change events.
    val motionFlipped = if (cfg.motionEnabled) gate.consider(provisional) else false
    val fix = if (cfg.motionEnabled) {
      provisional.copy(isMoving = gate.isMoving, activity = gate.activity)
    } else {
      provisional.copy(isMoving = false, activity = "unknown")
    }

    // Persist first — the buffer is the durable record even if JS is gone.
    if (cfg.persist) {
      serviceScope.launch {
        runCatching {
          LocationBufferStore.get(this@BackgroundLocationService)
            .insert(fix, cfg.maxRecordsToPersist)
        }
        maybeAutoSync(cfg)
      }
    }

    LocationEventBus.emitLocation(fix)
    if (motionFlipped) {
      LocationEventBus.emitMotionChange(gate.isMoving, gate.activity, fix)
      applyMotionThrottle(cfg, gate.isMoving)
    }
  }

  /** Called by [ActivityRecognitionReceiver] when a hardware activity transition lands. */
  private fun onActivityLabel(label: String) {
    val gate = motionGate ?: return
    val cfg = config ?: return
    val wasMoving = gate.isMoving
    gate.applyActivity(label)
    if (wasMoving != gate.isMoving) {
      LocationEventBus.emitMotionChange(gate.isMoving, gate.activity, LocationEventBus.lastFix)
      applyMotionThrottle(cfg, gate.isMoving)
    }
  }

  /** Swap the engine between full-rate and low-power cadence on motion changes. */
  private fun applyMotionThrottle(cfg: LocationConfig, moving: Boolean) {
    if (!cfg.motionEnabled) return
    val wantLowPower = !moving
    if (wantLowPower == lowPowerActive) return
    lowPowerActive = wantLowPower

    val effective = if (wantLowPower) {
      cfg.copy(
        accuracy = "low",
        interval = (cfg.interval * STATIONARY_INTERVAL_FACTOR).coerceAtLeast(cfg.interval),
        fastestInterval = (cfg.fastestInterval * STATIONARY_INTERVAL_FACTOR).coerceAtLeast(cfg.fastestInterval)
      )
    } else {
      cfg
    }
    runCatching { engine?.start(effective, locationThread.looper) { onRawLocation(it) } }
  }

  private fun startAutoSyncLoop(cfg: LocationConfig) {
    syncJob?.cancel()
    if (!cfg.autoSync || cfg.syncUrl.isNullOrEmpty()) return
    syncJob = serviceScope.launch {
      while (isActive) {
        delay(AUTO_SYNC_INTERVAL_MS)
        runSync(cfg)
      }
    }
  }

  private suspend fun maybeAutoSync(cfg: LocationConfig) {
    if (!cfg.autoSync || cfg.syncUrl.isNullOrEmpty()) return
    val count = runCatching { LocationBufferStore.get(this).count() }.getOrDefault(0)
    if (count >= cfg.batchSize) runSync(cfg)
  }

  private suspend fun runSync(cfg: LocationConfig) {
    val result = syncer?.flush(cfg) ?: return
    if (result.count > 0 || !result.success) LocationEventBus.emitSync(result)
  }

  private fun registerProviderReceiver() {
    if (providerReceiver != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val gps = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val net = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        LocationEventBus.emitProviderChange(gps || net, gps, net)
      }
    }
    providerReceiver = receiver
    // ContextCompat supplies the mandatory RECEIVER_NOT_EXPORTED flag on API 33+
    // and is a no-op flag on older releases.
    ContextCompat.registerReceiver(
      this,
      receiver,
      IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    val cfg = config
    if (cfg == null || cfg.stopOnTerminate) {
      shutdown(markStopped = true)
      super.onTaskRemoved(rootIntent)
      return
    }
    // Best-effort: schedule a near-immediate restart. On API 31+ a background
    // FGS start may be refused — the START_STICKY contract is the real backstop,
    // so we never depend on this alarm firing.
    scheduleRestart()
    super.onTaskRemoved(rootIntent)
  }

  private fun scheduleRestart() {
    runCatching {
      val restartIntent = Intent(this, BackgroundLocationService::class.java)
        .setAction(Constants.ACTION_RESTART)
      val pi = PendingIntent.getService(
        this,
        Constants.RESTART_ALARM_REQUEST_CODE,
        restartIntent,
        pendingIntentFlags()
      )
      val alarm = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      alarm.set(
        AlarmManager.ELAPSED_REALTIME,
        SystemClock.elapsedRealtime() + Constants.RESTART_DELAY_MS,
        pi
      )
    }
  }

  private fun shutdown(markStopped: Boolean) {
    syncJob?.cancel()
    syncJob = null
    runCatching { engine?.stop() }
    engine = null
    runCatching { ActivityRecognitionHelper.remove(this) }
    providerReceiver?.let { runCatching { unregisterReceiver(it) } }
    providerReceiver = null
    motionGate?.reset()
    if (markStopped) ConfigStore.markStopped(this)
    LocationEventBus.setRunning(false, 0L)
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onDestroy() {
    runCatching { engine?.stop() }
    syncJob?.cancel()
    providerReceiver?.let { runCatching { unregisterReceiver(it) } }
    providerReceiver = null
    runCatching { ActivityRecognitionHelper.remove(this) }
    LocationEventBus.setRunning(false, 0L)
    serviceScope.cancel()
    if (this::locationThread.isInitialized) locationThread.quitSafely()
    if (instance === this) instance = null
    super.onDestroy()
  }

  private fun pendingIntentFlags(): Int {
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
    return flags
  }

  companion object {
    const val EXTRA_CONFIG_JSON = "config_json"

    private const val AUTO_SYNC_INTERVAL_MS = 30_000L
    private const val STATIONARY_INTERVAL_FACTOR = 6L

    @Volatile
    private var instance: BackgroundLocationService? = null

    /** Bridge from [ActivityRecognitionReceiver] — a no-op when not running. */
    fun applyActivity(label: String) {
      instance?.onActivityLabel(label)
    }

    /** Whether the service process believes it is currently running. */
    fun isRunning(): Boolean = LocationEventBus.running

    fun start(context: Context, config: LocationConfig) {
      val intent = Intent(context, BackgroundLocationService::class.java)
        .setAction(Constants.ACTION_START)
        .putExtra(EXTRA_CONFIG_JSON, config.toJson())
      ContextCompat_startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, BackgroundLocationService::class.java)
        .setAction(Constants.ACTION_STOP)
      // Use startForegroundService, not startService: the latter throws
      // IllegalStateException when the app is backgrounded (API 26+), which would
      // make stop() a silent no-op — the common case for a background tracker.
      // The service is already foreground, so this is permitted; the STOP branch
      // re-promotes to honor the 5s contract, then stops.
      runCatching { ContextCompat_startForegroundService(context, intent) }
    }

    private fun ContextCompat_startForegroundService(context: Context, intent: Intent) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }
  }
}
