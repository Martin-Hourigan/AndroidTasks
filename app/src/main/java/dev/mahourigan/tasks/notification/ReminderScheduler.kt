package dev.mahourigan.tasks.notification

import dev.mahourigan.tasks.data.HouseholdSnapshot
import dev.mahourigan.tasks.domain.Task
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Puts reminders on the clock.
 *
 * Reminders are stored as wall-clock local time — "09:00" means nine in the
 * morning wherever you are — so the conversion to an actual instant happens
 * here, at scheduling time, and everything is re-armed when the timezone or the
 * clock changes.
 */
object ReminderScheduler {

    private const val PREFS = "reminder_alarms"
    private const val KEY_SCHEDULED = "scheduled_ids"

    /**
     * Re-arms every reminder in [snapshot], clearing any that no longer apply.
     *
     * The ids that were armed last time are kept in preferences: a task that has
     * since been deleted is not in the snapshot at all, so without that record
     * its alarm would fire for something that no longer exists.
     */
    fun syncAll(context: Context, snapshot: HouseholdSnapshot) {
        val alarms = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previously = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty()

        val now = LocalDateTime.now()
        val wanted = snapshot.tasks.filter { it.shouldRemind(snapshot.viewerUid, now) }

        (previously - wanted.map { it.id }.toSet()).forEach { staleId ->
            cancel(context, alarms, staleId)
        }
        wanted.forEach { task -> arm(context, alarms, task.id, task.remindAt!!) }

        prefs.edit { putStringSet(KEY_SCHEDULED, wanted.map { it.id }.toSet()) }
    }

    /** Snooze puts one task back on the clock without touching the rest. */
    fun snooze(context: Context, taskId: String, minutes: Long) {
        val alarms = ContextCompat.getSystemService(context, AlarmManager::class.java) ?: return
        arm(context, alarms, taskId, LocalDateTime.now().plusMinutes(minutes))
    }

    /**
     * Whether the system will honour an exact alarm.
     *
     * Users can revoke this in Settings at any time, so it is checked at every
     * scheduling rather than only when the permission is first granted.
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarms = ContextCompat.getSystemService(context, AlarmManager::class.java)
        return alarms?.canScheduleExactAlarms() == true
    }

    private fun arm(
        context: Context,
        alarms: AlarmManager,
        taskId: String,
        at: LocalDateTime,
    ) {
        val triggerAt = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = pendingIntent(context, taskId)

        // Exact where allowed, because a reminder to leave at 08:15 is worthless
        // fifteen minutes late. Where it isn't, an inexact alarm still arrives
        // eventually, which beats no reminder at all — the UI offers to send the
        // user to Settings to grant it.
        if (canScheduleExact(context)) {
            runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }.onFailure {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
            }
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
    }

    private fun cancel(context: Context, alarms: AlarmManager, taskId: String) {
        alarms.cancel(pendingIntent(context, taskId))
        Reminders.dismiss(context, taskId)
    }

    private fun pendingIntent(context: Context, taskId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            Reminders.requestCode(taskId),
            Intent(context, ReminderReceiver::class.java)
                .setAction(Reminders.ACTION_FIRE)
                .putExtra(Reminders.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/**
 * Whether this task should put a reminder on *this* phone.
 *
 * A reminder belongs to whoever has to act on it: the person whose turn it is,
 * or everyone when nobody has picked it up. Buzzing all five phones for a job
 * one person has already taken is how a household turns notifications off.
 */
fun Task.shouldRemind(viewerUid: String, now: LocalDateTime): Boolean {
    val at = remindAt ?: return false
    if (isComplete) return false
    if (!at.isAfter(now)) return false
    val owners = effectiveAssignees
    return owners.isEmpty() || viewerUid in owners
}
