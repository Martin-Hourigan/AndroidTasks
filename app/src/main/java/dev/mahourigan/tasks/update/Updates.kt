package dev.mahourigan.tasks.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asking GitHub whether there is a newer build, and fetching it.
 *
 * The only network this app does. It talks to one host, reads one release, and
 * sends nothing — there is no body on the request and no identifier beyond the
 * user agent, which is the app's own name.
 *
 * **Why downloading and installing an APK is safe here.** Android refuses to
 * install an update signed by a different key from the one already installed,
 * and that check is the operating system's, not this code's. So the worst a
 * tampered download can do is fail to install. It cannot become a different
 * app wearing this one's name, which is the attack that would otherwise make
 * an in-app updater a bad idea.
 */
object Updates {

    private const val TAG = "Updates"
    private const val REPO = "Martin-Hourigan/AndroidTasks"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Long enough for a slow connection, short enough not to hang a screen. */
    private const val TIMEOUT_MS = 15_000

    /**
     * The latest release, or null if there isn't one this build understands.
     *
     * Failure is null rather than an exception: a checked-for update that
     * cannot be reached is not an error the user did anything about, and a
     * widget app that crashes on a flaky connection is worse than one that
     * quietly says nothing. The reason is logged.
     */
    suspend fun latest(): ReleaseVersion? = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetch(LATEST) ?: return@runCatching null
            val release = JSONObject(json)

            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return@runCatching null

            val tag = release.optString("tag_name").ifBlank { return@runCatching null }
            val code = ReleaseVersion.codeOf(tag) ?: return@runCatching null

            // The APK, not the source archives GitHub attaches to every release.
            val assets = release.optJSONArray("assets") ?: return@runCatching null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }

            ReleaseVersion(
                name = tag.removePrefix("v"),
                code = code,
                apkUrl = apkUrl ?: return@runCatching null,
                notes = release.optString("body").take(400),
            )
        }.onFailure { Log.w(TAG, "Could not check for an update", it) }.getOrNull()
    }

    /**
     * The APK, saved where the installer can read it.
     *
     * Cache rather than files: if the install is abandoned there is no reason
     * to keep several megabytes around for ever, and the system may reclaim it.
     */
    suspend fun download(context: Context, release: ReleaseVersion): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(context.cacheDir, "update-${release.code}.apk")
                if (target.exists()) target.delete()

                openConnection(release.apkUrl).use { stream ->
                    target.outputStream().use { out -> stream.copyTo(out) }
                }
                // A truncated download would otherwise reach the installer and
                // fail there, with a far less obvious message.
                if (target.length() <= 0) {
                    target.delete()
                    null
                } else {
                    target
                }
            }.onFailure { Log.w(TAG, "Could not download the update", it) }.getOrNull()
        }

    /** Clears anything left by an abandoned install. */
    fun clearDownloads(context: Context) {
        runCatching {
            context.cacheDir.listFiles { f -> f.name.startsWith("update-") }?.forEach { it.delete() }
        }
    }

    private fun fetch(url: String): String? =
        openConnection(url, accept = "application/vnd.github+json").use { it.readBytes().decodeToString() }

    private fun openConnection(url: String, accept: String? = null) =
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Tasks")
            accept?.let { setRequestProperty("Accept", it) }
            inputStream
        }
}
