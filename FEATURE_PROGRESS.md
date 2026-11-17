# FEATURE_PROGRESS

## Audit (Phase 1)
- Repo contains only README.md. No app/, Gradle, Media3 service, ViewModels, or CI yet. Greenfield.

## Vote (awaiting user)
- [ ] Option A: Full Native Torio Engine (Media3 + TorBox/AIOStreams + Dialogue DSP + Room Resume & Library)
- [ ] Option B: Basic Audio Player Wrapper

## Planned tasks (start after vote)
- [ ] Phase 2: CLAUDE.md, .github/workflows/build-apk.yml, .claude/rules/ui.md, .claude/settings.json
- [ ] Gradle scaffold (com.fitzy.torio)
- [ ] TorBoxApiClient + AIOStreams client + source picker
- [ ] Media3AudioService (prefetch buffering, speed 0.5-3.5x)
- [ ] VoiceDspEnhancer
- [ ] Room progress (3s saves) + SyncRepository interface
- [ ] Compose UI: player, library, source drawer
- [ ] assembleDebug passes, release + APK links
