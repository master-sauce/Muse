
# Muse
Your Music, Your Rules. No Subscriptions. No Cloud Lock‑in.

Muse is a freedom-first music player that puts you back in control of your library. Import your local files, extract Media from supported sources, and enjoy a sleek interface — all without monthly fees or accounts.

## Download
Grab the latest APK from the Releases page.

https://github.com/master-sauce/Muse/releases


## Why Muse?
Other music apps lock you into monthly subscriptions, make songs disappear when licensing expires, impose cloud storage quotas, and bombard you with ads, trackers, and telemetry. Many won't even let you play local files alongside streaming content.

Muse is different. One download and it's yours forever. No subscription. Your files live on your device where nobody can take them away. No cloud means no quotas — your storage, your rules. No accounts, no tracking, no ads. Import anything from your device and keep everything in one unified library.

## What It Does
🎵 Import Your Local Library
Pick audio and video files or entire folders from your device. MP3, FLAC, M4A, MP4 — whatever you have.

🔗 Extract from Supported Sources
Paste a media URL. Muse uses yt-dlp (the open-source media extraction tool) to retrieve content for offline playback.

🎧 Full-Featured Playback
Playlists — Group songs however you want. Add, remove, reorder.
Lyrics — Auto-fetched synced lyrics that scroll with the music.
Background Playback — Mini player, shuffle, repeat, seek, skip. Full MediaSession support.

🔒 No Accounts, No Ads, No Paywalls
Just a music player that respects your files and your privacy.

📂 Your Library, Your Folder
Songs are stored as normal files in `Download/Muse` (or any folder you pick), with a `.json` sidecar holding their metadata and a `.m3u8` per playlist. Edit or delete them with any file manager — Muse re-syncs from the folder on launch.

---

## Tech Stack

- **UI**: Jetpack Compose, Material 3, Navigation for Compose
- **Player**: ExoPlayer (Media3) with MediaSessionService for background playback
- **Database**: Room (SQLite) with migrations
- **Networking**: Retrofit (Odesli for link resolution, LrcLib for lyrics)
- **Downloader**: youtube-dl-android (yt-dlp + FFmpeg)
- **Architecture**: MVVM with AndroidViewModel, Coroutines + Flow
- **Build**: Gradle with Kotlin DSL

---

## Library Folder

Muse keeps every song outside the app sandbox so the files stay yours:

```
Download/Muse/
  Artist - Title [songId].m4a     ← audio (any format yt-dlp produced)
  Artist - Title [songId].json    ← title, artist, duration, artwork URL, source
                                    link, add date, playlist membership
  Playlists/
    Chill.m3u8                    ← standard M3U8; one per playlist
```

The folder is chosen once on first launch (`Download/Muse` is the default spot)
and can be changed any time under **Add Music → Folder → Library folder**,
where **Rescan** re-reads it on demand. While a migration/rescan is running an
ongoing notification shows real progress (`32% • 16/50` plus the current song);
it can't be swiped away and stops only through its **Cancel** button (the pass
resumes where it left off next time). On every cold start Muse re-syncs:

- file deleted in the folder → song removed from the library
- file added to the folder → adopted (tags, then `Artist - Title` filename)
- `.json` edited → title / artist / duration / artwork / source link updated
- `.m3u8` edited or deleted → playlist membership / the playlist itself updated

No folder access is required — without it downloads simply stay in app-private
storage — but only a granted folder is user-editable.

---

## Permissions
| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Fetch lyrics, metadata, and media extraction|
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

MIT License

Copyright (c) 2026 master-sauce

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

---


## ⚠️ Legal & Fair Use Notice
Muse is a tool for managing and playing media files. The included yt-dlp integration allows extraction of content from supported platforms for personal, offline use.

By using this software, you agree that:

You are solely responsible for complying with the Terms of Service of any platform you extract content from
You will only extract content that you have the legal right to access and store, including:
Content you own or have created
Creative Commons or public domain works
Content where the rights holder has granted permission for download
You understand that unauthorized extraction of copyrighted material may violate laws in your jurisdiction
The developers of Muse do not endorse, encourage, or support copyright infringement and provide this tool solely for legitimate personal media management
This software is provided for educational and archival purposes. Respect creators' rights.

---


## Acknowledgments


**ExoPlayer/Media3** — media playback - https://github.com/androidx/media

**yt-dlp** — open-source media extraction - https://github.com/yt-dlp/yt-dlp

**LrcLib** — lyrics database - https://lrclib.net

**Odesli / song.link** — cross-platform track link resolution - https://odesli.co

**Jetpack Compose & Material 3** — UI toolkit

---

> Made with ❤️ for music lovers who demand freedom.