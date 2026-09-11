package dev.mahourigan.tasks.notification

import dev.mahourigan.tasks.R
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Reminders {

    const val CHANNEL_ID = "task_reminders"

    const val ACTION_FIRE = "dev.mahourigan.tasks.REMINDER"
    const val ACTION_COMPLETE = "dev.mahourigan.tasks.COMPLETE"
    const val ACTION_SNOOZE = "dev.mahourigan.tasks.SNOOZE"
    const val EXTRA_TASK_ID = "task_id"

    /** Long enough to be out of the way, short enough that you don't forget. */
    const val SNOOZE_MINUTES = 10L

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Task reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders for tasks you've set a time on"
        }
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * A task id is text and a request code is an int, so the code is derived
     * from the hash. Deriving it the same way everywhere is what lets an alarm
     * be cancelled later without keeping a separate table of codes.
     */
    fun requestCode(taskId: String, salt: Int = 0): Int = taskId.hashCode() * 31 + salt

    fun notificationId(taskId: String): Int = requestCode(taskId)

    fun show(context: Context, task: Task) {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            requestCode(task.id, salt = 1),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_task)
            .setContentTitle(task.title)
            .setContentText(task.notes.takeIf { it.isNotBlank() } ?: "Reminder")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Both actions exist so the notification is the whole interaction —
            // being made to open the app to tick something off is why reminders
            // get swiped away and forgotten instead.
            .addAction(
                0,
                "Done",
                actionIntent(context, ACTION_COMPLETE, task.id, salt = 2),
            )
            .addAction(
                0,
                "Snooze ${SNOOZE_MINUTES}m",
                actionIntent(context, ACTION_SNOOZE, task.id, salt = 3),
            )
            .build()

        // Permission is requested when the first reminder is set, but it can be
        // revoked from Settings afterwards; posting anyway would throw.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching {
                NotificationManagerCompat.from(context).notify(notificationId(task.id), notification)
            }
        }
    }

    fun dismiss(context: Context, taskId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun actionIntent(context: Context, action: String, taskId: String, salt: Int) =
        PendingIntent.getBroadcast(
            context,
            requestCode(taskId, salt),
            Intent(context, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
