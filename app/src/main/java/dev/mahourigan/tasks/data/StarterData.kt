package dev.mahourigan.tasks.data

import dev.mahourigan.tasks.domain.DueWindow
import dev.mahourigan.tasks.domain.Household
import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.TagKind
import dev.mahourigan.tasks.domain.TaskFilter
import dev.mahourigan.tasks.domain.TaskSort
import java.util.UUID

/**
 * What a brand new household starts with.
 *
 * An empty app with an empty tag list gives you nothing to press and no idea
 * what the tags are for. These are a worked example you can rename or delete —
 * enough to show that a saved filter and a list are the same thing.
 */
object StarterData {

    /** Fixed ids, so the shipped filters below can refer to them. */
    const val TAG_KITCHEN = "tag_kitchen"
    const val TAG_LAUNDRY = "tag_laundry"
    const val TAG_PURCHASE = "tag_purchase"
    const val TAG_URGENT = "tag_urgent"
    const val TAG_ERRAND = "tag_errand"
    const val TAG_INDOOR = "tag_indoor"
    const val TAG_OUTDOOR = "tag_outdoor"
    const val TAG_RAINY = "tag_rainy"

    fun tags(): List<Tag> = listOf(
        Tag(TAG_KITCHEN, "kitchen", 0xFFE07A5F, TagKind.PLAIN, position = 0),
        Tag(TAG_LAUNDRY, "laundry", 0xFF81B29A, TagKind.PLAIN, position = 1),
        Tag(TAG_PURCHASE, "purchase", 0xFFF2CC8F, TagKind.PLAIN, position = 2),
        Tag(TAG_URGENT, "urgent", 0xFFD64550, TagKind.PLAIN, position = 3),
        Tag(TAG_ERRAND, "errand", 0xFF7B9EA8, TagKind.PLAIN, position = 4),
        // Weather tags start as ordinary labels. Once the home grid can read a
        // forecast, the matching card floats to the top on its own.
        Tag(TAG_INDOOR, "indoor", 0xFF9A8C98, TagKind.PLAIN, position = 5),
        Tag(TAG_OUTDOOR, "outdoor", 0xFF6A994E, TagKind.PLAIN, position = 6),
        Tag(TAG_RAINY, "rainy day", 0xFF577590, TagKind.PLAIN, position = 7),
    )

    /**
     * The home grid for a household of one — which is how everybody starts, and
     * how plenty of people will stay.
     *
     * Deliberately excludes the cards that only mean something with other people
     * in the house: on your own, "Me" and "Unassigned" both just mean
     * "everything", and "Requests" can never fill because nobody can ask you
     * anything. Three dead cards on the home screen is a worse first impression
     * than three fewer.
     *
     * These are ordinary saved filters, not special cases — which is the point.
     * Deleting Urgent works exactly like deleting one you made yourself.
     */
    fun soloFilters(): List<TaskFilter> = listOf(
        TaskFilter(
            id = "filter_today",
            name = "Today",
            dueWindow = DueWindow.DUE_OR_OVERDUE,
            sort = TaskSort.DUE_DATE,
            position = 0,
        ),
        TaskFilter(
            id = "filter_urgent",
            name = "Urgent",
            tagIds = setOf(TAG_URGENT),
            position = 1,
        ),
        TaskFilter(
            id = "filter_upcoming",
            name = "Upcoming",
            dueWindow = DueWindow.UPCOMING,
            position = 2,
        ),
        TaskFilter(
            id = "filter_someday",
            name = "Someday",
            // No date on it — the pile you get to eventually, kept out of Today.
            dueWindow = DueWindow.NO_DUE,
            position = 3,
        ),
        TaskFilter(
            id = "filter_indoor",
            name = "Indoor",
            tagIds = setOf(TAG_INDOOR, TAG_RAINY),
            // Either one will do: both mean "something to do while it buckets down".
            matchAll = false,
            position = 4,
        ),
        TaskFilter(
            id = "filter_outdoor",
            name = "Outdoor",
            tagIds = setOf(TAG_OUTDOOR),
            position = 5,
        ),
    )

    /**
     * The cards that start earning their place the moment somebody else joins,
     * added when the second member arrives rather than sitting empty until then.
     */
    fun householdFilters(fromPosition: Int): List<TaskFilter> = listOf(
        TaskFilter(
            id = "filter_me",
            name = "Me",
            assignedToViewer = true,
            sort = TaskSort.DUE_DATE,
            position = fromPosition,
        ),
        TaskFilter(
            id = "filter_requests",
            name = "Requests",
            pendingForViewer = true,
            position = fromPosition + 1,
        ),
        TaskFilter(
            id = "filter_unassigned",
            name = "Unassigned",
            unassignedOnly = true,
            position = fromPosition + 2,
        ),
    )

    /**
     * A household of one, which is what everybody starts as.
     *
     * Sharing later means adding members, not switching mode — there is no
     * separate personal code path to grow out of.
     */
    fun newHousehold(viewerName: String): Pair<HouseholdSnapshot, String> {
        val uid = UUID.randomUUID().toString()
        val member = Member(uid = uid, name = viewerName, colorArgb = 0xFF3D5A80)
        val snapshot = HouseholdSnapshot(
            household = Household(
                id = UUID.randomUUID().toString(),
                name = "Home",
                members = listOf(member),
            ),
            viewerUid = uid,
            tags = tags() + personTag(member, position = tags().size),
            filters = soloFilters(),
        )
        return snapshot to uid
    }

    /** Every member gets one of these, so assigning is the same gesture as tagging. */
    fun personTag(member: Member, position: Int): Tag = Tag(
        id = personTagId(member.uid),
        name = member.name,
        colorArgb = member.colorArgb,
        kind = TagKind.PERSON,
        personUid = member.uid,
        position = position,
    )

    fun personTagId(uid: String): String = "person_$uid"
}
