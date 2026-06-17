package com.margelo.nitro.persistentbackgroundlocation

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Cheap, allocation-light battery snapshot stamped onto every fix. */
internal object BatteryHelper {

  /** @return `level` in `[0,1]` (or `null`) paired with `isCharging` (or `null`). */
  fun snapshot(context: Context): Pair<Double?, Boolean?> {
    val intent = runCatching {
      context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return null to null

    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val batteryLevel = if (level >= 0 && scale > 0) level.toDouble() / scale.toDouble() else null

    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = if (status >= 0) {
      status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } else null

    return batteryLevel to charging
  }
}
