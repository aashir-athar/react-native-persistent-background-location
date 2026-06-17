package com.margelo.nitro.persistentbackgroundlocation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Coded exception carrying one of the [Constants] error codes. */
internal class PermissionException(val code: String, message: String) : Exception(message)

/** Resolved permission snapshot mirroring `PermissionResult` in TypeScript. */
internal data class PermissionState(
  val status: String,
  val foreground: String,
  val background: String,
  val canAskAgain: Boolean
) {
  fun toNitro(): NativePermissionResult = NativePermissionResult(
    status = status,
    foreground = foreground,
    background = background,
    canAskAgain = canAskAgain
  )
}

/**
 * Owns the messy Android location-permission state machine:
 *
 *  - foreground = `ACCESS_FINE_LOCATION` OR `ACCESS_COARSE_LOCATION`
 *  - background = `ACCESS_BACKGROUND_LOCATION` (API 29+), implicit before that
 *  - `BLOCKED` detection via `shouldShowRequestPermissionRationale`
 *  - the **mandatory two-step escalation** on Android 11+: foreground must be
 *    granted before the OS will even consider a background request.
 *
 * Status checks use the application Context from [AppContextHolder]. The runtime
 * prompt is routed through the current resumed Activity cast to
 * [PermissionAwareActivity] (the React Native bridge surface for the `onRequest
 * PermissionsResult` callback), so no Expo permissions manager is involved.
 */
internal object PermissionHelper {

  private const val PREFS_KEY_PROMPTED = "location_prompt_shown"

  // Distinct request codes per escalation step so the listener can disambiguate.
  private const val REQUEST_CODE_FOREGROUND = 0xB6100
  private const val REQUEST_CODE_BACKGROUND = 0xB6101

  private val FOREGROUND_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION
  )

  fun hasForeground(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

  fun hasBackground(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasForeground(context)
    return ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  /** Pure read of the current state (no activity needed for the basics). */
  fun currentState(): PermissionState {
    val context = AppContextHolder.appContext
      ?: return PermissionState("undetermined", "undetermined", "undetermined", true)
    val activity = AppContextHolder.currentActivity

    val foregroundGranted = hasForeground(context)
    val backgroundGranted = hasBackground(context)
    val prompted = wasPrompted(context)

    val foreground = when {
      foregroundGranted -> "granted"
      !prompted -> "undetermined"
      activity != null && canStillAsk(activity, FOREGROUND_PERMISSIONS) -> "denied"
      activity != null -> "blocked"
      else -> "denied"
    }

    val background = when {
      backgroundGranted -> "granted"
      !foregroundGranted -> "denied"
      Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "granted"
      else -> "denied"
    }

    return PermissionState(
      status = combinedStatus(foreground, background),
      foreground = foreground,
      background = background,
      canAskAgain = foreground != "blocked"
    )
  }

  /**
   * Drive the request flow: foreground first, then (optionally) background. On
   * Android 11+ the background ask is a separate prompt that the OS routes
   * through "Allow all the time" / Settings — we surface whatever the user picks.
   */
  suspend fun request(requestBackground: Boolean): PermissionState {
    val context = AppContextHolder.appContext
      ?: throw PermissionException(Constants.ERR_NO_CONTEXT, "Android context unavailable.")
    val activity = AppContextHolder.currentActivity
      ?: throw PermissionException(Constants.ERR_NO_ACTIVITY, "An Activity is required to request permissions.")
    val permissionAware = activity as? PermissionAwareActivity
      ?: throw PermissionException(
        Constants.ERR_NO_ACTIVITY,
        "Current Activity does not implement PermissionAwareActivity; cannot prompt for permissions."
      )

    markPrompted(context)

    // Step 1 — foreground.
    awaitPermissions(permissionAware, REQUEST_CODE_FOREGROUND, FOREGROUND_PERMISSIONS)
    val foregroundGranted = hasForeground(context)

    // Step 2 — background, only when asked for, granted foreground, and API 29+.
    if (foregroundGranted && requestBackground &&
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackground(context)
    ) {
      awaitPermissions(
        permissionAware,
        REQUEST_CODE_BACKGROUND,
        arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      )
    }

    val state = currentState()
    // Recompute `canAskAgain` against the post-prompt activity rationale.
    val canAsk = canStillAsk(activity, FOREGROUND_PERMISSIONS) || state.foreground == "granted"
    return state.copy(canAskAgain = canAsk && state.foreground != "blocked")
  }

  fun openSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.fromParts("package", context.packageName, null)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }

  private fun combinedStatus(foreground: String, background: String): String = when {
    background == "granted" -> "granted"
    foreground == "granted" -> "whenInUse"
    else -> foreground // denied / undetermined / blocked
  }

  /**
   * Bridge the React Native `requestPermissions` / `onRequestPermissionsResult`
   * callback into a coroutine. Resolves a [CompletableDeferred] from the
   * [PermissionListener] (which the host Activity invokes when the OS dialog is
   * dismissed). The actual grant state is re-read from the Context afterwards, so
   * we ignore the grantResults payload here.
   *
   * `Activity.requestPermissions(...)` launches the system dialog (via
   * `startActivityForResult`) and writes the host Activity's pending-listener
   * field that `onRequestPermissionsResult` later reads on the main thread, so it
   * must be issued on the main thread — Nitro's `Promise.async` runs this coroutine
   * on `Dispatchers.Default`. We hop only the framework call onto `Dispatchers.Main`
   * and keep the suspending `await()` off it, so the main thread is never blocked.
   */
  private suspend fun awaitPermissions(
    activity: PermissionAwareActivity,
    requestCode: Int,
    permissions: Array<String>
  ) {
    val deferred = CompletableDeferred<Unit>()
    val listener = PermissionListener { code, _, _ ->
      if (code == requestCode) {
        if (!deferred.isCompleted) deferred.complete(Unit)
        true
      } else {
        false
      }
    }
    withContext(Dispatchers.Main) {
      activity.requestPermissions(permissions, requestCode, listener)
    }
    deferred.await()
  }

  private fun canStillAsk(activity: Activity, permissions: Array<String>): Boolean =
    permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

  private fun wasPrompted(context: Context): Boolean =
    context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(PREFS_KEY_PROMPTED, false)

  private fun markPrompted(context: Context) {
    context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
      .edit().putBoolean(PREFS_KEY_PROMPTED, true).apply()
  }

  /** Whether *any* of GPS/network providers are enabled. */
  fun locationServicesEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
      ?: return false
    val gps = runCatching { lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    val net = runCatching { lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
    return gps || net
  }
}
