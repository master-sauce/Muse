package com.Music.downloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.Music.data.MusicRepository
import com.Music.data.remote.OdesliService
import com.Music.player.DownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * A single active (single-song) download, mirrored in the UI and used to drive
 * the aggregate progress notification.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val progress: Float = 0f,
    val title: String? = null
)

/** State of the playlist-link fetch operation in the Add Music > Playlist tab. */
data class PlaylistFetchState(
    val isLoading: Boolean = false,
    val playlistUrl: String = "",
    val entries: List<PlaylistEntry> = emptyList(),
    val error: String? = null
)

/** State of a batch playlist download. */
data class BatchDownloadState(
    val total: Int = 0,
    val completed: Int = 0,
    val currentTitle: String? = null,
    val currentProgress: Float = 0f,
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false
)

/**
 * Process-wide, application-scoped download engine.
 *
 * Why this exists separately from [com.Music.MainViewModel]:
 *  - The ViewModel is tied to the Activity lifecycle. When the user swipes the
 *    app away (or the system reclaims the Activity), the ViewModel is cleared
 *    and its `viewModelScope` is cancelled — which would kill any in-flight
 *    downloads.
 *  - This object owns its own [CoroutineScope] backed by a [SupervisorJob], so
 *    downloads keep running independently of any Activity/ViewModel. The
 *    [DownloadService] foreground service keeps the whole process alive while
 *    downloads are active, so they survive the app being closed.
 *
 * The UI (via the ViewModel) simply observes the [activeDownloads] /
 * [batchDownload] / [playlistFetch] flows and calls the public methods here.
 *
 * Notification strategy: there is exactly ONE foreground notification (id
 * [DownloadNotificationManager.FOREGROUND_NOTIFICATION_ID]) that is both the
 * foreground-service notification (keeps the process alive) and the
 * user-visible progress notification (non-dismissable). A combined flow
 * ([notificationFlow]) observes both [activeDownloads] and [batchDownload] and
 * pushes aggregate progress to that single notification. The notification is
 * only removed once every download has finished.
 */
object DownloadState {

    private lateinit var appContext: Context
    private lateinit var repository: MusicRepository

    /** Application-scoped scope — survives Activity/ViewModel destruction. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadNotifications by lazy { DownloadNotificationManager(appContext) }

    // ── Observable state ────────────────────────────────────────────────────

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadTask>> = _activeDownloads.asStateFlow()

    private val _playlistFetch = MutableStateFlow(PlaylistFetchState())
    val playlistFetch: StateFlow<PlaylistFetchState> = _playlistFetch.asStateFlow()

    private val _batchDownload = MutableStateFlow(BatchDownloadState())
    val batchDownload: StateFlow<BatchDownloadState> = _batchDownload.asStateFlow()

    /**
     * `true` while any download (single-song or batch) is in progress.
     * Derived from both [_activeDownloads] and [_batchDownload] so batch
     * downloads also keep the foreground service alive.
     */
    val isDownloading: StateFlow<Boolean> = MutableStateFlow(false).also { outer ->
        scope.launch {
            // Combine singles + batch into a single "anything downloading?" flag.
            // We re-collect both flows; whenever either changes we recompute.
            var singlesEmpty = true
            var batchRunning = false
            launch {
                _activeDownloads.collect { map ->
                    singlesEmpty = map.isEmpty()
                    val downloading = !singlesEmpty || batchRunning
                    if (outer.value != downloading) {
                        outer.value = downloading
                        if (downloading) ensureServiceRunning() else stopService()
                    }
                }
            }
            launch {
                _batchDownload.collect { batch ->
                    batchRunning = batch.isRunning
                    val downloading = !singlesEmpty || batchRunning
                    if (outer.value != downloading) {
                        outer.value = downloading
                        if (downloading) ensureServiceRunning() else stopService()
                    }
                }
            }
        }
    }

