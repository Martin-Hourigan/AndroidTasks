@file:UseSerializers(
    dev.mahourigan.tasks.data.LocalDateSerializer::class,
    dev.mahourigan.tasks.data.LocalDateTimeSerializer::class,
    dev.mahourigan.tasks.data.InstantSerializer::class,
)

package dev.mahourigan.tasks.domain

import dev.mahourigan.tasks.domain.sync.Stamp
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.LocalDate

/** Which due dates a filter lets through. */
enum class DueWindow { ANY, HAS_DUE, NO_DUE, OVERDUE, DUE_TODAY, DUE_OR_OVERDUE, UPCOMING }

enum class TaskSort { DUE_DATE, CREATED, TITLE }

/** Where a task sits when the list is grouped by date. */
enum class TaskBucket { OVERDUE, TODAY, UPCOMING, NO_DATE, DONE }

/**
 * A saved view — which is to say, a list.
 *
 * The app has no separate concept of a list: "Groceries" is the tag `purchase`,
 * "weekend jobs" is `laundry` or `kitchen`. These are what the home grid renders
 * as cards, so a filter is navigation, not a feature.
 *
 * Membership questions are phrased against "the viewer" rather than a stored
 * uid, so the shipped Me and Requests cards mean the right thing for whoever is
 * holding the phone.
 */
@Serializable
data class TaskFilter(
    val id: String = "",
    val name: String = "",
    val tagIds: Set<String> = emptySet(),

    /**
     * True means every tag must be present, false means any one will do.
     *
     * `kitchen + purchase` almost always means "kitchen things to buy", while
     * `laundry + kitchen` means "chores" — so this is per-filter rather than a
     * global setting.
     */
    val matchAll: Boolean = true,

    val assignedToViewer: Boolean = false,
    val pendingForViewer: Boolean = false,
    val unassignedOnly: Boolean = false,
    val dueWindow: DueWindow = DueWindow.ANY,
    val includeCompleted: Boolean = false,
    val sort: TaskSort = TaskSort.DUE_DATE,
    val position: Int = 0,

    val stamp: Stamp = Stamp.UNSET,
) {

    fun matches(task: Task, viewerUid: String, today: LocalDate): Boolean {
        if (task.isComplete && !includeCompleted) return false

        if (tagIds.isNotEmpty()) {
            val hit = if (matchAll) {
                task.tagIds.containsAll(tagIds)
            } else {
                task.tagIds.any { it in tagIds }
            }
            if (!hit) return false
        }

        if (assignedToViewer && viewerUid !in task.effectiveAssignees) return false
        if (pendingForViewer && viewerUid !in task.pendingAssignees) return false

        // A task awaiting someone's answer is not unassigned — it is spoken for,
        // just not settled. Showing it under Unassigned would have two people
        // pick it up at once.
        if (unassignedOnly &&
            (task.effectiveAssignees.isNotEmpty() || task.pendingAssignees.isNotEmpty())
        ) {
            return false
        }

        return matchesDueWindow(task, today)
    }

    private fun matchesDueWindow(task: Task, today: LocalDate): Boolean {
        val due = task.dueOn
        return when (dueWindow) {
            DueWindow.ANY -> true
            DueWindow.HAS_DUE -> due != null
            DueWindow.NO_DUE -> due == null
            DueWindow.OVERDUE -> due != null && due.isBefore(today)
            DueWindow.DUE_TODAY -> due == today
            DueWindow.DUE_OR_OVERDUE -> due != null && !due.isAfter(today)
            DueWindow.UPCOMING -> due != null && due.isAfter(today)
        }
    }

    fun apply(tasks: List<Task>, viewerUid: String, today: LocalDate): List<Task> =
        tasks.filter { matches(it, viewerUid, today) }.sortedWith(comparator())

    private fun comparator(): Comparator<Task> = when (sort) {
        // Undated tasks sort last rather than first — a task with no date is not
        // more urgent than one due today, which is what a null-first ordering
        // would imply.
        TaskSort.DUE_DATE -> compareBy(nullsLast()) { it.dueOn }
        TaskSort.CREATED -> compareBy { it.createdAt }
        TaskSort.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
    }

    companion object {
        fun bucketOf(task: Task, today: LocalDate): TaskBucket = when {
            task.isComplete -> TaskBucket.DONE
            task.dueOn == null -> TaskBucket.NO_DATE
            task.dueOn.isBefore(today) -> TaskBucket.OVERDUE
            task.dueOn == today -> TaskBucket.TODAY
            else -> TaskBucket.UPCOMING
        }
    }
}
