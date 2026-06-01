package com.Music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val _isDownloading    = MutableStateFlow(false)
    val isDownloading             = _isDownloading.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress          = _downloadProgress.asStateFlow()
    private val _isImporting      = MutableStateFlow(false)
    val isImporting               = _isImporting.asStateFlow()

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

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    // Flag indicating if we are in a manually built queue or playing a natural order
    private val _isQueueMode = MutableStateFlow(false)
    val isQueueMode: StateFlow<Boolean> = _isQueueMode.asStateFlow()

    // Tracking set for songs manually added to the queue
    private val manualQueueIds = mutableSetOf<String>()

    private val _lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()
    val inSelectionMode get() = _selectedIds.value.isNotEmpty()

    private val _playlists = MutableStateFlow<List<PlaylistWithSongs>>(emptyList())
    val playlists: StateFlow<List<PlaylistWithSongs>> = _playlists.asStateFlow()

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
        updateQueue()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startProgressUpdate() else stopProgressUpdate()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Keep the song in manual queue tracking if we transition to it,
                // but we filter manualQueueIds in updateQueue to ensure UI is correct.
                
                _currentSong.value  = _songs.value.find { it.id == mediaItem?.mediaId }
                _playbackProgress.value = 0f
                _currentPosition.value  = 0L
                updateQueue()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) _duration.value = player.duration
                updateQueue()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                updateQueue()
            }
        })
    }

    private fun updateQueue() {
        val player = controller ?: return
        
        // The UI Queue tab only shows items that were manually queued.
        val items = mutableListOf<MediaItem>()
        for (i in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(i)
            // Show if it's in the manual tracking set
            if (manualQueueIds.contains(item.mediaId)) {
                items.add(item)
            }
        }
        _queue.value = items
        _isQueueMode.value = manualQueueIds.isNotEmpty()
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let { p ->
                    if (p.duration > 0) {
                        _playbackProgress.value = p.currentPosition.toFloat() / p.duration
                        _currentPosition.value  = p.currentPosition
                        _duration.value         = p.duration
                    }
                }
                delay(500)
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
        viewModelScope.launch {
            _isDownloading.value = true
            try { repository.downloadAndSave(url) { _downloadProgress.value = it } }
            catch (e: Exception) {
                _errorEvents.emit("Download failed: ${e.localizedMessage}")
            } finally {
                _isDownloading.value  = false
                _downloadProgress.value = 0f
            }
        }
    }

    fun importLocalSong(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try { repository.importFromUri(uri) }
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

    /**
     * Regular Play: Discards the manual queue and starts playing from this song in regular order.
     */
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

    /**
     * Helper to transition from regular playback to manual queue mode.
     * Adds the current song to the manual tracking and clears future natural songs.
     */
    private fun enterManualQueueMode() {
        val player = controller ?: return
        if (manualQueueIds.isEmpty()) {
            _currentSong.value?.let { current ->
                manualQueueIds.add(current.id)
                // Clear everything after the current song to override natural order
                val currentIndex = player.currentMediaItemIndex
                if (player.mediaItemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                }
            }
        }
    }

    /**
     * Add to Queue: Appends the song to the manual queue.
     */
    fun addToQueue(song: SongEntity) {
        val player = controller ?: return
        if (!File(song.filePath).exists()) return
        
        // 1. Enter manual mode if we were in regular mode
        enterManualQueueMode()

        // 2. Prevent duplicates: remove if already exists (unless currently playing)
        if (manualQueueIds.contains(song.id)) {
            if (song.id == _currentSong.value?.id) return
            removeFromQueue(song.id)
        }

        // 3. Add to player and tracking
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
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == songId) {
                player.removeMediaItem(i)
                break
            }
        }
        updateQueue()
    }

    fun removeFromQueueByIndex(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            manualQueueIds.remove(item.mediaId)
            player.removeMediaItem(index)
            updateQueue()
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

    /**
     * Play Next: Inserts the song immediately after the current song.
     */
    fun playNext(song: SongEntity) {
        val player = controller ?: return
        if (!File(song.filePath).exists()) return
        
        enterManualQueueMode()

        if (manualQueueIds.contains(song.id)) {
            if (song.id == _currentSong.value?.id) return
            removeFromQueue(song.id)
        }

        manualQueueIds.add(song.id)
        val nextIndex = if (player.mediaItemCount > 0) player.currentMediaItemIndex + 1 else 0
        player.addMediaItem(nextIndex, buildMediaItem(song))
        
        if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 1) {
            player.prepare(); player.play()
        }
        updateQueue()
    }

    /**
     * Play List: Discards the manual queue and plays the provided list in natural order.
     */
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
        if (p.duration > 0) p.seekTo((fraction * p.duration).toLong())
    }

    fun togglePlayback() {
        val p = controller ?: return
        if (p.isPlaying) p.pause() else if (p.mediaItemCount > 0) p.play()
    }

    fun playPrevious() { controller?.seekToPrevious() }
    fun playNext()     { controller?.seekToNext() }

    fun toggleShuffle() {
        _isShuffled.value = !_isShuffled.value
        controller?.shuffleModeEnabled = _isShuffled.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        controller?.repeatMode = _repeatMode.value.toExo()
    }

    private fun RepeatMode.toExo() = when (this) {
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
    }

    fun startDrag() { isDragInProgress = true }
    fun moveSong(fromIndex: Int, toIndex: Int) {
        val list = _songs.value.toMutableList()
        list.add(toIndex, list.removeAt(fromIndex))
        _songs.value = list
    }

    fun endDrag() {
        isDragInProgress = false
        viewModelScope.launch {
            _songs.value.forEachIndexed { index, song -> repository.updateSortOrder(song.id, index) }
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

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch {
            controller?.let { player ->
                for (i in 0 until player.mediaItemCount) {
                    if (player.getMediaItemAt(i).mediaId == song.id) { player.removeMediaItem(i); break }
                }
            }
            repository.deleteSong(song)
        }
    }

    fun createPlaylist(name: String) = viewModelScope.launch { repository.createPlaylist(name) }
    fun deletePlaylist(playlist: PlaylistEntity) = viewModelScope.launch { repository.deletePlaylist(playlist) }
    fun addSongToPlaylist(playlistId: Long, songId: String) = viewModelScope.launch { repository.addSongToPlaylist(playlistId, songId) }
    fun removeSongFromPlaylist(playlistId: Long, songId: String) = viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, songId) }
    fun getPlaylistSongs(playlistId: Long) = repository.getPlaylistSongs(playlistId)
    suspend fun getPlaylistById(id: Long) = repository.getPlaylistById(id)

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
