package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceTest {

    @Test
    fun `completion anchored counts from the day it was done`() {
        val everyThreeMonths = Recurrence(3, RecurrenceUnit.MONTH, RecurrenceAnchor.COMPLETION)

        // The Brita filter case: a month late changing it means the next one is
        // three months from today, not two months away.
        val next = everyThreeMonths.next(
            seriesAnchor = LocalDate.of(2026, 7, 1),
            completedOn = LocalDate.of(2026, 8, 30),
            today = LocalDate.of(2026, 8, 30),
        )

        assertEquals(LocalDate.of(2026, 11, 30), next)
    }

    @Test
    fun `schedule anchored ignores when it was actually done`() {
        val monthly = Recurrence(1, RecurrenceUnit.MONTH, RecurrenceAnchor.SCHEDULE)

        // Rent is due on the 1st however late it was paid.
        val next = monthly.next(
            seriesAnchor = LocalDate.of(2026, 8, 1),
            completedOn = LocalDate.of(2026, 8, 9),
            today = LocalDate.of(2026, 8, 9),
        )

        assertEquals(LocalDate.of(2026, 9, 1), next)
    }

    @Test
    fun `monthly on the 31st clamps in February without losing the 31st`() {
        val monthly = Recurrence(1, RecurrenceUnit.MONTH, RecurrenceAnchor.SCHEDULE)
        val anchor = LocalDate.of(2026, 1, 31)

        val february = monthly.next(anchor, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31))
        assertEquals("February has to clamp", LocalDate.of(2026, 2, 28), february)

        // The regression this guards: measuring the next step from the clamped
        // date would give 28 March and pin the task to the 28th forever.
        val march = monthly.next(anchor, february, february)
        assertEquals("and March has to come back to the 31st", LocalDate.of(2026, 3, 31), march)

        val april = monthly.next(anchor, march, march)
        assertEquals(LocalDate.of(2026, 4, 30), april)
    }

    @Test
    fun `a long overdue task lands on one future date rather than a backlog`() {
        val weekly = Recurrence(1, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE)

        // Six weeks ignored should not produce six occurrences to work through.
        val next = weekly.next(
            seriesAnchor = LocalDate.of(2026, 1, 1),
            completedOn = LocalDate.of(2026, 2, 15),
            today = LocalDate.of(2026, 2, 15),
        )

        assertEquals(LocalDate.of(2026, 2, 19), next)
        assertTrue(next.isAfter(LocalDate.of(2026, 2, 15)))
    }

    @Test
    fun `next is always in the future even when completed late`() {
        val daily = Recurrence(1, RecurrenceUnit.DAY, RecurrenceAnchor.COMPLETION)
        val today = LocalDate.of(2026, 8, 30)

        // A back-dated completion must not reschedule into the past, or the task
        // is overdue again the instant it is ticked.
        val next = daily.next(
            seriesAnchor = LocalDate.of(2026, 8, 1),
            completedOn = LocalDate.of(2026, 8, 1),
            today = today,
        )

        assertTrue(next.isAfter(today))
    }

    @Test
    fun `serialises and parses back`() {
        val rule = Recurrence(2, RecurrenceUnit.WEEK, RecurrenceAnchor.SCHEDULE)
        assertEquals("2:WEEK:SCHEDULE:CLAMP", rule.serialize())
        assertEquals(rule, Recurrence.parse(rule.serialize()))
    }

    @Test
    fun `parsing rejects rubbish rather than throwing`() {
        assertNull(Recurrence.parse(null))
        assertNull(Recurrence.parse(""))
        assertNull(Recurrence.parse("2:FORTNIGHT:SCHEDULE"))
        assertNull(Recurrence.parse("0:WEEK:SCHEDULE"))
        assertNull(Recurrence.parse("2:WEEK"))
    }
}
