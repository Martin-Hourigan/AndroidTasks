package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The 29th, 30th and 31st don't exist in every month, and every sensible answer
 * to that gives different dates. These pin all three.
 */
class MonthEndPolicyTest {

    private fun monthly(policy: MonthEndPolicy) =
        Recurrence(1, RecurrenceUnit.MONTH, RecurrenceAnchor.SCHEDULE, policy)

    @Test
    fun `clamping falls back in February and returns to the chosen day after`() {
        val rule = monthly(MonthEndPolicy.CLAMP)

        // Set to the 29th: February has to give way, March gets it back.
        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 29),
                LocalDate.of(2026, 4, 29),
                LocalDate.of(2026, 5, 29),
            ),
            rule.preview(LocalDate.of(2026, 1, 29), occurrences = 4),
        )
    }

    @Test
    fun `last day of month tracks the end of each month`() {
        val rule = monthly(MonthEndPolicy.LAST_DAY)

        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
            ),
            rule.preview(LocalDate.of(2026, 1, 31), occurrences = 4),
        )
    }

    @Test
    fun `strict skips the months that have no 31st`() {
        val rule = monthly(MonthEndPolicy.STRICT)

        // Seven months a year, and February never.
        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 31),
            ),
            rule.preview(LocalDate.of(2026, 1, 31), occurrences = 4),
        )
    }

    @Test
    fun `strict still lands on a future date rather than giving up`() {
        val rule = monthly(MonthEndPolicy.STRICT)

        val next = rule.next(
            seriesAnchor = LocalDate.of(2026, 1, 31),
            completedOn = LocalDate.of(2026, 1, 31),
            today = LocalDate.of(2026, 1, 31),
        )

        assertEquals("February is skipped entirely", LocalDate.of(2026, 3, 31), next)
    }

    @Test
    fun `a strict 29 February waits for the leap year`() {
        val yearly = Recurrence(1, RecurrenceUnit.YEAR, RecurrenceAnchor.SCHEDULE, MonthEndPolicy.STRICT)

        val next = yearly.next(
            seriesAnchor = LocalDate.of(2028, 2, 29),
            completedOn = LocalDate.of(2028, 2, 29),
            today = LocalDate.of(2028, 2, 29),
        )

        assertEquals(LocalDate.of(2032, 2, 29), next)
    }

    @Test
    fun `the choice is only raised when the day is actually ambiguous`() {
        assertTrue(Recurrence.needsMonthEndChoice(RecurrenceUnit.MONTH, LocalDate.of(2026, 1, 29)))
        assertTrue(Recurrence.needsMonthEndChoice(RecurrenceUnit.MONTH, LocalDate.of(2026, 1, 31)))
        assertFalse(Recurrence.needsMonthEndChoice(RecurrenceUnit.MONTH, LocalDate.of(2026, 1, 28)))

        // Only 29 February is ambiguous for a yearly rule.
        assertTrue(Recurrence.needsMonthEndChoice(RecurrenceUnit.YEAR, LocalDate.of(2028, 2, 29)))
        assertFalse(Recurrence.needsMonthEndChoice(RecurrenceUnit.YEAR, LocalDate.of(2026, 3, 31)))

        // Days and weeks never hit it.
        assertFalse(Recurrence.needsMonthEndChoice(RecurrenceUnit.DAY, LocalDate.of(2026, 1, 31)))
        assertFalse(Recurrence.needsMonthEndChoice(RecurrenceUnit.WEEK, LocalDate.of(2026, 1, 31)))
    }

    @Test
    fun `the 31st is offered end-of-month, the 29th is not`() {
        // "The 31st" nearly always means the end of the month; the 29th is more
        // often a real date someone picked.
        assertEquals(MonthEndPolicy.LAST_DAY, Recurrence.suggestedPolicyFor(LocalDate.of(2026, 1, 31)))
        assertEquals(MonthEndPolicy.CLAMP, Recurrence.suggestedPolicyFor(LocalDate.of(2026, 1, 29)))
        assertEquals(MonthEndPolicy.CLAMP, Recurrence.suggestedPolicyFor(LocalDate.of(2026, 1, 30)))
    }

    @Test
    fun `from the 31st, clamping and last-day are the same rule and only one is offered`() {
        val options = Recurrence.meaningfulPolicies(
            count = 1,
            unit = RecurrenceUnit.MONTH,
            anchor = RecurrenceAnchor.SCHEDULE,
            startingOn = LocalDate.of(2026, 1, 31),
        )

        // Clamping the 31st always lands on the last day, so offering both would
        // be two identical columns of dates to choose between.
        assertEquals(listOf(MonthEndPolicy.LAST_DAY, MonthEndPolicy.STRICT), options)
    }

    @Test
    fun `from the 29th all three genuinely differ`() {
        val options = Recurrence.meaningfulPolicies(
            count = 1,
            unit = RecurrenceUnit.MONTH,
            anchor = RecurrenceAnchor.SCHEDULE,
            startingOn = LocalDate.of(2026, 1, 29),
        )

        assertEquals(
            listOf(MonthEndPolicy.CLAMP, MonthEndPolicy.LAST_DAY, MonthEndPolicy.STRICT),
            options,
        )
    }

    @Test
    fun `from the 30th all three genuinely differ`() {
        val options = Recurrence.meaningfulPolicies(
            count = 1,
            unit = RecurrenceUnit.MONTH,
            anchor = RecurrenceAnchor.SCHEDULE,
            startingOn = LocalDate.of(2026, 1, 30),
        )

        assertEquals(3, options.size)
        assertEquals(MonthEndPolicy.CLAMP, options.first())
    }

    @Test
    fun `rules saved before month-end was a choice still read back`() {
        val old = Recurrence.parse("1:MONTH:SCHEDULE")

        assertEquals(
            "existing rows keep doing what they were doing",
            Recurrence(1, RecurrenceUnit.MONTH, RecurrenceAnchor.SCHEDULE, MonthEndPolicy.CLAMP),
            old,
        )
    }

    @Test
    fun `the policy survives a round trip`() {
        val rule = monthly(MonthEndPolicy.STRICT)

        assertEquals(rule, Recurrence.parse(rule.serialize()))
    }
}
