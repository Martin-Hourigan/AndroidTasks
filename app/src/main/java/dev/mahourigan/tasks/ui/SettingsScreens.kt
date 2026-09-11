package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.data.TagUsage
import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.TagKind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    state: UiState,
    onRenameHousehold: (String) -> Unit,
    onOpenMembers: () -> Unit,
    onOpenTags: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    exactAlarmsAllowed: Boolean,
    onFixExactAlarms: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var name by remember(state.snapshot.household.id) {
        mutableStateOf(state.snapshot.household.name)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Only surfaced when it is actually withheld. Reminders still arrive
        // without it, just late — which is fine for "buy milk" and useless for
        // "leave now", so it is worth offering the fix rather than failing quietly.
        if (!exactAlarmsAllowed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        MaterialTheme.shapes.medium,
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Reminders may arrive late",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Android is holding back exact alarms for this app, so a 9:00 " +
                        "reminder can drift by several minutes.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onFixExactAlarms, contentPadding = PaddingValues(0.dp)) {
                    Text("Allow exact alarms")
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onRenameHousehold(it)
            },
            label = { Text("Household name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        HorizontalDivider()

        SettingsRow(
            title = "People",
            detail = state.snapshot.household.members.joinToString { it.name },
            onClick = onOpenMembers,
        )
        SettingsRow(
            title = "Tags",
            detail = "${state.snapshot.tags.count { it.kind == TagKind.PLAIN }} tags",
            onClick = onOpenTags,
        )

        HorizontalDivider()

        SettingsRow(
            title = "Export",
            detail = "Copy everything as JSON",
            onClick = onExport,
        )
        SettingsRow(
            title = "Import",
            detail = "Replace everything from an export",
            onClick = onImport,
        )
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The household.
 *
 * Adding someone here creates their person tag with them, which is what makes
 * them assignable and puts them in the pool for rotations.
 */
@Composable
fun MembersScreen(
    state: UiState,
    onAdd: (String) -> Unit,
    onUpdate: (Member) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var removing by remember { mutableStateOf<Member?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = 32.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.snapshot.household.members.forEach { member ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = member }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(member.colorArgb.toComposeColor(), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(member.name, style = MaterialTheme.typography.bodyLarge)
                    if (member.uid == state.viewerUid) {
                        Text(
                            "That's you",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Removing yourself would leave the phone with no identity to
                // assign anything to.
                if (member.uid != state.viewerUid) {
                    IconButton(onClick = { removing = member }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${member.name}")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("+ Add someone")
        }
    }

    if (adding) {
        NameDialog(
            title = "Add someone",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = {
                onAdd(it)
                adding = false
            },
        )
    }

    editing?.let { member ->
        NameDialog(
            title = "Rename",
            initial = member.name,
            onDismiss = { editing = null },
            onConfirm = {
                onUpdate(member.copy(name = it))
                editing = null
            },
        )
    }

    removing?.let { member ->
        val theirTasks = state.snapshot.tasks.count { member.uid in it.effectiveAssignees }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.name}?") },
            text = {
                Text(
                    if (theirTasks > 0) {
                        // Deleting the jobs along with the person would quietly
                        // lose work that still needs doing.
                        "$theirTasks task(s) will come back unassigned, and they'll " +
                            "drop out of any rotation. Nothing is deleted."
                    } else {
                        "They'll be removed from the household."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(member.uid)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Tag management.
 *
 * Only plain tags appear: person tags belong to membership and the smart tags
 * are a fixed enum, so neither can be renamed or deleted from here.
 */
@Composable
fun TagsScreen(
    state: UiState,
    usageOf: (String) -> TagUsage,
    onUpdate: (Tag) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var editing by remember { mutableStateOf<Tag?>(null) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    var adding by remember { mutableStateOf(false) }

    val tags = state.snapshot.tags.filter { it.kind == TagKind.PLAIN }.sortedBy { it.position }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = 32.dp,
            ),
    ) {
        tags.forEach { tag ->
            val usage = usageOf(tag.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = tag }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .background(tag.colorArgb.toComposeColor(), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = usageSummary(usage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { deleting = tag }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${tag.name}")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 8.dp)) {
            Text("+ New tag")
        }
    }

    if (adding) {
        NameDialog(
            title = "New tag",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = {
                onCreate(it)
                adding = false
            },
        )
    }

    editing?.let { tag ->
        NameDialog(
            title = "Rename tag",
            initial = tag.name,
            onDismiss = { editing = null },
            onConfirm = {
                onUpdate(tag.copy(name = it))
                editing = null
            },
        )
    }

    deleting?.let { tag ->
        val usage = usageOf(tag.id)
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${tag.name}?") },
            text = {
                Text(
                    if (usage.isUsed) {
                        // Saying what it holds up first, because a filter that
                        // quietly matches nothing is very hard to diagnose later.
                        "It'll be taken off ${usage.taskCount} task(s) and " +
                            "${usage.filterCount} saved filter(s). The tasks stay."
                    } else {
                        "Nothing is using it."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(tag.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun usageSummary(usage: TagUsage): String = when {
    !usage.isUsed -> "Unused"
    usage.filterCount == 0 -> "${usage.taskCount} task(s)"
    else -> "${usage.taskCount} task(s), ${usage.filterCount} filter(s)"
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
