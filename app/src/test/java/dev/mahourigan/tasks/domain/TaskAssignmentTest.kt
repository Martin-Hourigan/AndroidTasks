package dev.mahourigan.tasks.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TaskAssignmentTest {

    private val now: Instant = Instant.parse("2026-08-30T10:00:00Z")

    @Test
    fun `asking someone else raises a request rather than assigning them`() {
        val task = Task(title = "Clean the bathroom")

        val asked = TaskAssignment.request(task, uid = "p2", byUid = "p1", now = now)

        // Person Two has not agreed to anything yet.
        assertTrue(asked.assigneeUids.isEmpty())
        assertEquals(setOf("p2"), asked.pendingAssignees.keys)
        assertEquals("p1", asked.pendingAssignees["p2"]?.askedByUid)
    }

    @Test
    fun `taking something on yourself needs no one's permission`() {
        val task = Task(title = "Clean the bathroom")

        val mine = TaskAssignment.request(task, uid = "p1", byUid = "p1", now = now)

        assertEquals(setOf("p1"), mine.assigneeUids)
        assertTrue(mine.pendingAssignees.isEmpty())
    }

    @Test
    fun `accepting withdraws everyone else's request`() {
        val task = Task(title = "Clean the bathroom")
            .let { TaskAssignment.request(it, "p2", "p1", now) }
            .let { TaskAssignment.request(it, "p3", "p1", now) }

        val accepted = TaskAssignment.accept(task, "p2")

        // Otherwise Person Three says yes an hour later and the bathroom gets done twice.
        assertEquals(setOf("p2"), accepted.assigneeUids)
        assertTrue(accepted.pendingAssignees.isEmpty())
    }

    @Test
    fun `declining leaves anyone else still asked`() {
        val task = Task(title = "Clean the bathroom")
            .let { TaskAssignment.request(it, "p2", "p1", now) }
            .let { TaskAssignment.request(it, "p3", "p1", now) }

        val declined = TaskAssignment.decline(task, "p2")

        assertEquals(setOf("p3"), declined.pendingAssignees.keys)
        assertTrue(declined.assigneeUids.isEmpty())
    }

    @Test
    fun `accepting something you were never asked about does nothing`() {
        val task = Task(title = "Clean the bathroom")

        assertEquals(task, TaskAssignment.accept(task, "p2"))
    }

    @Test
    fun `someone leaving gives their tasks back rather than taking them away`() {
        val task = Task(
            title = "Take the bins out",
            assigneeUids = setOf("p2"),
            rotation = Rotation(listOf("p1", "p2", "p3"), index = 1),
        )

        val after = TaskAssignment.removeMember(task, "p2")

        assertTrue(after.assigneeUids.isEmpty())
        assertEquals(listOf("p1", "p3"), after.rotation?.order)
        assertEquals("the job still needs doing", "Take the bins out", after.title)
    }

    @Test
    fun `a request nobody answers is surfaced rather than expiring`() {
        val task = TaskAssignment.request(Task(title = "Clean the bathroom"), "p2", "p1", now)

        val fresh = TaskAssignment.staleRequests(task, now.plusSeconds(60))
        val stale = TaskAssignment.staleRequests(task, now.plus(TaskAssignment.STALE_AFTER))

        assertTrue(fresh.isEmpty())
        // Dropping it silently would leave a job everyone assumes was taken.
        assertEquals(setOf("p2"), stale.keys)
        assertEquals(setOf("p2"), task.pendingAssignees.keys)
    }
}
