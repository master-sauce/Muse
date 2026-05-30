package com.Music

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Initialize YoutubeDL and FFmpeg
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            
            // Background update yt-dlp to ensure it works with latest site changes
            // This often fixes the "doesn't do anything" issue when downloading
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(this@MuseApp)
                    Log.d("MuseApp", "YoutubeDL updated successfully")
                } catch (e: Exception) {
                    Log.e("MuseApp", "Failed to update YoutubeDL", e)
                }
            }
        } catch (e: Exception) {
            Log.e("MuseApp", "Failed to initialize YoutubeDL components", e)
        }
    }
}
