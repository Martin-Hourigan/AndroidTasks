package dev.mahourigan.tasks.data

import dev.mahourigan.tasks.domain.Member
import dev.mahourigan.tasks.domain.Recurrence
import dev.mahourigan.tasks.domain.RecurrenceAnchor
import dev.mahourigan.tasks.domain.RecurrenceUnit
import dev.mahourigan.tasks.domain.Rotation
import dev.mahourigan.tasks.domain.Task
import dev.mahourigan.tasks.domain.TaskFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class LocalTaskRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun repo(file: File = temp.newFile("tasks.json").also { it.delete() }) =
        LocalTaskRepository(file, TestScope(), viewerName = "Person One") to file

    @Test
    fun `a fresh install comes with tags and a home grid`() = runTest {
        val (repository, _) = repo()

        repository.load()
        val state = repository.snapshot.value

        assertTrue(state.tags.any { it.name == "kitchen" })
        assertTrue(state.filters.any { it.name == "Today" })
        assertEquals("a household of one, not a special personal mode", 1, state.household.members.size)
        assertEquals("Person One", state.household.members.single().name)
    }

    @Test
    fun `everything survives a round trip through the file`() = runTest {
        val (repository, file) = repo()
        repository.load()

        val task = Task(
            id = "t1",
            title = "Replace the Brita filters",
            tagIds = setOf(StarterData.TAG_KITCHEN, StarterData.TAG_PURCHASE),
            dueOn = LocalDate.of(2026, 9, 1),
            remindAt = LocalDateTime.of(2026, 8, 30, 9, 0),
            recurrence = Recurrence(3, RecurrenceUnit.MONTH, RecurrenceAnchor.COMPLETION),
            rotation = Rotation(listOf("a", "b"), index = 1),
        )
        repository.upsertTask(task)

        // A second repository over the same file is what a cold start actually is.
        val reopened = LocalTaskRepository(file, TestScope())
        reopened.load()

        assertEquals(task, reopened.snapshot.value.task("t1"))
    }

    @Test
    fun `deleting a tag strips it from tasks and filters`() = runTest {
        val (repository, _) = repo()
        repository.load()
        repository.upsertTask(Task(id = "t1", title = "Buy milk", tagIds = setOf(StarterData.TAG_PURCHASE)))

        val usage = repository.tagUsage(StarterData.TAG_PURCHASE)
        repository.deleteTag(StarterData.TAG_PURCHASE)
        val state = repository.snapshot.value

        assertEquals(1, usage.taskCount)
        assertNull(state.tag(StarterData.TAG_PURCHASE))
        // A dangling id shows as a chip with no name and a filter matching nothing.
        assertTrue(state.task("t1")!!.tagIds.isEmpty())
        assertTrue(state.filters.none { StarterData.TAG_PURCHASE in it.tagIds })
    }

    @Test
    fun `tag usage counts before anything is destroyed`() = runTest {
        val (repository, _) = repo()
        repository.load()
        repository.upsertTask(Task(id = "t1", tagIds = setOf(StarterData.TAG_URGENT)))
        repository.upsertFilter(TaskFilter(id = "f1", name = "Mine", tagIds = setOf(StarterData.TAG_URGENT)))

        val usage = repository.tagUsage(StarterData.TAG_URGENT)

        assertEquals(1, usage.taskCount)
        assertEquals("the shipped Urgent card plus the new one", 2, usage.filterCount)
        assertTrue(usage.isUsed)
    }

    @Test
    fun `a solo household is not given cards that only make sense with other people`() = runTest {
        val (repository, _) = repo()

        repository.load()
        val names = repository.snapshot.value.filters.map { it.name }

        // On your own, "Me" and "Unassigned" both just mean everything, and
        // nobody can put a request to you.
        assertTrue(names.contains("Today"))
        assertTrue(names.contains("Someday"))
        assertFalse(names.contains("Me"))
        assertFalse(names.contains("Requests"))
        assertFalse(names.contains("Unassigned"))
    }

    @Test
    fun `the household cards arrive when a second person joins`() = runTest {
        val (repository, _) = repo()
        repository.load()

        repository.addMember(Member(uid = "p2", name = "Person Two"))
        val names = repository.snapshot.value.filters.map { it.name }

        assertTrue(names.contains("Me"))
        assertTrue(names.contains("Requests"))
        assertTrue(names.contains("Unassigned"))
        assertEquals("and the solo ones are kept", 9, names.size)
    }

    @Test
    fun `a third person does not add the household cards again`() = runTest {
        val (repository, _) = repo()
        repository.load()
        repository.addMember(Member(uid = "p2", name = "Person Two"))

        repository.addMember(Member(uid = "p3", name = "Person Three"))
        val names = repository.snapshot.value.filters.map { it.name }

        assertEquals("Me", 1, names.count { it == "Me" })
        assertEquals(9, names.size)
    }

    @Test
    fun `a household card the user deleted stays deleted`() = runTest {
        val (repository, _) = repo()
        repository.load()
        repository.addMember(Member(uid = "p2", name = "Person Two"))
        repository.deleteFilter("filter_requests")

        // A third member must not resurrect it — deleting a card is a decision.
        repository.addMember(Member(uid = "p3", name = "Person Three"))

        assertFalse(repository.snapshot.value.filters.any { it.id == "filter_requests" })
    }

    @Test
    fun `adding a member creates their person tag with them`() = runTest {
        val (repository, _) = repo()
        repository.load()

        repository.addMember(Member(uid = "p2", name = "Person Two"))
        val state = repository.snapshot.value

        // A member without a person tag could not be assigned anything.
        assertNotNull(state.tag(StarterData.personTagId("p2")))
        assertEquals("Person Two", state.tag(StarterData.personTagId("p2"))?.name)
    }

    @Test
    fun `a member leaving hands their tasks back rather than deleting them`() = runTest {
        val (repository, _) = repo()
        repository.load()
        repository.addMember(Member(uid = "p2", name = "Person Two"))
        repository.upsertTask(
            Task(
                id = "t1",
                title = "Take the bins out",
                tagIds = setOf(StarterData.personTagId("p2")),
                assigneeUids = setOf("p2"),
                rotation = Rotation(listOf("p2", "p1"), index = 0),
            ),
        )

        repository.removeMember("p2")
        val task = repository.snapshot.value.task("t1")!!

        assertEquals("the job still needs doing", "Take the bins out", task.title)
        assertTrue(task.assigneeUids.isEmpty())
        assertTrue(task.tagIds.isEmpty())
        assertEquals(listOf("p1"), task.rotation?.order)
        assertFalse(repository.snapshot.value.household.memberUids.contains("p2"))
    }

    @Test
    fun `a corrupt file falls back to a fresh household rather than crashing`() = runTest {
        val file = temp.newFile("broken.json")
        file.writeText("{ this is not json")

        val repository = LocalTaskRepository(file, TestScope(), viewerName = "Person One")
        repository.load()

        // Losing the data is bad; refusing to start is worse.
        assertTrue(repository.snapshot.value.tags.isNotEmpty())
    }
}