    /**
     * Combined flow that pushes the single foreground notification whenever
     * either the singles map or the batch state changes. The notification is
     * only cancelled (via [DownloadNotificationManager.finish]) when BOTH are
     * idle.
     */
    private val notificationFlow: Unit = run {
        scope.launch {
            var lastSingles: Map<String, DownloadTask> = emptyMap()
            var lastBatch: BatchDownloadState = BatchDownloadState()
            launch {
                _activeDownloads.collect { map ->
                    lastSingles = map
                    refreshNotification(lastSingles, lastBatch)
                }
            }
            launch {
                _batchDownload.collect { batch ->
                    lastBatch = batch
                    refreshNotification(lastSingles, lastBatch)
                }
            }
        }
    }

    /** Errors surfaced to the UI as toasts. */
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    /** Share intents (links file / library export) for the UI to startActivity on. */
    private val _shareIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val shareIntents: SharedFlow<Intent> = _shareIntents.asSharedFlow()

    @Volatile private var batchCancelFlag = false
    private var currentProcessId: String? = null
    private var batchJob: Job? = null

    // ── Init ────────────────────────────────────────────────────────────────

    /**
     * Must be called once from [com.Music.MuseApp.onCreate] before any download
     * method is used. Builds the DB + repository + Odesli service.
     */
    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext

        val db = androidx.room.Room.databaseBuilder(
            appContext, com.Music.data.local.AppDatabase::class.java, "muse-db"
        )
            .addMigrations(com.Music.data.local.AppDatabase.MIGRATION_1_2)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()

        val odesliService = Retrofit.Builder()
            .baseUrl("https://api.song.link/v1-alpha.1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(OdesliService::class.java)

        repository = MusicRepository(
            db.songDao(), db.playlistDao(), odesliService,
            DownloadManager(appContext), appContext
        )

        // The foreground notification's Cancel button cancels EVERY active
        // download (singles + batch). Registered once as PERSISTENT so it keeps
        // working for every future download for the lifetime of the process.
        DownloadCancelRegistry.registerPersistent(DownloadCancelRegistry.ALL_ID) { cancelAllDownloads() }
    }

    /** Expose the repository so the ViewModel can reuse it for non-download ops. */
    fun repository(): MusicRepository = repository

    // ── Single-song downloads ───────────────────────────────────────────────

    /**
     * Download one or more songs (URLs split on newlines/commas). Each runs in
     * its own coroutine on the application scope, so they survive Activity death.
     */
    fun downloadSong(url: String) {
        val urls = url.split(Regex("[\\n,]")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        urls.forEach { singleUrl ->
            scope.launch {
                if (repository.isSongDownloaded(singleUrl)) {
                    _errorEvents.emit("Song already in library")
                    return@launch
                }
                if (_activeDownloads.value.values.any { it.url == singleUrl }) {
                    return@launch
                }

                val taskId = System.currentTimeMillis().toString() + singleUrl.hashCode()
                _activeDownloads.update { it + (taskId to DownloadTask(taskId, singleUrl)) }

                try {
                    repository.downloadAndSave(
                        url = singleUrl,
                        taskId = taskId,
                        onTitleRetrieved = { title ->
                            _activeDownloads.update { tasks ->
                                tasks[taskId]?.let { tasks + (taskId to it.copy(title = title)) } ?: tasks
                            }
                        },
                        onProgress = { progress ->
                            _activeDownloads.update { tasks ->
                                tasks[taskId]?.let { tasks + (taskId to it.copy(progress = progress)) } ?: tasks
                            }
                        }
                    )
                } catch (e: Exception) {
                    _errorEvents.emit("Download failed: ${e.localizedMessage}")
                } finally {
                    _activeDownloads.update { it - taskId }
                }
            }
        }
    }

    /** Cancel a specific single-song download by its taskId. */
    fun cancelDownload(taskId: String) {
        repository.cancelDownload(taskId)
    }

    // ── Playlist fetch ──────────────────────────────────────────────────────

    /** Fetch the list of song links from a YouTube playlist URL. */
    fun fetchPlaylistLinks(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _errorEvents.tryEmit("Please paste a playlist URL")
            return
        }
        scope.launch {
            _playlistFetch.value = PlaylistFetchState(isLoading = true, playlistUrl = trimmed)
            try {
                val entries = repository.fetchPlaylistEntries(trimmed)
                _playlistFetch.value = PlaylistFetchState(
                    isLoading = false, playlistUrl = trimmed, entries = entries
                )
            } catch (e: Exception) {
                _playlistFetch.value = PlaylistFetchState(
                    isLoading = false, playlistUrl = trimmed,
                    error = e.localizedMessage ?: "Failed to fetch playlist"
                )
                _errorEvents.emit("Playlist fetch failed: ${e.localizedMessage}")
            }
        }
    }

