package com.margelo.nitro.persistentbackgroundlocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult

/**
 * Receives activity-transition broadcasts triggered by
 * [ActivityRecognitionHelper] and forwards the latest label to the running
 * service's [MotionGate]. Declared in the library manifest; a no-op when the
 * service is not running.
 */
internal class ActivityRecognitionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    try {
      if (!ActivityTransitionResult.hasResult(intent)) return
      val result = ActivityTransitionResult.extractResult(intent) ?: return
      // Latest transition wins — events are ordered oldest → newest.
      val event = result.transitionEvents.lastOrNull() ?: return
      val label = ActivityRecognitionHelper.labelFor(event.activityType)
      BackgroundLocationService.applyActivity(label)
    } catch (t: Throwable) {
      // Never throw from a receiver — Android kills the process if we do.
      Log.e(Constants.TAG, "ActivityRecognitionReceiver failed", t)
    }
  }

  companion object {
    const val ACTION_TRANSITION =
      "com.margelo.nitro.persistentbackgroundlocation.action.ACTIVITY_TRANSITION"
  }
}
