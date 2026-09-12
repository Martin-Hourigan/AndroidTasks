package dev.mahourigan.tasks.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.io.File

/** What happened when an install was started. */
enum class InstallStart {
    /** Handed to the system. Its confirmation dialog takes over from here. */
    STARTED,

    /**
     * The user has not allowed this app to install anything yet.
     *
     * Declaring REQUEST_INSTALL_PACKAGES in the manifest is not enough on
     * Android 8 and up: "Install unknown apps" is granted per app, by the
     * person, in system settings. Until then an install is refused — and
     * refused quietly, which is why this has to be checked rather than
     * attempted and hoped for.
     */
    NEEDS_PERMISSION,

    FAILED,
}

/**
 * Handing a downloaded APK to Android to install.
 *
 * Uses [PackageInstaller] rather than firing an `ACTION_VIEW` at the APK. The
 * old way needs a FileProvider, a world-readable content URI and a mime type,
 * and has been deprecated since API 29; this streams the file straight into a
 * session the system owns.
 *
 * There is no way to make this silent, and that is Android's decision, not an
 * omission here: only the Play Store holds the privileged permission that skips
 * confirmation. The honest shape is "the app fetches, the system asks".
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    /** Whether the user has allowed this app to install packages at all. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The system screen where that permission is granted. */
    fun permissionSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Starts the install. The system takes over from here.
     *
     * Everything after the commit arrives at [InstallResultReceiver].
     */
    fun install(context: Context, apk: File): InstallStart {
        if (!canInstall(context)) return InstallStart.NEEDS_PERMISSION

        return runCatching {
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

                // Explicit, naming the receiver class. An implicit intent — even
                // one scoped with setPackage — is only delivered to a receiver
                // whose manifest entry declares a matching intent-filter, and
                // this one has none. Getting that wrong is silent: the session
                // commits, the system asks for confirmation, nothing is
                // listening, and the install simply never appears.
                val intent = Intent(context, InstallResultReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    // Mutable because the system fills in the status extras.
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            InstallStart.STARTED
        }.onFailure { Log.w(TAG, "Could not start the install", it) }
            .getOrDefault(InstallStart.FAILED)
    }
}

/**
 * Where the install session reports back.
 *
 * The status that matters is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the
 * system has staged the APK and now wants the user to confirm, handing back an
 * Intent to show. Failing to launch that is the classic reason an in-app
 * updater appears to do nothing — the download succeeds, the session commits,
 * and the confirmation dialog is never shown.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "Asked for confirmation but sent no intent to show")
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
