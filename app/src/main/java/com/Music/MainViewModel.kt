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
import com.Music.data.remote.LyricsQueryCleaner
import com.Music.data.remote.LyricsResponse
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * State for the 30-second search-result audio preview.
 *
 * Driven by the YouTube-search screen: tapping a card downloads a short
 * audio-only clip to a temp dir (NOT the library) and opens a mini preview
 * player so the user can hear the song before deciding to download it fully.
 */
sealed interface PreviewState {
    /** No preview active; sheet hidden. */
    data object Idle : PreviewState
    /** Clip is being fetched for [result]; sheet shows a spinner. */
    data class Loading(val result: com.Music.downloader.SearchResult) : PreviewState
    /** Clip ready and playing; [file] is the temp audio file. */
    data class Ready(
        val result: com.Music.downloader.SearchResult,
        val file: File
    ) : PreviewState
    /** Fetch failed; [message] shown inline in the sheet. */
    data class Error(
        val result: com.Music.downloader.SearchResult,
        val message: String
    ) : PreviewState
}

enum class RepeatMode { NONE, ALL, ONE }

/**
 * How the Library's Songs list is ordered.
 *  - [NEWEST]  : most recently added first (createdAt DESC). This is the
 *                default so newly downloaded / imported songs land at the TOP.
 *  - [OLDEST]  : least recently added first (createdAt ASC).
 *  - [CUSTOM]  : the user's manual drag order (stored in `songs.sortOrder`).
 *                Drag-to-reorder is only available in this mode.
 */