    /** Reset the playlist fetch state (e.g. when the user clears the field). */
    fun clearPlaylistFetch() {
        if (_batchDownload.value.isRunning) return
        _playlistFetch.value = PlaylistFetchState()
    }

    /**
     * Save the currently fetched playlist links to a text file (one URL per
     * line) and emit a share intent so the UI can offer to share the file.
     */
    fun saveAndShareLinksFile() {
        val state = _playlistFetch.value
        if (state.entries.isEmpty()) {
            _errorEvents.tryEmit("Fetch a playlist first")
            return
        }
        scope.launch {
            try {
                val file = repository.saveLinksToFile(state.entries, state.playlistUrl)
                val uri = FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _shareIntents.emit(Intent.createChooser(shareIntent, "Share playlist links"))
            } catch (e: Exception) {
                _errorEvents.emit("Failed to save links file: ${e.localizedMessage}")
            }
        }
    }

    // ── Batch (playlist / links-file) downloads ─────────────────────────────

    /** Start downloading every song in the fetched playlist, one at a time. */
    fun downloadPlaylistSongs() {
        val entries = _playlistFetch.value.entries
        if (entries.isEmpty()) {
            _errorEvents.tryEmit("Fetch a playlist first")
            return
        }
        startBatchDownload(entries)
    }

    /**
     * Core batch-download engine. Downloads [entries] one at a time, skipping
     * songs already in the library, and reports progress via [_batchDownload]
     * (which feeds the aggregate notification). Honors [cancelPlaylistDownload].
     */
    private fun startBatchDownload(entries: List<PlaylistEntry>) {
        if (_batchDownload.value.isRunning) return

        batchCancelFlag = false
        batchJob?.cancel()
        batchJob = scope.launch {
            _batchDownload.value = BatchDownloadState(
                total = entries.size, completed = 0, isRunning = true
            )

            var completed = 0
            try {
                for (entry in entries) {
                    if (batchCancelFlag) break

                    if (repository.isSongDownloaded(entry.url)) {
                        completed++
                        _batchDownload.value = _batchDownload.value.copy(completed = completed)
                        continue
                    }

                    val processId = "batch_${System.currentTimeMillis()}_${entry.index}"
                    currentProcessId = processId
                    _batchDownload.value = _batchDownload.value.copy(
                        completed = completed, currentTitle = entry.title, currentProgress = 0f
                    )

                    try {
                        repository.downloadAndSave(
                            url = entry.url,
                            taskId = processId,
                            processId = processId,
                            onProgress = { progress ->
                                _batchDownload.value = _batchDownload.value.copy(currentProgress = progress)
                            }
                        )
                        completed++
                        _batchDownload.value = _batchDownload.value.copy(
                            completed = completed, currentProgress = 100f
                        )
                    } catch (e: com.yausername.youtubedl_android.YoutubeDL.CanceledException) {
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _errorEvents.emit("Skipped \"${entry.title}\": ${e.localizedMessage}")
                        completed++
                        _batchDownload.value = _batchDownload.value.copy(completed = completed)
                    }
                }
            } finally {
                val wasCancelled = batchCancelFlag
                _batchDownload.value = BatchDownloadState(
                    total = entries.size, completed = completed,
                    isRunning = false, isCancelling = false
                )
                currentProcessId = null
                if (wasCancelled) _errorEvents.emit("Download cancelled — $completed/${entries.size} done")
            }
        }
    }

