package com.msp1974.vacompanion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.msp1974.vacompanion.MainActivity
import com.msp1974.vacompanion.R
import timber.log.Timber

/**
 * Brings the satellite back after the app is updated. Android kills the app on an update and,
 * as the home app, reopens it only when the screen next turns on, so a satellite with its screen
 * off stayed deaf until someone touched it. Android doesn't let an app open an activity from the
 * background, but a full-screen notification (as alarm clocks use) opens it at once when the screen
 * is off; when it's on, home reopens the app anyway. The notification goes away by itself.
 */
class UpdateRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Timber.i("App updated: reopening the satellite")
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Allowed where the system permits it (e.g. a device owner); otherwise the notification does it.
        runCatching { context.startActivity(open) }
        val pending = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Restart after update", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Updated - restarting")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Timber.w(it, "Restart notification not shown") }
    }

    companion object {
        private const val CHANNEL_ID = "restart_after_update"
        private const val NOTIFICATION_ID = 4711
        private const val TIMEOUT_MS = 60_000L
    }
}
