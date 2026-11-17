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

## Unverified / next
- [ ] Not tested on a device: needs real TorBox key. AIOStreams search assumes a Stremio-style manifest with search catalogs.
- [ ] Cover art + author lookup (none yet); chapter markers inside single M4B files
- [ ] Release-signed APK (currently debug-signed)
- [ ] Phase 2: Windows sync implementing SyncRepository
