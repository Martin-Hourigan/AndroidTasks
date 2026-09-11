package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.SmartTag
import dev.mahourigan.tasks.domain.SmartTags
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskAssignment
import dev.mahourigan.tasks.domain.TaskBucket
import dev.mahourigan.tasks.domain.TaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.Instant

@Composable
fun TaskListScreen(
    state: UiState,
    filter: TaskFilter,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onAccept: (Task) -> Unit,
    onDecline: (Task) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // Filtering, sorting and grouping the whole list is derived data, so it is
    // computed once per data change rather than on every recomposition.
    //
    // Grouped by when rather than by tag: an overdue job and one due next month
    // want different amounts of your attention, and the tags are already on the
    // rows themselves.
    val sections = remember(state.snapshot, filter, state.today) {
        val tasks = state.tasksFor(filter)
        val grouped = tasks.groupBy { TaskFilter.bucketOf(it, state.today) }
        BUCKET_ORDER.mapNotNull { bucket ->
            grouped[bucket]?.takeIf { it.isNotEmpty() }?.let { bucket to it }
        }
    }

    if (sections.isEmpty()) {
        EmptyState(filter, modifier.fillMaxSize())
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = 96.dp,
        ),
    ) {
        sections.forEach { (bucket, inBucket) ->
            item(key = "header_$bucket") {
                Text(
                    text = bucketLabel(bucket),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (bucket == TaskBucket.OVERDUE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
                )
            }

            items(inBucket, key = { it.id }) { task ->
                SwipeToComplete(onComplete = { onToggle(task) }) {
                    TaskRow(
                        state = state,
                        task = task,
                        onToggle = { onToggle(task) },
                        onOpen = { onOpen(task) },
                        onAccept = { onAccept(task) },
                        onDecline = { onDecline(task) },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/**
 * Swipe right to tick something off.
 *
 * Only one direction is wired up. A swipe-to-delete on the opposite side is the
 * standard pairing and a bad idea here: the two gestures are easy to confuse,
 * and getting them the wrong way round destroys a task instead of completing it.
 * Deleting stays on the task's own screen, where it is deliberate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToComplete(onComplete: () -> Unit, content: @Composable () -> Unit) {
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onComplete()
            // Never actually dismissed: completing usually removes the row from
            // this filter anyway, and when it doesn't — a repeating task moving
            // to its next date — the row must stay put rather than vanish.
            false
        },
    )

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

private val BUCKET_ORDER = listOf(
    TaskBucket.OVERDUE,
    TaskBucket.TODAY,
    TaskBucket.UPCOMING,
    TaskBucket.NO_DATE,
    TaskBucket.DONE,
)

private fun bucketLabel(bucket: TaskBucket) = when (bucket) {
    TaskBucket.OVERDUE -> "Overdue"
    TaskBucket.TODAY -> "Today"
    TaskBucket.UPCOMING -> "Upcoming"
    TaskBucket.NO_DATE -> "No date"
    TaskBucket.DONE -> "Done"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskRow(
    state: UiState,
    task: Task,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val household = state.snapshot.household
    val isRequestForMe = state.viewerUid in task.pendingAssignees
    val stale = TaskAssignment.staleRequests(task, Instant.now())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = task.isComplete, onCheckedChange = { onToggle() })

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.isComplete) TextDecoration.LineThrough else null,
                color = if (task.isComplete) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                task.dueOn?.let { due ->
                    SmartTagChip(
                        label = describeDate(due, state.today),
                        icon = smartTagIcon(SmartTag.DUE),
                        emphasised = task.isOverdue(state.today),
                    )
                }
                task.recurrence?.let {
                    SmartTagChip(describeRecurrence(it), smartTagIcon(SmartTag.REPEATING))
                }
                task.rotation?.takeIf { it.order.isNotEmpty() }?.let { rotation ->
                    SmartTagChip(
                        label = describeRotation(rotation) { household.displayName(it) },
                        icon = smartTagIcon(SmartTag.ROTATING),
                    )
                }
                state.tagsFor(task).forEach { TagChip(it) }

                // Someone asked but not yet agreed. Outlined, so it never looks
                // like the job is already spoken for.
                task.pendingAssignees.keys.forEach { uid ->
                    PendingPersonChip(
                        name = household.displayName(uid),
                        stale = uid in stale,
                    )
                }
            }

            if (isRequestForMe) {
                RequestActions(
                    askedBy = household.displayName(
                        task.pendingAssignees[state.viewerUid]?.askedByUid.orEmpty(),
                    ),
                    onAccept = onAccept,
                    onDecline = onDecline,
                )
            }
        }
    }
}

@Composable
private fun RequestActions(askedBy: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$askedBy asked you",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Accept",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onAccept),
        )
        Text(
            text = "Decline",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onDecline),
        )
    }
}

@Composable
private fun EmptyState(filter: TaskFilter, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing in ${filter.name}", style = MaterialTheme.typography.titleMedium)
        Text(
            text = emptyHint(filter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun emptyHint(filter: TaskFilter): String = when {
    filter.pendingForViewer -> "Nobody has asked you to take anything on."
    filter.assignedToViewer -> "Nothing is yours at the moment."
    filter.unassignedOnly -> "Everything has someone on it."
    filter.tagIds.isNotEmpty() -> "Tag a task to see it here."
    else -> "Add a task with the button below."
}
