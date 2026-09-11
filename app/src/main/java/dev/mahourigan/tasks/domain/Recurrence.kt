@file:UseSerializers(
    dev.mahourigan.tasks.data.LocalDateSerializer::class,
    dev.mahourigan.tasks.data.LocalDateTimeSerializer::class,
    dev.mahourigan.tasks.data.InstantSerializer::class,
)

package dev.mahourigan.tasks.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

import java.time.LocalDate
import java.time.Month

enum class RecurrenceUnit { DAY, WEEK, MONTH, YEAR }

enum class RecurrenceAnchor {
    /** Counts from the day it was actually done — "3 months after I changed the filter". */
    COMPLETION,

    /** Counts from when it was scheduled, however late it was done — rent is due on the 1st. */
    SCHEDULE,
}

/**
 * What "the 31st" means in a month that hasn't got one.
 *
 * Every option here is defensible and they give different answers, so this is
 * asked rather than assumed — see [Recurrence.needsMonthEndChoice].
 */
enum class MonthEndPolicy {
    /**
     * The nearest day that month, returning to the chosen day whenever it
     * exists: the 29th falls back to the 28th in February and is the 29th
     * everywhere else.
     */
    CLAMP,

    /**
     * Always the final day of the month — 28 February, 31 March, 30 April.
     * What people usually mean by "the 31st".
     */
    LAST_DAY,

    /**
     * Only months that really have the day. A strict 31st happens seven times a
     * year and skips the rest.
     */
    STRICT,
}

/**
 * How often a task comes back.
 *
 * Deliberately free of Android so the arithmetic can be tested directly. Three
 * things go wrong here and none is visible from the UI until a chore turns up on
 * the wrong day: month-end drift, overdue tasks piling up, and short months.
 */
