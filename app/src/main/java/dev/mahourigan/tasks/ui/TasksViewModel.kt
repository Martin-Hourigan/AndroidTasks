package dev.mahourigan.tasks.ui

import dev.mahourigan.tasks.data.HouseholdSnapshot
import dev.mahourigan.tasks.data.Repositories
import dev.mahourigan.tasks.data.StarterData
import dev.mahourigan.tasks.data.TagUsage
import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskAssignment
import dev.mahourigan.tasks.domain.TaskCompletion
import dev.mahourigan.tasks.domain.TaskFilter
import dev.mahourigan.tasks.notification.ReminderScheduler
import dev.mahourigan.tasks.notification.shouldRemind
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * What the screens are actually undone by, when a checkbox is tapped by mistake.
 *
 * A repeating task never closes — completing it moves its date and advances the
 * roster — so there is no flag to clear on the way back. The only reliable undo
 * is putting the whole task back as it was, which means keeping a copy.
 */
data class UndoableCompletion(val before: Task, val wasRepeating: Boolean)

data class UiState(
    val snapshot: HouseholdSnapshot = HouseholdSnapshot(),
    val today: LocalDate = LocalDate.now(),
    val loading: Boolean = true,
    val undo: UndoableCompletion? = null,
) {
    val viewerUid: String get() = snapshot.viewerUid

    fun tagsFor(task: Task): List<Tag> =
        task.tagIds.mapNotNull { snapshot.tag(it) }.sortedBy { it.position }

    fun count(filter: TaskFilter): Int =
        snapshot.tasks.count { filter.matches(it, viewerUid, today) }

    fun tasksFor(filter: TaskFilter): List<Task> =
        filter.apply(snapshot.tasks, viewerUid, today)
}

@OptIn(FlowPreview::class)
class TasksViewModel(app: Application) : AndroidViewModel(app) {

    // Shared with the alarm receivers — see Repositories for why it has to be
    // the same instance rather than a second one over the same file.
    private val repository = Repositories.tasks(app)

    private val local = MutableStateFlow(UiState())

