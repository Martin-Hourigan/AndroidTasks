package dev.mahourigan.tasks.domain.sync

import dev.mahourigan.tasks.data.HouseholdSnapshot
import dev.mahourigan.tasks.domain.Household
import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Rotation
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Two phones meeting.
 *
 * The interesting failures in sync are never crashes. They are one flatmate's
 * edit quietly not being there, or a chore whose turn has skipped somebody, or
 * a deleted task coming back next week. So most of what follows is about what
 * *survives* a merge rather than what it returns.
 *
 * The three algebraic tests at the bottom are the ones worth keeping longest:
 * they hold for any data, so they catch whole classes of mistake that no
 * hand-written scenario would think to try.
 */
class MergeTest {

    private fun stamp(millis: Long, device: String = "a") = Stamp(millis, 0, device)

    private val bins = Task(
        id = "bins",
        title = "Take the bins out",
        createdAt = Instant.ofEpochMilli(1_000),
        dueOn = LocalDate.of(2026, 9, 14),
        rotation = Rotation(order = listOf("u1", "u2"), index = 0),
        defStamp = stamp(1_000),
        schedStamp = stamp(1_000),
        assignStamp = stamp(1_000),
    )

    private fun snapshot(
        viewer: String = "u1",
        tasks: List<Task> = emptyList(),
        tags: List<Tag> = emptyList(),
        filters: List<TaskFilter> = emptyList(),
        household: Household = Household(id = "h", name = "Home", members = emptyList()),
        tombstones: List<Tombstone> = emptyList(),
    ) = HouseholdSnapshot(
        household = household,
        viewerUid = viewer,
        tasks = tasks,
        tags = tags,
        filters = filters,
        tombstones = tombstones,
    )

    private fun grave(kind: Deleted, id: String, millis: Long, device: String = "b") =
        Tombstone(kind, id, stamp(millis, device))

    // ---- the case the three clocks exist for --------------------------------

    @Test
    fun `retitling on one phone and ticking on the other keeps both`() {
        // The whole reason a task carries three stamps. Under one stamp these
        // are competing claims and exactly one survives; they are not
        // competing claims.
        val renamed = bins.copy(title = "Bins (green lid)", defStamp = stamp(2_000, "a"))
        val ticked = bins.copy(
            dueOn = LocalDate.of(2026, 9, 21),
            lastCompletedOn = LocalDate.of(2026, 9, 14),
            completedByUid = "u1",
            rotation = Rotation(order = listOf("u1", "u2"), index = 1),
            schedStamp = stamp(3_000, "b"),
        )

        val merged = Merge.snapshots(snapshot(tasks = listOf(renamed)), snapshot(tasks = listOf(ticked)))
            .tasks.single()

        assertEquals("Bins (green lid)", merged.title)
        assertEquals(LocalDate.of(2026, 9, 21), merged.dueOn)
        assertEquals(1, merged.rotation?.index)
        assertEquals("u1", merged.completedByUid)
    }

    @Test
    fun `the schedule cluster moves as one piece`() {
        // Taking the new due date from one phone and the old roster position
        // from the other would be a state neither device was ever in, and it
        // is how a rotation loses somebody's turn.
        val ticked = bins.copy(
            dueOn = LocalDate.of(2026, 9, 21),
            rotation = Rotation(order = listOf("u1", "u2"), index = 1),
            lastCompletedOn = LocalDate.of(2026, 9, 14),
            schedStamp = stamp(3_000, "b"),
        )
        val merged = Merge.snapshots(snapshot(tasks = listOf(bins)), snapshot(tasks = listOf(ticked)))
            .tasks.single()

        assertEquals(LocalDate.of(2026, 9, 21), merged.dueOn)
        assertEquals(1, merged.rotation?.index)
        assertEquals(LocalDate.of(2026, 9, 14), merged.lastCompletedOn)
    }

    @Test
    fun `assignment does not lose to a title edit`() {
        val renamed = bins.copy(title = "Bins", defStamp = stamp(9_000, "a"))
        val accepted = bins.copy(assigneeUids = setOf("u2"), assignStamp = stamp(2_000, "b"))

        val merged = Merge.snapshots(snapshot(tasks = listOf(renamed)), snapshot(tasks = listOf(accepted)))
            .tasks.single()

        assertEquals("Bins", merged.title)
        assertEquals(setOf("u2"), merged.assigneeUids)
    }

    // ---- what happens to the losing side -----------------------------------

    @Test
    fun `the newer edit within a cluster wins whichever phone it came from`() {
        val mine = bins.copy(title = "Mine", defStamp = stamp(5_000, "a"))
        val theirs = bins.copy(title = "Theirs", defStamp = stamp(6_000, "b"))

        assertEquals(
            "Theirs",
            Merge.snapshots(snapshot(tasks = listOf(mine)), snapshot(tasks = listOf(theirs)))
                .tasks.single().title,
        )
        assertEquals(
            "Theirs",
            Merge.snapshots(snapshot(tasks = listOf(theirs)), snapshot(tasks = listOf(mine)))
                .tasks.single().title,
        )
    }

