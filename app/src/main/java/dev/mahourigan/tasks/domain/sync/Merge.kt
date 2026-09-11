package dev.mahourigan.tasks.domain.sync

import dev.mahourigan.tasks.data.HouseholdSnapshot
import dev.mahourigan.tasks.domain.Household
import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Tag
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter

/**
 * Two phones' pictures of the household, reconciled into one.
 *
 * Everything here is a pure function of its two inputs, which is the only
 * reason it is testable at all: the alternative is two handsets, a Bluetooth
 * stack and a stopwatch.
 *
 * **The three properties that make sync work**, each pinned by a test:
 *
 * - *Commutative.* `merge(a, b)` equals `merge(b, a)`. Both phones run the
 *   merge locally with the roles swapped, so if the result depended on which
 *   side you were standing on they would disagree forever, each convinced the
 *   other was wrong.
 * - *Associative.* Three phones can meet in any order and land in the same
 *   place. Without it, a household of three converges or doesn't depending on
 *   who happened to be in the kitchen first — and that is precisely the test
 *   that caught the tombstone bug described in [Tombstone].
 * - *Idempotent.* `merge(a, a)` is `a`. Syncing twice does nothing, so a
 *   dropped connection can simply be retried. Over Bluetooth that is worth a
 *   great deal.
 *
 * The one deliberate exception is [HouseholdSnapshot.viewerUid], which means
 * "whoever is holding this phone" and must never travel. Merging it would make
 * each device believe it was the other person: every task of yours would show
 * as your flatmate's, and Requests would fill up with your own requests. It is
 * always taken from the local side, which is why the commutativity test
 * compares everything else.
 */
object Merge {

    /**
     * [local] and [remote] reconciled, from [local]'s point of view.
     *
     * Both devices call this with their own snapshot as [local]; the results
     * agree on everything but the viewer, by construction.
     */
    fun snapshots(local: HouseholdSnapshot, remote: HouseholdSnapshot): HouseholdSnapshot {
        val graves = tombstones(local.tombstones, remote.tombstones)

        return local.copy(
            household = households(local.household, remote.household, graves),
            tasks = merged(local.tasks, remote.tasks, Task::id, ::reconcileTask)
                .filterNot { buried(graves, Deleted.TASK, it.id, it.newestStamp) },
            tags = merged(local.tags, remote.tags, Tag::id, ::reconcileTag)
                .filterNot { buried(graves, Deleted.TAG, it.id, it.stamp) },
            filters = merged(local.filters, remote.filters, TaskFilter::id, ::reconcileFilter)
                .filterNot { buried(graves, Deleted.FILTER, it.id, it.stamp) },
            tombstones = graves.values.sortedBy { it.key },
            // Deliberately not merged: see the class comment.
            viewerUid = local.viewerUid,
            version = maxOf(local.version, remote.version),
        )
    }

    /** Every stamp in a snapshot, for [StampSource.observeAll] before use. */
    fun stampsIn(snapshot: HouseholdSnapshot): List<Stamp> = buildList {
        add(snapshot.household.nameStamp)
        snapshot.household.members.forEach { add(it.stamp) }
        snapshot.tasks.forEach { add(it.defStamp); add(it.schedStamp); add(it.assignStamp) }
        snapshot.tags.forEach { add(it.stamp) }
        snapshot.filters.forEach { add(it.stamp) }
        snapshot.tombstones.forEach { add(it.at) }
    }

    // ---- tombstones --------------------------------------------------------

    /**
     * Every deletion either side knows about, keeping the later one per record.
     *
     * These are *not* filtered against the live lists and never dropped. That
     * was the bug: a tombstone that survives one merge and then disappears
     * lets the third phone resurrect the record.
     */
    private fun tombstones(a: List<Tombstone>, b: List<Tombstone>): Map<String, Tombstone> {
        val out = LinkedHashMap<String, Tombstone>()
        (a + b).forEach { stone ->
            val existing = out[stone.key]
            if (existing == null || stone.at > existing.at) out[stone.key] = stone
        }
        return out
    }

