package com.Music.data.storage

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.Music.player.LibraryTaskService

/**
 * App-scoped runner for library jobs that take noticeable time — bulk playlist
 * edits, bulk deletes, restores.
 *
 * Why not `viewModelScope`: that dies with the Activity (app closed / swiped
 * away), which used to abort a 1000-song playlist edit halfway. Jobs here run in
 * their own scope, are protected by [LibraryTaskService] (foreground), and show
 * determinate progress in an ongoing notification that can only be stopped via
 * its Cancel action.
 */
object LibraryTasks {

    data class Progress(
        val title: String,
        val phase: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val cancelling: Boolean = false
    )

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications by lazy { TaskNotifications(appContext) }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var serviceRunning = false

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    fun isRunning(): Boolean = _progress.value != null

    fun titleOrDefault(): String = _progress.value?.title ?: "Updating library"

    /** Cancel the running job after the item currently being handled. */
    fun cancel() {
        if (_progress.value == null) return
        cancelRequested = true
        _progress.value = _progress.value?.copy(cancelling = true)
    }

    fun isCancelling(): Boolean = cancelRequested

    /**
     * Run [block] under the foreground service + progress notification.
     * [block] receives a `step` callback: `step(phase, done, total)`.
     */
    fun run(title: String, block: suspend (step: (String, Int, Int) -> Unit) -> Unit) {
        if (_progress.value != null) return
        cancelRequested = false
        _progress.value = Progress(title = title)
        startService(title)

        scope.launch {
            fun step(phase: String, done: Int, total: Int) {
                if (cancelRequested) return
                _progress.value = Progress(title, phase, done, total, cancelRequested)
                notifications.update(title, phase, done, total, cancelRequested)
            }

            try {
                block(::step)
            } catch (_: Throwable) {
                // Errors are surfaced by the caller's own repo layer.
            } finally {
                _progress.value = null
                stopService()
            }
        }
    }

    private fun startService(title: String) {
        runCatching {
            val intent = Intent(appContext, LibraryTaskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            serviceRunning = true
        }
        notifications.update(title, "Starting…", 0, 1, false)
    }

    private fun stopService() {
        if (serviceRunning) {
            serviceRunning = false
            runCatching {
                appContext.startService(
                    Intent(appContext, LibraryTaskService::class.java)
                        .setAction(LibraryTaskService.ACTION_STOP)
                )
            }
        }
        notifications.finish()
    }
}
