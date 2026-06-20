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
 * Open YouTube. Tries, in order:
 *   1. YouTube Music — Morph version (app.morphe.android.apps.youtube.music)
 *   2. YouTube Music app (com.google.android.apps.youtube.music)
 *   3. YouTube app (vnd.youtube://)
 *   4. https://music.youtube.com in a browser
 *   5. https://www.youtube.com in a browser
 * Falls through to the next option whenever the previous one isn't available.
 */
fun openYouTube(context: Context) {
    // 1. YouTube Music — Morph version
    val morphIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage("app.morphe.android.apps.youtube.music")
    }
    if (tryStart(context, morphIntent)) return

    // 2. YouTube Music app
    val ytMusicIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage("com.google.android.apps.youtube.music")
    }
    if (tryStart(context, ytMusicIntent)) return

    // 3. YouTube app
    val ytAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStart(context, ytAppIntent)) return

    // 4. YouTube Music in browser
    val ytMusicWebIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStart(context, ytMusicWebIntent)) return

    // 5. YouTube in browser
    val ytWebIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (tryStart(context, ytWebIntent)) return

    Toast.makeText(context, "No browser or YouTube app found", Toast.LENGTH_SHORT).show()
}

private fun tryStart(context: Context, intent: Intent): Boolean {
    return try {
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