    @Test
    fun `the merged record carries the newer stamp so it stops losing`() {
        // Without this the merge result still looks old, and the next peer to
        // arrive with a stale copy overwrites the edit we just accepted.
        val mine = bins.copy(title = "Mine", defStamp = stamp(5_000, "a"))
        val theirs = bins.copy(title = "Theirs", defStamp = stamp(6_000, "b"))
        val merged = Merge.snapshots(snapshot(tasks = listOf(mine)), snapshot(tasks = listOf(theirs)))
            .tasks.single()

        assertEquals(stamp(6_000, "b"), merged.defStamp)
    }

    @Test
    fun `creation time never moves forward`() {
        val recreated = bins.copy(createdAt = Instant.ofEpochMilli(9_999))
        val merged = Merge.snapshots(snapshot(tasks = listOf(bins)), snapshot(tasks = listOf(recreated)))
            .tasks.single()
        assertEquals(Instant.ofEpochMilli(1_000), merged.createdAt)
    }

    // ---- deletion -----------------------------------------------------------

    @Test
    fun `a task only one phone has still arrives`() {
        val extra = bins.copy(id = "dishes", title = "Dishes")
        val merged = Merge.snapshots(snapshot(), snapshot(tasks = listOf(extra)))
        assertEquals(listOf("dishes"), merged.tasks.map { it.id })
    }

    @Test
    fun `a delete travels rather than the record simply being absent`() {
        // An absence cannot propagate: a peer that never heard of the deletion
        // would send the task straight back.
        val theirs = snapshot(tombstones = listOf(grave(Deleted.TASK, "bins", 4_000)))
        val merged = Merge.snapshots(snapshot(tasks = listOf(bins)), theirs)
        assertTrue("the deleted task came back", merged.tasks.isEmpty())
    }

    @Test
    fun `an edit after a delete brings the task back`() {
        // Delete-wins-if-newer, not delete-wins-always. Deleting it last week
        // must not undo an edit made since.
        val theirs = snapshot(tombstones = listOf(grave(Deleted.TASK, "bins", 2_000)))
        val edited = bins.copy(title = "Bins, actually", defStamp = stamp(5_000, "a"))
        val merged = Merge.snapshots(snapshot(tasks = listOf(edited)), theirs)
        assertEquals("Bins, actually", merged.tasks.single().title)
    }

    @Test
    fun `deleting while the other person edits an older version wins`() {
        val edited = bins.copy(title = "stale", defStamp = stamp(2_000, "a"))
        val theirs = snapshot(tombstones = listOf(grave(Deleted.TASK, "bins", 6_000)))
        assertTrue(Merge.snapshots(snapshot(tasks = listOf(edited)), theirs).tasks.isEmpty())
    }

    @Test
    fun `a departed member is a tombstone, not a gap`() {
        val household = Household(
            id = "h", name = "Home",
            members = listOf(Member(uid = "u1", name = "One", stamp = stamp(1_000))),
        )
        val theirs = snapshot(
            household = household,
            tombstones = listOf(grave(Deleted.MEMBER, "u1", 5_000)),
        )
        val merged = Merge.snapshots(snapshot(household = household), theirs)
        assertTrue(merged.household.members.isEmpty())
    }

    // ---- the viewer, which must not travel ----------------------------------

    @Test
    fun `the viewer stays whoever is holding this phone`() {
        // Merging this would make each phone think it was the other person:
        // your tasks would show as your flatmate's, and Requests would fill
        // with your own requests.
        val merged = Merge.snapshots(snapshot(viewer = "u1"), snapshot(viewer = "u2"))
        assertEquals("u1", merged.viewerUid)
    }

    // ---- the household ------------------------------------------------------

    @Test
    fun `members from both phones survive`() {
        val a = Household(id = "h", name = "Home", members = listOf(Member("u1", "One", stamp = stamp(1))))
        val b = Household(id = "h", name = "Home", members = listOf(Member("u2", "Two", stamp = stamp(1))))
        val merged = Merge.snapshots(snapshot(household = a), snapshot(household = b))
        assertEquals(listOf("u1", "u2"), merged.household.members.map { it.uid })
    }

    @Test
    fun `renaming the household follows its own clock`() {
        val a = Household(id = "h", name = "Ours", nameStamp = stamp(9_000, "a"))
        val b = Household(id = "h", name = "Theirs", nameStamp = stamp(2_000, "b"))
        assertEquals("Ours", Merge.snapshots(snapshot(household = a), snapshot(household = b)).household.name)
    }

    @Test
    fun `tags and filters merge by id like everything else`() {
        val mine = snapshot(
            tags = listOf(Tag(id = "t1", name = "kitchen", stamp = stamp(1_000))),
            filters = listOf(TaskFilter(id = "f1", name = "Mine", stamp = stamp(1_000))),
        )
        val theirs = snapshot(
            tags = listOf(Tag(id = "t1", name = "Kitchen", stamp = stamp(2_000, "b"))),
            filters = listOf(TaskFilter(id = "f2", name = "Theirs", stamp = stamp(1_000, "b"))),
        )
        val merged = Merge.snapshots(mine, theirs)
        assertEquals("Kitchen", merged.tags.single().name)
        assertEquals(listOf("f1", "f2"), merged.filters.map { it.id })
    }

