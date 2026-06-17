package com.margelo.nitro.persistentbackgroundlocation

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Zero-cost bootstrap [ContentProvider] used purely for its lifecycle: Android
 * instantiates every declared provider and calls [onCreate] **before**
 * `Application.onCreate`, on the main thread, with a valid [Context]. That makes
 * it the canonical place to capture the application Context for code (like a
 * Nitro HybridObject) that is otherwise handed no Context at all.
 *
 * It also registers [Application.ActivityLifecycleCallbacks] so we always know
 * the current resumed Activity — required to route a runtime permission prompt
 * through [com.facebook.react.modules.core.PermissionAwareActivity].
 *
 * Declared in the manifest with a unique authority
 * (`${applicationId}.persistentbackgroundlocation.init`), `exported="false"`,
 * and a high `initOrder`. It never serves data — every query path returns null.
 */
class LocationContextProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    val context = context ?: return false
    AppContextHolder.initialize(context)

    (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
          AppContextHolder.setCurrentActivity(activity)
        }

        override fun onActivityPaused(activity: Activity) {
          if (AppContextHolder.currentActivity === activity) {
            AppContextHolder.setCurrentActivity(null)
          }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
          if (AppContextHolder.currentActivity === activity) {
            AppContextHolder.setCurrentActivity(null)
          }
        }
      }
    )
    return true
  }

  // ── Inert ContentProvider surface — this provider never serves data. ───────

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0
}
