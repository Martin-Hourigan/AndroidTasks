package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TasksApp()
                }
            }
        }
    }
}

/**
 * Short enough not to feel sluggish, long enough to cover the cost of composing
 * a screen so the tap never reads as a dropped input.
 */
private const val SCREEN_FADE_MS = 140

private enum class Screen { HOME, LIST, EDIT, SETTINGS, MEMBERS, TAGS, FILTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksApp(viewModel: TasksViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var openFilterId by remember { mutableStateOf<String?>(null) }
    var openTaskId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val snackbars = remember { SnackbarHostState() }

    val openFilter: TaskFilter? = openFilterId?.let { state.snapshot.filter(it) }
    val openTask: Task? = openTaskId?.let { state.snapshot.task(it) }

    // Falling back rather than showing a blank screen: a filter or task can
    // vanish underneath us when it is deleted here or, later, by somebody else
    // in the household.
    if (screen == Screen.LIST && openFilter == null) screen = Screen.HOME
    if (screen == Screen.EDIT && openTask == null) {
        screen = if (openFilter != null) Screen.LIST else Screen.HOME
    }

    fun back() {
        screen = when (screen) {
            Screen.EDIT -> if (openFilter != null) Screen.LIST else Screen.HOME
            Screen.MEMBERS, Screen.TAGS -> Screen.SETTINGS
            else -> Screen.HOME
        }
    }

    var editingFilterId by remember { mutableStateOf<String?>(null) }
    val editingFilter: TaskFilter? = editingFilterId?.let { state.snapshot.filter(it) }
    if (screen == Screen.FILTER && editingFilter == null) screen = Screen.HOME

    BackHandler(enabled = screen != Screen.HOME) { back() }

    // Asked the first time a reminder actually exists, rather than on launch —
    // a permission prompt before the app has done anything is the one people
    // reflexively decline.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val wantsReminders = state.snapshot.tasks.any { it.remindAt != null }
    LaunchedEffect(wantsReminders) {
        if (wantsReminders && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The undo carries the whole pre-completion task: a repeating one never
    // closed, it moved, so there is no flag to flip back.
    val undo = state.undo
    LaunchedEffect(undo) {
        val pending = undo ?: return@LaunchedEffect
        val result = snackbars.showSnackbar(
            message = if (pending.wasRepeating) "Done — next one scheduled" else "Done",
            actionLabel = "Undo",
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoCompletion() else viewModel.dismissUndo()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.HOME -> state.snapshot.household.name.ifBlank { "Tasks" }
                            Screen.LIST -> openFilter?.name.orEmpty()
                            Screen.EDIT -> "Task"
                            Screen.SETTINGS -> "Settings"
                            Screen.MEMBERS -> "People"
                            Screen.TAGS -> "Tags"
                            Screen.FILTER -> "Card"
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == Screen.HOME) {
                        IconButton(onClick = { screen = Screen.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (screen == Screen.HOME || screen == Screen.LIST) {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add task")
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        // Screens cross-fade rather than swapping outright.
        //
        // Composing a screen costs real time, and with an instant swap that time
        // reads as the app having frozen — you tap and nothing happens, then the
        // new screen appears fully formed. A short fade gives the eye something
        // moving on the very next frame, so the same work now reads as a
        // transition rather than a stall. It does not make anything faster; it
        // stops the cost landing where the user is waiting on it.
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                fadeIn(animationSpec = tween(SCREEN_FADE_MS)) togetherWith
                    fadeOut(animationSpec = tween(SCREEN_FADE_MS / 2))
            },
            label = "screen",
        ) { current ->
        when (current) {
            Screen.HOME -> HomeScreen(
                state = state,
                onOpenFilter = { filter ->
                    openFilterId = filter.id
                    screen = Screen.LIST
                },
                onEditFilter = { filter ->
                    editingFilterId = filter.id
                    screen = Screen.FILTER
                },
                onNewFilter = {
                    val created = TaskFilter(
                        id = viewModel.newFilterId(),
                        name = "New card",
                        position = state.snapshot.filters.size,
                    )
                    viewModel.saveFilter(created)
                    editingFilterId = created.id
                    screen = Screen.FILTER
                },
                modifier = Modifier.padding(padding),
            )

            Screen.FILTER -> editingFilter?.let { filter ->
                FilterEditScreen(
                    state = state,
                    original = filter,
                    onSave = viewModel::saveFilter,
                    onDelete = {
                        viewModel.deleteFilter(it.id)
                        screen = Screen.HOME
                    },
                    modifier = Modifier.padding(padding),
                )
            }

            Screen.LIST -> openFilter?.let { filter ->
                TaskListScreen(
                    state = state,
                    filter = filter,
                    onToggle = viewModel::toggleComplete,
                    onOpen = { task ->
                        openTaskId = task.id
                        screen = Screen.EDIT
                    },
                    onAccept = viewModel::acceptRequest,
                    onDecline = viewModel::declineRequest,
                    modifier = Modifier.padding(padding),
                )
            }

            Screen.EDIT -> openTask?.let { task ->
                TaskEditScreen(
                    state = state,
                    original = task,
                    onSave = viewModel::save,
                    onDelete = {
                        viewModel.delete(it)
                        back()
                    },
                    onCreateTag = { name -> viewModel.createTag(name, nextTagColor(state)) },
                    modifier = Modifier.padding(padding),
                )
            }

            Screen.SETTINGS -> SettingsScreen(
                state = state,
                onRenameHousehold = viewModel::renameHousehold,
                onOpenMembers = { screen = Screen.MEMBERS },
                onOpenTags = { screen = Screen.TAGS },
                onExport = {
                    copyToClipboard(context, viewModel.exportJson())
                    scope.launch { snackbars.showSnackbar("Copied to clipboard") }
                },
                onImport = { importing = true },
                exactAlarmsAllowed = viewModel.canScheduleExactAlarms(),
                onFixExactAlarms = { openExactAlarmSettings(context) },
                modifier = Modifier.padding(padding),
            )

            Screen.MEMBERS -> MembersScreen(
                state = state,
                onAdd = viewModel::addMember,
                onUpdate = viewModel::updateMember,
                onRemove = viewModel::removeMember,
                modifier = Modifier.padding(padding),
            )

            Screen.TAGS -> TagsScreen(
                state = state,
                usageOf = viewModel::tagUsage,
                onUpdate = viewModel::saveTag,
                onDelete = viewModel::deleteTag,
                onCreate = { viewModel.createTag(it, nextTagColor(state)) },
                modifier = Modifier.padding(padding),
            )
        }
        }
    }

    if (adding) {
        AddTaskDialog(
            onDismiss = { adding = false },
            onAdd = { title ->
                // Pre-tagged from whichever list you added it in, so adding to
                // Groceries actually puts it in Groceries.
                val id = UUID.randomUUID().toString()
                viewModel.save(
                    Task(
                        id = id,
                        title = title.trim(),
                        tagIds = openFilter?.takeIf { it.matchAll }?.tagIds.orEmpty(),
                        createdAt = Instant.now(),
                    ),
                )
                adding = false
                openTaskId = id
                screen = Screen.EDIT
            },
        )
    }

    if (importing) {
        ImportDialog(
            onDismiss = { importing = false },
            onImport = { raw ->
                importing = false
                scope.launch {
                    val ok = viewModel.importJson(raw)
                    snackbars.showSnackbar(
                        if (ok) "Imported" else "That doesn't look like an export",
                    )
                }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    ContextCompat.getSystemService(context, ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Tasks export", text))
}

/**
 * Exact alarms can only be granted from system Settings, never from an in-app
 * prompt, so this is the only way to offer the fix.
 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Cycles the starter palette so new tags aren't all the same grey. */
private fun nextTagColor(state: UiState): Long {
    val palette = listOf(
        0xFFE07A5F, 0xFF81B29A, 0xFFF2CC8F, 0xFFD64550,
        0xFF7B9EA8, 0xFF9A8C98, 0xFF6A994E, 0xFF577590,
    )
    return palette[state.snapshot.tags.size % palette.size]
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What needs doing?") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onAdd(title) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "This replaces everything in the household.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it },
                    label = { Text("Paste an export") },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = raw.isNotBlank(), onClick = { onImport(raw) }) { Text("Replace") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
