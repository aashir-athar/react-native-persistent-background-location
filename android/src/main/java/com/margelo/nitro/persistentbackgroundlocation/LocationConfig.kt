package com.margelo.nitro.persistentbackgroundlocation

import android.content.Context
import org.json.JSONObject

/**
 * Immutable snapshot of a tracking session's configuration.
 *
 * The Nitro struct bound from JS ([NativeStartConfig]) is converted into this
 * once, on `start`, and from then on *only* this snapshot is passed around. The
 * service and the boot receiver reload it from disk via [ConfigStore] — they
 * never touch the React bridge, so they cannot depend on the struct type.
 *
 * Field names map 1:1 onto `NativeStartConfig` in the TypeScript layer.
 */
internal data class LocationConfig(
  val accuracy: String,
  val distanceFilter: Double,
  val interval: Long,
  val fastestInterval: Long,
  val activityType: String,
  val showsBackgroundLocationIndicator: Boolean,
  val pausesUpdatesAutomatically: Boolean,
  val stopOnTerminate: Boolean,
  val restartOnBoot: Boolean,
  val useSignificantChanges: Boolean,
  val debug: Boolean,
  val notificationTitle: String,
  val notificationBody: String,
  val notificationChannelId: String,
  val notificationChannelName: String,
  val notificationColor: String?,
  val notificationIcon: String?,
  val tapToOpenApp: Boolean,
  val persist: Boolean,
  val syncUrl: String?,
  val httpMethod: String,
  val headers: Map<String, String>,
  val batchSize: Int,
  val autoSync: Boolean,
  val maxRecordsToPersist: Int,
  val motionEnabled: Boolean,
  val stationaryTimeoutMs: Long
) {

  fun toJson(): String = JSONObject().apply {
    put("accuracy", accuracy)
    put("distanceFilter", distanceFilter)
    put("interval", interval)
    put("fastestInterval", fastestInterval)
    put("activityType", activityType)
    put("showsBackgroundLocationIndicator", showsBackgroundLocationIndicator)
    put("pausesUpdatesAutomatically", pausesUpdatesAutomatically)
    put("stopOnTerminate", stopOnTerminate)
    put("restartOnBoot", restartOnBoot)
    put("useSignificantChanges", useSignificantChanges)
    put("debug", debug)
    put("notificationTitle", notificationTitle)
    put("notificationBody", notificationBody)
    put("notificationChannelId", notificationChannelId)
    put("notificationChannelName", notificationChannelName)
    put("notificationColor", notificationColor ?: JSONObject.NULL)
    put("notificationIcon", notificationIcon ?: JSONObject.NULL)
    put("tapToOpenApp", tapToOpenApp)
    put("persist", persist)
    put("syncUrl", syncUrl ?: JSONObject.NULL)
    put("httpMethod", httpMethod)
    put("headers", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
    put("batchSize", batchSize)
    put("autoSync", autoSync)
    put("maxRecordsToPersist", maxRecordsToPersist)
    put("motionEnabled", motionEnabled)
    put("stationaryTimeoutMs", stationaryTimeoutMs)
  }.toString()

  companion object {
    /** All-defaults config — used only to build a transient notification when we
     *  must honor the `startForegroundService()` contract but have no real config. */
    fun defaults(): LocationConfig = LocationConfig(
      accuracy = "high",
      distanceFilter = 10.0,
      interval = 5_000L,
      fastestInterval = 2_500L,
      activityType = "other",
      showsBackgroundLocationIndicator = true,
      pausesUpdatesAutomatically = false,
      stopOnTerminate = false,
      restartOnBoot = true,
      useSignificantChanges = true,
      debug = false,
      notificationTitle = "Location tracking active",
      notificationBody = "Your location is being tracked in the background.",
      notificationChannelId = "persistent_background_location",
      notificationChannelName = "Background location",
      notificationColor = null,
      notificationIcon = null,
      tapToOpenApp = true,
      persist = false,
      syncUrl = null,
      httpMethod = "POST",
      headers = emptyMap(),
      batchSize = 50,
      autoSync = false,
      maxRecordsToPersist = 10_000,
      motionEnabled = false,
      stationaryTimeoutMs = 60_000L
    )

    fun fromJson(raw: String): LocationConfig {
      val j = JSONObject(raw)
      val headers = mutableMapOf<String, String>()
      j.optJSONObject("headers")?.let { h ->
        for (key in h.keys()) headers[key] = h.optString(key)
      }
      return LocationConfig(
        accuracy = j.optString("accuracy", "high"),
        distanceFilter = j.optDouble("distanceFilter", 10.0),
        interval = j.optLong("interval", 5_000L),
        fastestInterval = j.optLong("fastestInterval", 2_500L),
        activityType = j.optString("activityType", "other"),
        showsBackgroundLocationIndicator = j.optBoolean("showsBackgroundLocationIndicator", true),
        pausesUpdatesAutomatically = j.optBoolean("pausesUpdatesAutomatically", false),
        stopOnTerminate = j.optBoolean("stopOnTerminate", false),
        restartOnBoot = j.optBoolean("restartOnBoot", true),
        useSignificantChanges = j.optBoolean("useSignificantChanges", true),
        debug = j.optBoolean("debug", false),
        notificationTitle = j.optString("notificationTitle", "Location tracking active"),
        notificationBody = j.optString(
          "notificationBody",
          "Your location is being tracked in the background."
        ),
        notificationChannelId = j.optString("notificationChannelId", "persistent_background_location"),
        notificationChannelName = j.optString("notificationChannelName", "Background location"),
        notificationColor = if (j.isNull("notificationColor")) null else j.optString("notificationColor"),
        notificationIcon = if (j.isNull("notificationIcon")) null else j.optString("notificationIcon"),
        tapToOpenApp = j.optBoolean("tapToOpenApp", true),
        persist = j.optBoolean("persist", false),
        syncUrl = if (j.isNull("syncUrl")) null else j.optString("syncUrl"),
        httpMethod = j.optString("httpMethod", "POST"),
        headers = headers,
        batchSize = j.optInt("batchSize", 50),
        autoSync = j.optBoolean("autoSync", false),
        maxRecordsToPersist = j.optInt("maxRecordsToPersist", 10_000),
        motionEnabled = j.optBoolean("motionEnabled", false),
        stationaryTimeoutMs = j.optLong("stationaryTimeoutMs", 60_000L)
      )
    }
  }
}

/**
 * Durable home for the active config + the "should be tracking" flag. Written
 * on `start`, read by the foreground service after a system-initiated
 * (START_STICKY) restart and by the boot receiver after a reboot.
 */
internal object ConfigStore {

  fun save(context: Context, config: LocationConfig, trackingSince: Long) {
    prefs(context).edit()
      .putString(Constants.KEY_CONFIG_JSON, config.toJson())
      .putBoolean(Constants.KEY_WAS_TRACKING, true)
      .putLong(Constants.KEY_TRACKING_SINCE, trackingSince)
      .apply()
  }

  fun load(context: Context): LocationConfig? {
    val raw = prefs(context).getString(Constants.KEY_CONFIG_JSON, null) ?: return null
    return runCatching { LocationConfig.fromJson(raw) }.getOrNull()
  }

  fun wasTracking(context: Context): Boolean =
    prefs(context).getBoolean(Constants.KEY_WAS_TRACKING, false)

  fun trackingSince(context: Context): Long =
    prefs(context).getLong(Constants.KEY_TRACKING_SINCE, 0L)

  /**
   * Clear the "should be tracking" flag **and** the persisted config blob. The
   * config can carry `buffer.headers` (e.g. an `Authorization` token), so we do
   * not leave it sitting in plaintext `SharedPreferences` after tracking stops.
   */
  fun markStopped(context: Context) {
    prefs(context).edit()
      .putBoolean(Constants.KEY_WAS_TRACKING, false)
      .remove(Constants.KEY_TRACKING_SINCE)
      .remove(Constants.KEY_CONFIG_JSON)
      .apply()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}
