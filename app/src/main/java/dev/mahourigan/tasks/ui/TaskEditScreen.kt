package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.Rotation
import dev.mahourigan.tasks.domain.SmartTag
import dev.mahourigan.tasks.domain.SmartTags
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.TagKind
import dev.mahourigan.tasks.domain.Task
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

private const val SAVE_DEBOUNCE_MS = 500L

private enum class OpenDialog { NONE, DUE, REMIND_DATE, REMIND_TIME, REPEAT, ROTATION, NEW_TAG }

/**
 * Everything about one task.
 *
 * Tags are the only control surface here: the plain ones label, the smart ones
 * open their settings when you tap them. There is deliberately no separate
 * "make this repeat" button — adding the chip is how you do it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    state: UiState,
    original: Task,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onCreateTag: (String) -> String?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var task by remember(original.id) { mutableStateOf(original) }
    var dialog by remember { mutableStateOf(OpenDialog.NONE) }
    var queued by remember(original.id) { mutableStateOf<Task?>(null) }

    // Saving as you go rather than behind a button: there is no draft state to
    // lose and no way to back out having silently discarded what you typed.
    //
    // Typing is debounced, though. A save rewrites the whole store, and doing
    // that on every keystroke made the field unusable — several hundred
    // milliseconds a character.
    LaunchedEffect(queued) {
        val pending = queued ?: return@LaunchedEffect
        delay(SAVE_DEBOUNCE_MS)
        onSave(pending)
        queued = null
    }

    // Leaving the screen inside the debounce window must not lose the last few
    // characters.
    DisposableEffect(Unit) {
        onDispose { queued?.let(onSave) }
    }

    /** Text edits: update immediately on screen, write shortly after. */
    fun edit(change: (Task) -> Task) {
        task = change(task)
        queued = task
    }

    /** Discrete changes — a chip, a dialog — are few and worth writing at once. */
    fun update(change: (Task) -> Task) {
        queued = null
        task = change(task).also(onSave)
    }

    val household = state.snapshot.household
    val plainTags = state.snapshot.tags.filter { it.kind == TagKind.PLAIN }.sortedBy { it.position }
    val personTags = state.snapshot.tags.filter { it.kind == TagKind.PERSON }.sortedBy { it.position }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = 96.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = task.title,
            onValueChange = { value -> edit { it.copy(title = value) } },
            label = { Text("Task") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = task.notes,
            onValueChange = { value -> edit { it.copy(notes = value) } },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        SectionLabel("Settings")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmartTagChip(
                label = task.dueOn?.let { describeDate(it, state.today) } ?: "Due date",
                icon = smartTagIcon(SmartTag.DUE),
                emphasised = task.isOverdue(state.today),
                onClick = { dialog = OpenDialog.DUE },
            )
            SmartTagChip(
                label = task.remindAt?.let { "Remind " + describeDate(it.toLocalDate(), state.today) + " " + it.toLocalTime() }
                    ?: "Remind me",
                icon = smartTagIcon(SmartTag.REMIND),
                onClick = { dialog = OpenDialog.REMIND_DATE },
            )
            SmartTagChip(
                label = task.recurrence?.let { describeRecurrence(it) } ?: "Repeating",
                icon = smartTagIcon(SmartTag.REPEATING),
                onClick = { dialog = OpenDialog.REPEAT },
            )
            // A roster of one is not a roster. Hidden rather than shown-then-
            // refused, so a solo user is never offered something that can only
            // tell them no.
            if (household.members.size > 1 || task.rotation != null) {
                SmartTagChip(
                    label = task.rotation?.takeIf { it.order.isNotEmpty() }
                        ?.let { describeRotation(it) { uid -> household.displayName(uid) } }
                        ?: "Rotating",
                    icon = smartTagIcon(SmartTag.ROTATING),
                    onClick = { dialog = OpenDialog.ROTATION },
                )
            }
        }

        if (task.stashed.isNotEmpty()) {
            Text(
                text = "Settings for " + task.stashed.keys.joinToString { it.name.lowercase() } +
                    " are kept, ready if you add them back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // On your own there is nobody to assign anything to, and every task is
        // implicitly yours — so the whole section is dropped rather than showing
        // a single chip for yourself.
        if (personTags.size > 1) {
        HorizontalDivider()

        SectionLabel("Who")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            personTags.forEach { tag ->
                val uid = tag.personUid.orEmpty()
                val accepted = uid in task.assigneeUids
                val pending = uid in task.pendingAssignees
                if (pending) {
                    PendingPersonChip(name = tag.name, stale = false)
                } else {
                    TagChip(
                        tag = tag,
                        selected = accepted,
                        onClick = {
                            update { current ->
                                when {
                                    accepted -> dev.mahourigan.tasks.domain.TaskAssignment
                                        .withdraw(current, uid)
                                    // Asking rather than assigning, unless it's you.
                                    else -> dev.mahourigan.tasks.domain.TaskAssignment
                                        .request(current, uid, state.viewerUid, Instant.now())
                                }
                            }
                        },
                    )
                }
            }
        }
        Text(
            text = "Tagging someone else asks them — it's theirs once they accept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }

        HorizontalDivider()

        SectionLabel("Tags")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            plainTags.forEach { tag ->
                val on = tag.id in task.tagIds
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
            TextButton(onClick = { dialog = OpenDialog.NEW_TAG }) { Text("+ New tag") }
        }

        HorizontalDivider()

        TextButton(onClick = { onDelete(task) }) {
            Text("Delete task", color = MaterialTheme.colorScheme.error)
        }
    }

    when (dialog) {
        OpenDialog.DUE -> DatePickerFor(
            initial = task.dueOn,
            onDismiss = { dialog = OpenDialog.NONE },
            onClear = {
                update { SmartTags.remove(it, SmartTag.DUE) }
                dialog = OpenDialog.NONE
            },
            onPick = { picked ->
                update { current ->
                    current.copy(
                        dueOn = picked,
                        // A schedule-anchored series measures from where it began,
                        // so moving the date by hand restarts that reference point.
                        seriesAnchorOn = picked,
                    )
                }
                dialog = OpenDialog.NONE
            },
        )

        OpenDialog.REMIND_DATE -> DatePickerFor(
            initial = task.remindAt?.toLocalDate() ?: task.dueOn,
            onDismiss = { dialog = OpenDialog.NONE },
            onClear = {
                update { SmartTags.remove(it, SmartTag.REMIND) }
                dialog = OpenDialog.NONE
            },
            onPick = { picked ->
                val time = task.remindAt?.toLocalTime() ?: LocalTime.of(9, 0)
                update { it.copy(remindAt = LocalDateTime.of(picked, time)) }
                dialog = OpenDialog.REMIND_TIME
            },
        )

        OpenDialog.REMIND_TIME -> {
            val current = task.remindAt ?: LocalDateTime.of(state.today, LocalTime.of(9, 0))
            val timeState = rememberTimePickerState(
                initialHour = current.hour,
                initialMinute = current.minute,
                is24Hour = false,
            )
            AlertDialog(
                onDismissRequest = { dialog = OpenDialog.NONE },
                title = { Text("Remind me at") },
                text = { TimePicker(state = timeState) },
                confirmButton = {
                    TextButton(onClick = {
                        update {
                            it.copy(
                                remindAt = LocalDateTime.of(
                                    current.toLocalDate(),
                                    LocalTime.of(timeState.hour, timeState.minute),
                                ),
                            )
                        }
                        dialog = OpenDialog.NONE
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = OpenDialog.NONE }) { Text("Cancel") }
                },
            )
        }

        OpenDialog.REPEAT -> RecurrenceDialog(
            initial = task.recurrence,
            startingOn = task.dueOn ?: state.today,
            onDismiss = { dialog = OpenDialog.NONE },
            onConfirm = { rule ->
                update { current ->
                    if (rule == null) {
                        SmartTags.remove(current, SmartTag.REPEATING)
                    } else {
                        current.copy(
                            recurrence = rule,
                            seriesAnchorOn = current.seriesAnchorOn ?: current.dueOn,
                        )
                    }
                }
                dialog = OpenDialog.NONE
            },
        )

        OpenDialog.ROTATION -> RotationDialog(
            state = state,
            initial = task.rotation,
            onDismiss = { dialog = OpenDialog.NONE },
            onConfirm = { rotation ->
                update { current ->
                    if (rotation == null) {
                        SmartTags.remove(current, SmartTag.ROTATING)
                    } else {
                        current.copy(rotation = rotation)
                    }
                }
                dialog = OpenDialog.NONE
            },
        )

        OpenDialog.NEW_TAG -> NewTagDialog(
            onDismiss = { dialog = OpenDialog.NONE },
            onCreate = { name ->
                onCreateTag(name)?.let { id -> update { it.copy(tagIds = it.tagIds + id) } }
                dialog = OpenDialog.NONE
            },
        )

        OpenDialog.NONE -> Unit
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerFor(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onClear) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * Who's in the roster, in order.
 *
 * Whoever is up next isn't asked to confirm each turn — joining the roster is
 * the agreement, and a weekly "do you accept the bins?" prompt would be
 * unbearable.
 */
@Composable
private fun RotationDialog(
    state: UiState,
    initial: Rotation?,
    onDismiss: () -> Unit,
    onConfirm: (Rotation?) -> Unit,
) {
    var order by remember { mutableStateOf(initial?.order.orEmpty()) }
    val members = state.snapshot.household.members

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Whose turn, in order") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (members.size < 2) {
                    Text(
                        "A rotation needs more than one person in the household.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                members.forEach { member ->
                    val position = order.indexOf(member.uid)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                order = if (position >= 0) {
                                    order - member.uid
                                } else {
                                    order + member.uid
                                }
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (position >= 0) "${position + 1}" else "–",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(member.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = order.isNotEmpty(),
                onClick = { onConfirm(Rotation(order, initial?.index?.coerceIn(0, order.lastIndex) ?: 0)) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onConfirm(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun NewTagDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tag") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
