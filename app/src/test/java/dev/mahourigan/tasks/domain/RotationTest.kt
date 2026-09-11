package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RotationTest {

    private val household = setOf("p1", "p2", "p3")

    @Test
    fun `the turn moves to the next person`() {
        val roster = Rotation(listOf("p1", "p2", "p3"), index = 0)

        assertEquals("p2", roster.advance(household).currentAssignee)
    }

    @Test
    fun `the turn wraps at the end of the order`() {
        val roster = Rotation(listOf("p1", "p2", "p3"), index = 2)

        assertEquals("p1", roster.advance(household).currentAssignee)
    }

    @Test
    fun `someone who has left the household is skipped`() {
        val roster = Rotation(listOf("p1", "p2", "p3"), index = 0)

        // Person Two has moved out, so the bins pass to Person Three rather than to nobody.
        assertEquals("p3", roster.advance(setOf("p1", "p3")).currentAssignee)
    }

    @Test
    fun `a roster with nobody left holds its position`() {
        val roster = Rotation(listOf("p2", "p3"), index = 0)

        // Reassigning to a departed flatmate would be worse than leaving it.
        assertEquals(roster, roster.advance(setOf("p1")))
    }

    @Test
    fun `removing someone earlier in the order keeps the turn on the same person`() {
        val roster = Rotation(listOf("p1", "p2", "p3"), index = 2)

        val after = roster.withoutMember("p2")

        assertEquals(listOf("p1", "p3"), after.order)
        assertEquals("p3", after.currentAssignee)
    }

    @Test
    fun `removing the person whose turn it is hands it to whoever slid into the slot`() {
        val roster = Rotation(listOf("p1", "p2", "p3"), index = 1)

        val after = roster.withoutMember("p2")

        assertEquals(listOf("p1", "p3"), after.order)
        assertEquals("p3", after.currentAssignee)
    }

    @Test
    fun `removing the last person whose turn it is wraps to the front`() {
        val roster = Rotation(listOf("p1", "p2"), index = 1)

        val after = roster.withoutMember("p2")

        assertEquals(listOf("p1"), after.order)
        assertEquals("p1", after.currentAssignee)
    }

    @Test
    fun `an empty roster has nobody up`() {
        assertNull(Rotation().currentAssignee)
        assertEquals(Rotation(), Rotation(listOf("p1")).withoutMember("p1"))
    }

    @Test
    fun `adding a member is idempotent`() {
        val roster = Rotation(listOf("p1"), index = 0)

        assertEquals(listOf("p1", "p2"), roster.withMember("p2").order)
        assertEquals(listOf("p1"), roster.withMember("p1").order)
    }
}
