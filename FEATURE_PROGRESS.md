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
- [x] Browse tab: in-app browser for AudiobookBay (address editable in Settings), Full Length Audiobooks, LibriVox, Internet Archive, Loyal Books; logins persist; tapping a magnet adds it to TorBox/Library

## Done (v0.2.1, code-review pass, built locally with SDK 35)
- [x] Progress percent no longer hits 100 early on multi-chapter books (unknown chapter lengths are estimated); finished only on the last track
- [x] Library "In progress" now includes books under 1%
- [x] Search shows real TorBox errors instead of "No results"; network timeouts 15s/45s
- [x] Adding a torrent already in the account but still preparing now waits instead of failing
- [x] Downloads run as a foreground service with a notification (fixes crash on Android 8-11); correct file extensions

## Unverified / next
- [ ] Not tested on a device: needs real TorBox key. AIOStreams search assumes a Stremio-style manifest with search catalogs.
- [x] Cover art + author lookup via Open Library when a book is first streamed/downloaded (shown in player + mini player). Books saved before this need Stream/Download tapped again. Not device-tested.
- [x] AIOStreams removed (Stremio = movies/series, no audiobooks). Replaced by TorBox usenet: cloud usenet list + usenet search + NZB add; tracks use torbox://id/file?u=1
- [x] TorBox's search API (search-api.torbox.app) is dead (NXDOMAIN). Search now = your cloud (torrent + usenet) + free public indexes (Pirate Bay apibay cat 102, Knaben) + TorBox checkcached to mark "Instant". Each source reports its own error on screen.
- [ ] apibay / Knaben / checkcached request + response shapes written from memory, NOT verified or device-tested. Usenet NZB-add path is unused until an NZB source exists.
- [ ] Chapter markers inside single M4B files
- [ ] Release-signed APK (currently debug-signed)
- [x] CI publishes a GitHub release (tag build-N) on every push (tag pushes are blocked in the cloud sandbox)
- [ ] Phase 2: Windows sync implementing SyncRepository

## Done (v0.2.2)
- [x] Library: tapping a book card opens the Player; if it is already loaded it is not reloaded
- [x] Artwork: lookup now tries iTunes audiobooks then Open Library with cleaned title variants (drops "Chapterized", "Easier Listen" etc.); old books without a cover are retried at launch; covers shown on Library cards. Not device-tested.
- [ ] Some sources fail to stream but download fine: on-screen errors seen: "No audio files found in this source" and "TorBox is still preparing". Fix (not device-tested): the wait loop now reads mylist with bypass_cache and the new torrent's id; cloud items are matched by TorBox id, not hash; the "no audio" error now lists the files it saw.

## Done (v0.3.0, auto-update)
- [x] Every build is signed with one fixed key (app/torio-update.keystore, committed on purpose) and versionCode = CI run number
- [x] Release workflow: pushes to main publish the normal "latest" release; other branches publish prereleases only
- [x] On launch the app checks the latest GitHub release (tag build-N), downloads the APK and opens the Android installer (Android always asks you to confirm; first time it also asks to allow installs from Torio)
- [x] Settings: "Auto-update on launch" switch, "Check for updates", "View on GitHub", current build number. Not device-tested.
- Note: builds made before this have a different signing key; uninstall once, then install the first new build. Updates work from then on.

## Fix (cached torrent "still preparing")
- [x] `mylist` now sent with `bypass_cache=true`, so the add-then-wait loop sees the new torrent instead of a stale cached list. Not built here (no Android SDK in this sandbox) and not device-tested.

## Done (player layout + voice clarity + skip silence)
- [x] Player: artwork fills the free space; title, seek bar, controls, speed chips and toggles sit at the bottom (big speed slider removed, chips kept)
- [x] Voice clarity DSP: deeper rumble/mud cut, stronger 1-5 kHz lift, slightly firmer compression and loudness
- [x] "Skip silence" toggle (off by default, saved in Settings, uses ExoPlayer's built-in silence skipping)
- [ ] Not built here (no Android SDK in this sandbox) and not device-tested; CI build will confirm

## Fix (updater said "latest" when build 20 was out)
- [x] Updater now reads the release list (no-cache) instead of /releases/latest, and Settings shows "GitHub latest: build N" so a wrong answer is visible. Not built here; CI will confirm.
