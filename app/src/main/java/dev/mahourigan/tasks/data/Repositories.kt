package dev.mahourigan.tasks.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * The single store, shared by the UI and the alarm receivers.
 *
 * It has to be one instance. A notification's Complete button runs in a
 * receiver, not the ViewModel, and a second repository over the same file would
 * hold its own copy of everything in memory — so ticking a task from the
 * notification shade would write a snapshot built from stale data and quietly
 * undo whatever had been edited in the app.
 */
object Repositories {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var instance: LocalTaskRepository? = null

    fun tasks(context: Context): LocalTaskRepository =
        instance ?: synchronized(this) {
            instance ?: LocalTaskRepository(
                file = File(context.applicationContext.filesDir, "tasks.json"),
                scope = scope,
            ).also { instance = it }
        }
}
