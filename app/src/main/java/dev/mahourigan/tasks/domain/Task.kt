@file:UseSerializers(
    dev.mahourigan.tasks.data.LocalDateSerializer::class,
    dev.mahourigan.tasks.data.LocalDateTimeSerializer::class,
    dev.mahourigan.tasks.data.InstantSerializer::class,
)

package dev.mahourigan.tasks.domain

import dev.mahourigan.tasks.domain.sync.Stamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Someone has been asked to take a task on, and has not answered yet.
 *
 * Assignment is proposed, never imposed: until this is accepted the task is not
 * theirs, and it shows up under Requests rather than under Me.
 */
@Serializable
data class PendingAssignment(
    val askedByUid: String = "",
    val askedAt: Instant = Instant.EPOCH,
)

/**
 * A task, as the rest of the app thinks about it.
 *
 * Free of both Android and Firestore so the rules around it stay testable; the
 * data layer maps documents onto this.
 *
 * A due date is a *date* — nobody means "due at 14:32". A reminder is wall-clock
 * local time, so "remind me at 09:00" stays 09:00 after you fly somewhere,
 * which is why neither of them is an [Instant].
 */
@Serializable
data class Task(
    val id: String = "",
    val title: String = "",
    val notes: String = "",
    val tagIds: Set<String> = emptySet(),

    /** Members who have accepted this task. */
    val assigneeUids: Set<String> = emptySet(),

    /** Members who have been asked and not yet answered. */
    val pendingAssignees: Map<String, PendingAssignment> = emptyMap(),

    val createdAt: Instant = Instant.EPOCH,

    /** Null while the task is open. A repeating task is never left non-null. */
    val completedAt: Instant? = null,
    val completedByUid: String? = null,

    /** Kept across repeats, so "who did the bins last time" survives. */
    val lastCompletedOn: LocalDate? = null,

    val dueOn: LocalDate? = null,
    val remindAt: LocalDateTime? = null,
    val recurrence: Recurrence? = null,

    /**
     * The date a schedule-anchored series started, held separately from [dueOn]
     * and never moved.
     *
     * Recomputing each repeat from the previous due date is what makes "monthly
     * on the 31st" slide to the 28th the first time it passes February and stay
     * there. Measuring from the original date instead keeps it on the 31st.
     */
    val seriesAnchorOn: LocalDate? = null,

    val rotation: Rotation? = null,

    /**
     * Settings belonging to smart tags that have been taken off the task.
     *
     * Removing the `repeating` chip should not throw away the interval you
     * configured — undoing a mis-tap must not cost you the setup.
     */
    val stashed: Map<SmartTag, String> = emptyMap(),

    // ---- sync ------------------------------------------------------------
    //
    // Three clocks, not one, because a task changes in three unrelated ways
    // and a single clock makes them fight. Retitling the bins and ticking the
    // bins are not competing claims about the same thing, but under one
    // last-write-wins stamp exactly one of them survives — and the one that
    // loses is silently gone.
    //
    // Grouped rather than per-field for the opposite reason: the schedule
    // fields move *together*. Completing a repeating task advances the due
    // date, pins the series anchor, records who did it and passes the roster
    // on. Merging those five independently could take the new due date from
    // one phone and the old roster position from the other, which is a state
    // neither device was ever in.

    /** What the task *is*: title, notes, tags, reminder, recurrence rule. */
    val defStamp: Stamp = Stamp.UNSET,

    /** When it is due and what has been done to it, including the roster position. */
    val schedStamp: Stamp = Stamp.UNSET,

    /** Who has it and who has been asked. */
    val assignStamp: Stamp = Stamp.UNSET,
) {
    val isComplete: Boolean get() = completedAt != null

    val isRepeating: Boolean get() = recurrence != null

    /** Whoever the task currently belongs to, roster included. */
    val effectiveAssignees: Set<String>
        get() = rotation?.currentAssignee?.let { assigneeUids + it } ?: assigneeUids

    fun isOverdue(today: LocalDate): Boolean =
        !isComplete && dueOn != null && dueOn.isBefore(today)

    fun isDueBy(today: LocalDate): Boolean =
        !isComplete && dueOn != null && !dueOn.isAfter(today)
}
