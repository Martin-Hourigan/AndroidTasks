package dev.mahourigan.tasks.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The built-in tags that carry settings rather than just labelling.
 *
 * Tagging is the only gesture for configuring a task — there is no separate
 * "make this repeat" button elsewhere in the UI. Most tags are inert; these four
 * open their settings when you apply them, and their chips display the value
 * ("Due Fri 12") instead of the tag name.
 *
 * They are an enum rather than documents, so "you can't rename or delete
 * `repeating`" falls out of the design instead of needing a guard.
 */
enum class SmartTag {
    DUE,
    REMIND,
    REPEATING,
    ROTATING,
}

/**
 * Applying and removing smart tags, including the stash that makes removal
 * reversible.
 */
object SmartTags {

    fun activeOn(task: Task): Set<SmartTag> = buildSet {
        if (task.dueOn != null) add(SmartTag.DUE)
        if (task.remindAt != null) add(SmartTag.REMIND)
        if (task.recurrence != null) add(SmartTag.REPEATING)
        if (task.rotation != null && task.rotation.order.isNotEmpty()) add(SmartTag.ROTATING)
    }

    /**
     * [task] with [tag] taken off, its value parked in the stash.
     *
     * The live columns go null so every query stays a trivial "is it set"; the
     * rare restore-after-mis-tap path pays the cost instead of every read.
     */
    fun remove(task: Task, tag: SmartTag): Task {
        val value = serialize(task, tag) ?: return clear(task, tag)
        return clear(task, tag).copy(stashed = task.stashed + (tag to value))
    }

    /**
     * [task] with [tag] put back, restoring whatever it was configured with
     * before. Returns the task unchanged when there is nothing stashed — the
     * caller then prompts for fresh settings.
     */
    fun restore(task: Task, tag: SmartTag): Task {
        val value = task.stashed[tag] ?: return task
        val restored = deserialize(task, tag, value) ?: return task
        return restored.copy(stashed = task.stashed - tag)
    }

    private fun clear(task: Task, tag: SmartTag): Task = when (tag) {
        SmartTag.DUE -> task.copy(dueOn = null)
        SmartTag.REMIND -> task.copy(remindAt = null)
        SmartTag.REPEATING -> task.copy(recurrence = null)
        SmartTag.ROTATING -> task.copy(rotation = null)
    }

    private fun serialize(task: Task, tag: SmartTag): String? = when (tag) {
        SmartTag.DUE -> task.dueOn?.toString()
        SmartTag.REMIND -> task.remindAt?.toString()
        SmartTag.REPEATING -> task.recurrence?.serialize()
        SmartTag.ROTATING -> task.rotation
            ?.takeIf { it.order.isNotEmpty() }
            ?.let { "${it.index}|${it.order.joinToString(",")}" }
    }

    private fun deserialize(task: Task, tag: SmartTag, value: String): Task? = when (tag) {
        SmartTag.DUE -> runCatching { task.copy(dueOn = LocalDate.parse(value)) }.getOrNull()
        SmartTag.REMIND -> runCatching { task.copy(remindAt = LocalDateTime.parse(value)) }.getOrNull()
        SmartTag.REPEATING -> Recurrence.parse(value)?.let { task.copy(recurrence = it) }
        SmartTag.ROTATING -> {
            val index = value.substringBefore('|').toIntOrNull()
            val order = value.substringAfter('|', "").split(",").filter { it.isNotBlank() }
            if (index == null || order.isEmpty()) null
            else task.copy(rotation = Rotation(order, index.coerceIn(0, order.lastIndex)))
        }
    }
}
