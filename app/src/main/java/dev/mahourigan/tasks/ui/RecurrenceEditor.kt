package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.MonthEndPolicy
import dev.mahourigan.tasks.domain.Recurrence
import dev.mahourigan.tasks.domain.RecurrenceAnchor
import dev.mahourigan.tasks.domain.RecurrenceUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val previewFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * How often, and — when the answer is genuinely ambiguous — what "the 31st"
 * means in a month that hasn't got one.
 *
 * The month-end question is shown as dates rather than described in words. Three
 * months of actual output settles it far faster than any wording of the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceDialog(
    initial: Recurrence?,
    startingOn: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (Recurrence?) -> Unit,
) {
    var count by remember { mutableStateOf(initial?.count ?: 1) }
    var unit by remember { mutableStateOf(initial?.unit ?: RecurrenceUnit.WEEK) }
    var anchor by remember { mutableStateOf(initial?.anchor ?: RecurrenceAnchor.COMPLETION) }
    var monthEnd by remember {
        mutableStateOf(initial?.monthEnd ?: Recurrence.suggestedPolicyFor(startingOn))
    }

    val needsMonthEnd = Recurrence.needsMonthEndChoice(unit, startingOn)
    val policies = if (needsMonthEnd) {
        Recurrence.meaningfulPolicies(count, unit, anchor, startingOn)
    } else {
        emptyList()
    }

    // Switching to a date where the current choice collapses into another one
    // would leave every radio button unselected. Snapping is safe precisely
    // because the dropped option behaved identically.
    LaunchedEffect(policies) {
        if (policies.isNotEmpty() && monthEnd !in policies) monthEnd = policies.first()
    }

    val rule = Recurrence(count, unit, anchor, monthEnd)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeats") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("How often", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 6).forEach { n ->
                        FilterChip(
                            selected = count == n,
                            onClick = { count = n },
                            label = { Text(if (n == 1) "Every" else "Every $n") },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecurrenceUnit.entries.forEach { candidate ->
                        FilterChip(
                            selected = unit == candidate,
                            onClick = { unit = candidate },
                            label = { Text(unitLabel(candidate, count)) },
                        )
                    }
                }

                Text("Counted from", style = MaterialTheme.typography.labelLarge)
                AnchorOption(
                    selected = anchor == RecurrenceAnchor.COMPLETION,
                    title = "When I finish it",
                    detail = "Three months after you actually change the filter.",
                    onClick = { anchor = RecurrenceAnchor.COMPLETION },
                )
                AnchorOption(
                    selected = anchor == RecurrenceAnchor.SCHEDULE,
                    title = "The schedule",
                    detail = "Still due on the 1st however late it was done.",
                    onClick = { anchor = RecurrenceAnchor.SCHEDULE },
                )

                if (needsMonthEnd) {
                    MonthEndSection(
                        startingOn = startingOn,
                        selected = monthEnd,
                        rule = rule,
                        onSelect = { monthEnd = it },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rule) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onConfirm(null) }) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun MonthEndSection(
    startingOn: LocalDate,
    selected: MonthEndPolicy,
    rule: Recurrence,
    onSelect: (MonthEndPolicy) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Not every month has a ${ordinal(startingOn.dayOfMonth)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Pick what should happen in the short ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Only the options that actually behave differently. From the 31st,
            // "closest day" and "last day" are the same rule, and showing both
            // would be two identical columns of dates to choose between.
            Recurrence.meaningfulPolicies(rule.count, rule.unit, rule.anchor, startingOn)
                .forEach { policy ->
                    val preview = rule.copy(monthEnd = policy).preview(startingOn, occurrences = 4)
                    PolicyOption(
                        selected = selected == policy,
                        title = policyTitle(policy),
                        dates = preview.joinToString("  ·  ") { it.format(previewFormat) },
                        onClick = { onSelect(policy) },
                    )
                }
        }
    }
}

/** "31st", not "31th". The teens are the exception that catches everyone out. */
private fun ordinal(day: Int): String {
    val suffix = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}

private fun policyTitle(policy: MonthEndPolicy) = when (policy) {
    MonthEndPolicy.CLAMP -> "Closest day that month"
    MonthEndPolicy.LAST_DAY -> "Last day of the month"
    MonthEndPolicy.STRICT -> "Only that exact date"
}

@Composable
private fun PolicyOption(
    selected: Boolean,
    title: String,
    dates: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            // The dates are the actual argument for each option.
            Text(
                text = dates,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnchorOption(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun unitLabel(unit: RecurrenceUnit, count: Int): String {
    val plural = count > 1
    return when (unit) {
        RecurrenceUnit.DAY -> if (plural) "days" else "day"
        RecurrenceUnit.WEEK -> if (plural) "weeks" else "week"
        RecurrenceUnit.MONTH -> if (plural) "months" else "month"
        RecurrenceUnit.YEAR -> if (plural) "years" else "year"
    }
}
