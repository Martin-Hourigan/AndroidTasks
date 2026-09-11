package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class TaskCompletionTest {

    private val today = LocalDate.of(2026, 8, 30)
    private val now: Instant = Instant.parse("2026-08-30T10:15:00Z")
    private val household = setOf("p1", "p2", "p3")

    @Test
    fun `a one-off task closes`() {
        val task = Task(id = "t1", title = "Cancel the gym")

        val done = TaskCompletion.complete(task, byUid = "p1", today = today, now = now)

        assertTrue(done.isComplete)
        assertEquals("p1", done.completedByUid)
        assertEquals(today, done.lastCompletedOn)
    }

    @Test
    fun `a repeating task stays open and moves to its next date`() {
        val task = Task(
            id = "t2",
            title = "Take the bins out",
            dueOn = today,
            recurrence = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE),
        )

        val after = TaskCompletion.complete(task, byUid = "p1", today = today, now = now)

        // One row that comes back, never a stack of identical overdue bins.
        assertFalse(after.isComplete)
        assertNull(after.completedAt)
        assertEquals(LocalDate.of(2026, 9, 6), after.dueOn)
        assertEquals(today, after.lastCompletedOn)
    }

    @Test
    fun `the series anchor is pinned once and then left alone`() {
        val task = Task(
            dueOn = LocalDate.of(2026, 1, 31),
            recurrence = Recurrence(1, RecurrenceUnit.MONTH, RecurrenceAnchor.SCHEDULE),
        )

        val february = TaskCompletion.complete(
            task,
            byUid = "p1",
            today = LocalDate.of(2026, 1, 31),
            now = now,
        )
        assertEquals(LocalDate.of(2026, 1, 31), february.seriesAnchorOn)
        assertEquals(LocalDate.of(2026, 2, 28), february.dueOn)

        val march = TaskCompletion.complete(
            february,
            byUid = "p1",
            today = LocalDate.of(2026, 2, 28),
            now = now,
        )
        assertEquals("anchor must not follow the due date", LocalDate.of(2026, 1, 31), march.seriesAnchorOn)
        assertEquals(LocalDate.of(2026, 3, 31), march.dueOn)
    }

    @Test
    fun `the reminder keeps its lead time and time of day`() {
        val task = Task(
            title = "Put the recycling out",
            dueOn = LocalDate.of(2026, 8, 30),
            // Two days of warning, at nine in the morning.
            remindAt = LocalDateTime.of(2026, 8, 28, 9, 0),
            recurrence = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE),
        )

        val after = TaskCompletion.complete(task, byUid = "p1", today = today, now = now)

        assertEquals(LocalDate.of(2026, 9, 6), after.dueOn)
        assertEquals(LocalDateTime.of(2026, 9, 4, 9, 0), after.remindAt)
    }

    @Test
    fun `completing on someone else's behalf still advances the turn`() {
        val task = Task(
            title = "Take the bins out",
            dueOn = today,
            recurrence = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE),
            rotation = Rotation(listOf("p1", "p2", "p3"), index = 2),
        )

        // Someone covers another person's turn. That person should not be
        // handed a second one for it.
        val after = TaskCompletion.complete(
            task,
            byUid = "p2",
            today = today,
            now = now,
            activeMembers = household,
        )

        assertEquals("p1", after.rotation?.currentAssignee)
        assertEquals("p2", after.completedByUid)
    }

    @Test
    fun `reopening a one-off clears the completion`() {
        val done = TaskCompletion.complete(Task(title = "Cancel the gym"), "p1", today, now)

        val reopened = TaskCompletion.reopen(done)

        assertFalse(reopened.isComplete)
        assertNull(reopened.completedByUid)
        assertEquals("history survives the reopen", today, reopened.lastCompletedOn)
    }

    @Test
    fun `completing a repeat twice in a day does not skip a turn`() {
        val task = Task(
            title = "Take the bins out",
            dueOn = today,
            recurrence = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE),
            rotation = Rotation(listOf("p1", "p2", "p3"), index = 0),
        )

        // Both offline, both tick it, both sync. Without the guard the bins jump
        // a fortnight and Person Two's turn is quietly skipped.
        val once = TaskCompletion.complete(task, "p1", today, now, household)
        val twice = TaskCompletion.complete(once, "p2", today, now, household)

        assertEquals(once, twice)
        assertEquals(LocalDate.of(2026, 9, 6), twice.dueOn)
        assertEquals("p2", twice.rotation?.currentAssignee)
    }

    @Test
    fun `completing an already closed one-off changes nothing`() {
        val done = TaskCompletion.complete(Task(title = "Cancel the gym"), "p1", today, now)

        assertEquals(done, TaskCompletion.complete(done, "p2", today.plusDays(1), now))
    }

    @Test
    fun `a repeating task cannot be undone by reopening it`() {
        val task = Task(
            title = "Take the bins out",
            dueOn = today,
            recurrence = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE),
        )

        val after = TaskCompletion.complete(task, "p1", today, now)

        // It never closed, so there is no flag to clear. Undo has to restore the
        // whole task, which is what the snackbar keeps a copy for.
        assertFalse(TaskCompletion.canReopen(after))
        assertEquals(after, TaskCompletion.reopen(after))
    }
}
