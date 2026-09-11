package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SmartTagsTest {

    private val everyThreeMonths = Recurrence(3, RecurrenceUnit.MONTH, RecurrenceAnchor.COMPLETION)

    @Test
    fun `active smart tags follow the fields that are set`() {
        val task = Task(
            title = "Replace the Brita filters",
            dueOn = LocalDate.of(2026, 9, 1),
            recurrence = everyThreeMonths,
        )

        assertEquals(setOf(SmartTag.DUE, SmartTag.REPEATING), SmartTags.activeOn(task))
    }

    @Test
    fun `an empty roster does not count as rotating`() {
        val task = Task(rotation = Rotation())

        assertTrue(SmartTags.activeOn(task).isEmpty())
    }

    @Test
    fun `removing repeating parks the interval instead of losing it`() {
        val task = Task(title = "Replace the Brita filters", recurrence = everyThreeMonths)

        val without = SmartTags.remove(task, SmartTag.REPEATING)

        assertNull(without.recurrence)
        assertEquals("3:MONTH:COMPLETION:CLAMP", without.stashed[SmartTag.REPEATING])
    }

    @Test
    fun `re-adding a smart tag restores what it was set to`() {
        val task = Task(
            dueOn = LocalDate.of(2026, 9, 1),
            remindAt = LocalDateTime.of(2026, 8, 30, 9, 0),
            recurrence = everyThreeMonths,
            rotation = Rotation(listOf("p1", "p2"), index = 1),
        )

        // A mis-tap that strips every smart tag and puts them all back must land
        // exactly where it started.
        val stripped = SmartTag.entries.fold(task) { acc, tag -> SmartTags.remove(acc, tag) }
        val restored = SmartTag.entries.fold(stripped) { acc, tag -> SmartTags.restore(acc, tag) }

        assertEquals(task.dueOn, restored.dueOn)
        assertEquals(task.remindAt, restored.remindAt)
        assertEquals(task.recurrence, restored.recurrence)
        assertEquals(task.rotation, restored.rotation)
        assertTrue("the stash empties as it is consumed", restored.stashed.isEmpty())
    }

    @Test
    fun `restoring a tag that was never configured leaves the task alone`() {
        val task = Task(title = "Buy milk")

        assertEquals(task, SmartTags.restore(task, SmartTag.DUE))
    }
}
