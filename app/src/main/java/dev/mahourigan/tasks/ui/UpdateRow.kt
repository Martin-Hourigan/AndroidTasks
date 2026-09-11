package dev.mahourigan.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mahourigan.tasks.BuildConfig
import dev.mahourigan.tasks.update.ReleaseVersion
import dev.mahourigan.tasks.update.UpdateInstaller
import dev.mahourigan.tasks.update.Updates
import kotlinx.coroutines.launch

/** What the row is doing, which is the whole of its state. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object Failed : UpdateState
    data class Available(val release: ReleaseVersion) : UpdateState
    data class Downloading(val release: ReleaseVersion) : UpdateState
}

/**
 * Checking for a newer build, and installing it.
 *
 * One row that changes what it says rather than a screen of its own. Checking
 * for an update is something you do occasionally and then forget about, and it
 * does not deserve navigation.
 *
 * The last step belongs to Android, not to this app. Once the APK is handed
 * over, the system shows its own confirmation and makes its own decision —
 * including refusing outright if the download is not signed by the same key as
 * the installed app. That refusal is what makes an in-app updater safe to have
 * at all, so it is worth knowing it is there rather than assuming this code
 * does the checking.
 */
@Composable
fun UpdateRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    val installed = BuildConfig.VERSION_NAME
    val current = state

    val subtitle = when (current) {
        UpdateState.Idle -> "Version $installed"
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Version $installed — up to date"
        UpdateState.Failed -> "Could not reach GitHub. Try again later."
        is UpdateState.Available -> "Version ${current.release.name} is available"
        is UpdateState.Downloading -> "Downloading ${current.release.name}…"
    }

    val action: () -> Unit = when (current) {
        // Nothing to do while work is in flight; a second tap would start a
        // second download of the same file.
        UpdateState.Checking, is UpdateState.Downloading -> ({})

        is UpdateState.Available -> ({
            val release = current.release
            state = UpdateState.Downloading(release)
            scope.launch {
                val apk = Updates.download(context, release)
                state = if (apk != null && UpdateInstaller.install(context, apk)) {
                    // The system prompt takes over. Back to Idle so that
                    // declining it leaves the row usable rather than stuck
                    // saying "Downloading" for ever.
                    UpdateState.Idle
                } else {
                    UpdateState.Failed
                }
            }
        })

        else -> ({
            state = UpdateState.Checking
            scope.launch {
                val latest = Updates.latest()
                state = when {
                    latest == null -> UpdateState.Failed
                    latest.isNewerThan(BuildConfig.VERSION_CODE) -> UpdateState.Available(latest)
                    else -> UpdateState.UpToDate
                }
            }
        })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = action)
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (current is UpdateState.Available) "Download and install" else "Check for updates",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (current is UpdateState.Available && current.release.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = current.release.notes.lines().take(4).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
