package org.schabi.newpipe.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.util.UUID
import org.schabi.newpipe.R
import org.schabi.newpipe.settings.SettingsActivity

object UpdateDownloadNotifications {
    const val EXTRA_WORK_ID = "org.schabi.newpipe.update.DOWNLOAD_WORK_ID"
    private const val COMPLETE_NOTIFICATION_ID = 2002

    fun progress(context: Context, workId: UUID, percent: Int): Notification {
        val message = if (percent < 0) {
            context.getString(R.string.app_update_download_progress_unknown)
        } else {
            context.getString(R.string.app_update_download_progress_message, percent)
        }
        return builder(context, workId)
            .setContentTitle(context.getString(R.string.app_update_download_progress_title))
            .setContentText(message)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .addAction(
                0,
                context.getString(R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(workId)
            )
            .build()
    }

    fun completed(context: Context, workId: UUID, version: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val notification = builder(context, workId)
            .setContentTitle(context.getString(R.string.app_update_download_complete_title))
            .setContentText(context.getString(R.string.app_update_download_complete_message, version))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(workId.toString(), COMPLETE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked after the check; keep the verified download available.
        }
    }

    @JvmStatic
    fun dismissCompleted(context: Context, workId: UUID) {
        NotificationManagerCompat.from(context).cancel(workId.toString(), COMPLETE_NOTIFICATION_ID)
    }

    private fun builder(context: Context, workId: UUID): NotificationCompat.Builder {
        val channelId = context.getString(R.string.app_update_notification_channel_id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.wizestream_update_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.wizestream_update_notification_channel_description)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val intent = Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("wizestream-update://download/$workId")
            putExtra(SettingsActivity.EXTRA_OPEN_UPDATE_SETTINGS, true)
            putExtra(EXTRA_WORK_ID, workId.toString())
        }
        val pendingIntent = PendingIntentCompat.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_wizestream_update)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
    }
}
