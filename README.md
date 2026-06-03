
# Muse

Your Music, Your Rules. No Subscriptions. No Cloud Lock‑in.

Muse is a freedom‑first music player that puts you back in control of your library. Download songs from anywhere, import your local files, and enjoy a sleek interface — all without a single monthly fee.

---


## Why Muse?

Other music apps — Streaming requires monthly payment. Songs disappear when licensing expires. Cloud storage quotas and sync limits. Ads, trackers, telemetry everywhere. Can't play local files alongside streaming.

Muse — One download, yours forever. No subscription. Files live on your device. Nobody can take them away. No cloud. No quotas. Your storage, your rules. Nothing. No accounts, no tracking, no ads. Import anything from your device. Everything in one library.

---

## What it does

Download songs from links — Paste a Music URL. Muse grabs the audio and video and stores it locally. Yours to keep.
Import your own files — Pick audio and video files or entire folders from your device. MP3, FLAC, M4A, MP4 whatever you have.
Playlists — Group your songs however you want. Add, remove, reorder.
Lyrics — Auto-fetched synced lyrics that scroll with the music. Works for most songs.
Playback controls — Shuffle, repeat, seek, skip. Mini player in the library so you never lose your place.
No accounts, no ads, no paywalls — Just a music player that respects your files.

---

## Download
The easiest way to get Muse is to download the APK from the Releases page on GitHub:

https://github.com/master-sauce/Muse/releases/tag/releases

Grab the latest .apk file and install it on your Android device.


---

## Project Structure

```
app/src/main/java/com/Music/
│   data/
│   │   ├── local/          // Room entities, DAOs, database
│   │   ├── remote/         // Retrofit services (Odesli, Lyrics)
│   │   └── MusicRepository.kt
│   downloader/            // DownloadManager (yt-dlp wrapper)
│   player/                // PlaybackService (MediaSession)
│   ui/theme/              // Material 3 theme, colors, typography
│   MainActivity.kt
│   MainViewModel.kt
│   LibraryScreen.kt
│   PlayerScreen.kt
│   LyricsScreen.kt
│   PlaylistDetailScreen.kt
│   MuseApp.kt
└── AndroidManifest.xml
```

---

## Tech Stack

- **UI**: Jetpack Compose, Material 3, Navigation for Compose
- **Player**: ExoPlayer (Media3) with MediaSessionService for background playback
- **Database**: Room (SQLite) with migrations
- **Networking**: Retrofit (Odesli API for link resolution, LrcLib for lyrics)
- **Downloader**: youtube-dl-android (yt-dlp + FFmpeg)
- **Architecture**: MVVM with AndroidViewModel, Coroutines + Flow
- **Build**: Gradle with Kotlin DSL

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Download/stream audio, fetch lyrics |
| `READ_MEDIA_AUDIO` (API 33+) | Access local audio files |
| `READ_EXTERNAL_STORAGE` (≤ API 32) | Legacy audio access |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Store downloads |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback |
| `POST_NOTIFICATIONS` (API 33+) | Show playback notification |

---

## Contributing

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit (`git commit -m 'Add amazing feature'`)
4. Push (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## Known Issues & Limitations

- Video playback is basic; no advanced controls or subtitles.
- Large library imports may take a few seconds; progress is shown in UI.
- Lyrics depend on LrcLib availability; some tracks may not have synced or plain lyrics.

---

## License

```

```

---

## Acknowledgments

- **ExoPlayer/Media3** — robust media playback foundation
- **yt-dlp** — reliable audio and video extraction
- **Odesli (song.link)** — cross‑platform link resolution
- **LrcLib** — lyrics database
- **Jetpack Compose** — declarative UI toolkit
- **Material 3** — design system and theming

---

> Made with ❤️ for music lovers who demand freedom. Enjoy the glow.