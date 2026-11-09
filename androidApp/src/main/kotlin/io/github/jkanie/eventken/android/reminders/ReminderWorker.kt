package io.github.jkanie.eventken.android.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jkanie.eventken.android.MainActivity
import io.github.jkanie.eventken.android.R

/** Shows 1 reminder. It reads everything from its input, so it works without the network. */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_EVENT_ID, -1)
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        if (id < 0) return Result.failure()
        ensureChannel(applicationContext)
        // Without the notification permission (Android 13 and later) there is nothing to show, and nothing to retry.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val open =
            PendingIntent.getActivity(
                applicationContext,
                id.toInt(),
                Intent(applicationContext, MainActivity::class.java).putExtra(KEY_EVENT_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(inputData.getString(KEY_TEXT))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(applicationContext).notify(id.toInt(), notification)
        return Result.success()
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val KEY_EVENT_ID = "eventId"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.reminder_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.reminder_channel_description) },
                )
            }
        }
    }
}
