package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.MonthEndPolicy
import dev.mahourigan.tasks.domain.Recurrence
import dev.mahourigan.tasks.domain.RecurrenceUnit
import dev.mahourigan.tasks.domain.Rotation
import dev.mahourigan.tasks.domain.SmartTag
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.TagKind
import dev.mahourigan.tasks.domain.Task
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun Long.toComposeColor(): Color = Color(this.toULong() shl 32)

/** Readable text on an arbitrary tag colour, without asking the user to pick one. */
fun Color.readableForeground(): Color =
    if (red * 0.299f + green * 0.587f + blue * 0.114f > 0.6f) Color(0xFF1A1A1A) else Color.White

@Composable
fun TagChip(
    tag: Tag,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val base = tag.colorArgb.toComposeColor()
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(50),
        color = if (selected) base else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, base),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            // A person tag carries a dot so an assignment is distinguishable from
            // a label at a glance, without spelling out "assigned to".
            if (tag.kind == TagKind.PERSON) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            if (selected) base.readableForeground() else base,
                            CircleShape,
                        ),
                )
            }
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) base.readableForeground() else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * A person who has been asked but hasn't answered.
 *
 * Drawn as an outline rather than a filled chip: tagging someone raises a
 * request, and a solid chip would read as though they had already agreed.
 */
@Composable
fun PendingPersonChip(name: String, stale: Boolean, modifier: Modifier = Modifier) {
    val color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
    ) {
        Text(
            text = if (stale) "$name — no answer" else "$name?",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * A smart tag chip, showing its *value* rather than its name — "Due Fri 12", not
 * "due". The icon is what marks it as one you can tap to configure.
 */
@Composable
fun SmartTagChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color =
        if (emphasised) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

fun smartTagIcon(tag: SmartTag): ImageVector = when (tag) {
    SmartTag.DUE -> Icons.Default.Event
    SmartTag.REMIND -> Icons.Default.NotificationsActive
    SmartTag.REPEATING -> Icons.Default.Autorenew
    SmartTag.ROTATING -> Icons.Default.Groups
}

// ---- wording -------------------------------------------------------------

private val dayMonth = DateTimeFormatter.ofPattern("d MMM")

/** Resolved once; Locale.getDefault() is a lookup, and this runs per chip. */
private val locale: Locale = Locale.getDefault()

/**
 * Dates as people say them. "Today" and "Tomorrow" beat a date nobody has to
 * decode, and a weekday is enough inside the coming week.
 */
fun describeDate(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.plusDays(1) -> "Tomorrow"
    date == today.minusDays(1) -> "Yesterday"
    date.isBefore(today) -> {
        val days = today.toEpochDay() - date.toEpochDay()
        if (days < 7) "$days days ago" else "Overdue " + date.format(dayMonth)
    }
    date.isBefore(today.plusWeeks(1)) ->
        date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    else -> date.format(dayMonth)
}

fun describeRecurrence(rule: Recurrence): String {
    val unit = when (rule.unit) {
        RecurrenceUnit.DAY -> "day"
        RecurrenceUnit.WEEK -> "week"
        RecurrenceUnit.MONTH -> "month"
        RecurrenceUnit.YEAR -> "year"
    }
    val every = if (rule.count == 1) "Every $unit" else "Every ${rule.count} ${unit}s"
    if (rule.unit != RecurrenceUnit.MONTH) return every
    return when (rule.monthEnd) {
        MonthEndPolicy.LAST_DAY -> "$every, last day"
        MonthEndPolicy.STRICT -> "$every, exact date only"
        MonthEndPolicy.CLAMP -> every
    }
}

fun describeRotation(rotation: Rotation, nameOf: (String) -> String): String =
    rotation.currentAssignee?.let { "${nameOf(it)}'s turn" } ?: "Rotating"

fun overdueDays(task: Task, today: LocalDate): Long =
    task.dueOn?.let { today.toEpochDay() - it.toEpochDay() }?.coerceAtLeast(0) ?: 0
