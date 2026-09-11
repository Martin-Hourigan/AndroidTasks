@file:UseSerializers(
    dev.mahourigan.tasks.data.InstantSerializer::class,
)

package dev.mahourigan.tasks.domain.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant

/**
 * When something last changed, in a way two phones can agree on.
 *
 * Sync needs a total order over edits, and a phone's clock is not one. Two
 * flatmates' phones can disagree by minutes; one of them can be wrong by hours
 * after a flat battery, and a manually set clock can be wrong by years. Order
 * edits by wall time alone and a phone running fast wins every conflict
 * forever, including ones it should lose.
 *
 * So this is a **hybrid logical clock**: wall time where wall time is
 * trustworthy, a counter where it is not, and the device id as a final
 * tiebreak.
 *
 * - [wallMillis] keeps stamps human-meaningful and roughly right, which matters
 *   because they are shown as "edited yesterday".
 * - [counter] advances when two edits land in the same millisecond, or when a
 *   peer's stamp is *ahead* of our clock. That second case is what protects
 *   causality: once we have seen an edit stamped in our future, everything we
 *   do afterwards is stamped after it, whatever our own clock says.
 * - [deviceId] breaks exact ties. Arbitrary but *consistent*, which is the only
 *   property that matters — both phones must pick the same winner, or they
 *   never converge.
 *
 * The result is a comparison that respects "this happened after that" whenever
 * the app could possibly know it, and is otherwise stable and identical on both
 * devices.
 */
@Serializable
data class Stamp(
    val wallMillis: Long = 0,
    val counter: Int = 0,
    val deviceId: String = "",
) : Comparable<Stamp> {

    override fun compareTo(other: Stamp): Int {
        wallMillis.compareTo(other.wallMillis).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return deviceId.compareTo(other.deviceId)
    }

    val isUnset: Boolean get() = wallMillis == 0L && counter == 0 && deviceId.isEmpty()

    val at: Instant get() = Instant.ofEpochMilli(wallMillis)

    companion object {
        /** Older than anything real, so an unstamped record always loses. */
        val UNSET = Stamp()
    }
}

/**
 * Issues stamps for one device, and keeps up with what it has seen.
 *
 * One instance per app process. It has to be told about every incoming stamp
 * ([observe]) before that data is used, because a clock that has not seen a
 * peer's future stamp will keep issuing stamps in that peer's past and its own
 * edits will be silently discarded as stale.
 *
 * Not thread-safe by itself; the repository serialises access, the same way it
 * serialises writes to the file.
 */
class StampSource(
    private val deviceId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastWall = 0L
    private var lastCounter = 0

    /** A stamp for an edit happening right now, always after every earlier one. */
    fun next(): Stamp {
        val wall = now()
        if (wall > lastWall) {
            lastWall = wall
            lastCounter = 0
        } else {
            // Either the clock stood still, or it went backwards, or we have
            // seen the future. All three are handled the same way: keep the
            // high-water mark and advance the counter, so stamps never
            // regress even when the clock does.
            lastCounter += 1
        }
        return Stamp(lastWall, lastCounter, deviceId)
    }

    /**
     * Takes note of a stamp from somewhere else.
     *
     * Call this for everything arriving from a peer. Skipping it is the classic
     * hybrid-clock bug: our next edit gets stamped before their last one, we
     * think ours is stale, and the edit vanishes with no error anywhere.
     */
    fun observe(stamp: Stamp) {
        if (stamp.wallMillis > lastWall) {
            lastWall = stamp.wallMillis
            lastCounter = stamp.counter
        } else if (stamp.wallMillis == lastWall && stamp.counter > lastCounter) {
            lastCounter = stamp.counter
        }
    }

    fun observeAll(stamps: Iterable<Stamp>) = stamps.forEach(::observe)
}
