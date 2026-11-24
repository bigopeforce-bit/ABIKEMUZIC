# ABIKEMUZIC

A small, original Android music app: stream a direct audio link, save tracks to an offline library, and follow synced lyrics.

## Features

- **Online listening** — paste a direct audio URL (MP3/Opus/AAC) and press Play Online.
- **Offline library** — download that URL to app-local storage, play it without a connection, long-press a track to delete it.
- **Lyrics** — paste LRC text, fetch a matching `.lrc` file from the same URL, and lyrics are saved per track for offline use. Timed lines highlight as the song plays.
- **Branding** — ABIKEMUZIC name, icon and dark/accent theme.

## Build

Built with the on-phone plain-Java toolchain (no Gradle, no Kotlin, no external libraries):

```bash
bash ~/.buddy/work/.claude/skills/android-app/setup-toolchain.sh
bash ~/.buddy/work/.claude/skills/android-app/build-apk.sh ~/.buddy/work/abikemuzic
```

Output: `build/app.apk`. Application id: `com.buddyapps.abikemuzic`.

## Relationship to SimpMusic and Spotube

This project combines the *ideas* of online streaming, offline downloads and synced lyrics found in [SimpMusic](https://github.com/maxrave-dev/SimpMusic) (GPL-3.0) and [Spotube](https://github.com/team-spotube/spotube) (AGPL-3.0). No source code from either project is included here; this is a fresh, minimal implementation written in plain Java so it can be built directly on a phone. It does not use Spotify, YouTube Music or any other service's private APIs — you supply a direct audio URL you are allowed to play and download.

## License

MIT. See `LICENSE`.
