package com.Music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import com.Music.data.MusicRepository
import com.Music.data.local.AppDatabase
import com.Music.data.local.SongEntity
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

    private val db = Room.databaseBuilder(application, AppDatabase::class.java, "muse-db").build()

    private val odesliService = Retrofit.Builder()
        .baseUrl("https://api.song.link/v1-alpha.1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OdesliService::class.java)

    private val repository = MusicRepository(
        db.songDao(), odesliService, DownloadManager(application), application
    )

    private val _songs = MutableStateFlow<List<SongEntity>>(emptyList())
    val songs: StateFlow<List<SongEntity>> = _songs.asStateFlow()

    private val _isDownloading   = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _isPlaying       = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentSong     = MutableStateFlow<SongEntity?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress = _playbackProgress.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration        = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _isShuffled      = MutableStateFlow(false)
    val isShuffled = _isShuffled.asStateFlow()

    private val _repeatMode      = MutableStateFlow(RepeatMode.NONE)
    val repeatMode = _repeatMode.asStateFlow()

    private var progressJob: Job? = null

    init {
        viewModelScope.launch { repository.allSongs.collect { _songs.value = it } }

        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, token).buildAsync()
        controllerFuture?.addListener({ setupController() }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val player = controller ?: return
        player.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
                if (playing) startProgressUpdate() else stopProgressUpdate()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentSong.value    = _songs.value.find { it.id == mediaItem?.mediaId }
                _playbackProgress.value = 0f
                _currentPosition.value  = 0L
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) _duration.value = controller?.duration ?: 0L
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                viewModelScope.launch { _errorEvents.emit("Playback error: ${error.localizedMessage}") }
            }
        })
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let { p ->
                    val dur = p.duration
                    val pos = p.currentPosition
                    if (dur > 0) {
                        _playbackProgress.value = pos.toFloat() / dur
                        _currentPosition.value  = pos
                        _duration.value         = dur
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() { progressJob?.cancel() }

    // ── Public actions ───────────────────────────────────────────────────────

    fun downloadSong(url: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            try {
                repository.downloadAndSave(url) { _downloadProgress.value = it }
            } catch (e: Exception) {
                _errorEvents.emit("Download failed: ${e.localizedMessage}")
                Log.e("MusicVM", "download", e)
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

    fun importFromFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            try { repository.importFromFolder(treeUri) }
            catch (e: Exception) { _errorEvents.emit("Folder import failed: ${e.localizedMessage}") }
            finally { _isImporting.value = false }
        }
    }

    /** Sets full library as ExoPlayer queue, starts at tapped song. */
    fun playSong(song: SongEntity) {
        val player = controller ?: run {
            viewModelScope.launch { _errorEvents.emit("Player not ready") }
            return
        }

        val items = mutableListOf<MediaItem>()
        var startIndex = 0

        _songs.value.forEach { s ->
            if (File(s.filePath).exists()) {
                if (s.id == song.id) startIndex = items.size
                items.add(
                    MediaItem.Builder()
                        .setMediaId(s.id)
                        .setUri(Uri.fromFile(File(s.filePath)))
                        .build()
                )
            }
        }

        if (items.isEmpty()) {
            viewModelScope.launch { _errorEvents.emit("No playable files found") }
            return
        }

        player.setMediaItems(items, startIndex, 0L)
        player.shuffleModeEnabled = _isShuffled.value
        player.repeatMode = _repeatMode.value.toExo()
        player.prepare()
        player.play()
    }

    // Uses ExoPlayer's built-in prev logic:
    // — position > 3s → restart current; position ≤ 3s → previous track
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

    fun seekTo(fraction: Float) {
        val p = controller ?: return
        if (p.duration > 0) p.seekTo((fraction * p.duration).toLong())
    }

    fun togglePlayback() {
        val p = controller ?: return
        if (p.isPlaying) p.pause()
        else if (p.mediaItemCount > 0) p.play()
    }

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch { repository.deleteSong(song) }
    }

    private fun RepeatMode.toExo() = when (this) {
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}