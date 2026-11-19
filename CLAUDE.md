# Torio (com.fitzy.torio)
Native Android audiobook player: TorBox + AIOStreams search, stream/download, voice DSP, Room-backed progress/library.
State of work lives in `FEATURE_PROGRESS.md`; decisions in `SPEC.md`. Read both after `/clear`.

## Stack
Kotlin, Jetpack Compose M3, Media3 (ExoPlayer + MediaSessionService), Room (KSP), OkHttp, kotlinx.serialization, WorkManager, Coil. Manual DI via `TorioApp.container`. minSdk 26, target 35.

## Layout (app/src/main/java/com/fitzy/torio)
- `data/` Room entities/DAO/DB, `SyncRepository` (Phase 2 hook; only `LocalSyncRepository` exists), `Settings`
- `net/` `TorBoxApiClient`, `SourceParser`, `SourceResult`
- `player/` `Media3AudioService`, `VoiceDspEnhancer`, `PlayerConnection`, `DownloadWorker`
- `ui/` Compose screens + ViewModels

## Build / verify
`./gradlew assembleDebug` (ANDROID_HOME must point at an SDK with platform 35 + build-tools 35.0.0). Run it after every change set; fix errors before moving on.

## Rules
- Minimal diffs; never reformat untouched code. No unasked abstractions.
- Read a file before editing it. Inspect the service/ViewModel before changing its callers.
- Progress is saved to Room every 3s with exact ms, track index, percent. Never bypass `ProgressDao`.
- Anything cross-device goes through `SyncRepository`, never directly to a network client.
- API keys live in `Settings` only. Never log or commit keys.
- Chat replies: short, plain English, no code dumps. Update `FEATURE_PROGRESS.md` after each milestone.
