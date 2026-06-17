package com.margelo.nitro.persistentbackgroundlocation

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Process-wide holder for the application [Context] and the current resumed
 * [Activity].
 *
 * Nitro HybridObjects are plain Kotlin classes constructed by the C++ layer —
 * they receive **no** Context, no Activity, and no React `ReactApplicationContext`
 * the way an old-architecture TurboModule would. So instead we capture the
 * Context once, the earliest possible moment in the process lifecycle, from a
 * [LocationContextProvider] `ContentProvider` (whose `onCreate` runs before
 * `Application.onCreate`). Every Context use across the package goes through
 * [appContext].
 *
 * The current [Activity] is tracked via `registerActivityLifecycleCallbacks`
 * (held weakly so a finished Activity can be GC'd) so [PermissionHelper] can
 * route a runtime permission prompt through the foreground Activity.
 */
internal object AppContextHolder {

  @Volatile
  var appContext: Context? = null
    private set

  @Volatile
  private var activityRef: WeakReference<Activity>? = null

  val currentActivity: Activity?
    get() = activityRef?.get()

  /** Called once from [LocationContextProvider.onCreate]. Idempotent. */
  fun initialize(context: Context) {
    if (appContext == null) {
      appContext = context.applicationContext
    }
  }

  /** Stores the foreground Activity (weakly). */
  fun setCurrentActivity(activity: Activity?) {
    activityRef = if (activity == null) null else WeakReference(activity)
  }

  /** Convenience accessor that throws a coded error when the Context is missing. */
  fun requireContext(): Context =
    appContext ?: throw IllegalStateException(
      "${Constants.ERR_NO_CONTEXT}: Android application context is unavailable."
    )
}