    /**
     * Whether a record has been deleted more recently than it was edited.
     *
     * Delete-wins-if-newer rather than delete-wins-always: deleting a task
     * while someone is still editing it should win, but deleting it last week
     * must not undo an edit made since.
     */
    private fun buried(graves: Map<String, Tombstone>, kind: Deleted, id: String, edited: Stamp): Boolean {
        val stone = graves["$kind/$id"] ?: return false
        return stone.at > edited
    }

    private val Task.newestStamp: Stamp get() = maxOf(defStamp, schedStamp, assignStamp)

    // ---- one collection ----------------------------------------------------

    /**
     * Union by id, reconciling whatever is on both sides.
     *
     * Sorted by id, and not for tidiness: two phones holding the same set in a
     * different order are the same household, so an order that depended on
     * arrival order would make convergence untestable and make the list jump
     * about after every sync.
     */
    private fun <T> merged(
        local: List<T>,
        remote: List<T>,
        id: (T) -> String,
        reconcile: (T, T) -> T,
    ): List<T> {
        val byKey = LinkedHashMap<String, T>()
        local.forEach { byKey[id(it)] = it }
        remote.forEach { incoming ->
            val key = id(incoming)
            val mine = byKey[key]
            byKey[key] = if (mine == null) incoming else reconcile(mine, incoming)
        }
        return byKey.values.sortedBy(id)
    }

    // ---- tasks -------------------------------------------------------------

    /**
     * One task from two.
     *
     * Each of the three clusters is taken whole from whichever side stamped it
     * last, independently of the others — so retitling the bins on one phone
     * and ticking them on the other keeps both, which a single stamp could not.
     */
    private fun reconcileTask(a: Task, b: Task): Task {
        val def = if (a.defStamp >= b.defStamp) a else b
        val sched = if (a.schedStamp >= b.schedStamp) a else b
        val assign = if (a.assignStamp >= b.assignStamp) a else b

        return Task(
            id = a.id,
            // The earlier creation is the true one. Nothing edits this, but a
            // record recreated by a peer that missed the original would
            // otherwise drag it forward.
            createdAt = minOf(a.createdAt, b.createdAt),

            title = def.title,
            notes = def.notes,
            tagIds = def.tagIds,
            remindAt = def.remindAt,
            recurrence = def.recurrence,
            stashed = def.stashed,

            dueOn = sched.dueOn,
            completedAt = sched.completedAt,
            completedByUid = sched.completedByUid,
            lastCompletedOn = sched.lastCompletedOn,
            seriesAnchorOn = sched.seriesAnchorOn,
            // Travels with the schedule rather than the definition: the index
            // moves on every completion, where the order is set once and
            // rarely touched. Reordering the roster at the same moment someone
            // ticks the task is the case this gets wrong, and it is far rarer
            // than the one it gets right.
            rotation = sched.rotation,

            assigneeUids = assign.assigneeUids,
            pendingAssignees = assign.pendingAssignees,

            defStamp = maxOf(a.defStamp, b.defStamp),
            schedStamp = maxOf(a.schedStamp, b.schedStamp),
            assignStamp = maxOf(a.assignStamp, b.assignStamp),
        )
    }

    // ---- tags, filters, members --------------------------------------------

    private fun reconcileTag(a: Tag, b: Tag): Tag =
        (if (a.stamp >= b.stamp) a else b).copy(stamp = maxOf(a.stamp, b.stamp))

    private fun reconcileFilter(a: TaskFilter, b: TaskFilter): TaskFilter =
        (if (a.stamp >= b.stamp) a else b).copy(stamp = maxOf(a.stamp, b.stamp))

    private fun reconcileMember(a: Member, b: Member): Member =
        (if (a.stamp >= b.stamp) a else b).copy(stamp = maxOf(a.stamp, b.stamp))

    private fun households(
        a: Household,
        b: Household,
        graves: Map<String, Tombstone>,
    ): Household = Household(
        // Two phones in one household agree on the id by construction — they
        // could not have paired otherwise. Taking the non-blank one covers a
        // snapshot from before pairing.
        id = a.id.ifBlank { b.id },
        name = if (a.nameStamp >= b.nameStamp) a.name else b.name,
        nameStamp = maxOf(a.nameStamp, b.nameStamp),
        members = merged(a.members, b.members, Member::uid, ::reconcileMember)
            .filterNot { buried(graves, Deleted.MEMBER, it.uid, it.stamp) },
    )
}