@Serializable
data class Recurrence(
    val count: Int,
    val unit: RecurrenceUnit,
    val anchor: RecurrenceAnchor = RecurrenceAnchor.COMPLETION,
    val monthEnd: MonthEndPolicy = MonthEndPolicy.CLAMP,
) {
    init {
        require(count >= 1) { "A recurrence has to advance by at least one period" }
    }

    fun serialize(): String =
        listOf(count.toString(), unit.name, anchor.name, monthEnd.name).joinToString(":")

    /**
     * The next date this task is due, given it was just completed.
     *
     * [seriesAnchor] is the date the series *started*, which only
     * schedule-anchored recurrences care about. It has to be the original date
     * rather than the last due date: measuring each step from the previous
     * result lets a monthly task on the 31st clamp to the 28th in February and
     * then stay on the 28th for good.
     *
     * Returns a date strictly after [today] in every case — a task that
     * reschedules into the past would immediately be overdue again.
     */
    fun next(seriesAnchor: LocalDate?, completedOn: LocalDate, today: LocalDate): LocalDate {
        // Completion-anchored counts from the later of the two, so a back-dated
        // tick can't reschedule into the past.
        val origin = when (anchor) {
            RecurrenceAnchor.COMPLETION -> maxOf(completedOn, today)
            RecurrenceAnchor.SCHEDULE -> seriesAnchor ?: maxOf(completedOn, today)
        }

        // Walking forward in whole periods lands on one future date however long
        // the task has been ignored, rather than leaving a backlog behind it —
        // and skips the months a STRICT rule doesn't occur in.
        var periods = 1
        while (periods <= MAX_CATCH_UP_PERIODS) {
            val candidate = origin.plusPeriods(periods)
            if (candidate != null && candidate.isAfter(today)) return candidate
            periods++
        }
        // Unreachable for any real rule; a plain clamped step beats throwing.
        return origin.plusPeriods(1) ?: today.plusDays(1)
    }

    /**
     * The next [occurrences] dates from [startingOn], for showing someone what a
     * rule will actually do.
     *
     * Three months of dates settles the 29th/30th/31st question far faster than
     * any wording of the choice can.
     */
    fun preview(startingOn: LocalDate, occurrences: Int = 5): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var periods = 1
        while (dates.size < occurrences && periods <= MAX_CATCH_UP_PERIODS) {
            startingOn.plusPeriods(periods)?.let(dates::add)
            periods++
        }
        return dates
    }

    /**
     * [origin] advanced by [periods] whole repeats, or null when this rule
     * simply doesn't occur that period.
     *
     * Multiplying out rather than adding repeatedly is what keeps the day of the
     * month pinned to the original.
     */
    private fun LocalDate.plusPeriods(periods: Int): LocalDate? {
        val amount = count.toLong() * periods
        return when (unit) {
            RecurrenceUnit.DAY -> plusDays(amount)
            RecurrenceUnit.WEEK -> plusWeeks(amount)
            RecurrenceUnit.MONTH -> plusMonths(amount).applyMonthEnd(dayOfMonth)
            RecurrenceUnit.YEAR -> plusYears(amount).applyMonthEnd(dayOfMonth)
        }
    }

    /**
     * [wantedDay] is the day of the month the series was set to. `plusMonths`
     * has already clamped by the time this runs, so a mismatch is exactly how a
     * short month is detected.
     */
    private fun LocalDate.applyMonthEnd(wantedDay: Int): LocalDate? = when (monthEnd) {
        MonthEndPolicy.CLAMP -> this
        MonthEndPolicy.LAST_DAY -> withDayOfMonth(lengthOfMonth())
        MonthEndPolicy.STRICT -> takeIf { it.dayOfMonth == wantedDay }
    }

    companion object {
        /**
         * A task ignored for years should not spin the loop forever. Well past
         * any real backlog — and past the 4-year gap a strict 29 February needs.
         */
        private const val MAX_CATCH_UP_PERIODS = 10_000

        /**
         * Whether starting a rule on [startingOn] hits days that don't exist in
         * every month, and so needs the choice put to the user.
         *
         * Monthly on the 29th, 30th or 31st does. So does a yearly 29 February,
         * which only comes round properly every fourth year.
         */
        fun needsMonthEndChoice(unit: RecurrenceUnit, startingOn: LocalDate): Boolean =
            when (unit) {
                RecurrenceUnit.MONTH -> startingOn.dayOfMonth >= 29
                RecurrenceUnit.YEAR ->
                    startingOn.month == Month.FEBRUARY && startingOn.dayOfMonth == 29
                else -> false
            }

        /**
         * The option to pre-select when asking.
         *
         * Someone picking the 31st almost always means "end of the month"; the
         * 29th and 30th are more often a genuine date, so those stay on [CLAMP].
         */
        fun suggestedPolicyFor(startingOn: LocalDate): MonthEndPolicy =
            if (startingOn.dayOfMonth == 31) MonthEndPolicy.LAST_DAY else MonthEndPolicy.CLAMP

        /**
         * The options actually worth offering for a rule starting on [startingOn],
         * best first.
         *
         * Some of them collapse into each other: from the 31st, "closest day that
         * month" and "last day of the month" are the same rule, because clamping
         * the 31st always lands on the last day. Offering both would be asking
         * someone to choose between two identical columns of dates.
         */
        fun meaningfulPolicies(
            count: Int,
            unit: RecurrenceUnit,
            anchor: RecurrenceAnchor,
            startingOn: LocalDate,
        ): List<MonthEndPolicy> {
            val preferred = suggestedPolicyFor(startingOn)
            val ordered = listOf(preferred) + (MonthEndPolicy.entries - preferred)

            val seen = mutableSetOf<List<LocalDate>>()
            return ordered.filter { policy ->
                seen.add(Recurrence(count, unit, anchor, policy).preview(startingOn, POLICY_SAMPLE))
            }
        }

        /**
         * Long enough to separate policies that only diverge at a February. A
         * shorter sample makes "closest day" and "exact date only" look alike.
         */
        private const val POLICY_SAMPLE = 14

        fun parse(raw: String?): Recurrence? {
            val parts = raw?.split(":") ?: return null
            // Three parts is the original format, from before month-end handling
            // was a choice. Those rows read back as CLAMP, which is what they
            // were doing at the time.
            if (parts.size !in 3..4) return null
            val count = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val unit = RecurrenceUnit.entries.firstOrNull { it.name == parts[1] } ?: return null
            val anchor = RecurrenceAnchor.entries.firstOrNull { it.name == parts[2] } ?: return null
            val monthEnd = if (parts.size == 4) {
                MonthEndPolicy.entries.firstOrNull { it.name == parts[3] } ?: return null
            } else {
                MonthEndPolicy.CLAMP
            }
            return Recurrence(count, unit, anchor, monthEnd)
        }
    }
}
