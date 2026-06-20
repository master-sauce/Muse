package com.Music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

fun Long.toTimeString(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Open YouTube. Tries the native app first (so the user can browse / search
 * comfortably), and falls back to the browser if the app isn't installed.
 */
fun openYouTube(context: Context) {
    val nativeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(nativeIntent)
        return
    } catch (_: Exception) {
        // YouTube app not installed — fall through to browser
    }

    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(webIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "No browser or YouTube app found", Toast.LENGTH_SHORT).show()
    }
}
