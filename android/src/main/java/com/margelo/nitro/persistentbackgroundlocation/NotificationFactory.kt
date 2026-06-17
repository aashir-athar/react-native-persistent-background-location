package com.margelo.nitro.persistentbackgroundlocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds the persistent notification that the foreground service is legally
 * required to show — and that, in practice, is what keeps the process alive
 * after the task is swiped away. Channel creation is idempotent.
 */
internal object NotificationFactory {

  fun ensureChannel(context: Context, config: LocationConfig) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(config.notificationChannelId) != null) return

    val channel = NotificationChannel(
      config.notificationChannelId,
      config.notificationChannelName,
      // LOW keeps it silent (no sound / heads-up) while remaining always-visible.
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Keeps background location tracking alive."
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
  }

  fun build(context: Context, config: LocationConfig): Notification {
    ensureChannel(context, config)

    val builder = NotificationCompat.Builder(context, config.notificationChannelId)
      .setContentTitle(config.notificationTitle)
      .setContentText(config.notificationBody)
      .setSmallIcon(resolveIcon(context, config.notificationIcon))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    config.notificationColor?.let { hex ->
      runCatching { builder.setColor(Color.parseColor(hex)) }
    }

    if (config.tapToOpenApp) {
      launchIntent(context)?.let { builder.setContentIntent(it) }
    }

    return builder.build()
  }

  private fun launchIntent(context: Context): PendingIntent? {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
      ?: return null
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(context, 0, intent, flags)
  }

  private fun resolveIcon(context: Context, iconName: String?): Int {
    if (!iconName.isNullOrBlank()) {
      val res = context.resources
      val pkg = context.packageName
      for (type in arrayOf("drawable", "mipmap")) {
        val id = res.getIdentifier(iconName, type, pkg)
        if (id != 0) return id
      }
    }
    // Fall back to the host app's launcher icon, then a guaranteed framework icon.
    val appIcon = context.applicationInfo.icon
    if (appIcon != 0) return appIcon
    return android.R.drawable.ic_menu_mylocation
  }
}
