package com.margelo.nitro.persistentbackgroundlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms tracking after the device reboots (or the app package is replaced).
 *
 * `BOOT_COMPLETED` is one of the few contexts from which Android still allows a
 * background start of a `location` foreground service, which is exactly why this
 * is the canonical way to resume tracking across a reboot. We only restart when
 * the user had tracking on (`wasTracking`), still holds permission, and did not
 * opt into `stopOnTerminate` / `restartOnBoot = false`.
 */
class BootBroadcastReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    if (action !in HANDLED_ACTIONS) return

    try {
      if (!ConfigStore.wasTracking(context)) return
      val config = ConfigStore.load(context) ?: return
      if (config.stopOnTerminate || !config.restartOnBoot) return
      // A location FGS started from BOOT_COMPLETED runs in the background, so on
      // API 29+ it needs ACCESS_BACKGROUND_LOCATION ("Allow all the time") — not
      // just the foreground grant — or startForeground(TYPE_LOCATION) throws.
      if (!PermissionHelper.hasForeground(context) || !PermissionHelper.hasBackground(context)) {
        Log.w(Constants.TAG, "Boot restart skipped — background location permission not granted.")
        return
      }

      BackgroundLocationService.start(context, config)
      Log.i(Constants.TAG, "Re-armed background location after $action.")
    } catch (t: Throwable) {
      // A receiver must never throw.
      Log.e(Constants.TAG, "Boot restart failed", t)
    }
  }

  companion object {
    // Note: LOCKED_BOOT_COMPLETED is intentionally absent — our config lives in
    // credential-encrypted storage that is unreadable before first unlock, so a
    // direct-boot restart could not load it anyway. We resume on the post-unlock
    // BOOT_COMPLETED instead.
    private val HANDLED_ACTIONS = setOf(
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED,
      "android.intent.action.QUICKBOOT_POWERON",
      "com.htc.intent.action.QUICKBOOT_POWERON"
    )
  }
}
