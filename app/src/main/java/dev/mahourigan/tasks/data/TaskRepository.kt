package dev.mahourigan.tasks.data

import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter
import kotlinx.coroutines.flow.StateFlow

/**
 * Where tasks live.
 *
 * The app is built against this rather than against a database, so the on-device
 * store can be swapped for a shared, syncing one without the domain logic or any
 * screen knowing it happened. That swap is the whole reason this interface
 * exists — everything above it already works offline, because offline is all
 * there is at the moment.
 */
interface TaskRepository {

    /** One consistent picture of everything, updated on every change. */
    val snapshot: StateFlow<HouseholdSnapshot>

    suspend fun upsertTask(task: Task)
    suspend fun deleteTask(id: String)

    suspend fun upsertTag(tag: Tag)

    /**
     * Removes [id] and strips it from every task and saved filter that referred
     * to it. Check [tagUsage] first if the user should be warned.
     */
    suspend fun deleteTag(id: String)

    fun tagUsage(id: String): TagUsage

    suspend fun upsertFilter(filter: TaskFilter)
    suspend fun deleteFilter(id: String)

    /** Adds a member and their person tag together, so the two cannot drift. */
    suspend fun addMember(member: Member)

    /** Renames a member, keeping their person tag in step. */
    suspend fun updateMember(member: Member)

    /** Removes a member, their person tag, their assignments and their turns. */
    suspend fun removeMember(uid: String)

    suspend fun renameHousehold(name: String)

    /** Wholesale replacement, for undo and for importing an export. */
    suspend fun replaceAll(snapshot: HouseholdSnapshot)
}
