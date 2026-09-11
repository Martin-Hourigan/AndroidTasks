package dev.mahourigan.tasks.domain

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What ticking a task off actually does.
 *
 * For a one-off task it closes it. For a repeating one it does *not* — the task
 * stays open and moves to its next date, so the bins are always one row in the
 * list rather than a growing stack of identical ones. A backlog of eleven
 * identical bin reminders is how people give up on an app like this.
 */
object TaskCompletion {

    /**
     * [task] after [byUid] completes it on [today].
     *
     * [activeMembers] is the current household, so a roster can skip anyone who
     * has since left.
     */
    fun complete(
        task: Task,
        byUid: String,
        today: LocalDate,
        now: Instant,
        activeMembers: Set<String> = emptySet(),
    ): Task {
        // Completing twice has to be a no-op, not a second step forward. Two
        // flatmates ticking the bins while both offline is not hypothetical, and
        // without this the task would jump two weeks and skip a turn.
        if (task.isComplete) return task
        if (task.recurrence != null && task.lastCompletedOn == today) return task

        val recurrence = task.recurrence
            ?: return task.copy(
                completedAt = now,
                completedByUid = byUid,
                lastCompletedOn = today,
            )

        val nextDue = recurrence.next(
            seriesAnchor = task.seriesAnchorOn ?: task.dueOn,
            completedOn = today,
            today = today,
        )

        return task.copy(
            // Pinned on first completion if it was never set, then left alone,
            // so the series keeps measuring from where it began.
            seriesAnchorOn = task.seriesAnchorOn ?: task.dueOn,
            // Stays open. The row ticks, animates out, and comes back on its
            // new date rather than closing.
            completedAt = null,
            completedByUid = byUid,
            lastCompletedOn = today,
            dueOn = nextDue,
            remindAt = nextReminder(task, nextDue),
            // The turn advances whoever ticked it — see Rotation.advance.
            rotation = task.rotation?.advance(activeMembers.ifEmpty { task.rotation.order.toSet() }),
        )
    }

    /**
     * Reopening a completed one-off.
     *
     * This is not enough to undo a *repeating* task. Completing one of those
     * moves its due date and advances the roster rather than closing it, so
     * there is no flag to clear — putting it back means restoring the whole task
     * as it was. [canReopen] tells the two apart; callers hold the pre-completion
     * copy for the undo, which is what the snackbar writes back.
     */
    fun reopen(task: Task): Task =
        if (canReopen(task)) task.copy(completedAt = null, completedByUid = null) else task

    fun canReopen(task: Task): Boolean = task.isComplete && !task.isRepeating

    /**
     * The reminder moved along with the due date, keeping its time of day and
     * its lead time.
     *
     * "Due Friday, remind me Wednesday morning" has to stay two days of warning
     * next time round, not collapse onto the due date.
     */
    private fun nextReminder(task: Task, nextDue: LocalDate): java.time.LocalDateTime? {
        val remindAt = task.remindAt ?: return null
        val previousDue = task.dueOn
            ?: return remindAt.plusDays(
                ChronoUnit.DAYS.between(remindAt.toLocalDate(), nextDue),
            )
        val shift = ChronoUnit.DAYS.between(previousDue, nextDue)
        return remindAt.plusDays(shift)
    }
}
