package dev.mahourigan.tasks.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock two phones have to agree on.
 *
 * Everything about merging rests on this comparison being a total order that
 * both devices compute identically. If it isn't, sync doesn't fail loudly — it
 * loses one person's edits quietly, which is much worse.
 */
class StampTest {

    private fun source(device: String, vararg times: Long): StampSource {
        val queue = times.toMutableList()
        return StampSource(device) { if (queue.size > 1) queue.removeAt(0) else queue.first() }
    }

    @Test
    fun `later wall time wins`() {
        assertTrue(Stamp(100, 0, "a") < Stamp(200, 0, "a"))
    }

    @Test
    fun `two edits in the same millisecond still order`() {
        val clock = source("a", 500, 500)
        val first = clock.next()
        val second = clock.next()
        assertTrue("$first should precede $second", first < second)
    }

    @Test
    fun `a clock going backwards does not issue a stamp in its own past`() {
        // A phone that syncs its time, or comes back from a flat battery, can
        // jump backwards. Left alone that would let an old edit outrank a new
        // one on the same device.
        val clock = source("a", 1_000, 400)
        val before = clock.next()
        val after = clock.next()
        assertTrue("$after should still follow $before", before < after)
    }

    @Test
    fun `the device id breaks an exact tie the same way on both phones`() {
        // Arbitrary, but it has to be *identical* on each device or they never
        // converge. Two phones must never each believe they won.
        val fromA = Stamp(100, 0, "aaa")
        val fromB = Stamp(100, 0, "bbb")
        assertTrue(fromA < fromB)
        assertTrue(fromB > fromA)
    }

    @Test
    fun `an unset stamp loses to everything real`() {
        assertTrue(Stamp.UNSET < Stamp(1, 0, "a"))
        assertTrue(Stamp.UNSET.isUnset)
    }

    @Test
    fun `seeing a peer's future stamp pushes our own edits after it`() {
        // The bug this prevents: their phone is ten minutes fast, we edit after
        // receiving their data, our stamp lands before theirs, and our edit is
        // discarded as stale. Nothing errors; the edit is simply gone.
        val clock = source("a", 1_000, 1_000)
        val theirs = Stamp(60_000, 0, "b")

        clock.observe(theirs)
        val ours = clock.next()

        assertTrue("$ours must follow $theirs", theirs < ours)
    }

    @Test
    fun `observing does not rewind a clock that is already ahead`() {
        val clock = source("a", 90_000, 90_000)
        val mine = clock.next()
        clock.observe(Stamp(1_000, 0, "b"))
        val next = clock.next()
        assertTrue(mine < next)
        assertEquals(90_000, next.wallMillis)
    }

    @Test
    fun `observing a same-millisecond peer stamp still advances past it`() {
        val clock = source("a", 5_000, 5_000)
        clock.next()
        clock.observe(Stamp(5_000, 7, "b"))
        assertTrue(Stamp(5_000, 7, "b") < clock.next())
    }

    @Test
    fun `comparison is a total order`() {
        // Sorting has to be well defined: no two distinct stamps may compare
        // equal, or the winner depends on list order and the two phones can
        // disagree.
        val stamps = listOf(
            Stamp(1, 0, "a"), Stamp(1, 0, "b"), Stamp(1, 1, "a"),
            Stamp(2, 0, "a"), Stamp.UNSET,
        )
        stamps.forEach { x ->
            stamps.forEach { y ->
                if (x != y) assertTrue("$x and $y compare equal", x.compareTo(y) != 0)
            }
        }
        assertEquals(stamps.sorted(), stamps.shuffled().sorted())
    }
}
