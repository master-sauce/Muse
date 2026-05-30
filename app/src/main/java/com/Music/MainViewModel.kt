package com.Music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = Room.databaseBuilder(
        application,
        AppDatabase::class.java, "muse-db"
    ).build()

    private val odesliService = Retrofit.Builder()
        .baseUrl("https://api.song.link/v1-alpha.1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OdesliService::class.java)

    private val repository = MusicRepository(
        db.songDao(),
        odesliService,
        DownloadManager(application)
    )

    val songs: StateFlow<List<SongEntity>> = repository.allSongs.let { flow ->
        val state = MutableStateFlow<List<SongEntity>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>()
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController? get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentSong = MutableStateFlow<SongEntity?>(null)
    val currentSong = _currentSong.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress = _playbackProgress.asStateFlow()

    private var progressJob: Job? = null

    init {
        val sessionToken = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(application, sessionToken).buildAsync()
        controllerFuture?.addListener({
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val player = controller ?: return
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startProgressUpdate() else stopProgressUpdate()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val songId = mediaItem?.mediaId
                _currentSong.value = songs.value.find { it.id == songId }
            }
        })
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let {
                    if (it.duration > 0) {
                        _playbackProgress.value = it.currentPosition.toFloat() / it.duration
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    fun downloadSong(url: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            try {
                repository.downloadAndSave(url) { progress ->
                    _downloadProgress.value = progress
                }
            } catch (e: Exception) {
                _errorEvents.emit("Download failed: ${e.localizedMessage}")
            } finally {
                _isDownloading.value = false
                _downloadProgress.value = 0f
            }
        }
    }

    fun playSong(song: SongEntity) {
        val player = controller ?: return
        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.fromFile(File(song.filePath)))
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun seekTo(progress: Float) {
        val player = controller ?: return
        if (player.duration > 0) {
            player.seekTo((progress * player.duration).toLong())
        }
    }

    fun togglePlayback() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch {
            repository.deleteSong(song)
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
