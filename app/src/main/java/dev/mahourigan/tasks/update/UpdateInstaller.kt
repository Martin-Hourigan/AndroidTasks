package dev.mahourigan.tasks.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

/**
 * Handing a downloaded APK to Android to install.
 *
 * Uses [PackageInstaller] rather than firing an `ACTION_VIEW` at the APK.
 * The old way needs a FileProvider, a world-readable content URI and a mime
 * type, and has been deprecated since API 29; this one streams the file
 * straight into a session the system owns.
 *
 * There is no way to make this silent, and that is by design on Android's part
 * — only the Play Store holds the privileged permission that skips
 * confirmation. So the honest shape of the feature is: the app fetches, and
 * the system asks. One tap, every time.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    const val ACTION_INSTALL_STATUS = "dev.mahourigan.tasks.INSTALL_STATUS"

    /**
     * Starts the install. The system takes over from here.
     *
     * Returns false only if the session could not be opened at all — a missing
     * permission, or no room to stage the file. Everything after the commit is
     * the system's business and arrives at [InstallResultReceiver].
     */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }

            val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                // Mutable because the system fills in the status extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
        true
    }.onFailure { Log.w(TAG, "Could not start the install", it) }.getOrDefault(false)
}

/**
 * Where the install session reports back.
 *
 * The status that matters is [PackageInstaller.STATUS_PENDING_USER_ACTION]:
 * the system has staged the APK and now wants the user to confirm, and it
 * hands back an Intent to show. Failing to launch that is the classic reason
 * an in-app updater appears to do nothing at all — the download succeeds, the
 * session commits, and the confirmation dialog is simply never shown.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "The system asked for confirmation but sent no intent to show")
                    return
                }
                // Arriving from a broadcast, so there is no activity to build on.
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w(TAG, "Could not show the install prompt", it) }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installed")
                Updates.clearDownloads(context)
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install did not complete (status $status): $message")
                Updates.clearDownloads(context)
            }
        }
    }

    private companion object {
        const val TAG = "InstallResult"
    }
}
