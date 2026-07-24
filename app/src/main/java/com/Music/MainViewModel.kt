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
import com.Music.data.MusicRepository
import com.Music.data.local.PlaylistEntity
import com.Music.data.local.PlaylistWithSongs
import com.Music.data.local.SongEntity
import com.Music.data.remote.LyricsService
import com.Music.data.remote.LrcParser
import com.Music.data.remote.LyricsState
import com.Music.downloader.BatchDownloadState
import com.Music.downloader.DownloadState
import com.Music.downloader.DownloadTask
import com.Music.downloader.PlaylistFetchState
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

// NOTE: DownloadTask, PlaylistFetchState and BatchDownloadState now live in
// com.Music.downloader.DownloadState so they can be shared between the
// ViewModel (UI) and the app-scoped download engine. They are re-exported here
// via the imports above so existing UI code keeps compiling unchanged.

class MainViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * The shared, app-scoped repository. Built once inside [DownloadState.init]
     * (called from [MuseApp.onCreate]) so the DB and download engine survive
     * Activity/ViewModel destruction — downloads keep running even if the app
     * is closed, backed by [com.Music.player.DownloadService].
     */
    private val repository: MusicRepository = DownloadState.repository()

    private val lyricsService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(LyricsService::class.java)

    private val _songs = MutableStateFlow<List<SongEntity>>(emptyList())
    val songs: StateFlow<List<SongEntity>> = _songs.asStateFlow()
    private var isDragInProgress = false

    // ── Download state: delegated to the app-scoped DownloadState ───────────
    val activeDownloads: StateFlow<Map<String, DownloadTask>> = DownloadState.activeDownloads
    val isDownloading: StateFlow<Boolean> = DownloadState.isDownloading
    val playlistFetch: StateFlow<PlaylistFetchState> = DownloadState.playlistFetch
    val batchDownload: StateFlow<BatchDownloadState> = DownloadState.batchDownload

    /** Errors from the download engine (surfaced as toasts by the UI). */
    val downloadErrorEvents = DownloadState.errorEvents

    /** Share intents emitted by the download engine (links file / library export). */
    val downloadShareIntents = DownloadState.shareIntents

    private val _isImporting      = MutableStateFlow(false)
    val isImporting               = _isImporting.asStateFlow()

    /** Share intents emitted by this ViewModel for the UI to startActivity on. */
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

    // ── Playlist-detail selection ──────────────────────────────────────────
    // Separate selection set for the PlaylistDetailScreen so it doesn't clash
    // with the library's selection. Backed by the same batch helpers below.
    private val _playlistSelectedIds = MutableStateFlow<Set<String>>(emptySet())
    val playlistSelectedIds: StateFlow<Set<String>> = _playlistSelectedIds.asStateFlow()
    val inPlaylistSelectionMode get() = _playlistSelectedIds.value.isNotEmpty()

    /** True while the selected songs are being zipped for sharing. */
    private val _isZipping = MutableStateFlow(false)
    val isZipping: StateFlow<Boolean> = _isZipping.asStateFlow()

    /**
     * State for the in-app YouTube search screen (the "Search YouTube" entry
     * point behind the top-bar YouTube button). Holds the current query,
     * whether a search is in flight, the result list, and any error message.
     */
    data class YouTubeSearchState(
        val isLoading: Boolean = false,
        val query: String = "",
        val results: List<com.Music.downloader.SearchResult> = emptyList(),
        val error: String? = null
    )

    private val _youtubeSearch = MutableStateFlow(YouTubeSearchState())
    val youtubeSearch: StateFlow<YouTubeSearchState> = _youtubeSearch.asStateFlow()
    private var youtubeSearchJob: Job? = null

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

    // ── Downloads: thin delegates to the app-scoped DownloadState ──────────
    // The engine (and its notifications + foreground service) live in
    // DownloadState so downloads keep running even when this ViewModel is
    // cleared (app closed / Activity destroyed).

    fun downloadSong(url: String) = DownloadState.downloadSong(url)

    /** Cancel a specific single-song download by its taskId (X button). */
    fun cancelDownload(taskId: String) = DownloadState.cancelDownload(taskId)

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

    // ── Playlist import (delegated to DownloadState) ───────────────────────

    fun fetchPlaylistLinks(url: String) = DownloadState.fetchPlaylistLinks(url)
    fun clearPlaylistFetch() = DownloadState.clearPlaylistFetch()
    fun saveAndShareLinksFile() = DownloadState.saveAndShareLinksFile()
    fun downloadPlaylistSongs() = DownloadState.downloadPlaylistSongs()
    fun cancelPlaylistDownload() = DownloadState.cancelPlaylistDownload()
    fun exportLibraryLinks() = DownloadState.exportLibraryLinks()
    fun importLinksFile(uri: Uri) = DownloadState.importLinksFile(uri)

    // ── In-app YouTube search ───────────────────────────────────────────────
    // Lives here (not in DownloadState) because it's a UI-driven, short-lived
    // lookup — nothing to keep running across Activity death. A previous
    // in-flight search is cancelled before a new one starts so the results
    // always match the latest query.

    /**
     * Run a YouTube search for [query] and publish the results to
     * [youtubeSearch]. Cancels any prior search first. Errors are surfaced
     * through the state's `error` field (the screen renders it inline) rather
     * than as a toast, since the search screen is the active context.
     */
    fun searchYouTube(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearYouTubeSearch()
            return
        }
        youtubeSearchJob?.cancel()
        youtubeSearchJob = viewModelScope.launch {
            _youtubeSearch.value = YouTubeSearchState(isLoading = true, query = trimmed)
            try {
                val results = repository.searchYouTube(trimmed)
                _youtubeSearch.value = YouTubeSearchState(
                    isLoading = false, query = trimmed, results = results
                )
            } catch (e: Exception) {
                _youtubeSearch.value = YouTubeSearchState(
                    isLoading = false, query = trimmed,
                    error = e.localizedMessage ?: "Search failed"
                )
            }
        }
    }

    /** Reset the search screen to its empty state (and cancel any in-flight job). */
    fun clearYouTubeSearch() {
        youtubeSearchJob?.cancel()
        _youtubeSearch.value = YouTubeSearchState()
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
        // Keep the drag flag on until the DB write-back finishes. Otherwise the
        // allSongs collector un-gates the instant we flip the flag, emitting a
        // partially-updated list (some rows have new sortOrder, others old) and
        // causing the list to flicker to a mixed order before settling.
        viewModelScope.launch {
            _songs.value.forEachIndexed { index, song -> repository.updateSortOrder(song.id, index) }
            isDragInProgress = false
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
        // Keep the drag flag on until the DB write-back finishes — same reason
        // as endDrag(): avoids the playlistSongs collector emitting a
        // partially-reordered list and flickering the UI.
        viewModelScope.launch {
            repository.updatePlaylistSongOrder(playlistId, _playlistSongs.value)
            isDragInProgress = false
        }
    }

    fun toggleSelect(id: String) {
        _selectedIds.value = _selectedIds.value.toMutableSet().also { if (!it.add(id)) it.remove(id) }
    }

    /** Add [ids] to the current selection (no-op for ids already selected). */
    fun selectIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        _selectedIds.value = _selectedIds.value + ids
    }

    /** Remove [ids] from the current selection. */
    fun deselectIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        _selectedIds.value = _selectedIds.value - ids
    }

    fun selectAll()      { _selectedIds.value = _songs.value.map { it.id }.toSet() }
    fun clearSelection() { _selectedIds.value = emptySet() }

    // ── Playlist-detail selection helpers ──────────────────────────────────
    fun togglePlaylistSelect(id: String) {
        _playlistSelectedIds.value = _playlistSelectedIds.value.toMutableSet().also { if (!it.add(id)) it.remove(id) }
    }
    fun selectPlaylistIds(ids: Collection<String>) {
        if (ids.isNotEmpty()) _playlistSelectedIds.value = _playlistSelectedIds.value + ids
    }
    fun deselectPlaylistIds(ids: Collection<String>) {
        if (ids.isNotEmpty()) _playlistSelectedIds.value = _playlistSelectedIds.value - ids
    }
    fun selectAllPlaylist() { _playlistSelectedIds.value = _playlistSongs.value.map { it.id }.toSet() }
    fun clearPlaylistSelection() { _playlistSelectedIds.value = emptySet() }

    /** Remove every selected song from the given playlist, then clear the selection. */
    fun removeSelectedFromPlaylist(playlistId: Long) {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        clearPlaylistSelection()
        viewModelScope.launch {
            ids.forEach { repository.removeSongFromPlaylist(playlistId, it) }
        }
    }

    /** Add every selected song to the given playlist, keeping the selection active. */
    fun addPlaylistSelectedToPlaylist(targetPlaylistId: Long) {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.addSongToPlaylist(targetPlaylistId, it) }
        }
    }

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

    /**
     * Share a single song as its source link (the URL originally pasted via the
     * "+" Add-Music button, which is stored verbatim in `song.sourceUrl`). No
     * resolution step and no network — the stored link is shared as-is, so this
     * works for YouTube, Spotify, Apple, or any other http(s) source the song
     * was imported from. Songs with no http(s) source (local file/folder
     * imports) surface an error toast and share nothing.
     */
    fun shareSongAsLink(song: SongEntity) {
        val link = song.sourceUrl.trim()
        if (!link.startsWith("http")) {
            viewModelScope.launch { _errorEvents.emit("This song has no link to share") }
            return
        }
        viewModelScope.launch {
            _shareIntents.emit(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                        putExtra(Intent.EXTRA_TITLE, song.title)
                    },
                    "Share \"${song.title}\" as link"
                )
            )
        }
    }

    /**
     * Share every currently selected song as a list of its source links (one
     * per line) instead of a zip of files. Each song's stored `sourceUrl` is
     * shared verbatim — no resolution, no network. Songs whose `sourceUrl` is
     * not an http(s) link (local imports) are silently dropped. If no selected
     * song has a link, an error toast is shown. The selection is cleared once
     * the links are shared.
     */
    fun shareSelectedAsLinks() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val links = _songs.value
                .filter { it.id in ids }
                .map { it.sourceUrl.trim() }
                .filter { it.startsWith("http") }
                .distinct()
            if (links.isEmpty()) {
                _errorEvents.emit("None of the selected songs have a link to share")
                return@launch
            }
            val text = links.joinToString("\n")
            _shareIntents.emit(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                        putExtra(Intent.EXTRA_TITLE, "Muse links")
                    },
                    "Share ${links.size} link${if (links.size == 1) "" else "s"}"
                )
            )
            clearSelection()
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
        // NOTE: we intentionally do NOT cancel downloads here — they live in
        // the app-scoped DownloadState and should keep running after the
        // ViewModel (and Activity) are destroyed.
    }
}
