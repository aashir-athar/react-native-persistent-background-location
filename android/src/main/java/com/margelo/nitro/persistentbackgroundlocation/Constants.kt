package com.margelo.nitro.persistentbackgroundlocation

/**
 * Single home for every string constant that crosses a process / IPC boundary
 * (event names, intent actions, prefs keys). Keeping them here prevents the
 * silent typo class of bug where the service emits `"onLocation"` but the module
 * listens for `"on_location"`.
 *
 * Event names and the module name are part of the public JS contract — see
 * `src/types.ts`. Do not rename without updating the TypeScript side in
 * lock-step.
 */
internal object Constants {
  const val MODULE_NAME = "PersistentBackgroundLocation"
  const val TAG = "RNBgLocation"

  // ── Events (must match the JS event contract) ─────────────────────────────
  const val EVENT_LOCATION = "onLocation"
  const val EVENT_MOTION_CHANGE = "onMotionChange"
  const val EVENT_PROVIDER_CHANGE = "onProviderChange"
  const val EVENT_SYNC = "onSync"
  const val EVENT_ERROR = "onError"

  // ── Error codes ───────────────────────────────────────────────────────────
  const val ERR_PERMISSION_DENIED = "ERR_PERMISSION_DENIED"
  const val ERR_LOCATION_UNAVAILABLE = "ERR_LOCATION_UNAVAILABLE"
  const val ERR_NO_ACTIVITY = "ERR_NO_ACTIVITY"
  const val ERR_NO_CONTEXT = "ERR_NO_CONTEXT"
  const val ERR_TIMEOUT = "ERR_TIMEOUT"
  const val ERR_SERVICE = "ERR_SERVICE"

  // ── Foreground-service intent actions ─────────────────────────────────────
  const val ACTION_START = "com.margelo.nitro.persistentbackgroundlocation.action.START"
  const val ACTION_STOP = "com.margelo.nitro.persistentbackgroundlocation.action.STOP"
  const val ACTION_RESTART = "com.margelo.nitro.persistentbackgroundlocation.action.RESTART"

  // ── SharedPreferences ─────────────────────────────────────────────────────
  const val PREFS_NAME = "persistent_background_location"
  const val KEY_CONFIG_JSON = "config_json"
  const val KEY_WAS_TRACKING = "was_tracking"
  const val KEY_TRACKING_SINCE = "tracking_since"

  // ── Foreground-service notification ───────────────────────────────────────
  // Notification id must be non-zero (0 is illegal for startForeground).
  const val NOTIFICATION_ID = 0xB6109 // "BGLOC"

  // ── Restart plumbing ──────────────────────────────────────────────────────
  const val RESTART_ALARM_REQUEST_CODE = 0xB610C
  const val RESTART_DELAY_MS = 1_000L
}
