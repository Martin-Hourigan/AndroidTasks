package dev.mahourigan.tasks.update

/**
 * A release on GitHub, as the app needs to think about it.
 *
 * [code] is the comparable number. It is derived from the tag by exactly the
 * same arithmetic the release workflow uses to stamp the APK — 0.3 becomes 300,
 * 1.4.2 becomes 10402 — because the two must agree. If the workflow and the
 * updater ever disagreed about what a tag means, the app would either offer an
 * update that Android then refuses to install, or go quiet about a real one.
 * The formula lives here, in one testable place, rather than in both.
 */
data class ReleaseVersion(
    val name: String,
    val code: Int,
    val apkUrl: String,
    val notes: String = "",
) {
    /**
     * Whether this is worth offering to someone running [installedCode].
     *
     * Strictly greater, never equal. Android refuses an install whose
     * versionCode does not exceed the installed one, so offering a same-version
     * "update" would end in a system error dialog nobody can act on.
     */
    fun isNewerThan(installedCode: Int): Boolean = code > installedCode

    companion object {

        /**
         * The versionCode a tag implies: `v0.3` and `0.3` both mean 300.
         *
         * Null for anything that does not parse, which is deliberate — a tag
         * like `nightly` or `v2-beta` should make the updater say nothing
         * rather than guess a number and offer a downgrade.
         */
        fun codeOf(tag: String): Int? {
            val cleaned = tag.trim().removePrefix("v").removePrefix("V")
            if (cleaned.isEmpty()) return null

            val parts = cleaned.split(".")
            if (parts.size > 3) return null

            val numbers = parts.map { part ->
                if (part.isEmpty() || !part.all(Char::isDigit)) return null
                part.toIntOrNull() ?: return null
            }

            val major = numbers.getOrElse(0) { 0 }
            val minor = numbers.getOrElse(1) { 0 }
            val patch = numbers.getOrElse(2) { 0 }

            // Each field gets two digits. Enough for 99 releases between
            // bumps, and beyond that the tag was the wrong shape anyway.
            if (minor > 99 || patch > 99) return null

            val code = major * 10_000 + minor * 100 + patch
            return if (code > 0) code else null
        }
    }
}
