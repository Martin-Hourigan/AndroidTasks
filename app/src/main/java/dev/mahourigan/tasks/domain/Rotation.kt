@file:UseSerializers(
    dev.mahourigan.tasks.data.LocalDateSerializer::class,
    dev.mahourigan.tasks.data.LocalDateTimeSerializer::class,
    dev.mahourigan.tasks.data.InstantSerializer::class,
)

package dev.mahourigan.tasks.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Whose turn it is, and who is next.
 *
 * A roster is an ordered list of household members plus a pointer. Completing
 * the task moves the pointer on — regardless of who actually ticked it, because
 * doing someone else's chore should not earn them another one.
 */
@Serializable
data class Rotation(
    val order: List<String> = emptyList(),
    val index: Int = 0,
) {
    val currentAssignee: String?
        get() = order.getOrNull(index)

    /**
     * The roster after a completion, skipping anyone who has left the household.
     *
     * Returns this unchanged when nobody in the order is still a member — the
     * turn has nowhere to go, and silently reassigning to a departed flatmate
     * would be worse than leaving it put.
     */
    fun advance(activeMembers: Set<String>): Rotation {
        if (order.isEmpty()) return this
        for (step in 1..order.size) {
            val candidate = (index + step) % order.size
            if (order[candidate] in activeMembers) return copy(index = candidate)
        }
        return this
    }

    /**
     * The roster with [uid] taken out, keeping the turn on the same person where
     * that still means anything.
     */
    fun withoutMember(uid: String): Rotation {
        val position = order.indexOf(uid)
        if (position < 0) return this

        val remaining = order.filterNot { it == uid }
        if (remaining.isEmpty()) return Rotation()

        // Removing someone earlier in the list shifts everyone after them down
        // one; removing the person whose turn it is hands the turn to whoever
        // slides into the slot, wrapping if they were last.
        val shifted = if (position < index) index - 1 else index
        return Rotation(remaining, shifted.coerceIn(0, remaining.lastIndex))
    }

    fun withMember(uid: String): Rotation =
        if (uid in order) this else copy(order = order + uid)
}
