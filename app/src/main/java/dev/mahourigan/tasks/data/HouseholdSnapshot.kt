package dev.mahourigan.tasks.data

import dev.mahourigan.tasks.domain.Household
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter
import dev.mahourigan.tasks.domain.sync.Deleted
import dev.mahourigan.tasks.domain.sync.Stamp
import dev.mahourigan.tasks.domain.sync.Tombstone
import kotlinx.serialization.Serializable

/**
 * Everything the app knows, in one value.
 *
 * A whole-snapshot model rather than a set of tables: a household's task list is
 * small enough that rewriting it is cheap, and it means the UI reads one
 * consistent picture instead of stitching four streams together and rendering a
 * half-updated state in between.
 *
 * It is also exactly the shape of the export file.
 */
@Serializable
data class HouseholdSnapshot(
    /** Bumped when the stored shape changes, so old files can be migrated. */
    val version: Int = CURRENT_VERSION,

    val household: Household = Household(),

    /** Whoever is holding this phone. */
    val viewerUid: String = "",

    val tasks: List<Task> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filters: List<TaskFilter> = emptyList(),

    /**
     * Everything that has been deleted, and when.
     *
     * Held here rather than as a flag on each record because a deletion has to
     * outlive the record it refers to — see [Tombstone]. The lists above stay
     * live-only, so every screen reading them is unaffected by any of this.
     */
    val tombstones: List<Tombstone> = emptyList(),
) {
    fun task(id: String): Task? = tasks.firstOrNull { it.id == id }
    fun tag(id: String): Tag? = tags.firstOrNull { it.id == id }
    fun filter(id: String): TaskFilter? = filters.firstOrNull { it.id == id }

    /**
     * Everything that could change when a reminder should fire, and nothing else.
     *
     * Rescheduling alarms is expensive, and almost every edit — retitling a task,
     * adding a tag — cannot possibly affect one. Comparing this instead of the
     * whole snapshot keeps that work off the common path.
     */
    fun alarmSignature(): List<String> = tasks.map { task ->
        listOf(
            task.id,
            task.remindAt?.toString().orEmpty(),
            task.completedAt?.toString().orEmpty(),
            task.effectiveAssignees.sorted().joinToString(","),
        ).joinToString("|")
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * What a tag is holding up, so deleting one can say so first.
 *
 * Firestore has no cascading delete and neither does a JSON file, so a tag
 * removed carelessly leaves dead ids on tasks and saved filters that silently
 * match nothing.
 */
data class TagUsage(val taskCount: Int, val filterCount: Int) {
    val isUsed: Boolean get() = taskCount > 0 || filterCount > 0
}
