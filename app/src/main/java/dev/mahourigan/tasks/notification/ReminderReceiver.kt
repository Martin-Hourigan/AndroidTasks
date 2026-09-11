package dev.mahourigan.tasks.notification

import dev.mahourigan.tasks.data.Repositories
import dev.mahourigan.tasks.domain.TaskCompletion
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/**
 * Fires reminders, and handles the buttons on them.
 *
 * Doing the work here rather than by opening the app is the point: a reminder
 * you have to open an app to dismiss gets swiped away and forgotten.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(Reminders.EXTRA_TASK_ID) ?: return
        val action = intent.action ?: return

        // The receiver's own lifetime ends when onReceive returns, so the work
        // is kept alive by goAsync until the store has actually been written.
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = Repositories.tasks(app)
                if (repository.snapshot.value.household.members.isEmpty()) repository.load()

                val task = repository.snapshot.value.task(taskId) ?: return@launch

                when (action) {
                    Reminders.ACTION_FIRE -> Reminders.show(app, task)

                    Reminders.ACTION_COMPLETE -> {
                        val viewer = repository.snapshot.value.viewerUid
                        val done = TaskCompletion.complete(
                            task = task,
                            byUid = viewer,
                            today = LocalDate.now(),
                            now = Instant.now(),
                            activeMembers = repository.snapshot.value.household.memberUids,
                        )
                        // A repeating task moves rather than closing, and its
                        // reminder moves with it, so the alarm has to be re-armed
                        // rather than simply dropped.
                        repository.upsertTask(done)
                        Reminders.dismiss(app, taskId)
                        ReminderScheduler.syncAll(app, repository.snapshot.value)
                    }

                    Reminders.ACTION_SNOOZE -> {
                        Reminders.dismiss(app, taskId)
                        ReminderScheduler.snooze(app, taskId, Reminders.SNOOZE_MINUTES)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Re-arms everything after the device forgets.
 *
 * Alarms do not survive a reboot, and a reminder set as wall-clock time means
 * something different after the clock or the timezone moves — all four of these
 * are exempt from the implicit-broadcast limits, so they still arrive.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = Repositories.tasks(app)
                repository.load()
                ReminderScheduler.syncAll(app, repository.snapshot.value)
            } finally {
                pending.finish()
            }
        }
    }
}

