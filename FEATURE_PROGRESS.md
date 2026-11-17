# FEATURE_PROGRESS
Vote: [x] Option A (Full Native Torio Engine). Build env: ANDROID_HOME=/opt/android, `./gradlew assembleDebug` passes.

## Done (v0.1.0)
- [x] CLAUDE.md, .claude/rules/ui.md, .claude/settings.json, CI workflow (.github/workflows/build-apk.yml)
- [x] Gradle scaffold, manifest, wrapper
- [x] Room: books/tracks/progress; SyncRepository + LocalSyncRepository (Phase 2 hook)
- [x] TorBoxApiClient (cloud list, search API, add magnet, links), AioStreamsClient (Stremio protocol), SourceParser
- [x] Media3AudioService: 96MB RAM buffer, 10-min read-ahead, torbox:// resolving, speed 0.5-3.5 pitch-kept, 3s progress saves
- [x] VoiceDspEnhancer: EQ + compressor/limiter + loudness
- [x] DownloadWorker (offline), BookRepository
- [x] Compose UI: Search + source picker, Library (in progress/downloaded/finished), Player (speed, DSP, chapters), Settings
- [x] Auto-resume on launch

## Done (v0.2.0)
- [x] Search is audio-only: video releases (BluRay/x264/1080p/S01E01...) dropped; needs an audio codec or audiobook wording
- [x] Results ranked by quality: FLAC > M4B > M4A/AAC/Opus/Ogg > MP3, then bitrate, then cached/seeders
- [x] Browse tab: in-app browser for AudiobookBay (address editable in Settings), LibriVox, Internet Archive, Loyal Books; logins persist; tapping a magnet adds it to TorBox/Library

## Done (v0.3.0)
- [x] Search errors are shown instead of a silent "No results"
- [x] Torznab source (Prowlarr/Jackett, audiobook category 3030) set in Settings; only results with an info hash are used
- [x] TorBox Usenet (NZB) search + stream/download via `tbusenet://` tracks
- [x] TorBox cache check on every found hash (marks them instant); duplicates across sources merged
- [x] Cached/in-cloud results listed first, then by quality
- [x] Looser audiobook filter for audiobook-focused sources (still drops video and ebooks)

## Unverified / next
- [ ] Not tested on a device: needs real TorBox key. Usenet/cache-check response fields are written from the TorBox docs, not yet seen live. AIOStreams search assumes a Stremio-style manifest with search catalogs.
- [ ] Cover art + author lookup (none yet); chapter markers inside single M4B files
- [ ] Release-signed APK (currently debug-signed)
- [x] CI publishes a GitHub release (tag build-N) on every push (tag pushes are blocked in the cloud sandbox)
- [ ] Phase 2: Windows sync implementing SyncRepository
