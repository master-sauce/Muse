package com.Music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.Music.data.MusicRepository
import com.Music.data.local.AppDatabase
import com.Music.data.local.SongEntity
import com.Music.data.remote.OdesliService
import com.Music.downloader.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

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

    fun downloadSong(url: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            try {
                repository.downloadAndSave(url) { progress ->
                    _downloadProgress.value = progress
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isDownloading.value = false
                _downloadProgress.value = 0f
            }
        }
    }

    fun deleteSong(song: SongEntity) {
        viewModelScope.launch {
            repository.deleteSong(song)
        }
    }
}
