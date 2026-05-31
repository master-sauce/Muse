
# Muse

Your Music, Your Rules. No Subscriptions. No Cloud Lock‑in.

Muse is a freedom‑first music player that puts you back in control of your library. Download songs from anywhere, import your local files, and enjoy a sleek interface — all without a single monthly fee.

---

## Why Muse?

- **🎵 Own Your Music** — Download from Music links and keep the files forever. No streaming, no disappearing albums.
- **📂 Bring Your Collection** — Import every song and playlist you already own. Muse plays your local files, no cloud required.
- **🎤 Lyrics That Keep Up** — Real‑time synced lyrics that scroll with the beat, plus plain text fallbacks.
- **📱 Mini Player, Big Impact** — A sleek bottom bar keeps your music at your fingertips while you browse.
- **🔄 Playlists Your Way** — Create, reorder, and manage playlists exactly how you like.

---

## Screenshots

> *(Add your own screenshots here)*

---

## Getting Started

### Prerequisites

- Android Studio Hedgehog | 2023.1.1 or newer
- JDK 17
- Android SDK (API 24+)

### Clone & Build

```bash
git clone https://github.com/yourusername/Muse.git
cd Muse
./gradlew assembleDebug
```

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

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
- **yt-dlp** — reliable audio extraction
- **Odesli (song.link)** — cross‑platform link resolution
- **LrcLib** — lyrics database
- **Jetpack Compose** — declarative UI toolkit
- **Material 3** — design system and theming

---

> Made with ❤️ for music lovers who demand freedom. Enjoy the glow.