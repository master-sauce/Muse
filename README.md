
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
Songs are stored as normal files in `Download/Muse` (or any folder you pick), with a `.json` sidecar holding their metadata and a `.m3u8` per playlist. Edit or delete them with any file manager — then hit **Sync folder** so Muse picks the changes up.

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
and can be changed any time under **Add Music → Folder → Library folder**.
Nothing is scanned automatically on startup (it made launches slow with a few
thousand files) — the folder is read when you ask for it:

- **Sync folder** — moves songs still in app-private storage into the folder,
  adopts added/edited files, drops deleted ones, and runs the playlist passes.
  The button shows how many songs are still waiting to be moved.
- **Restore playlists from files** — rebuilds the whole playlist set from the
  per-song `.json` records: creates any playlist that is missing (even when no
  `.m3u8` exists), fills it with the songs whose JSON names it, and rewrites the
  `.m3u8`. On a fresh install this runs automatically the first time a folder is
  granted, so pointing Muse at a backed-up folder restores library + playlists.

While a sync is running an ongoing notification shows real progress
(`Checking playlists • 3/7` plus the current item); it can't be swiped away
(and comes back if it is) and stops only through its **Cancel** button — the
pass resumes where it left off next time. What a sync applies:

- file deleted in the folder → song removed from the library
- file added to the folder → adopted (tags, then `Artist - Title` filename)
- `.json` edited → title / artist / duration / artwork / source link updated
- `.m3u8` edited or deleted → playlist membership / the playlist itself updated

No folder access is required — without it downloads simply stay in app-private
storage — but only a granted folder is user-editable.

---

## Migration & Fresh Install

### Fresh install (nothing on the device yet)
1. Install, open Muse.
2. The folder picker opens at `Download` → create/pick `Muse` → **Use this folder** → allow.
3. Done. Downloads land in that folder from the first song on, each with its
   `.json` sidecar and a `.m3u8` per playlist.

### Fresh install over an existing backup folder (new phone / reinstall / cleared data)
1. Open Muse and pick **the same** `Download/Muse` folder (not `Download` — the
   scan is not recursive).
2. Press **Sync folder** in **Add Music → Folder → Library folder**.
3. Wait for the ongoing notification (`Moving songs` → `Reading folder` →
   `Checking playlists` → disappears). Muse adopts every audio file, reads its
   `.json`, recreates the playlists from the records and rewrites the `.m3u8`
   files. Nothing else to do.
4. If a playlist still looks short, press **Restore playlists from files**
   (forced rebuild from the `.json` records).

### Updating from a build that stored songs in app-private storage
1. Sign with the **same key** and install over the top (do **not** uninstall —
   that wipes the database the migration reads from).
2. Open Muse → pick `Download/Muse` when asked (or use Change).
3. Press **Sync folder**. The button shows `N songs are still in app storage`;
   the notification tracks the move. Each song is copied first, then its row is
   repointed and the private copy deleted — interrupting it is safe, the pass
   resumes next time.
4. Keep the app in the foreground for the first run if you can: a file copied
   at the moment the process dies can land truncated (the next sync repairs or
   you delete that one file).

### What survives what

| | In-place update (same key) | Reinstall / clear data |
|---|---|---|
| Songs, `.json` metadata, playlists | ✅ | ✅ rebuilt from the folder |
| Playlist order & membership | ✅ | ✅ from `.m3u8` / `.json` |
| Export-links data (`sourceUrl`) | ✅ | ✅ from `.json` |
| Newest/Oldest sort (`createdAt`) | ✅ | ✅ from `.json` |
| Custom drag order (songs, playlist list) | ✅ | ❌ reset |
| Last played / shuffle / auto-add playlist | ✅ | ❌ |

### Rules of thumb
- `Sync folder` = "make the app match the folder" (deletions included).
  `Restore playlists from files` = "rebuild from the `.json` records" (additive).
- Edit the folder however you like, then Sync. Deleting a song file removes it
  from the library **and** from every playlist.
- Removing a song from an `.m3u8` removes it on the next sync; Muse also clears
  that name from the song's `.json`, so a later restore won't bring it back.
- Never copy songs into `Download/Muse` by hand while a migration is running.
- Keep the `.json` files — they hold the metadata and playlist membership; the
  audio alone loses titles, links and playlists.

---

## Permissions
| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Fetch lyrics, metadata, and media extraction|
| `READ_MEDIA_AUDIO` (API 33+) | Access local audio files |
| `READ_EXTERNAL_STORAGE` (≤ API 32) | Legacy audio access |
| `WRITE_EXTERNAL_STORAGE` (≤ API 28) | Store downloads |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background playback |
| `FOREGROUND_SERVICE_DATA_SYNC` | Downloads, folder sync and bulk library jobs keep running with the app closed |
| `POST_NOTIFICATIONS` (API 33+) | Show playback / progress notifications |

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