    /**
     * Cancel an in-progress batch download: kills the current download and
     * skips all remaining songs. A watchdog cancels the coroutine outright if
     * yt-dlp lingers after `destroy()`.
     */
    fun cancelPlaylistDownload() {
        if (!_batchDownload.value.isRunning) return
        batchCancelFlag = true
        _batchDownload.value = _batchDownload.value.copy(isCancelling = true)
        currentProcessId?.let { repository.cancelDownload(it) }

        val job = batchJob
        scope.launch {
            delay(3000)
            if (job?.isActive == true && batchCancelFlag) job.cancel()
        }
    }

    /**
     * Cancel EVERY active download — invoked by the foreground notification's
     * Cancel button (registered under [DownloadCancelRegistry.ALL_ID]). Cancels
     * all single-song downloads and the batch (if running).
     */
    fun cancelAllDownloads() {
        // Cancel every active single-song download.
        _activeDownloads.value.keys.forEach { repository.cancelDownload(it) }
        // Cancel the batch if it's running.
        if (_batchDownload.value.isRunning) cancelPlaylistDownload()
    }

    // ── Library export / links-file import ──────────────────────────────────

    /** Export every downloaded song's source URL to a shareable text file. */
    fun exportLibraryLinks() {
        if (_batchDownload.value.isRunning) {
            _errorEvents.tryEmit("Wait for the current download to finish first")
            return
        }
        scope.launch {
            try {
                val file = repository.exportLibraryLinks()
                if (file == null) {
                    _errorEvents.emit("No downloadable links in your library to export")
                    return@launch
                }
                val uri = FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.provider", file
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _shareIntents.emit(Intent.createChooser(shareIntent, "Share library links"))
            } catch (e: Exception) {
                _errorEvents.emit("Failed to export library: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Import a links file and publish the URLs to [playlistFetch] as
     * [PlaylistEntry]s so they appear in the Playlist tab's list.
     */
    fun importLinksFile(uri: Uri) {
        if (_batchDownload.value.isRunning) {
            _errorEvents.tryEmit("A download is already running")
            return
        }
        scope.launch {
            val urls = repository.importLinksFromFile(uri)
            if (urls.isEmpty()) {
                _errorEvents.emit("No links found in that file")
                return@launch
            }
            val entries = urls.mapIndexed { i, u -> PlaylistEntry(url = u, title = u, index = i + 1) }
            _playlistFetch.value = PlaylistFetchState(
                isLoading = false, playlistUrl = "", entries = entries
            )
        }
    }

    // ── Notification + foreground service lifecycle ─────────────────────────

    @Volatile private var serviceRunning = false

    /**
     * Push the current aggregate state to the single foreground notification.
     * If nothing is downloading, remove the notification (which also lets the
     * foreground service stop).
     */
    private fun refreshNotification(
        singles: Map<String, DownloadTask>,
        batch: BatchDownloadState
    ) {
        if (singles.isEmpty() && !batch.isRunning) {
            downloadNotifications.finish()
        } else {
            downloadNotifications.showAggregateProgress(singles, batch)
        }
    }

    /** Start [DownloadService] (foreground) if not already running. */
    private fun ensureServiceRunning() {
        if (serviceRunning) return
        try {
            val intent = Intent(appContext, DownloadService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
            serviceRunning = true
        } catch (_: Throwable) {
            // Some OEMs / background-launch restrictions may block this; the
            // download still runs in our app-scoped coroutine, it just won't be
            // protected from the system killing the process as aggressively.
        }
    }

    /** Tell [DownloadService] to stop itself once all downloads are done. */
    private fun stopService() {
        if (!serviceRunning) return
        try {
            appContext.startService(Intent(appContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_STOP
            })
        } catch (_: Throwable) {}
        serviceRunning = false
    }
}
