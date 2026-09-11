package dev.mahourigan.tasks.domain.sync

import kotlinx.serialization.Serializable

/** What kind of thing a [Tombstone] is for. */
@Serializable
enum class Deleted { TASK, TAG, FILTER, MEMBER }

/**
 * A record of something that used to exist.
 *
 * Deletions have to be *stated*, because an absence cannot travel. If deleting
 * a task simply removed it, the next phone to sync — which never heard about
 * the deletion — would send the task straight back, and the bins would
 * reappear next week with no explanation.
 *
 * These are kept separately from the records they refer to, which is the
 * correction to a first attempt at this. Putting a `deletedAt` field on the
 * task itself and then filtering deleted tasks out of the merged list looked
 * equivalent and was not: filtering discarded the very fact that had to
 * propagate. The tombstone survived exactly one merge and then vanished, so a
 * third phone with a stale copy resurrected the task — and the associativity
 * test failed, because the answer then depended on which two phones met first.
 *
 * They accumulate. A household deleting a task a week reaches a few hundred
 * over years, which is nothing next to the task list itself, so nothing prunes
 * them yet. Doing so safely means knowing every peer has seen the deletion,
 * which needs per-peer sync watermarks — worth having eventually, not now.
 */
@Serializable
data class Tombstone(
    val kind: Deleted,
    val id: String,
    /** When the deletion happened, on the clock everything else uses. */
    val at: Stamp,
) {
    val key: String get() = "$kind/$id"
}
