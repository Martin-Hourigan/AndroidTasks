package dev.mahourigan.tasks.domain

import dev.mahourigan.tasks.domain.sync.Stamp
import kotlinx.serialization.Serializable

enum class TagKind {
    /** An ordinary label the user made up: kitchen, laundry, purchase, urgent. */
    PLAIN,

    /**
     * One household member. Created and removed with membership, never by hand,
     * so assigning a task is the same gesture as tagging it.
     */
    PERSON,
}

/**
 * A label on a task — and, because a saved filter is just a set of these, the
 * thing the whole app is organised around.
 *
 * The smart tags (`due`, `remind`, `repeating`, `rotating`) are deliberately not
 * here; they are a fixed enum, which is what stops anyone renaming or deleting
 * them. See [SmartTag].
 */
@Serializable
data class Tag(
    val id: String = "",
    val name: String = "",
    val colorArgb: Long = 0xFF6B7280,
    val kind: TagKind = TagKind.PLAIN,

    /** Set only when [kind] is [TagKind.PERSON]. */
    val personUid: String? = null,

    val position: Int = 0,

    val stamp: Stamp = Stamp.UNSET,
) {
    val isEditable: Boolean get() = kind == TagKind.PLAIN
}