    // ---- the properties that matter most ------------------------------------

    /** Everything except the viewer, which is local by design. */
    private fun comparable(s: HouseholdSnapshot) = s.copy(viewerUid = "")

    private val a = snapshot(
        viewer = "u1",
        tasks = listOf(
            bins.copy(title = "A's bins", defStamp = stamp(5_000, "a")),
            bins.copy(id = "dishes", title = "Dishes", defStamp = stamp(2_000, "a")),
        ),
        tags = listOf(Tag(id = "t1", name = "kitchen", stamp = stamp(3_000, "a"))),
        household = Household(id = "h", name = "A", nameStamp = stamp(4_000, "a"), members = listOf(Member("u1", "One", stamp = stamp(1)))),
    )
    private val b = snapshot(
        viewer = "u2",
        tasks = listOf(
            bins.copy(dueOn = LocalDate.of(2026, 10, 1), schedStamp = stamp(7_000, "b")),
            bins.copy(id = "rent", title = "Rent", defStamp = stamp(6_000, "b")),
        ),
        tags = listOf(Tag(id = "t1", name = "Kitchen", stamp = stamp(1_000, "b"))),
        household = Household(id = "h", name = "B", nameStamp = stamp(2_000, "b"), members = listOf(Member("u2", "Two", stamp = stamp(1)))),
    )
    private val c = snapshot(
        viewer = "u3",
        tasks = listOf(bins.copy(id = "post", title = "Post", defStamp = stamp(1, "c"))),
        household = Household(id = "h", name = "C", nameStamp = stamp(1, "c")),
        tombstones = listOf(grave(Deleted.TASK, "bins", 9_000, "c")),
    )

    @Test
    fun `merging is commutative, so it does not matter whose phone runs it`() {
        // Both devices merge locally with the roles swapped. If the answer
        // depended on which side you stood on they would never converge, and
        // each would go on believing the other was wrong.
        assertEquals(
            comparable(Merge.snapshots(a, b)),
            comparable(Merge.snapshots(b, a)),
        )
    }

    @Test
    fun `merging is associative, so three phones can meet in any order`() {
        assertEquals(
            comparable(Merge.snapshots(Merge.snapshots(a, b), c)),
            comparable(Merge.snapshots(a, Merge.snapshots(b, c))),
        )
    }

    @Test
    fun `merging is idempotent, so a retry costs nothing`() {
        // Bluetooth drops. Being able to just run it again, and again, without
        // wondering what state it left behind, is most of what makes the
        // transport layer simple.
        val once = Merge.snapshots(a, b)
        assertEquals(comparable(once), comparable(Merge.snapshots(once, b)))
        assertEquals(comparable(once), comparable(Merge.snapshots(once, once)))
    }

    @Test
    fun `merging with an empty snapshot changes nothing`() {
        // A phone that has just been factory reset and re-paired must receive
        // the household, not wipe it.
        assertEquals(comparable(a), comparable(Merge.snapshots(a, snapshot(viewer = "u9", household = Household(id = "h")))))
    }

    @Test
    fun `every stamp in a snapshot is offered to the clock`() {
        // Miss one and our next edit gets stamped in its past and is silently
        // discarded. The list is what StampSource.observeAll consumes.
        val stamps = Merge.stampsIn(b)
        assertTrue(stamp(7_000, "b") in stamps)
        assertTrue(stamp(6_000, "b") in stamps)
        assertTrue(stamp(2_000, "b") in stamps)
    }

    @Test
    fun `a tombstone stamp is offered to the clock too`() {
        assertTrue(stamp(9_000, "c") in Merge.stampsIn(c))
    }

    @Test
    fun `a tombstone outlives the merge that applied it`() {
        // The bug the associativity test found. A tombstone that is consumed
        // and then dropped works exactly once; the third phone brings the task
        // straight back.
        val merged = Merge.snapshots(snapshot(tasks = listOf(bins)), c)
        assertTrue(merged.tasks.none { it.id == "bins" })
        assertTrue(
            "the tombstone was consumed instead of kept",
            merged.tombstones.any { it.kind == Deleted.TASK && it.id == "bins" },
        )
    }

    @Test
    fun `the merged list order does not depend on arrival order`() {
        // Two phones holding the same set in a different order are the same
        // household. Anything else makes convergence tests flake and makes the
        // list jump around after a sync.
        assertEquals(
            Merge.snapshots(a, b).tasks.map { it.id },
            Merge.snapshots(b, a).tasks.map { it.id },
        )
    }

    @Test
    fun `a deleted task stays deleted through a later merge with a stale peer`() {
        val afterDelete = Merge.snapshots(a, c)
        assertNull(afterDelete.tasks.firstOrNull { it.id == "bins" })
        // b still has its own copy of the bins, edited before the delete.
        val again = Merge.snapshots(afterDelete, b)
        assertNull("the bins came back from a stale peer", again.tasks.firstOrNull { it.id == "bins" })
        assertNotNull(again.tasks.firstOrNull { it.id == "rent" })
    }
}