    val state: StateFlow<UiState> = combine(repository.snapshot, local.asStateFlow()) { stored, ui ->
        ui.copy(snapshot = stored)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            repository.load()
            local.value = local.value.copy(loading = false)
        }
        // Re-arming on every change rather than at each edit site: a reminder
        // moves when a repeat is completed, when someone is assigned, and when a
        // task is deleted, and one of those is always the one that gets forgotten.
        //
        // Off the main thread and off the hot path, though. syncAll makes a
        // binder call to AlarmManager per reminder plus a SharedPreferences
        // write, which is far too much to run between keystrokes — and the
        // signature means typing a title, which cannot change any alarm, does
        // not trigger it at all.
        viewModelScope.launch(Dispatchers.Default) {
            repository.snapshot
                .map { snapshot -> snapshot to snapshot.alarmSignature() }
                .distinctUntilChangedBy { (_, signature) -> signature }
                .debounce(ALARM_SYNC_DEBOUNCE_MS)
                .collect { (snapshot, _) ->
                    if (snapshot.household.members.isNotEmpty()) {
                        ReminderScheduler.syncAll(app, snapshot)
                    }
                }
        }
    }

    fun canScheduleExactAlarms(): Boolean =
        ReminderScheduler.canScheduleExact(getApplication())

    /** Tasks with a reminder still to come, for the settings screen to report. */
    fun pendingReminderCount(): Int {
        val now = java.time.LocalDateTime.now()
        val snapshot = state.value.snapshot
        return snapshot.tasks.count { it.shouldRemind(snapshot.viewerUid, now) }
    }

    // ---- tasks -------------------------------------------------------------

    fun addTask(title: String, tagIds: Set<String> = emptySet()) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            repository.upsertTask(
                Task(
                    id = UUID.randomUUID().toString(),
                    title = trimmed,
                    tagIds = tagIds,
                    createdAt = Instant.now(),
                ),
            )
        }
    }

    fun save(task: Task) = viewModelScope.launch { repository.upsertTask(task) }

    fun delete(task: Task) = viewModelScope.launch { repository.deleteTask(task.id) }

    fun toggleComplete(task: Task) {
        val state = state.value
        viewModelScope.launch {
            if (task.isComplete) {
                repository.upsertTask(TaskCompletion.reopen(task))
                return@launch
            }
            val after = TaskCompletion.complete(
                task = task,
                byUid = state.viewerUid,
                today = state.today,
                now = Instant.now(),
                activeMembers = state.snapshot.household.memberUids,
            )
            if (after == task) return@launch
            repository.upsertTask(after)
            local.value = local.value.copy(
                undo = UndoableCompletion(before = task, wasRepeating = task.isRepeating),
            )
        }
    }

    /** Puts the task back exactly as it was, roster position included. */
    fun undoCompletion() {
        val pending = state.value.undo ?: return
        viewModelScope.launch {
            repository.upsertTask(pending.before)
            local.value = local.value.copy(undo = null)
        }
    }

    fun dismissUndo() {
        local.value = local.value.copy(undo = null)
    }

    // ---- assignment --------------------------------------------------------

    fun askToTake(task: Task, uid: String) = save(
        TaskAssignment.request(task, uid, state.value.viewerUid, Instant.now()),
    )

    fun acceptRequest(task: Task) = save(TaskAssignment.accept(task, state.value.viewerUid))

    fun declineRequest(task: Task) = save(TaskAssignment.decline(task, state.value.viewerUid))

    fun withdraw(task: Task, uid: String) = save(TaskAssignment.withdraw(task, uid))

    // ---- tags and filters --------------------------------------------------

    fun createTag(name: String, colorArgb: Long): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        // Two tags called "kitchen" would split every filter that used either.
        val existing = state.value.snapshot.tags
            .firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing.id

        val id = "tag_" + UUID.randomUUID().toString()
        viewModelScope.launch {
            repository.upsertTag(
                Tag(
                    id = id,
                    name = trimmed,
                    colorArgb = colorArgb,
                    position = state.value.snapshot.tags.size,
                ),
            )
        }
        return id
    }

    fun saveTag(tag: Tag) = viewModelScope.launch { repository.upsertTag(tag) }

    fun tagUsage(id: String): TagUsage = repository.tagUsage(id)

    fun deleteTag(id: String) = viewModelScope.launch { repository.deleteTag(id) }

    fun saveFilter(filter: TaskFilter) = viewModelScope.launch { repository.upsertFilter(filter) }

    fun deleteFilter(id: String) = viewModelScope.launch { repository.deleteFilter(id) }

    fun newFilterId(): String = "filter_" + UUID.randomUUID().toString()

    // ---- household ---------------------------------------------------------

    fun personTagId(uid: String): String = StarterData.personTagId(uid)

    fun addMember(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val used = state.value.snapshot.household.members.size
        viewModelScope.launch {
            repository.addMember(
                Member(
                    uid = UUID.randomUUID().toString(),
                    name = trimmed,
                    colorArgb = MEMBER_COLORS[used % MEMBER_COLORS.size],
                ),
            )
        }
    }

    fun updateMember(member: Member) = viewModelScope.launch { repository.updateMember(member) }

    fun removeMember(uid: String) = viewModelScope.launch { repository.removeMember(uid) }

    fun renameHousehold(name: String) = viewModelScope.launch { repository.renameHousehold(name) }

    fun exportJson(): String = repository.exportJson()

    /**
     * Replaces everything from an export.
     *
     * Returns false rather than throwing when the text isn't a valid export —
     * pasting the wrong thing should say so, not wipe the household.
     */
    suspend fun importJson(raw: String): Boolean = repository.importJson(raw)

    private companion object {
        /** Long enough to coalesce a burst of edits, short enough to feel instant. */
        const val ALARM_SYNC_DEBOUNCE_MS = 400L

        val MEMBER_COLORS = listOf(
            0xFF3D5A80, 0xFFEE6C4D, 0xFF6A994E, 0xFF9D4EDD,
            0xFF00897B, 0xFFC9184A, 0xFF7B9EA8, 0xFFB5651D,
        )
    }
}
