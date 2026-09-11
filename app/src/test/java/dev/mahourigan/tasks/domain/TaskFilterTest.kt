package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskFilterTest {

    private val today = LocalDate.of(2026, 8, 30)
    private val me = "p1"

    private fun task(
        id: String = "t",
        tags: Set<String> = emptySet(),
        assignees: Set<String> = emptySet(),
        pending: Set<String> = emptySet(),
        due: LocalDate? = null,
        complete: Boolean = false,
        rotation: Rotation? = null,
    ) = Task(
        id = id,
        title = id,
        tagIds = tags,
        assigneeUids = assignees,
        pendingAssignees = pending.associateWith { PendingAssignment("p2", Instant.EPOCH) },
        dueOn = due,
        completedAt = if (complete) Instant.EPOCH else null,
        rotation = rotation,
    )

    @Test
    fun `matching all tags means kitchen things to buy`() {
        val filter = TaskFilter(tagIds = setOf("kitchen", "purchase"), matchAll = true)

        assertTrue(filter.matches(task(tags = setOf("kitchen", "purchase")), me, today))
        assertFalse(filter.matches(task(tags = setOf("kitchen")), me, today))
    }

    @Test
    fun `matching any tag means chores`() {
        val filter = TaskFilter(tagIds = setOf("laundry", "kitchen"), matchAll = false)

        assertTrue(filter.matches(task(tags = setOf("kitchen")), me, today))
        assertTrue(filter.matches(task(tags = setOf("laundry")), me, today))
        assertFalse(filter.matches(task(tags = setOf("garden")), me, today))
    }

    @Test
    fun `completed tasks are hidden unless asked for`() {
        val done = task(complete = true)

        assertFalse(TaskFilter().matches(done, me, today))
        assertTrue(TaskFilter(includeCompleted = true).matches(done, me, today))
    }

    @Test
    fun `the Me card counts whoever's turn it is`() {
        val filter = TaskFilter(assignedToViewer = true)

        assertTrue(filter.matches(task(assignees = setOf(me)), me, today))
        assertTrue(
            "a roster turn is just as much mine as an assignment",
            filter.matches(task(rotation = Rotation(listOf("p2", me), index = 1)), me, today),
        )
        assertFalse(filter.matches(task(rotation = Rotation(listOf("p2", me), index = 0)), me, today))
    }

    @Test
    fun `the Requests card shows what I have been asked to take, not what is mine`() {
        val filter = TaskFilter(pendingForViewer = true)

        assertTrue(filter.matches(task(pending = setOf(me)), me, today))
        assertFalse(
            "an accepted task belongs under Me instead",
            filter.matches(task(assignees = setOf(me)), me, today),
        )
    }

    @Test
    fun `a task awaiting an answer is not unassigned`() {
        val filter = TaskFilter(unassignedOnly = true)

        // Otherwise two people pick up the same job — one from Unassigned, one
        // because they were asked.
        assertFalse(filter.matches(task(pending = setOf("p2")), me, today))
        assertTrue(filter.matches(task(), me, today))
    }

    @Test
    fun `the Today card takes overdue as well as due`() {
        val filter = TaskFilter(dueWindow = DueWindow.DUE_OR_OVERDUE)

        assertTrue(filter.matches(task(due = today.minusWeeks(3)), me, today))
        assertTrue(filter.matches(task(due = today), me, today))
        assertFalse(filter.matches(task(due = today.plusDays(1)), me, today))
        assertFalse(filter.matches(task(due = null), me, today))
    }

    @Test
    fun `undated tasks sort last, not first`() {
        val tasks = listOf(
            task(id = "no date"),
            task(id = "tomorrow", due = today.plusDays(1)),
            task(id = "overdue", due = today.minusDays(1)),
        )

        val sorted = TaskFilter(sort = TaskSort.DUE_DATE).apply(tasks, me, today)

        // A task with no date is not more urgent than one due today.
        assertEquals(listOf("overdue", "tomorrow", "no date"), sorted.map { it.id })
    }

    @Test
    fun `bucketing splits overdue from today`() {
        assertEquals(TaskBucket.OVERDUE, TaskFilter.bucketOf(task(due = today.minusDays(1)), today))
        assertEquals(TaskBucket.TODAY, TaskFilter.bucketOf(task(due = today), today))
        assertEquals(TaskBucket.UPCOMING, TaskFilter.bucketOf(task(due = today.plusDays(1)), today))
        assertEquals(TaskBucket.NO_DATE, TaskFilter.bucketOf(task(), today))
        assertEquals(TaskBucket.DONE, TaskFilter.bucketOf(task(complete = true), today))
    }
}
