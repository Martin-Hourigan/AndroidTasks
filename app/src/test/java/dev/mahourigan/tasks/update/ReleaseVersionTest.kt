package dev.mahourigan.tasks.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a git tag into the number Android compares.
 *
 * This arithmetic exists twice — here, and in the release workflow that stamps
 * the APK. They have to agree exactly. If the workflow stamps 300 and the
 * updater reads the same tag as 30, the app either offers an update the system
 * then refuses to install, or stays silent about a real one. Neither failure
 * announces itself, so the formula is pinned here.
 */
class ReleaseVersionTest {

    @Test
    fun `a tag becomes the same number the build workflow stamps`() {
        assertEquals(300, ReleaseVersion.codeOf("v0.3"))
        assertEquals(200, ReleaseVersion.codeOf("v0.2"))
        assertEquals(10402, ReleaseVersion.codeOf("v1.4.2"))
    }

    @Test
    fun `the v is optional`() {
        assertEquals(ReleaseVersion.codeOf("v1.2.3"), ReleaseVersion.codeOf("1.2.3"))
    }

    @Test
    fun `a missing patch counts as zero`() {
        assertEquals(ReleaseVersion.codeOf("v1.4"), ReleaseVersion.codeOf("v1.4.0"))
    }

    @Test
    fun `versions order the way people expect`() {
        val order = listOf("v0.1", "v0.2", "v0.10", "v1.0", "v1.0.1", "v1.2", "v2.0")
            .map { ReleaseVersion.codeOf(it)!! }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun `0-10 really is newer than 0-9, which string comparison would get wrong`() {
        // The reason this is a number at all. "v0.10" < "v0.9" alphabetically,
        // and an updater that compared strings would go quiet for ever once the
        // minor version reached double figures.
        assertTrue(ReleaseVersion.codeOf("v0.10")!! > ReleaseVersion.codeOf("v0.9")!!)
    }

    @Test
    fun `a tag nobody can parse is refused rather than guessed`() {
        // Better to say nothing than to invent a number: a wrong guess could
        // offer a downgrade, which Android then rejects with an error the user
        // can do nothing about.
        listOf("nightly", "v2-beta", "release", "", "v", "v1.2.3.4", "v1.x", "-1")
            .forEach { assertNull("'$it' should not parse", ReleaseVersion.codeOf(it)) }
    }

    @Test
    fun `a field wider than two digits is refused`() {
        // 1.100.0 would collide with 2.0.0 under this scheme, so it is rejected
        // rather than silently mapped onto a different release.
        assertNull(ReleaseVersion.codeOf("v1.100.0"))
        assertEquals(ReleaseVersion.codeOf("v2.0.0"), 20000)
    }

    @Test
    fun `version zero is not a release`() {
        assertNull(ReleaseVersion.codeOf("v0.0.0"))
    }

    @Test
    fun `an update must be strictly newer`() {
        val release = ReleaseVersion("0.3", 300, "url")
        assertTrue(release.isNewerThan(200))
        // Equal is not an update: Android refuses an install whose versionCode
        // does not exceed the installed one, so offering it ends in a system
        // error the user cannot act on.
        assertFalse(release.isNewerThan(300))
        assertFalse(release.isNewerThan(400))
    }
}
