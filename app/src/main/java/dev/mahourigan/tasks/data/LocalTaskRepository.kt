package dev.mahourigan.tasks.data

import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskAssignment
import dev.mahourigan.tasks.domain.TaskFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The on-device store: one JSON file, held in memory, written on every change.
 *
 * A household's task list is a few hundred rows at the very most, so rewriting
 * the whole file is simpler and far less error-prone than tracking dirty rows,
 * and it makes the export and the store literally the same format.
 *
 * This is the piece that gets swapped for a shared, syncing store later; nothing
 * above [TaskRepository] has to change when it does.
 */
class LocalTaskRepository(
    private val file: File,
    private val scope: CoroutineScope,
    private val viewerName: String = "Me",
) : TaskRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val writeLock = Mutex()
    private val _snapshot = MutableStateFlow(HouseholdSnapshot())
    override val snapshot: StateFlow<HouseholdSnapshot> = _snapshot.asStateFlow()

    /**
     * Reads the file, seeding a fresh household when there isn't one.
     *
     * Must be awaited before the UI reads [snapshot], or the first frame renders
     * an empty household and then flickers.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        val stored = runCatching {
            if (file.exists()) json.decodeFromString<HouseholdSnapshot>(file.readText()) else null
        }.getOrNull()

        _snapshot.value = stored ?: StarterData.newHousehold(viewerName).first.also { seeded ->
            scope.launch { persist(seeded) }
        }
    }

    private suspend fun persist(snapshot: HouseholdSnapshot) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            // Written beside the real file and moved into place, so a crash
            // half-way through leaves the previous good copy rather than a
            // truncated one. Losing a task is bad; losing the lot is worse.
            val scratch = File(file.parentFile, file.name + ".tmp")
            scratch.parentFile?.mkdirs()
            scratch.writeText(json.encodeToString(snapshot))
            if (!scratch.renameTo(file)) {
                file.writeText(scratch.readText())
                scratch.delete()
            }
        }
    }

    private suspend fun mutate(block: (HouseholdSnapshot) -> HouseholdSnapshot) {
        val updated = block(_snapshot.value)
        _snapshot.value = updated
        persist(updated)
    }

    override suspend fun upsertTask(task: Task) = mutate { state ->
        val existing = state.tasks.indexOfFirst { it.id == task.id }
        state.copy(
            tasks = if (existing >= 0) {
                state.tasks.toMutableList().apply { this[existing] = task }
            } else {
                state.tasks + task
            },
        )
    }

    override suspend fun deleteTask(id: String) = mutate { state ->
        state.copy(tasks = state.tasks.filterNot { it.id == id })
    }

    override suspend fun upsertTag(tag: Tag) = mutate { state ->
        val existing = state.tags.indexOfFirst { it.id == tag.id }
        state.copy(
            tags = if (existing >= 0) {
                state.tags.toMutableList().apply { this[existing] = tag }
            } else {
                state.tags + tag
            },
        )
    }

    override suspend fun deleteTag(id: String) = mutate { state ->
        // Stripping the id everywhere is the whole job. Leaving it behind gives
        // saved filters that quietly match nothing and tasks carrying a chip
        // with no name.
        state.copy(
            tags = state.tags.filterNot { it.id == id },
            tasks = state.tasks.map { task ->
                if (id in task.tagIds) task.copy(tagIds = task.tagIds - id) else task
            },
            filters = state.filters.map { filter ->
                if (id in filter.tagIds) filter.copy(tagIds = filter.tagIds - id) else filter
            },
        )
    }

    override fun tagUsage(id: String): TagUsage {
        val state = _snapshot.value
        return TagUsage(
            taskCount = state.tasks.count { id in it.tagIds },
            filterCount = state.filters.count { id in it.tagIds },
        )
    }

    override suspend fun upsertFilter(filter: TaskFilter) = mutate { state ->
        val existing = state.filters.indexOfFirst { it.id == filter.id }
        state.copy(
            filters = if (existing >= 0) {
                state.filters.toMutableList().apply { this[existing] = filter }
            } else {
                state.filters + filter
            },
        )
    }

    override suspend fun deleteFilter(id: String) = mutate { state ->
        state.copy(filters = state.filters.filterNot { it.id == id })
    }

    override suspend fun addMember(member: Member) = mutate { state ->
        if (member.uid in state.household.memberUids) return@mutate state

        // Going from one person to two is the moment "Me", "Requests" and
        // "Unassigned" start meaning something, so they arrive then rather than
        // sitting empty on a solo home screen from the start. Only ones the user
        // hasn't already got — deleting a card should stay deleted.
        val newCards = if (state.household.members.size == 1) {
            StarterData.householdFilters(fromPosition = state.filters.size)
                .filter { candidate -> state.filters.none { it.id == candidate.id } }
        } else {
            emptyList()
        }

        state.copy(
            household = state.household.copy(members = state.household.members + member),
            // The person tag is created here rather than by the caller, so a
            // member can never exist without one to assign them by.
            tags = state.tags + StarterData.personTag(member, position = state.tags.size),
            filters = state.filters + newCards,
        )
    }

    override suspend fun updateMember(member: Member) = mutate { state ->
        state.copy(
            household = state.household.copy(
                members = state.household.members.map { if (it.uid == member.uid) member else it },
            ),
            // The person tag carries the member's name and colour, so it has to
            // move with them or the chip goes on saying "Person Two" after a rename.
            tags = state.tags.map { tag ->
                if (tag.personUid == member.uid) {
                    tag.copy(name = member.name, colorArgb = member.colorArgb)
                } else {
                    tag
                }
            },
        )
    }

    override suspend fun removeMember(uid: String) = mutate { state ->
        state.copy(
            household = state.household.copy(
                members = state.household.members.filterNot { it.uid == uid },
            ),
            tags = state.tags.filterNot { it.personUid == uid },
            tasks = state.tasks.map { task ->
                TaskAssignment.removeMember(task, uid)
                    .copy(tagIds = task.tagIds - StarterData.personTagId(uid))
            },
        )
    }

    override suspend fun renameHousehold(name: String) = mutate { state ->
        state.copy(household = state.household.copy(name = name))
    }

    override suspend fun replaceAll(snapshot: HouseholdSnapshot) = mutate { snapshot }

    fun exportJson(): String = json.encodeToString(_snapshot.value)

    /**
     * Replaces everything from an export, or returns false if [raw] isn't one.
     *
     * Refusing bad input matters more than the happy path here: this overwrites
     * the entire household, and pasting the wrong thing should be a message
     * rather than an empty app.
     */
    suspend fun importJson(raw: String): Boolean {
        val parsed = runCatching { json.decodeFromString<HouseholdSnapshot>(raw) }.getOrNull()
            ?: return false
        if (parsed.household.members.isEmpty()) return false
        replaceAll(parsed)
        return true
    }
}
