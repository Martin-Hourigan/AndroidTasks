package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.DueWindow
import dev.mahourigan.tasks.domain.TagKind
import dev.mahourigan.tasks.domain.TaskFilter
import dev.mahourigan.tasks.domain.TaskSort
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Building a card for the home grid.
 *
 * This is the only place a "list" is ever created, because a list is nothing but
 * a saved set of tags and conditions — "Groceries" is the `purchase` tag with a
 * name on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterEditScreen(
    state: UiState,
    original: TaskFilter,
    onSave: (TaskFilter) -> Unit,
    onDelete: (TaskFilter) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var filter by remember(original.id) { mutableStateOf(original) }

    fun update(change: (TaskFilter) -> TaskFilter) {
        filter = change(filter).also(onSave)
    }

    val tags = state.snapshot.tags.sortedBy { it.position }
    val matching = state.count(filter)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = 48.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = filter.name,
            onValueChange = { value -> update { it.copy(name = value) } },
            label = { Text("Card name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // The count updates as the conditions change, so you can tell whether a
        // filter does what you meant before saving it to the home screen.
        Text(
            text = "$matching task(s) match right now",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text("Tags", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tags.forEach { tag ->
                val on = tag.id in filter.tagIds
                TagChip(
                    tag = tag,
                    selected = on,
                    onClick = {
                        update {
                            it.copy(tagIds = if (on) it.tagIds - tag.id else it.tagIds + tag.id)
                        }
                    },
                )
            }
        }

        if (filter.tagIds.size > 1) {
            // `kitchen + purchase` means "kitchen things to buy"; `laundry +
            // kitchen` means "chores". Neither reading is wrong, so it is asked
            // per card rather than fixed globally.
            ToggleRow(
                title = if (filter.matchAll) "Must have every tag" else "Any one tag will do",
                detail = if (filter.matchAll) {
                    "Kitchen things to buy"
                } else {
                    "Anything from either pile"
                },
                checked = filter.matchAll,
                onCheckedChange = { value -> update { it.copy(matchAll = value) } },
            )
        }

        HorizontalDivider()

        Text("Who", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        ToggleRow(
            title = "Mine",
            detail = "Assigned to me, or my turn on a roster",
            checked = filter.assignedToViewer,
            onCheckedChange = { value -> update { it.copy(assignedToViewer = value) } },
        )
        ToggleRow(
            title = "Asked of me",
            detail = "Waiting on my answer",
            checked = filter.pendingForViewer,
            onCheckedChange = { value -> update { it.copy(pendingForViewer = value) } },
        )
        ToggleRow(
            title = "Nobody on it",
            detail = "Not assigned and nobody has been asked",
            checked = filter.unassignedOnly,
            onCheckedChange = { value -> update { it.copy(unassignedOnly = value) } },
        )

        HorizontalDivider()

        Text("When", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DueWindow.entries.forEach { window ->
                FilterChip(
                    selected = filter.dueWindow == window,
                    onClick = { update { it.copy(dueWindow = window) } },
                    label = { Text(dueWindowLabel(window)) },
                )
            }
        }

        Text("Order", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TaskSort.entries.forEach { sort ->
                FilterChip(
                    selected = filter.sort == sort,
                    onClick = { update { it.copy(sort = sort) } },
                    label = { Text(sortLabel(sort)) },
                )
            }
        }

        ToggleRow(
            title = "Show finished",
            detail = "Keep completed tasks in the list",
            checked = filter.includeCompleted,
            onCheckedChange = { value -> update { it.copy(includeCompleted = value) } },
        )

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { update { it.copy(position = it.position - 3) } }) {
                Text("Move earlier")
            }
            TextButton(onClick = { update { it.copy(position = it.position + 3) } }) {
                Text("Move later")
            }
        }

        TextButton(onClick = { onDelete(filter) }) {
            Text("Delete card", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun dueWindowLabel(window: DueWindow) = when (window) {
    DueWindow.ANY -> "Any"
    DueWindow.HAS_DUE -> "Has a date"
    DueWindow.NO_DUE -> "No date"
    DueWindow.OVERDUE -> "Overdue"
    DueWindow.DUE_TODAY -> "Due today"
    DueWindow.DUE_OR_OVERDUE -> "Today or overdue"
    DueWindow.UPCOMING -> "Upcoming"
}

private fun sortLabel(sort: TaskSort) = when (sort) {
    TaskSort.DUE_DATE -> "By date"
    TaskSort.CREATED -> "By age"
    TaskSort.TITLE -> "A–Z"
}
