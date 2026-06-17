package com.margelo.nitro.persistentbackgroundlocation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Optional hardware activity-recognition layer. When motion gating is enabled
 * *and* the runtime permission is held, it subscribes to activity-transition
 * events and feeds the resulting label into the [MotionGate]. Entirely best-
 * effort — every Play Services call is wrapped so a missing dependency or a
 * revoked permission degrades to the pure-heuristic gate rather than crashing.
 */
internal object ActivityRecognitionHelper {

  fun isPermissionGranted(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      "android.permission.ACTIVITY_RECOGNITION"
    } else {
      "com.google.android.gms.permission.ACTIVITY_RECOGNITION"
    }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
  }

  fun request(context: Context): Boolean {
    if (!isPermissionGranted(context)) return false
    return runCatching {
      val transitions = TRACKED_ACTIVITIES.map { type ->
        ActivityTransition.Builder()
          .setActivityType(type)
          // The method is `setActivityTransition(Int)` in play-services-location
          // 21.x — there is NO `setActivityTransitionType`.
          .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
          .build()
      }
      val request = ActivityTransitionRequest(transitions)
      ActivityRecognition.getClient(context)
        .requestActivityTransitionUpdates(request, pendingIntent(context))
      true
    }.getOrElse {
      Log.w(Constants.TAG, "ActivityRecognition subscription failed", it)
      false
    }
  }

  fun remove(context: Context) {
    runCatching {
      ActivityRecognition.getClient(context)
        .removeActivityTransitionUpdates(pendingIntent(context))
    }
  }

  fun labelFor(activityType: Int): String = when (activityType) {
    DetectedActivity.STILL -> "still"
    DetectedActivity.WALKING -> "walking"
    DetectedActivity.RUNNING -> "running"
    DetectedActivity.ON_FOOT -> "on_foot"
    DetectedActivity.ON_BICYCLE -> "on_bicycle"
    DetectedActivity.IN_VEHICLE -> "in_vehicle"
    else -> "unknown"
  }

  private fun pendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, ActivityRecognitionReceiver::class.java)
      .setAction(ActivityRecognitionReceiver.ACTION_TRANSITION)
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // AR must be able to populate the result extras → mutable.
      flags = flags or PendingIntent.FLAG_MUTABLE
    }
    return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
  }

  private const val REQUEST_CODE = 0xB610A
  private val TRACKED_ACTIVITIES = intArrayOf(
    DetectedActivity.STILL,
    DetectedActivity.WALKING,
    DetectedActivity.RUNNING,
    DetectedActivity.ON_FOOT,
    DetectedActivity.ON_BICYCLE,
    DetectedActivity.IN_VEHICLE
  ).toList()
}