enum class SongSortMode { NEWEST, OLDEST, CUSTOM }

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

    // Guard the preview engine against a missing clip dir cleanup race: clips
    // older than this are wiped on ViewModel init so cacheDir/previews never
    // grows unbounded even if dismiss/crash paths leak files.
    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dir = File(getApplication<Application>().cacheDir, "previews")
                val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6)
                dir.listFiles()?.forEach { f ->
                    if (f.lastModified() < cutoff) f.delete()
                }
            } catch (_: Exception) {}
        }
    }

    // LRCLIB requires every client to identify itself with a User-Agent header
    // of the form "<AppName> vX.Y (https://...)". Without it the request is
    // fronted by Cloudflare and returns 520 ("Web server is returning an
    // unknown error") — which is exactly why no lyrics were loading even with
    // a correct query. The interceptor below stamps every request with one.
    private val lrclibUserAgent = "Muse v1.0 (https://github.com/Muse/music-app)"

    private val lyricsHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain: Interceptor.Chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", lrclibUserAgent)
                .build()
            chain.proceed(req)
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val lyricsService = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/")
        .client(lyricsHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(LyricsService::class.java)

    private val _songs = MutableStateFlow<List<SongEntity>>(emptyList())
    val songs: StateFlow<List<SongEntity>> = _songs.asStateFlow()
    private var isDragInProgress = false

    // ── Song sort mode (Newest / Oldest / Custom) ───────────────────────────
    // Persisted in SharedPreferences so the user's choice survives restarts.
    // Default is NEWEST so newly added songs appear at the top of the Library.
    private val _songSortMode = MutableStateFlow(
        loadSongSortMode()
    )
    val songSortMode: StateFlow<SongSortMode> = _songSortMode.asStateFlow()

    private fun loadSongSortMode(): SongSortMode {
        val prefs = getApplication<Application>().getSharedPreferences("muse_prefs", android.content.Context.MODE_PRIVATE)
        return when (prefs.getString("song_sort_mode", "NEWEST")) {
            "OLDEST" -> SongSortMode.OLDEST
            "CUSTOM" -> SongSortMode.CUSTOM
            else     -> SongSortMode.NEWEST
        }
    }

    private fun persistSongSortMode(mode: SongSortMode) {
        getApplication<Application>().getSharedPreferences("muse_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("song_sort_mode", mode.name).apply()
    }

    /** Re-apply the current sort mode to a snapshot of songs. */
    private fun applySort(list: List<SongEntity>): List<SongEntity> = when (_songSortMode.value) {
        SongSortMode.NEWEST -> list.sortedByDescending { it.createdAt }
        SongSortMode.OLDEST -> list.sortedBy { it.createdAt }
        SongSortMode.CUSTOM -> list.sortedBy { it.sortOrder }
    }

    /** Switch the Library's song sort mode; re-sorts the live list immediately. */
    fun setSongSortMode(mode: SongSortMode) {
        if (_songSortMode.value == mode) return
        _songSortMode.value = mode
        persistSongSortMode(mode)
        if (!isDragInProgress) {
            _songs.value = applySort(_songs.value)
        }
    }

    // ── Download state: delegated to the app-scoped DownloadState ───────────
    val activeDownloads: StateFlow<Map<String, DownloadTask>> = DownloadState.activeDownloads
    val isDownloading: StateFlow<Boolean> = DownloadState.isDownloading
    val playlistFetch: StateFlow<PlaylistFetchState> = DownloadState.playlistFetch
    val batchDownload: StateFlow<BatchDownloadState> = DownloadState.batchDownload

    // ── Auto-add-to-playlist preference ─────────────────────────────────────
    // When non-null, every newly downloaded song (single link or batch/playlist
    // import) is automatically added to the playlist with this id. Delegated to
    // the app-scoped DownloadState so it applies even to downloads that outlive
    // this ViewModel. null = feature off.
    val autoAddPlaylistId: StateFlow<Long?> = DownloadState.autoAddPlaylistId

    /** Set the playlist newly downloaded songs should be auto-added to (null = off). */
    fun setAutoAddPlaylistId(id: Long?) = DownloadState.setAutoAddPlaylistId(id)

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

    // ── Search-result 30s preview player ─────────────────────────────────────
    // A dedicated, throwaway ExoPlayer (separate from the main MediaController
    // session) so previewing a clip never disturbs the user's real queue or
    // playback position. The clip lives in cacheDir/previews (tmp), never in
    // the library, and is deleted when the preview is dismissed/superseded.
    private var previewPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var previewJob: Job? = null
    private var previewProgressJob: Job? = null

    private val _preview = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val preview: StateFlow<PreviewState> = _preview.asStateFlow()
    private val _previewPlaying = MutableStateFlow(false)
    val previewPlaying: StateFlow<Boolean> = _previewPlaying.asStateFlow()
    private val _previewPosition = MutableStateFlow(0L)
    val previewPosition: StateFlow<Long> = _previewPosition.asStateFlow()
    private val _previewDuration = MutableStateFlow(0L)
    val previewDuration: StateFlow<Long> = _previewDuration.asStateFlow()

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
    // Remembers whether shuffle was ON before the user started a manual queue.
    // While the manual queue has songs in it we force shuffle OFF so the queued
    // songs play in their exact added order (right after the current song);
    // once the queue drains we restore this saved state so playback continues
    // shuffled over the remaining playlist / library songs. -1 = nothing to
    // restore. See [enterManualQueueMode] and [onMediaItemTransition].
    //
    // Exposed as a StateFlow (not a plain var) so the UI can observe the user's
    // shuffle *intent* while a manual queue is suppressing the live flag — see
    // [shuffleIntent], which the player's shuffle button tints to so it stays
    // "active" even while the queue holds actual shuffle off.
    private val _shuffleRestoreOnDrain = MutableStateFlow(-1)
    private var shuffleRestoreOnDrain: Int
        get() = _shuffleRestoreOnDrain.value
        set(value) { _shuffleRestoreOnDrain.value = value }
    private val _repeatMode       = MutableStateFlow(RepeatMode.NONE)
    val repeatMode                = _repeatMode.asStateFlow()
    private var progressJob: Job? = null
    private var lastMediaItemIndex = C.INDEX_UNSET

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    // ── "Up Next" (full-timeline upcoming) ──────────────────────────────────
    // The manual queue ([queue] / [manualQueueIds]) only contains songs the user
    // explicitly queued. This flow shows the *actual* upcoming media items in the
    // player's timeline (everything after the currently-playing index), which is
    // what users expect an "Up Next" panel to surface. Refreshed by
    // [updateQueue] whenever the timeline/current index changes.
    private val _upNext = MutableStateFlow<List<MediaItem>>(emptyList())
    val upNext: StateFlow<List<MediaItem>> = _upNext.asStateFlow()

    private val _isQueueMode = MutableStateFlow(false)
    val isQueueMode: StateFlow<Boolean> = _isQueueMode.asStateFlow()

    // The shuffle button in the player should reflect the user's shuffle
    // *intent*, not the live player flag. While a manual queue is active we
    // hold actual shuffle OFF (so queued songs play in added order), but the
    // user can still toggle the intent — turning shuffle "on" during a queue
    // remembers that and restores it once the queue drains. This flow surfaces
    // that intent so the button stays highlighted (primary tint) even while
    // the live flag is off, and flips off when the user toggles intent off.
    // Outside queue mode it simply mirrors [isShuffled].
    val shuffleIntent: StateFlow<Boolean> =
        combine(_isShuffled, _isQueueMode, _shuffleRestoreOnDrain) { live, queuing, restore ->
            if (queuing) restore == 1 else live
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )

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

    // ── Playlist sort + add-to-top preferences ──────────────────────────────
    // Same Newest / Oldest / Custom idea as the library, but for the songs
    // inside a playlist — and now PER-PLAYLIST. Each playlist remembers its own
    // sort mode independently (so you can keep "Newest" on one playlist and
    // "Custom" drag-order on another). CUSTOM = the per-playlist manual drag
    // order (stored in `playlist_songs.position`). Newest/Oldest re-sort the
    // playlist's members by their `createdAt` without touching the persisted
    // order, so toggling back to Custom restores the user's arrangement.
    //
    // The map holds an entry only for playlists whose mode the user has
    // explicitly set; [getPlaylistSortMode] falls back to NEWEST otherwise so
    // newly created playlists behave like the library default.
    private val _playlistSortModes = MutableStateFlow<Map<Long, SongSortMode>>(loadPlaylistSortModes())
    val playlistSortModes: StateFlow<Map<Long, SongSortMode>> = _playlistSortModes.asStateFlow()

    /** Convenience: the sort mode currently in effect for [playlistId]. */
    fun getPlaylistSortMode(playlistId: Long): SongSortMode =
        _playlistSortModes.value[playlistId] ?: SongSortMode.NEWEST

    private fun loadPlaylistSortModes(): Map<Long, SongSortMode> {
        val prefs = getApplication<Application>().getSharedPreferences("muse_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("playlist_sort_modes", null) ?: return emptyMap()
        return buildMap {
            raw.split(',').forEach { pair ->
                val parts = pair.split('=')
                if (parts.size == 2) {
                    val id = parts[0].toLongOrNull() ?: return@forEach
                    val mode = when (parts[1]) {
                        "OLDEST" -> SongSortMode.OLDEST
                        "CUSTOM" -> SongSortMode.CUSTOM
                        "NEWEST" -> SongSortMode.NEWEST
                        else     -> return@forEach
                    }
                    put(id, mode)
                }
            }
        }
    }

    private fun persistPlaylistSortModes(modes: Map<Long, SongSortMode>) {
        val raw = modes.entries.joinToString(",") { "${it.key}=${it.value.name}" }
        getApplication<Application>().getSharedPreferences("muse_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("playlist_sort_modes", raw).apply()
    }

    /** Re-apply the given playlist sort mode to a snapshot of songs. */
    private fun applyPlaylistSort(list: List<SongEntity>, mode: SongSortMode): List<SongEntity> = when (mode) {
        SongSortMode.NEWEST -> list.sortedByDescending { it.createdAt }
        SongSortMode.OLDEST -> list.sortedBy { it.createdAt }
        SongSortMode.CUSTOM -> list // playlist_songs.position order comes from the DB
    }

    /**
     * Switch the sort mode for [playlistId] and re-sort the loaded playlist if
     * it's the one currently on screen. Each playlist keeps its own setting.
     */
    fun setPlaylistSortMode(playlistId: Long, mode: SongSortMode) {
        if (_playlistSortModes.value[playlistId] == mode) return
        _playlistSortModes.value = _playlistSortModes.value + (playlistId to mode)
        persistPlaylistSortModes(_playlistSortModes.value)
        if (!isDragInProgress && loadedPlaylistId == playlistId) {
            _playlistSongs.value = applyPlaylistSort(_playlistSongs.value, mode)
        }
    }

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
    /** The playlist currently loaded into [_playlistSongs] (for re-sort on mode change). */
    private var loadedPlaylistId: Long? = null

    // ── "Playing from" playlist tracking ────────────────────────────────────
    // When the user starts playback from a playlist (Play All / tapping a row
    // in PlaylistDetailScreen), we remember which playlist the current
    // playback list came from. The big Player's overflow menu uses this to
    // offer a "Remove from playlist" action for the currently-playing song
    // (mirroring the per-row option in PlaylistDetailScreen). null when
    // playing from the Library or a manual queue.
    private val _playingPlaylistId = MutableStateFlow<Long?>(null)
    val playingPlaylistId: StateFlow<Long?> = _playingPlaylistId.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allSongs.collect {
                if (!isDragInProgress) {
                    _songs.value = applySort(it)
                    _currentSong.value = it.find { s -> s.id == _currentSong.value?.id }
                }
            }
        }
        viewModelScope.launch {
            repository.playlistsWithSongs.collect {
                // Don't let the DB emission override the live drag-reorder while
                // the user is still arranging playlists — the in-memory list is
                // the source of truth mid-drag and gets written back on drop.
                if (!isDragInProgress) {
                    _playlists.value = it
                }
            }
        }
        viewModelScope.launch {
            _currentSong.collect { song ->
                if (song != null) fetchLyrics(song) else _lyrics.value = LyricsState.Idle
            }
        }

        // Eagerly restore the last played song so the mini player
        // appears immediately on app launch, before the MediaController
        // connects and the full session restore completes.
        viewModelScope.launch {
            val lastId = repository.getLastPlayedSongId()
            if (lastId != null) {
                _songs.filter { it.isNotEmpty() }.first()
                val song = _songs.value.find { it.id == lastId }
                if (song != null && File(song.filePath).exists()) {
                    _currentSong.value = song
                    val lastPos = repository.getLastPlayedPosition()
                    _currentPosition.value = lastPos
                    _duration.value = song.duration
                    if (song.duration > 0L) {
                        _playbackProgress.value = lastPos.toFloat() / song.duration
                    }
                }
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

        // Restore last session if player is empty, or sync current song if
        // the player already has items (e.g. from a MediaSession restore).
        viewModelScope.launch {
            _songs.filter { it.isNotEmpty() }.first()
            if (player.mediaItemCount == 0) {
                val lastId = repository.getLastPlayedSongId()
                val lastPos = repository.getLastPlayedPosition()
                // The playlist (if any) the last-played song was heard from.
                // Restoring the timeline from that playlist — instead of the
                // whole library — keeps shuffle / next / previous scoped to
                // the songs the user actually queued up.
                val lastPlaylistId = repository.getLastPlayedPlaylistId()
                if (lastId != null) {
                    val song = repository.getSongById(lastId)
                    if (song != null) {
                        val sourceSongs = if (lastPlaylistId != null) {
                            val pl = repository.getPlaylistSongsOnce(lastPlaylistId)
                            // Fall back to library if the playlist is gone or
                            // no longer contains the song (user deleted it).
                            if (pl.any { it.id == lastId }) pl else _songs.value
                        } else _songs.value
                        val items = sourceSongs.filter { File(it.filePath).exists() }.map { buildMediaItem(it) }
                        val startIndex = items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
                        if (items.isNotEmpty()) {
                            // Reflect the restored context so the player's
                            // overflow "Remove from playlist" and subsequent
                            // saveLastPlayed calls keep the right playlist id.
                            _playingPlaylistId.value =
                                if (sourceSongs === _songs.value) null else lastPlaylistId
                            player.setMediaItems(items, startIndex, lastPos)
                            player.playWhenReady = false
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
            } else {
                // Player already has items — either from a MediaSession restore
                // (continuous playback) or from PlaybackService's eager restore
                // (which loads just the last song for the notification). In both
                // cases we need to:
                //  1) Sync the current song metadata from the player.
                //  2) Replace the single-item playlist with the full library so
                //     next/previous navigation works. Preserve the current
                //     position and playing state so the user's play/pause
                //     choice (including from the notification) is respected.
                val currentMediaId = player.currentMediaItem?.mediaId
                val wasPlaying = player.isPlaying
                val currentPos = player.currentPosition
                if (currentMediaId != null) {
                    val song = _songs.value.find { it.id == currentMediaId }
                    if (song != null) {
                        _currentSong.value = song
                        val dur = if (player.duration > 0) player.duration else song.duration
                        _currentPosition.value = currentPos
                        _duration.value = dur
                        if (dur > 0L) {
                            _playbackProgress.value = currentPos.toFloat() / dur
                        }

                        // Replace the single-item playlist with the real context
                        // (playlist songs if the last session was a playlist,
                        // else the full library) so next/previous navigation and
                        // shuffle stay scoped to what the user was listening to.
                        // Preserve the user's current play/pause state — if they
                        // unpaused from the notification, keep playing.
                        val lastPlaylistId = repository.getLastPlayedPlaylistId()
                        val sourceSongs = if (lastPlaylistId != null) {
                            val pl = repository.getPlaylistSongsOnce(lastPlaylistId)
                            if (pl.any { it.id == currentMediaId }) pl else _songs.value
                        } else _songs.value
                        _playingPlaylistId.value =
                            if (sourceSongs === _songs.value) null else lastPlaylistId
                        val items = sourceSongs.filter { File(it.filePath).exists() }
                            .map { buildMediaItem(it) }
                        val startIndex = items.indexOfFirst { it.mediaId == currentMediaId }
                            .coerceAtLeast(0)
                        if (items.isNotEmpty()) {
                            player.setMediaItems(items, startIndex, currentPos)
                            player.playWhenReady = wasPlaying
                            player.prepare()
                            if (wasPlaying) player.play()
                        }
                    }
                }
                if (wasPlaying) startProgressUpdate()
            }
        }

        // Sync initial state
        _isPlaying.value = player.isPlaying
        // A fresh player always starts with shuffle off. If the user had
        // shuffle on last session, restore it now so both the player and the
        // UI flow reflect it. The onShuffleModeEnabledChanged listener (added
        // below, but registered before any user action) persists this to prefs.
        val restoredShuffle = repository.getShuffleEnabled()
        if (restoredShuffle && !player.shuffleModeEnabled) {
            player.shuffleModeEnabled = true
        }
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
                    _currentSong.value?.let {
                        repository.saveLastPlayed(it.id, player.currentPosition, _playingPlaylistId.value)
                    }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val oldSongId = _currentSong.value?.id
                val newIndex = player.currentMediaItemIndex

                if (_isQueueMode.value && oldSongId != null && oldSongId != mediaItem?.mediaId) {
                    val isForward = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && (lastMediaItemIndex == C.INDEX_UNSET || newIndex > lastMediaItemIndex))

                    // Only "consume" the just-played song if it was a manually-
                    // queued one (its id is in [manualQueueIds]). Playlist /
                    // library songs stay in the timeline so playback can fall
                    // back to them once the queue drains and repeat-all loops
                    // cleanly; queued songs are removed once played so they
                    // don't replay on loop.
                    if (isForward && oldSongId in manualQueueIds) {
                        manualQueueIds.remove(oldSongId)
                        for (i in player.mediaItemCount - 1 downTo 0) {
                            if (player.getMediaItemAt(i).mediaId == oldSongId) {
                                player.removeMediaItem(i)
                            }
                        }
                    }

                    // The manual queue just drained (the last queued song
                    // finished and was removed above). If we had saved the
                    // user's shuffle state to restore, do so now so playback
                    // continues shuffled over the remaining playlist / library
                    // songs — the requested "everything after the queue
                    // continues in shuffle" behaviour.
                    if (manualQueueIds.isEmpty() && shuffleRestoreOnDrain != -1) {
                        val restore = shuffleRestoreOnDrain == 1
                        shuffleRestoreOnDrain = -1
                        player.shuffleModeEnabled = restore
                        _isShuffled.value = restore
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
                song?.let { repository.saveLastPlayed(it.id, currentPos, _playingPlaylistId.value) }
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
                // Persist every real player-flag change so shuffle survives
                // restarts. The manual-queue suppression path never flips the
                // player flag, so this can't be clobbered by it.
                repository.saveShuffleEnabled(shuffleModeEnabled)
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

        // "Up Next": every media item strictly after the current index in the
        // timeline. In shuffle/repeat this is the player's own forward order;
        // when the list is exhausted (last item / repeat-one) it's empty.
        val upList = if (player.mediaItemCount > 0) {
            val current = player.currentMediaItemIndex
            val nextIndex = if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                C.INDEX_UNSET
            } else {
                player.currentTimeline.getNextWindowIndex(
                    current, player.repeatMode, player.shuffleModeEnabled
                )
            }
            if (nextIndex == C.INDEX_UNSET) emptyList()
            else buildList {
                var idx = nextIndex
                val seen = mutableSetOf<Int>()
                while (idx != C.INDEX_UNSET && seen.add(idx)) {
                    add(player.getMediaItemAt(idx))
                    idx = player.currentTimeline.getNextWindowIndex(
                        idx, player.repeatMode, player.shuffleModeEnabled
                    )
                }
            }
        } else emptyList()
        _upNext.value = upList
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
                            _currentSong.value?.let { repository.saveLastPlayed(it.id, pos, _playingPlaylistId.value) }
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
        // Re-fetch guard: keep a token of the song we kicked off the request
        // for, and ignore the result if the current song has since changed
        // (otherwise a slow in-flight call for song A can clobber lyrics for
        // song B that just started playing).
        val requestToken = song.id
        viewModelScope.launch {
            _lyrics.value = LyricsState.Loading
            try {
                // Clean + split YouTube-style "Artist - Title" metadata into the
                // separate (artist, title) values LRCLIB expects. The DB stores
                // the YouTube *video* title (e.g. "Blur - Song 2 (Official Video)")
                // as `title` and yt-dlp's `uploader` as `artist`. LRCLIB's /get
                // wants track_name="Song 2" + artist_name="Blur"; sending
                // track_name="Blur - Song 2" 404s — the single most common reason
                // no lyrics ever load. normalize() splits the prefix and strips
                // the noise so the query lines up with LRCLIB's canonical entries.
                val (cleanArtist, cleanTitle) =
                    LyricsQueryCleaner.normalize(song.title, song.artist)
                val durationSec = if (song.duration > 0) (song.duration / 1000).toInt() else null

                // Stage 1 — exact [/get] (fastest, highest confidence). LRCLIB
                // requires `duration` here; without it the server returns 400,
                // so only attempt the exact endpoint when we actually have one.
                val exact: LyricsState? = if (durationSec != null) {
                    try {
                        val resp = lyricsService.getLyrics(
                            artistName = cleanArtist,
                            trackName  = cleanTitle,
                            duration   = durationSec
                        )
                        if (resp.isSuccessful) resp.body()?.toLyricsState() else null
                    } catch (_: Exception) { null }
                } else null

                val result = exact ?: run {
                    // Stage 2 — fuzzy [/search]. Every parameter is optional,
                    // so this works without a duration and tolerates imperfect
                    // artist/title strings. Pick the best candidate from the
                    // ranked list: prefer synced → plain → instrumental, and
                    // (when we have a duration) the one closest to it.
                    val searchResp = lyricsService.searchLyrics(
                        trackName  = cleanTitle,
                        artistName = cleanArtist.takeIf { it.isNotBlank() }
                    )
                    if (searchResp.isSuccessful) {
                        searchResp.body()?.pickBest(durationSec)
                    } else null
                } ?: LyricsState.NotFound

                if (requestToken == _currentSong.value?.id) {
                    _lyrics.value = result
                }
            } catch (e: Exception) {
                if (requestToken == _currentSong.value?.id) {
                    _lyrics.value = LyricsState.NotFound
                }
            }
        }
    }

    /** Map a single LRCLIB [LyricsResponse] to the UI [LyricsState]. */
    private fun LyricsResponse.toLyricsState(): LyricsState = when {
        instrumental -> LyricsState.Instrumental
        !syncedLyrics.isNullOrBlank() -> {
            val lines = LrcParser.parse(syncedLyrics)
            if (lines.isNotEmpty()) LyricsState.Synced(lines)
            else plainLyrics?.takeIf { it.isNotBlank() }?.let { LyricsState.Plain(it) }
                ?: LyricsState.NotFound
        }
        !plainLyrics.isNullOrBlank() -> LyricsState.Plain(plainLyrics)
        else -> LyricsState.NotFound
    }

    /**
     * From a list of LRCLIB search candidates, pick the most useful one.
     *
     * Preference order (each step falls through if it yields nothing):
     *  1. Entries that actually have lyrics (synced preferred over plain, so
     *     the karaoke-style synced view is used whenever available).
     *  2. If we know the song's duration, bias toward the candidate whose
     *     duration is closest to it — same-named covers / remixes are common
     *     and the duration filter reliably separates them.
     *  3. If no candidate has lyrics but one is flagged instrumental, show the
     *     instrumental state (better than "No lyrics found").
     *  4. Otherwise [LyricsState.NotFound].
     */
    private fun List<LyricsResponse>.pickBest(durationSec: Int?): LyricsState {
        if (isEmpty()) return LyricsState.NotFound

        val withSynced = filter { !it.syncedLyrics.isNullOrBlank() }
        val withPlain  = filter { !it.plainLyrics.isNullOrBlank() }

        val bestSynced = withSynced.closestByDuration(durationSec)
        if (bestSynced != null) {
            val lines = LrcParser.parse(bestSynced.syncedLyrics!!)
            if (lines.isNotEmpty()) return LyricsState.Synced(lines)
            // synced string present but unparseable — fall through to plain
        }

        val bestPlain = withPlain.closestByDuration(durationSec)
        if (bestPlain != null) return LyricsState.Plain(bestPlain.plainLyrics!!)

        if (any { it.instrumental }) return LyricsState.Instrumental
        return LyricsState.NotFound
    }

    /** Pick the entry whose `duration` is closest to [target] (seconds). */
    private fun List<LyricsResponse>.closestByDuration(target: Int?): LyricsResponse? {
        if (isEmpty()) return null
        if (target == null) return first()
        // Allow up to ~10s of slack — covers rounding and trimmed/extended
        // versions of the same track without matching a totally different song.
        val tolerance = 10
        return filter { it.duration != null }
            .minByOrNull { kotlin.math.abs(it.duration!!.toInt() - target) }
            ?.takeIf { kotlin.math.abs(it.duration!!.toInt() - target) <= tolerance }
            ?: firstOrNull()
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
                // Guard against a stale result landing after the user changed
                // the query (or navigated away) — only commit if this job is
                // still the active one.
                if (!currentCoroutineContext().isActive) return@launch
                _youtubeSearch.value = YouTubeSearchState(
                    isLoading = false, query = trimmed, results = results
                )
            } catch (e: CancellationException) {
                // Debounce / clear cancels in-flight searches. This is normal
                // and must propagate, NOT be surfaced as an error to the user.
                throw e
            } catch (e: Exception) {
                // Real failure (network / yt-dlp). Don't show the raw exception
                // text — fall back to the empty-results state, which renders
                // "No results for …" (or the initial empty state if the query
                // has since been cleared).
                _youtubeSearch.value = YouTubeSearchState(
                    isLoading = false, query = trimmed, results = emptyList()
                )
            }
        }
    }

    /** Reset the search screen to its empty state (and cancel any in-flight job). */
    fun clearYouTubeSearch() {
        youtubeSearchJob?.cancel()
        _youtubeSearch.value = YouTubeSearchState()
    }

    // ── Search-result 30s preview ────────────────────────────────────────────
    // Tapping a YouTube-search card kicks this off. The previous preview (if
    // any) is stopped and its temp file deleted before the new one starts, so
    // only one preview is ever active and tmp clips don't pile up.

    /**
     * Download the first 30s of [result]'s audio to a temp file and open the
     * mini preview player. Safe to call repeatedly — each call supersedes the
     * previous preview. The clip is stored in cacheDir/previews (tmp), never
     * added to the songs library.
     */
    fun startPreview(result: com.Music.downloader.SearchResult) {
        // Cancel any in-flight preview fetch + stop the current clip.
        previewJob?.cancel()
        stopPreviewPlayback(deleteFile = true)

        _preview.value = PreviewState.Loading(result)
        previewJob = viewModelScope.launch {
            try {
                val processId = "preview_" + System.currentTimeMillis()
                val file = repository.downloadPreviewClip(result.url, processId)
                if (!currentCoroutineContext().isActive) { file.delete(); return@launch }
                _preview.value = PreviewState.Ready(result, file)
                playPreviewFile(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _preview.value = PreviewState.Error(
                    result, e.localizedMessage ?: "Preview failed"
                )
            }
        }
    }

    /** Build (or reuse) the throwaway preview ExoPlayer and play [file]. */
    private fun playPreviewFile(file: File) {
        val app = getApplication<Application>()
        val player = previewPlayer ?: androidx.media3.exoplayer.ExoPlayer.Builder(app).build().also { p ->
            previewPlayer = p
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _previewPlaying.value = playing
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        _previewDuration.value = p.duration.coerceAtLeast(0L)
                    }
                    if (state == Player.STATE_ENDED) {
                        _previewPlaying.value = false
                        p.seekTo(0)
                        p.pause()
                    }
                }
            })
        }
        // Pause the main player so the two never play over each other.
        controller?.pause()

        _previewPlaying.value = false
        _previewPosition.value = 0L
        _previewDuration.value = 0L
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        startPreviewProgress()
    }

    private fun startPreviewProgress() {
        previewProgressJob?.cancel()
        previewProgressJob = viewModelScope.launch {
            while (true) {
                previewPlayer?.let { p ->
                    _previewPosition.value = p.currentPosition.coerceAtLeast(0L)
                    if (p.duration > 0) _previewDuration.value = p.duration
                }
                delay(200)
            }
        }
    }

    /** Toggle play/pause on the active preview clip. */
    fun togglePreviewPlayback() {
        val p = previewPlayer ?: return
        if (p.isPlaying) p.pause() else {
            // Restart from the top if the clip already played through.
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    /** Seek the active preview clip to [positionMs]. */
    fun seekPreviewTo(positionMs: Long) {
        previewPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        _previewPosition.value = positionMs.coerceAtLeast(0L)
    }

    /** Stop playback, release the player and (optionally) delete the tmp file. */
    private fun stopPreviewPlayback(deleteFile: Boolean) {
        val file = (_preview.value as? PreviewState.Ready)?.file
        previewProgressJob?.cancel()
        previewPlayer?.let { it.stop(); it.clearMediaItems() }
        _previewPlaying.value = false
        _previewPosition.value = 0L
        _previewDuration.value = 0L
        if (deleteFile) file?.delete()
    }

    /** Dismiss the preview sheet: stop playback, delete the tmp clip, hide UI. */
    fun dismissPreview() {
        previewJob?.cancel()
        stopPreviewPlayback(deleteFile = true)
        _preview.value = PreviewState.Idle
    }

    fun playSong(song: SongEntity) {
        manualQueueIds.clear()
        _isQueueMode.value = false
        // Starting fresh playback discards the manual queue, so any saved
        // shuffle-restore intent is obsolete. Shuffle state is left as-is on
        // the player (the user controls it via [toggleShuffle]).
        shuffleRestoreOnDrain = -1
        // Playing from the Library, not a playlist.
        _playingPlaylistId.value = null
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
        // On the first manual-queue action, temporarily disable shuffle so the
        // queued songs play in the exact order the user added them (right after
        // the current song). We remember whether shuffle was on so that once
        // the queue drains we can restore it — letting the remaining playlist
        // / library songs continue in shuffle, as requested.
        //
        // We intentionally do NOT truncate the timeline here. The playlist /
        // library songs that were already loaded remain in the timeline AFTER
        // the queued songs, so once the manual queue drains playback naturally
        // continues with the rest of the playlist / library the user was in —
        // instead of stopping dead the way the old truncate-and-replace
        // behaviour did. The current song is also NOT added to
        // [manualQueueIds]: only songs the user explicitly queued belong there,
        // which keeps the consume logic in [onMediaItemTransition] from
        // removing playlist songs after they play.
        if (manualQueueIds.isEmpty()) {
            if (player.shuffleModeEnabled) {
                shuffleRestoreOnDrain = if (player.shuffleModeEnabled) 1 else 0
                player.shuffleModeEnabled = false
                _isShuffled.value = false
            }
        }
    }

    /**
     * Index in the player's timeline where the next manually-queued song should
     * be inserted so it lands at the *end* of the queue zone — i.e. right after
     * any already-queued songs and before the first remaining playlist / library
     * song. The queue zone is the contiguous run of [manualQueueIds] members
     * immediately following the currently-playing item.
     *
     * Used by [addToQueue] (append to queue). [playNext] inserts at the *front*
     * of the zone (currentIndex + 1) instead, so its song plays immediately.
     */
    private fun queueZoneEndIndex(): Int {
        val player = controller ?: return 0
        var idx = player.currentMediaItemIndex + 1
        while (idx < player.mediaItemCount) {
            if (player.getMediaItemAt(idx).mediaId !in manualQueueIds) break
            idx++
        }
        return idx
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
        // Insert at the end of the queue zone (after any already-queued songs,
        // before the remaining playlist / library songs) so the manual queue
        // takes priority over the current list while still letting the list
        // resume once the queue is exhausted.
        player.addMediaItem(queueZoneEndIndex(), buildMediaItem(song))
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
        // If removing this song drained the manual queue, restore the shuffle
        // state we saved when the queue was started — same as the auto-advance
        // path in [onMediaItemTransition] — so playback continues shuffled over
        // the remaining playlist / library songs.
        if (manualQueueIds.isEmpty() && shuffleRestoreOnDrain != -1) {
            val restore = shuffleRestoreOnDrain == 1
            shuffleRestoreOnDrain = -1
            player.shuffleModeEnabled = restore
            _isShuffled.value = restore
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

    /**
     * Jump to a media item in the player's timeline by id and start playing it.
     * Used by the big player's "Up Next" panel — unlike [playFromQueue] this
     * works for *any* timeline item, not just the manual queue, since the
     * upcoming list reflects the player's actual forward order.
     */
    fun playTimelineItem(item: MediaItem) {
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
     * Move a media item already in the player's timeline into the manual queue
     * (appended at the end of the queue zone, after any already-queued songs and
     * before the remaining playlist / library songs) — the timeline equivalent
     * of [addToQueue]. Used by the big player's "Up Next" panel when the user
     * swipes a row start-to-end (left-to-right): the opposite direction from the
     * end-to-start remove gesture. Resolves [item]'s media id to its real
     * timeline index (which may differ from the row's list position under
     * shuffle), pulls it out of its current spot, and re-inserts it at
     * [queueZoneEndIndex]. Marks the song as manually queued (via
     * [manualQueueIds] and [enterManualQueueMode]) so it's consumed after it
     * plays, matching [addToQueue] semantics.
     */
    fun queueTimelineItem(item: MediaItem) {
        val player = controller ?: return
        val mediaId = item.mediaId
        var fromIndex = -1
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == mediaId) { fromIndex = i; break }
        }
        if (fromIndex < 0) return
        if (fromIndex == player.currentMediaItemIndex) return
        enterManualQueueMode()
        val captured = player.getMediaItemAt(fromIndex)
        player.removeMediaItem(fromIndex)
        player.addMediaItem(queueZoneEndIndex(), captured)
        manualQueueIds.add(mediaId)
        updateQueue()
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
        // Insert at the FRONT of the queue zone (right after the current song)
        // so this song plays next, ahead of any other queued songs and ahead
        // of the remaining playlist / library songs. Contrast with
        // [addToQueue], which appends to the end of the queue zone.
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

    /**
     * Start playing [songs] from [startIndex].
     *
     * @param fromPlaylistId when the songs come from a playlist, pass its id so
     *   the big Player's overflow menu can offer a "Remove from playlist"
     *   action for the currently-playing song. Pass null (the default) for
     *   Library / ad-hoc lists.
     * @param shuffle when true, force shuffle ON for this list (used by the
     *   "Shuffle" buttons in Library / PlaylistDetail). Set directly on the
     *   player — NOT via [toggleShuffle] — because toggle would (a) turn
     *   shuffle OFF if it was already on, and (b) be swallowed by the
     *   manual-queue pending-restore path when a queue was active. When false,
     *   the current shuffle state is left untouched.
     */
    fun playSongList(
        songs: List<SongEntity>,
        startIndex: Int = 0,
        fromPlaylistId: Long? = null,
        shuffle: Boolean = false
    ) {
        manualQueueIds.clear()
        _isQueueMode.value = false
        // Same as [playSong]: fresh playback discards the manual queue and any
        // saved shuffle-restore intent.
        shuffleRestoreOnDrain = -1
        _playingPlaylistId.value = fromPlaylistId
        val player = controller ?: return
        val items  = songs.filter { File(it.filePath).exists() }.map { buildMediaItem(it) }
        if (items.isEmpty()) return
        if (shuffle && !player.shuffleModeEnabled) {
            // onShuffleModeEnabledChanged persists this to prefs + updates
            // _isShuffled, so the player's shuffle button reflects it too.
            player.shuffleModeEnabled = true
            _isShuffled.value = true
        }
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare(); player.play()
        updateQueue()
    }

    /**
     * Remove the currently-playing song from the playlist it's currently
     * playing from ([playingPlaylistId]). The song stays in the library. No-op
     * if playback isn't from a playlist or there's no current song. Used by the
     * big Player's overflow menu — mirrors the per-row "Remove from Playlist"
     * option in PlaylistDetailScreen.
     */
    fun removeCurrentSongFromPlaylist() {
        val song = _currentSong.value ?: return
        val playlistId = _playingPlaylistId.value ?: return
        viewModelScope.launch {
            repository.removeSongFromPlaylist(playlistId, song.id)
        }
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
            _currentSong.value?.let { repository.saveLastPlayed(it.id, newPosition, _playingPlaylistId.value) }
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
        // While a manual queue is active, shuffle is intentionally held OFF so
        // the queued songs play in their added order. The user can still toggle
        // the *intent*: turning it OFF just stays off; turning it ON while a
        // queue is active remembers that intent and applies it automatically
        // once the queue drains (so "everything after the queue continues in
        // shuffle"). We never leave shuffle forced-off silently — we record
        // the user's wish via [shuffleRestoreOnDrain].
        if (_isQueueMode.value) {
            // The player is currently held off-shuffle during the queue.
            // Toggle the *pending* restore state instead of the live flag.
            val wantOn = shuffleRestoreOnDrain == -1 || shuffleRestoreOnDrain == 0
            shuffleRestoreOnDrain = if (wantOn) 1 else 0
            _isShuffled.value = false // live state stays off until the queue drains
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
        // Drag-reorder only makes sense in CUSTOM mode — in Newest / Oldest the
        // list is sorted by an attribute the user can't override by dragging.
        if (_songSortMode.value != SongSortMode.CUSTOM) return
        val list = _songs.value.toMutableList()
        val song = list.removeAt(fromIndex)
        list.add(toIndex, song)
        _songs.value = list
        syncPlayerWithMove(song.id, fromIndex, toIndex, list)
    }

    /**
     * Reorder an item within the manual Queue tab.
     *
     * The queue tab renders only the [manualQueueIds] songs, so the
     * [fromIndex]/[toIndex] it reports refer to positions inside the queue
     * *list* — NOT absolute timeline positions. Now that the queue is a zone
     * nested inside the larger playlist / library timeline (those songs sit
     * before/after the queue zone), a queue-list index no longer maps 1:1 to a
     * timeline index, which made the old `player.moveMediaItem(from, to)`
     * reorder (or delete) the wrong song. Resolve each list position to its
     * real timeline index via media id first, then move — same approach
     * [moveUpNextItem] already uses for the Up Next panel.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val player = controller ?: return
        val q = _queue.value
        if (fromIndex !in q.indices || toIndex !in q.indices) return
        if (fromIndex == toIndex) return

        val fromMediaId = q[fromIndex].mediaId
        val toMediaId = q[toIndex].mediaId

        var fromTimelineIndex = -1
        var toTimelineIndex = -1
        for (i in 0 until player.mediaItemCount) {
            val id = player.getMediaItemAt(i).mediaId
            if (id == fromMediaId) fromTimelineIndex = i
            if (id == toMediaId) toTimelineIndex = i
        }
        if (fromTimelineIndex < 0 || toTimelineIndex < 0) return

        if (fromTimelineIndex != toTimelineIndex &&
            toTimelineIndex in 0 until player.mediaItemCount) {
            player.moveMediaItem(fromTimelineIndex, toTimelineIndex)
            updateQueue()
        }
    }

    /**
     * Reorder an item within the "Up Next" list shown in the big player's
     * queue panel. The up-next list reflects the player's forward playback
     * order (which may differ from raw timeline indices under shuffle), so the
     * [fromListIndex]/[toListIndex] refer to positions inside [_upNext] rather
     * than absolute timeline positions. We resolve each list position back to
     * its real timeline index, then ask the player to move the media item —
     * the same primitive used by [moveQueueItem].
     *
     * The reorderable list library calls [onMove] with the dragging item's
     * current index and the target item's index and expects a plain
     * index→index move on the backing data (it tracks the dragged item's
     * offset itself and applies no extra adjustment), so — like
     * [moveQueueItem] — we pass the resolved timeline indices straight to
     * [Player.moveMediaItem] without any ±1 correction.
     */
    fun moveUpNextItem(fromListIndex: Int, toListIndex: Int) {
        val player = controller ?: return
        val upList = _upNext.value
        if (fromListIndex !in upList.indices || toListIndex !in upList.indices) return
        if (fromListIndex == toListIndex) return

        val fromMediaId = upList[fromListIndex].mediaId
        val toMediaId = upList[toListIndex].mediaId

        // Resolve the two media ids to their real timeline indices.
        var fromTimelineIndex = -1
        var toTimelineIndex = -1
        for (i in 0 until player.mediaItemCount) {
            val id = player.getMediaItemAt(i).mediaId
            if (id == fromMediaId) fromTimelineIndex = i
            if (id == toMediaId) toTimelineIndex = i
        }
        if (fromTimelineIndex < 0 || toTimelineIndex < 0) return

        if (fromTimelineIndex != toTimelineIndex && toTimelineIndex in 0 until player.mediaItemCount) {
            player.moveMediaItem(fromTimelineIndex, toTimelineIndex)
            updateQueue()
        }
    }

    /**
     * Remove an item from the "Up Next" list shown in the big player's queue
     * panel. Resolves [item]'s media id to its real timeline index (which may
     * differ from the row's list position under shuffle) and removes it from
     * the player — the same primitive used by [removeFromQueue]. Also drops it
     * from the manual queue set if it was queued manually, keeping the manual
     * [queue] flow consistent.
     *
     * Takes the [MediaItem] (identity) rather than a list index so the removal
     * always targets the exact row the user swiped, even if the list reorders
     * between the swipe starting and [androidx.compose.material3.SwipeToDismissBox]
     * firing its confirm callback (a stale positional index could otherwise
     * delete the row below). Mirrors how the Queue tab removes by media id.
     */
    fun removeUpNextItem(item: MediaItem) {
        val player = controller ?: return
        val mediaId = item.mediaId

        // Resolve the media id to its real timeline index and remove it.
        for (i in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == mediaId) {
                player.removeMediaItem(i)
                break
            }
        }
        manualQueueIds.remove(mediaId)
        updateQueue()
    }

    fun endDrag() {
        // Keep the drag flag on until the DB write-back finishes. Otherwise the
        // allSongs collector un-gates the instant we flip the flag, emitting a
        // partially-updated list (some rows have new sortOrder, others old) and
        // causing the list to flicker to a mixed order before settling.
        // Only write back in CUSTOM mode — in Newest / Oldest the on-screen
        // order isn't the persisted order, so writing indices would scramble
        // the user's custom order behind their back.
        if (_songSortMode.value != SongSortMode.CUSTOM) {
            isDragInProgress = false
            return
        }
        viewModelScope.launch {
            _songs.value.forEachIndexed { index, song -> repository.updateSortOrder(song.id, index) }
            isDragInProgress = false
        }
    }

    fun loadPlaylistSongs(playlistId: Long) {
        playlistSongsJob?.cancel()
        loadedPlaylistId = playlistId
        playlistSongsJob = viewModelScope.launch {
            val mode = getPlaylistSortMode(playlistId)
            repository.getPlaylistSongs(playlistId).collect {
                if (!isDragInProgress) {
                    _playlistSongs.value = applyPlaylistSort(it, mode)
                }
            }
        }
    }

    fun movePlaylistSong(playlistId: Long, fromIndex: Int, toIndex: Int) {
        // Drag-reorder only makes sense in CUSTOM mode (Newest / Oldest are
        // sorted by an attribute the user can't override by dragging).
        if (getPlaylistSortMode(playlistId) != SongSortMode.CUSTOM) return
        val list = _playlistSongs.value.toMutableList()
        val song = list.removeAt(fromIndex)
        list.add(toIndex, song)
        _playlistSongs.value = list
        syncPlayerWithMove(song.id, fromIndex, toIndex, list)
    }

    fun endPlaylistDrag(playlistId: Long) {
        // Keep the drag flag on until the DB write-back finishes — same reason
        // as endDrag(): avoids the playlistSongs collector emitting a
        // partially-reordered list and flickering the UI. Only persist in
        // CUSTOM mode; in Newest / Oldest the on-screen order isn't the
        // persisted one so writing it back would scramble the user's order.
        if (getPlaylistSortMode(playlistId) != SongSortMode.CUSTOM) {
            isDragInProgress = false
            return
        }
        viewModelScope.launch {
            repository.updatePlaylistSongOrder(playlistId, _playlistSongs.value)
            isDragInProgress = false
        }
    }

    // ── Playlists-tab drag (reorder the playlists themselves) ───────────────
    // Reuses the same `isDragInProgress` flag as the song/playlist-song drags.
    // Since the Playlists tab is always shown in manual order (sorted by
    // `playlists.sortOrder ASC`), there's no sort-mode gate here — any reorder
    // is the user's intended custom order and gets persisted on drop.
    fun movePlaylistListItem(fromIndex: Int, toIndex: Int) {
        val list = _playlists.value.toMutableList()
        if (fromIndex !in list.indices || toIndex !in 0..list.size) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _playlists.value = list
    }

    /** Persist the current on-screen playlist order and re-open the collector. */
    fun endPlaylistListDrag() {
        viewModelScope.launch {
            repository.updatePlaylistListOrder(_playlists.value.map { it.playlist })
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

    /**
     * Permanently delete every song selected on the Playlist Detail screen from
     * the library (removing their files and playlist cross-refs), then clear
     * the playlist selection. Mirrors [deleteSelected] but operates on the
     * playlist-detail selection set instead of the library's.
     */
    fun deletePlaylistSelected() {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        clearPlaylistSelection()
        viewModelScope.launch {
            controller?.let { player ->
                val toRemove = mutableListOf<Int>()
                for (i in 0 until player.mediaItemCount) if (player.getMediaItemAt(i).mediaId in ids) toRemove.add(i)
                toRemove.sortedDescending().forEach { player.removeMediaItem(it) }
            }
            repository.deleteSongs(ids)
        }
    }

    /** Add every selected song to the given playlist, keeping the selection active. */
    fun addPlaylistSelectedToPlaylist(targetPlaylistId: Long) {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // Insert in reverse so the first selected song ends up at the very
            // top of the playlist (each later insert bumps the previous ones
            // down by one). New songs always land at the top.
            ids.reversed().forEach { repository.addSongToPlaylist(targetPlaylistId, it) }
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
            // Insert in reverse so the first selected song ends up at the very
            // top of the playlist (each later insert bumps the previous ones
            // down by one). New songs always land at the top.
            ids.reversed().forEach { repository.addSongToPlaylist(playlistId, it) }
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
        viewModelScope.launch {
            if (buildAndShareZip(ids, _songs.value)) clearSelection()
        }
    }

    /**
     * Same as [shareSelectedAsZip] but for songs selected on the Playlist Detail
     * screen (the playlist-detail selection set, not the library's). Shares the
     * playlist's selected songs' files as a zip and clears the playlist
     * selection on success.
     */
    fun sharePlaylistSelectedAsZip() {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (buildAndShareZip(ids, _playlistSongs.value)) clearPlaylistSelection()
        }
    }

    /**
     * Build "muse_share.zip" from the given songs (those whose id is in [ids])
     * and emit a share intent. Reports progress via [isZipping] and surfaces
     * failures via [errorEvents]. Returns true on success (so the caller can
     * clear its selection), false otherwise. Shared by the library and playlist
     * batch-share paths so both behave identically.
     */
    private suspend fun buildAndShareZip(ids: Set<String>, songs: List<SongEntity>): Boolean {
        if (_isZipping.value) return false
        _isZipping.value = true
        try {
            val toZip = songs.filter { it.id in ids }
            val file = repository.zipSongs(toZip)
            if (file == null) {
                _errorEvents.emit("None of the selected songs have a file to share")
                return false
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
            return true
        } catch (e: Exception) {
            _errorEvents.emit("Failed to create zip: ${e.localizedMessage}")
            return false
        } finally {
            _isZipping.value = false
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
            if (buildAndShareLinks(ids, _songs.value)) clearSelection()
        }
    }

    /**
     * Same as [shareSelectedAsLinks] but for songs selected on the Playlist
     * Detail screen. Shares the playlist's selected songs' source links as text
     * and clears the playlist selection on success.
     */
    fun sharePlaylistSelectedAsLinks() {
        val ids = _playlistSelectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (buildAndShareLinks(ids, _playlistSongs.value)) clearPlaylistSelection()
        }
    }

    /**
     * Collect the source URLs of the given songs (those whose id is in [ids]),
     * drop non-http(s) ones, dedupe, and emit a text/plain share intent with one
     * link per line. Surfaces an error if no selected song has a link. Returns
     * true on success (so the caller can clear its selection), false otherwise.
     * Shared by the library and playlist batch-share-link paths.
     */
    private suspend fun buildAndShareLinks(ids: Set<String>, songs: List<SongEntity>): Boolean {
        val links = songs
            .filter { it.id in ids }
            .map { it.sourceUrl.trim() }
            .filter { it.startsWith("http") }
            .distinct()
        if (links.isEmpty()) {
            _errorEvents.emit("None of the selected songs have a link to share")
            return false
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
        return true
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
    fun addSongToPlaylist(playlistId: Long, songId: String) = viewModelScope.launch {
        repository.addSongToPlaylist(playlistId, songId)
    }
    fun renamePlaylist(id: Long, name: String) = viewModelScope.launch { repository.renamePlaylist(id, name) }
    fun removeSongFromPlaylist(playlistId: Long, songId: String) = viewModelScope.launch { repository.removeSongFromPlaylist(playlistId, songId) }
    fun getPlaylistSongs(playlistId: Long) = repository.getPlaylistSongs(playlistId)
    suspend fun getPlaylistById(id: Long) = repository.getPlaylistById(id)

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        playlistSongsJob?.cancel()
        previewJob?.cancel()
        previewProgressJob?.cancel()
        // Release the throwaway preview player and delete any leftover tmp clip.
        (_preview.value as? PreviewState.Ready)?.file?.delete()
        previewPlayer?.release()
        previewPlayer = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        // NOTE: we intentionally do NOT cancel downloads here — they live in
        // the app-scoped DownloadState and should keep running after the
        // ViewModel (and Activity) are destroyed.
    }
}
