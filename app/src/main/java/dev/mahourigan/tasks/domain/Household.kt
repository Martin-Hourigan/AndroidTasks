package dev.mahourigan.tasks.domain

import dev.mahourigan.tasks.domain.sync.Stamp
import kotlinx.serialization.Serializable

/**
 * Someone in the household.
 *
 * [uid] is stable and never displayed; the name is. Keeping them separate means
 * renaming yourself doesn't orphan every task you're on.
 */
@Serializable
data class Member(
    val uid: String = "",
    val name: String = "",
    val colorArgb: Long = 0xFF6B7280,

    val stamp: Stamp = Stamp.UNSET,
)

/**
 * A couple, a sharehouse of five, or one person on their own.
 *
 * There is no separate "personal" mode: your private tasks live in a household
 * with one member, so nothing in the app needs a second code path for the
 * unshared case.
 */
@Serializable
data class Household(
    val id: String = "",
    val name: String = "",
    val members: List<Member> = emptyList(),

    /** The name is the only thing here that gets edited, so it is the only clock. */
    val nameStamp: Stamp = Stamp.UNSET,
) {
    val memberUids: Set<String> get() = members.map { it.uid }.toSet()

    fun member(uid: String): Member? = members.firstOrNull { it.uid == uid }

    fun displayName(uid: String): String = member(uid)?.name ?: "Someone"
}
