package dev.mahourigan.tasks.domain

import java.time.Duration
import java.time.Instant

/**
 * Asking someone to take a task on.
 *
 * Assignment is proposed, not imposed: applying someone else's person tag raises
 * a request they can turn down, so nobody comes home to a list of chores they
 * never agreed to. Rotations are the exception and deliberately so — joining a
 * roster is the consent, and being asked to confirm the bins every single
 * Tuesday would be unbearable.
 */
object TaskAssignment {

    /** After this long unanswered, the UI starts nagging about it. */
    val STALE_AFTER: Duration = Duration.ofDays(3)

    /**
     * [task] with [uid] asked to take it on.
     *
     * Taking something on yourself needs nobody's permission, so it skips the
     * request and lands straight in the accepted set.
     */
    fun request(task: Task, uid: String, byUid: String, now: Instant): Task = when {
        uid in task.assigneeUids -> task
        uid == byUid -> task.copy(assigneeUids = task.assigneeUids + uid)
        else -> task.copy(
            pendingAssignees = task.pendingAssignees + (uid to PendingAssignment(byUid, now)),
        )
    }

    /**
     * [task] accepted by [uid].
     *
     * Everyone else's request is withdrawn at the same time. Asking three
     * flatmates and having two of them say yes is how a job gets done twice.
     */
    fun accept(task: Task, uid: String): Task {
        if (uid !in task.pendingAssignees) return task
        return task.copy(
            assigneeUids = task.assigneeUids + uid,
            pendingAssignees = emptyMap(),
        )
    }

    /** [task] with [uid]'s request turned down. Anyone else asked is still asked. */
    fun decline(task: Task, uid: String): Task =
        task.copy(pendingAssignees = task.pendingAssignees - uid)

    /** [task] with [uid] no longer on it, whether they had accepted or not. */
    fun withdraw(task: Task, uid: String): Task = task.copy(
        assigneeUids = task.assigneeUids - uid,
        pendingAssignees = task.pendingAssignees - uid,
    )

    /**
     * Everything belonging to someone who has left the household.
     *
     * Their tasks stay — a job nobody has picked up is still a job — but they
     * come back unassigned rather than pointing at a person who isn't there.
     */
    fun removeMember(task: Task, uid: String): Task = withdraw(task, uid)
        .copy(rotation = task.rotation?.withoutMember(uid))

    /**
     * Requests left hanging longer than [STALE_AFTER].
     *
     * Nothing expires on its own — silently dropping a request would leave a job
     * that everyone assumes someone else took. The UI surfaces these instead.
     */
    fun staleRequests(task: Task, now: Instant): Map<String, PendingAssignment> =
        task.pendingAssignees.filterValues { Duration.between(it.askedAt, now) >= STALE_AFTER }
}
