package com.Music.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.Music.data.local.SongEntity
import com.Music.downloader.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Pauses playback when audio is about to become "noisy" — i.e. the user
    // unplugs headphones or disconnects a Bluetooth audio device. Matches the
    // behavior of YouTube Music / Spotify, which stop rather than blasting out
    // the phone speaker.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                mediaSession?.player?.pause()
            }
        }
    }
    private var noisyReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()

        // AudioAttributes.DEFAULT has USAGE_UNKNOWN — breaks audio focus
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()

        // Restore the last played song immediately so the media notification
        // appears on launch before the Activity's ViewModel even connects.
        // Without this, there's a gap between the app opening and the
        // notification appearing while the MediaController connection is
        // being established.
        scope.launch {
            try {
                val repo = DownloadState.repository()
                val lastId = repo.getLastPlayedSongId() ?: return@launch
                val song = repo.getSongById(lastId) ?: return@launch
                if (!File(song.filePath).exists()) return@launch
                val lastPos = repo.getLastPlayedPosition()

                withContext(Dispatchers.Main) {
                    mediaSession?.player?.let { p ->
                        val item = buildMediaItem(song)
                        p.playWhenReady = false
                        p.setMediaItem(item, lastPos)
                        p.prepare()
                    }
                }
            } catch (_: Exception) {
                // Non-critical: if this fails, the ViewModel will still
                // restore the session when it connects.
            }
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Register the "becoming noisy" receiver once the service is actually
        // running so we can pause when headphones / BT disconnect mid-playback.
        if (!noisyReceiverRegistered) {
            registerReceiver(
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            noisyReceiverRegistered = true
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(becomingNoisyReceiver) }
            noisyReceiverRegistered = false
        }
        scope.coroutineContext.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (mediaSession?.player?.playWhenReady != true) stopSelf()
    }
}