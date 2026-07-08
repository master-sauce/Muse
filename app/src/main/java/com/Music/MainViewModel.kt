package com.Music

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import com.Music.data.MusicRepository
import com.Music.data.local.AppDatabase
import com.Music.data.local.PlaylistEntity
import com.Music.data.local.PlaylistWithSongs
import com.Music.data.local.SongEntity
import com.Music.data.remote.LyricsService
import com.Music.data.remote.LrcParser
import com.Music.data.remote.LyricsState
import com.Music.data.remote.OdesliService
import com.Music.downloader.DownloadManager
import com.Music.downloader.PlaylistEntry
import com.Music.player.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

enum class RepeatMode { NONE, ALL, ONE }

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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = Room.databaseBuilder(application, AppDatabase::class.java, "muse-db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        })
        .build()

    private val odesliService = Retrofit.Builder()
        .baseUrl("https://api.song.link/v1-alpha.1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(OdesliService::class.java)

    private val lyricsService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(LyricsService::class.java)

    private val repository = MusicRepository(
        db.songDao(), db.playlistDao(), odesliService, DownloadManager(application), application
    )

    private val _songs = MutableStateFlow<List<SongEntity>>(emptyList())
    val songs: StateFlow<List<SongEntity>> = _songs.asStateFlow()
    private var isDragInProgress = false

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val activeDownloads = _activeDownloads.asStateFlow()
    
    val isDownloading = _activeDownloads.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)
    
    private val _isImporting      = MutableStateFlow(false)
    val isImporting               = _isImporting.asStateFlow()

    // ── Playlist import state ──────────────────────────────────────────────
    private val _playlistFetch = MutableStateFlow(PlaylistFetchState())
    val playlistFetch: StateFlow<PlaylistFetchState> = _playlistFetch.asStateFlow()

    private val _batchDownload = MutableStateFlow(BatchDownloadState())
    val batchDownload: StateFlow<BatchDownloadState> = _batchDownload.asStateFlow()

    @Volatile private var batchCancelFlag = false
    private var currentProcessId: String? = null
    private var batchJob: Job? = null

    /** Share intents emitted by the ViewModel for the UI to startActivity on. */
    private val _shareIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val shareIntents: SharedFlow<Intent> = _shareIntents.asSharedFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _exoPlayer        = MutableStateFlow<Player?>(null)
    val exoPlayer: StateFlow<Player?> = _exoPlayer.asStateFlow()

    private val _isPlaying        = MutableStateFlow(false)
    val isPlaying                 = _isPlaying.asStateFlow()
    private val _currentSong      = MutableStateFlow<SongEntity?>(null)
    val currentSong               = _currentSong.asStateFlow()
    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress          = _playbackProgress.asStateFlow()
    private val _currentPosition  = MutableStateFlow(0L)
    val currentPosition           = _currentPosition.asStateFlow()
    private val _duration         = MutableStateFlow(0L)
    val duration                  = _duration.asStateFlow()
    private val _isShuffled       = MutableStateFlow(false)
    val isShuffled                = _isShuffled.asStateFlow()
    private val _repeatMode       = MutableStateFlow(RepeatMode.NONE)
    val repeatMode                = _repeatMode.asStateFlow()
    private var progressJob: Job? = null
    private var lastMediaItemIndex = C.INDEX_UNSET

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val _isQueueMode = MutableStateFlow(false)
    val isQueueMode: StateFlow<Boolean> = _isQueueMode.asStateFlow()

    private val manualQueueIds = mutableSetOf<String>()

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()
    val inSelectionMode get() = _selectedIds.value.isNotEmpty()

    /** True while the selected songs are being zipped for sharing. */
    private val _isZipping = MutableStateFlow(false)
    val isZipping: StateFlow<Boolean> = _isZipping.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistWithSongs>>(emptyList())
    val playlists: StateFlow<List<PlaylistWithSongs>> = _playlists.asStateFlow()

    private val _playlistSongs = MutableStateFlow<List<SongEntity>>(emptyList())
    val playlistSongs: StateFlow<List<SongEntity>> = _playlistSongs.asStateFlow()
    private var playlistSongsJob: Job? = null

    init {
        viewModelScope.launch {
            repository.allSongs.collect {
                if (!isDragInProgress) {
                    _songs.value = it
                    _currentSong.value = it.find { s -> s.id == _currentSong.value?.id }
                }
            }
        }
        viewModelScope.launch {
            repository.playlistsWithSongs.collect { _playlists.value = it }
        }
        viewModelScope.launch {
            _currentSong.collect { song ->
                if (song != null) fetchLyrics(song) else _lyrics.value = LyricsState.Idle
            }
        }

        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, token).buildAsync()
        controllerFuture?.addListener({ setupController() }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val player = controller ?: return
        _exoPlayer.value = player
        lastMediaItemIndex = player.currentMediaItemIndex

        // Restore last session if player is empty
        viewModelScope.launch {
            _songs.filter { it.isNotEmpty() }.first()
            if (player.mediaItemCount == 0) {
                val lastId = repository.getLastPlayedSongId()
                val lastPos = repository.getLastPlayedPosition()
                if (lastId != null) {
                    val song = repository.getSongById(lastId)
                    if (song != null) {
                        val items = _songs.value.filter { File(it.filePath).exists() }.map { buildMediaItem(it) }
                        val startIndex = items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
                        if (items.isNotEmpty()) {
                            player.setMediaItems(items, startIndex, lastPos)
                            player.prepare()
                            _currentSong.value = song
                            _currentPosition.value = lastPos
                            _duration.value = song.duration
                            if (song.duration > 0) {
                                _playbackProgress.value = lastPos.toFloat() / song.duration
                            }
                        }
                    }
                }
            }
        }

        // Sync initial state
        _isShuffled.value = player.shuffleModeEnabled
        _repeatMode.value = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.NONE
        }

        updateQueue()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startProgressUpdate() else {
                    stopProgressUpdate()
                    _currentSong.value?.let { repository.saveLastPlayed(it.id, player.currentPosition) }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val oldSongId = _currentSong.value?.id
                val newIndex = player.currentMediaItemIndex

                if (_isQueueMode.value && oldSongId != null && oldSongId != mediaItem?.mediaId) {
                    val isForward = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && (lastMediaItemIndex == C.INDEX_UNSET || newIndex > lastMediaItemIndex))

                    if (isForward) {
                        manualQueueIds.remove(oldSongId)
                        for (i in player.mediaItemCount - 1 downTo 0) {
                            if (player.getMediaItemAt(i).mediaId == oldSongId) {
                                player.removeMediaItem(i)
                            }
                        }
                    }
                }

                val song = _songs.value.find { it.id == mediaItem?.mediaId }
                _currentSong.value = song
                
                // Sync with player's current position (don't force to 0)
                val currentPos = player.currentPosition
                _currentPosition.value = currentPos
                
                val dur = if (player.duration > 0) player.duration else (song?.duration ?: 0L)
                _duration.value = dur
                
                if (dur > 0) {
                    _playbackProgress.value = currentPos.toFloat() / dur
                } else {
                    _playbackProgress.value = 0f
                }

                lastMediaItemIndex = player.currentMediaItemIndex
                song?.let { repository.saveLastPlayed(it.id, currentPos) }
                updateQueue()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val dur = player.duration
                    val pos = player.currentPosition
                    _duration.value = dur
                    if (dur > 0) {
                        _currentPosition.value = pos
                        _playbackProgress.value = pos.toFloat() / dur
                    }
                }
                updateQueue()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                updateQueue()
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = when (repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.NONE
                }
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _isShuffled.value = shuffleModeEnabled
            }
        })

        if (player.isPlaying) startProgressUpdate()
    }

    private fun updateQueue() {
        val player = controller ?: return
        val items = mutableListOf<MediaItem>()
        val seenIds = mutableSetOf<String>()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            if (manualQueueIds.contains(item.mediaId) && seenIds.add(item.mediaId)) {
                items.add(item)
            }
        }
        _queue.value = items
        _isQueueMode.value = manualQueueIds.isNotEmpty()
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            var lastSaveTime = 0L
            while (true) {
                controller?.let { p ->
                    val dur = p.duration
                    val pos = p.currentPosition
                    if (dur > 0) {
                        _playbackProgress.value = pos.toFloat() / dur
                        _currentPosition.value  = pos
                        _duration.value         = dur
                        
                        // Save position every 5 seconds to SharedPreferences
                        val now = System.currentTimeMillis()
                        if (now - lastSaveTime > 5000) {
                            _currentSong.value?.let { repository.saveLastPlayed(it.id, pos) }
                            lastSaveTime = now
                        }
                    }
                }
                delay(100)
            }
        }
    }

    private fun stopProgressUpdate() { progressJob?.cancel() }

    private fun fetchLyrics(song: SongEntity) {
        viewModelScope.launch {
            _lyrics.value = LyricsState.Loading
            try {
                val resp = lyricsService.getLyrics(
                    artistName = song.artist,
                    trackName  = song.title,
                    duration   = if (song.duration > 0) (song.duration / 1000).toInt() else null
                )
                _lyrics.value = if (resp.isSuccessful) {
                    val body = resp.body()!!
                    when {
                        body.instrumental -> LyricsState.Instrumental
                        !body.syncedLyrics.isNullOrBlank() -> {
                            val lines = LrcParser.parse(body.syncedLyrics)
                            if (lines.isNotEmpty()) LyricsState.Synced(lines)
                            else LyricsState.Plain(body.plainLyrics ?: "")
                        }
                        !body.plainLyrics.isNullOrBlank() -> LyricsState.Plain(body.plainLyrics)
                        else -> LyricsState.NotFound
                    }
                } else LyricsState.NotFound
            } catch (e: Exception) {
                _lyrics.value = LyricsState.NotFound
            }
        }
    }

    fun downloadSong(url: String) {
        val urls = url.split(Regex("[\\n,]")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        urls.forEach { singleUrl ->
            viewModelScope.launch {
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

    fun importLocalSong(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try { 
                val result = repository.importFromUri(uri)
                if (result == "duplicate") {
                    _errorEvents.emit("This song is already in your library")
                }
            }
            catch (e: Exception) { _errorEvents.emit("Import failed: ${e.localizedMessage}") }
            finally { _isImporting.value = false }
        }
    }

    fun importFromFolder(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try { repository.importFromFolder(uri) }
            catch (e: Exception) { _errorEvents.emit("Folder import failed: ${e.localizedMessage}") }
            finally { _isImporting.value = false }
        }
    }

    // ── Playlist import ────────────────────────────────────────────────────

    /** Fetch the list of song links from a YouTube playlist URL. */
    fun fetchPlaylistLinks(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _errorEvents.tryEmit("Please paste a playlist URL")
            return
        }
        viewModelScope.launch {
            _playlistFetch.value = PlaylistFetchState(isLoading = true, playlistUrl = trimmed)
            try {
                val entries = repository.fetchPlaylistEntries(trimmed)
                _playlistFetch.value = PlaylistFetchState(
                    isLoading = false,
                    playlistUrl = trimmed,
                    entries = entries
                )
            } catch (e: Exception) {
                _playlistFetch.value = PlaylistFetchState(
                    isLoading = false,
                    playlistUrl = trimmed,
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
        viewModelScope.launch {
            try {
                val file = repository.saveLinksToFile(state.entries, state.playlistUrl)
                val context = getApplication<Application>()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
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

    /**
     * Start downloading every song in the fetched playlist, one at a time.
     * Skips songs already in the library. Honors [cancelPlaylistDownload]:
     * when the user cancels, the current download is killed and all remaining
     * songs are skipped.
     */
    fun downloadPlaylistSongs() {
        val entries = _playlistFetch.value.entries
        if (entries.isEmpty()) {
            _errorEvents.tryEmit("Fetch a playlist first")
            return
        }
        startBatchDownload(entries)
    }

    /**
     * Core batch-download engine shared by the playlist importer and the
     * links-file importer. Downloads [entries] one at a time, skipping songs
     * already in the library, and reports progress via [_batchDownload].
     */
    private fun startBatchDownload(entries: List<PlaylistEntry>) {
        if (_batchDownload.value.isRunning) return

        batchCancelFlag = false
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            _batchDownload.value = BatchDownloadState(
                total = entries.size,
                completed = 0,
                isRunning = true
            )
            var completed = 0
            try {
                for (entry in entries) {
                    if (batchCancelFlag) break

                    // Skip songs already in the library
                    if (repository.isSongDownloaded(entry.url)) {
                        completed++
                        _batchDownload.value = _batchDownload.value.copy(completed = completed)
                        continue
                    }

                    val processId = "batch_${System.currentTimeMillis()}_${entry.index}"
                    currentProcessId = processId
                    _batchDownload.value = _batchDownload.value.copy(
                        completed = completed,
                        currentTitle = entry.title,
                        currentProgress = 0f
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
                            completed = completed,
                            currentProgress = 100f
                        )
                    } catch (e: com.yausername.youtubedl_android.YoutubeDL.CanceledException) {
                        // Cancelled by the user — stop the whole batch.
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Job cancelled (e.g. by the watchdog below) — propagate.
                        throw e
                    } catch (e: Exception) {
                        // Per-song failure: report but continue with the next song.
                        _errorEvents.emit("Skipped \"${entry.title}\": ${e.localizedMessage}")
                        completed++
                        _batchDownload.value = _batchDownload.value.copy(completed = completed)
                    }
                }
            } finally {
                val wasCancelled = batchCancelFlag
                _batchDownload.value = BatchDownloadState(
                    total = entries.size,
                    completed = completed,
                    isRunning = false,
                    isCancelling = false
                )
                currentProcessId = null
                if (wasCancelled) _errorEvents.emit("Download cancelled — $completed/${entries.size} done")
            }
        }
    }

    /**
     * Cancel an in-progress playlist batch download: kills the current download
     * and skips all remaining songs.
     *
     * After asking yt-dlp to stop, a watchdog waits a short grace period; if
     * the batch coroutine is still running (the yt-dlp child process can
     * sometimes linger after `destroy()`), the coroutine is cancelled outright
     * so the UI never gets stuck in the "Cancelling" state.
     */
    fun cancelPlaylistDownload() {
        if (!_batchDownload.value.isRunning) return
        batchCancelFlag = true
        _batchDownload.value = _batchDownload.value.copy(isCancelling = true)
        currentProcessId?.let { repository.cancelDownload(it) }

        val job = batchJob
        viewModelScope.launch {
            delay(3000)
            if (job?.isActive == true && batchCancelFlag) {
                job.cancel()
            }
        }
    }

    // ── Library export / import (transfer songs between phones) ─────────────

    /**
     * Export every downloaded song's source URL to a text file and emit a
     * share intent so the user can send the file to another phone. The file
     * uses the same one-URL-per-line format that the importer understands.
     */
    fun exportLibraryLinks() {
        if (_batchDownload.value.isRunning) {
            _errorEvents.tryEmit("Wait for the current download to finish first")
            return
        }
        viewModelScope.launch {
            try {
                val file = repository.exportLibraryLinks()
                if (file == null) {
                    _errorEvents.emit("No downloadable links in your library to export")
                    return@launch
                }
                val context = getApplication<Application>()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
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
     * Import a links file picked by the user via a document-open intent.
     * Reads the URLs and publishes them to [_playlistFetch] as
     * [PlaylistEntry]s so they appear in the Playlist tab's list — exactly
     * like [fetchPlaylistLinks] does for a YouTube playlist. The user can
     * then review the list and press "Download All" to start the batch
     * download, with full cancel support, reusing the same UI and engine
     * as the playlist flow.
     */
    fun importLinksFile(uri: Uri) {
        if (_batchDownload.value.isRunning) {
            _errorEvents.tryEmit("A download is already running")
            return
        }
        viewModelScope.launch {
            val urls = repository.importLinksFromFile(uri)
            if (urls.isEmpty()) {
                _errorEvents.emit("No links found in that file")
                return@launch
            }
            val entries = urls.mapIndexed { i, u ->
                PlaylistEntry(url = u, title = u, index = i + 1)
            }
            _playlistFetch.value = PlaylistFetchState(
                isLoading = false,
                playlistUrl = "",
                entries = entries
            )
        }
    }

    fun playSong(song: SongEntity) {
        manualQueueIds.clear()
        _isQueueMode.value = false
        val player = controller ?: return
        val items  = _songs.value.filter { File(it.filePath).exists() }.map { buildMediaItem(it) }
        if (items.isEmpty()) return

        val startIndex = items.indexOfFirst { it.mediaId == song.id }.coerceAtLeast(0)
        player.setMediaItems(items, startIndex, 0L)
        player.prepare(); player.play()
        updateQueue()
    }

    private fun enterManualQueueMode() {
        val player = controller ?: return
        if (manualQueueIds.isEmpty()) {
            player.shuffleModeEnabled = false
            _isShuffled.value = false
            _currentSong.value?.let { current ->
                manualQueueIds.add(current.id)
                val currentIndex = player.currentMediaItemIndex
                if (player.mediaItemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                }
                if (currentIndex > 0) {
                    player.removeMediaItems(0, currentIndex)
                }
                lastMediaItemIndex = player.currentMediaItemIndex
            }
        }
    }


    fun addToQueue(song: SongEntity) {
        val player = controller ?: return
        if (!File(song.filePath).exists()) return
        enterManualQueueMode()
        if (song.id == _currentSong.value?.id) return
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId == song.id) {
                if (i != player.currentMediaItemIndex) player.removeMediaItem(i)
            }
        }
        manualQueueIds.add(song.id)
        player.addMediaItem(buildMediaItem(song))
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 1) {
            player.prepare(); player.play()
        }
        updateQueue()
    }

    fun removeFromQueue(songId: String) {
        val player = controller ?: return
        manualQueueIds.remove(songId)
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId == songId) {
                player.removeMediaItem(i)
            }
        }
        updateQueue()
    }

    fun removeFromQueueByIndex(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            removeFromQueue(item.mediaId)
        }
    }

    fun playFromQueue(item: MediaItem) {
        val player = controller ?: return
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == item.mediaId) {
                player.seekTo(i, 0L)
                player.play()
                break
            }
        }
    }

    fun playNext(song: SongEntity) {
        val player = controller ?: return
        if (!File(song.filePath).exists()) return
        enterManualQueueMode()
        if (song.id == _currentSong.value?.id) return
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId == song.id) {
                if (i != player.currentMediaItemIndex) player.removeMediaItem(i)
            }
        }
        manualQueueIds.add(song.id)
        val nextIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
        player.addMediaItem(nextIndex, buildMediaItem(song))
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 1) {
            player.prepare(); player.play()
        }
        updateQueue()
    }

    fun playNext() {
        val p = controller ?: return
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return

        if (p.repeatMode == Player.REPEAT_MODE_ONE) {
            p.seekTo(p.currentMediaItemIndex, 0L)
        } else {
            val nextIndex = timeline.getNextWindowIndex(
                p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled
            )
            if (nextIndex != C.INDEX_UNSET) {
                p.seekTo(nextIndex, 0L)
            } else {
                p.seekTo(timeline.getFirstWindowIndex(p.shuffleModeEnabled), 0L)
            }
        }
        p.play()
    }

    fun playSongList(songs: List<SongEntity>, startIndex: Int = 0) {
        manualQueueIds.clear()
        _isQueueMode.value = false
        val player = controller ?: return
        val items  = songs.filter { File(it.filePath).exists() }.map { buildMediaItem(it) }
        if (items.isEmpty()) return
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare(); player.play()
        updateQueue()
    }

    private fun buildMediaItem(s: SongEntity) = MediaItem.Builder()
        .setMediaId(s.id)
        .setUri(Uri.fromFile(File(s.filePath)))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(s.title)
                .setArtist(s.artist)
                .setArtworkUri(s.thumbnailUrl?.let { Uri.parse(it) })
                .build()
        ).build()

    fun seekTo(fraction: Float) {
        val p = controller ?: return
        if (p.duration > 0) {
            val newPosition = (fraction * p.duration).toLong()
            p.seekTo(newPosition)
            // Update flows immediately for a more responsive UI
            _currentPosition.value = newPosition
            _playbackProgress.value = fraction
            _currentSong.value?.let { repository.saveLastPlayed(it.id, newPosition) }
        }
    }

    fun togglePlayback() {
        val p = controller ?: return
        if (p.isPlaying) p.pause() else if (p.mediaItemCount > 0) p.play()
    }

    fun playPrevious() {
        val p = controller ?: return
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return

        if (p.repeatMode == Player.REPEAT_MODE_ONE) {
            p.seekTo(p.currentMediaItemIndex, 0L)
        } else {
            val prevIndex = timeline.getPreviousWindowIndex(
                p.currentMediaItemIndex, p.repeatMode, p.shuffleModeEnabled
            )
            if (prevIndex != C.INDEX_UNSET) {
                p.seekTo(prevIndex, 0L)
            } else {
                p.seekTo(timeline.getLastWindowIndex(p.shuffleModeEnabled), 0L)
            }
        }
        p.play()
    }

    fun toggleShuffle() {
        val p = controller ?: return
        if (_isQueueMode.value) {
            p.shuffleModeEnabled = false
            _isShuffled.value = false
            return
        }
        val newState = !p.shuffleModeEnabled
        p.shuffleModeEnabled = newState
        _isShuffled.value = newState
    }

    fun toggleRepeat() {
        val p = controller ?: return
        val nextMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF  -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL  -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE  -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = nextMode
        _repeatMode.value = when (nextMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.NONE
        }
    }

    fun startDrag() { isDragInProgress = true }
    
    private fun syncPlayerWithMove(songId: String, fromListIndex: Int, toListIndex: Int, currentList: List<SongEntity>) {
        val player = controller ?: return
        if (_isQueueMode.value) return

        val pCount = player.mediaItemCount
        val idToIndex = mutableMapOf<String, Int>()
        for (i in 0 until pCount) {
            idToIndex[player.getMediaItemAt(i).mediaId] = i
        }

        val pFrom = idToIndex[songId] ?: return
        var pTo = 0
        for (i in toListIndex - 1 downTo 0) {
            val idx = idToIndex[currentList[i].id]
            if (idx != null) {
                pTo = idx + 1
                break
            }
        }

        val finalTo = if (pTo > pFrom) pTo - 1 else pTo
        if (pFrom != finalTo && finalTo in 0 until pCount) {
            player.moveMediaItem(pFrom, finalTo)
        }
    }

    fun moveSong(fromIndex: Int, toIndex: Int) {
        val list = _songs.value.toMutableList()
        val song = list.removeAt(fromIndex)
        list.add(toIndex, song)
        _songs.value = list
        syncPlayerWithMove(song.id, fromIndex, toIndex, list)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        if (fromIndex in 0 until player.mediaItemCount && toIndex in 0 until player.mediaItemCount) {
            player.moveMediaItem(fromIndex, toIndex)
            updateQueue()
        }
    }

    fun endDrag() {
        isDragInProgress = false
        viewModelScope.launch {
            _songs.value.forEachIndexed { index, song -> repository.updateSortOrder(song.id, index) }
        }
    }

    fun loadPlaylistSongs(playlistId: Long) {
        playlistSongsJob?.cancel()
        playlistSongsJob = viewModelScope.launch {
            repository.getPlaylistSongs(playlistId).collect {
                if (!isDragInProgress) {
                    _playlistSongs.value = it
                }
            }
        }
    }

    fun movePlaylistSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val list = _playlistSongs.value.toMutableList()
        val song = list.removeAt(fromIndex)
        list.add(toIndex, song)
        _playlistSongs.value = list
        syncPlayerWithMove(song.id, fromIndex, toIndex, list)
    }

    fun endPlaylistDrag(playlistId: Long) {
        isDragInProgress = false
        viewModelScope.launch {
            repository.updatePlaylistSongOrder(playlistId, _playlistSongs.value)
        }
    }

    fun toggleSelect(id: String) {
        _selectedIds.value = _selectedIds.value.toMutableSet().also { if (!it.add(id)) it.remove(id) }
    }
    fun selectAll()      { _selectedIds.value = _songs.value.map { it.id }.toSet() }
    fun clearSelection() { _selectedIds.value = emptySet() }

    fun deleteSelected() {
        val ids = _selectedIds.value
        clearSelection()
        viewModelScope.launch {
            controller?.let { player ->
                val toRemove = mutableListOf<Int>()
                for (i in 0 until player.mediaItemCount) if (player.getMediaItemAt(i).mediaId in ids) toRemove.add(i)
                toRemove.sortedDescending().forEach { player.removeMediaItem(it) }
            }
            repository.deleteSongs(ids)
        }
    }

    /**
     * Add every currently selected song to the given playlist. Keeps the
     * selection active afterwards so the user can add the same batch to more
     * playlists if they want.
     */
    fun addSelectedToPlaylist(playlistId: Long) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.addSongToPlaylist(playlistId, it) }
        }
    }

    /**
     * Bundle every currently selected song's media file into "muse_share.zip"
     * and emit a share intent so the UI can offer to send it to other apps.
     * Reports progress via [isZipping] and surfaces failures via
     * [errorEvents]. The selection is cleared once the zip is shared.
     */
    fun shareSelectedAsZip() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        if (_isZipping.value) return
        viewModelScope.launch {
            _isZipping.value = true
            try {
                val songs = _songs.value.filter { it.id in ids }
                val file = repository.zipSongs(songs)
                if (file == null) {
                    _errorEvents.emit("None of the selected songs have a file to share")
                    return@launch
                }
                val context = getApplication<Application>()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _shareIntents.emit(Intent.createChooser(shareIntent, "Share selected songs"))
                clearSelection()
            } catch (e: Exception) {
                _errorEvents.emit("Failed to create zip: ${e.localizedMessage}")
            } finally {
                _isZipping.value = false
            }
        }
    }

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch {
            controller?.let { player ->
                for (i in player.mediaItemCount - 1 downTo 0) {
                    if (player.getMediaItemAt(i).mediaId == song.id) player.removeMediaItem(i)
                }
            }
            repository.deleteSong(song)
        }
    }

    fun createPlaylist(name: String) = viewModelScope.launch { repository.createPlaylist(name) }
    fun deletePlaylist(playlist: PlaylistEntity) = viewModelScope.launch { repository.deletePlaylist(playlist) }
    fun addSongToPlaylist(playlistId: Long, songId: String) = viewModelScope.launch { repository.addSongToPlaylist(playlistId, songId) }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { repository.renamePlaylist(id, name) }
    fun removeSongFromPlaylist(playlistId: Long, songId: String) = viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, songId) }
    fun getPlaylistSongs(playlistId: Long) = repository.getPlaylistSongs(playlistId)
    suspend fun getPlaylistById(id: Long) = repository.getPlaylistById(id)

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        playlistSongsJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}