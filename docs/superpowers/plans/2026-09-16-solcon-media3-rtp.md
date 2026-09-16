# Solcon Media3 RTP Multicast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native Media3 playback for clear RTP/UDP IPv4 multicast channels in OwnTV, using an adapted MIT-licensed MyIPTV transport, with mpv fallback and no provider-secret or DRM-bypass logic.

**Architecture:** Keep OwnTV_Core untouched. Add a focused app-side multicast stack (`SolconStreamPolicy` → `RtpDataSource`/`RtpTransport` → `SolconMulticastEngine`) and route only recognized clear multicast URLs through it. `LiveViewModel`, `LiveScreen`, and `OwnTVShell` gain a small third-engine state so the existing ExoPlayer HTTP/HLS/DASH and mpv paths remain unchanged.

**Tech Stack:** Kotlin, Android, Media3 ExoPlayer, `ProgressiveMediaSource`, MPEG-TS, `MulticastSocket`, Koin, JUnit4, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-16-solcon-tv-design.md`

## Global Constraints

- The Media3 RTP/UDP multicast engine is part of v1, not a later phase.
- Support `rtp://` and `udp://`, including `rtp://@224.x.x.x:port`.
- Accept only IPv4 multicast destinations (`224.0.0.0/4`) for the provider multicast route.
- Keep existing HTTP/HLS/DASH routing unchanged.
- Retain mpv as a compatibility fallback for a multicast tune that Media3 cannot open.
- Port/adapt MyIPTV's RTP concepts under its MIT licence and retain attribution.
- Never add private keys, provider credentials, copied Arris authorization, control words, descrambling keys, captured signed HLS URLs, packet captures, MACs, serials, or account data.
- The default authorization seam is `NotProvisioned`; it never decrypts protected content.
- Diagnostics may report aggregate packet/reorder/drop counts and the selected interface name, but not payloads, secrets, query strings, MAC addresses, or local IP addresses.
- GitHub Actions is the build/test source of truth for this environment.

---

### Task 1: Pure stream policy and RTP packet parser

**Files:**
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpPacket.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/SolconStreamPolicyTest.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/multicast/RtpPacketTest.kt`

**Interfaces:**
- Produces: `SolconStreamPolicy.classify(url: String): Decision`
- Produces: `RtpPacket.parse(datagram: ByteArray, length: Int): Parsed`
- Produces: `RtpPacket.compareSequence(a: Int, b: Int): Int`

- [ ] **Step 1: Write failing stream-policy tests** covering RTP/UDP multicast, `@` syntax, unicast rejection, invalid ports, malformed URLs, and preservation of non-multicast URLs.
- [ ] **Step 2: Write failing RTP parser tests** for raw MPEG-TS, basic RTP v2, CSRC headers, 32-bit-word RTP extension lengths, padding, truncation rejection, unsupported versions, and 16-bit sequence wraparound.
- [ ] **Step 3: Run `./gradlew testStandardDebugUnitTest --tests '*SolconStreamPolicyTest' --tests '*RtpPacketTest'` and verify RED because production types are absent.**
- [ ] **Step 4: Implement the minimal pure Kotlin parser/policy.** RTP extension length is `words * 4`, with bounds checks before every offset change.
- [ ] **Step 5: Re-run the focused tests and verify GREEN.**
- [ ] **Step 6: Commit.**

### Task 2: Multicast transport and Media3 DataSource

**Files:**
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/MulticastNetworkSelector.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpTransport.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpDataSource.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpDataSourceFactory.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/multicast/RtpReorderBufferTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `SolconStreamPolicy.Decision` and `RtpPacket.Parsed`.
- Produces: bounded `RtpReorderBuffer.offer(packet): List<RtpPacket.Parsed>`.
- Produces: `RtpDataSourceFactory(context)` for `ProgressiveMediaSource.Factory`.

- [ ] **Step 1: Write failing reorder tests** for in-order delivery, small reordering, duplicate/late packets, forced progress at the 64-packet bound, and wraparound.
- [ ] **Step 2: Verify RED in GitHub Actions.**
- [ ] **Step 3: Implement bounded reorder logic.** Never allow an unbounded list or queue.
- [ ] **Step 4: Implement network selection** from Android's active `LinkProperties.interfaceName`, validating `isUp`, non-loopback, multicast support; fall back to active Ethernet/Wi-Fi-capable interfaces without hardcoded `eth0`/`wlan0`.
- [ ] **Step 5: Implement `RtpTransport`.** Join the multicast group on the selected interface, use RTP stripping for `rtp://`, raw datagrams for `udp://`, maintain aggregate counters, and leave/close deterministically.
- [ ] **Step 6: Implement blocking `RtpDataSource`.** `read()` blocks for bytes or terminates on close/error; it never returns a spurious zero-length read for a non-zero request.
- [ ] **Step 7: Add `CHANGE_WIFI_MULTICAST_STATE` and `ACCESS_WIFI_STATE`; acquire/release a reference-count-disabled `WifiManager.MulticastLock` only for Wi-Fi multicast sessions.**
- [ ] **Step 8: Re-run focused and full unit tests.**
- [ ] **Step 9: Commit.**

### Task 3: Media3 multicast playback engine and surface

**Files:**
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/SolconMulticastEngine.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/multicast/SolconMulticastSurface.kt`
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/SolconAuthorizationProvider.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/SolconAuthorizationProviderTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/di/PlayerModule.kt`
- Modify: `app/src/main/java/tv/own/owntv/di/AppModule.kt`

**Interfaces:**
- Produces: `SolconMulticastEngine : PlaybackEngine` with `play(url, muted, meta)`, `stop()`, `setSurface(Surface?)`, `currentUrl`, `state`, `videoFps`, and aggregate transport diagnostics.
- Produces: `SolconMulticastSurface(engine, modifier, keepAwake)`.
- Produces: `SolconAuthorizationProvider` whose default implementation returns `NotProvisioned` only.

- [ ] **Step 1: Write the failing authorization seam test.** It must prove the default provider cannot fabricate authorization material.
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Implement the inert authorization provider.**
- [ ] **Step 4: Implement the Media3 engine** with `ProgressiveMediaSource.Factory(RtpDataSourceFactory(...))`, MPEG-TS media type, state/error/audio-only/video metadata publication, mute/play-pause/track selection, retry, deterministic stop, and no DRM logic.
- [ ] **Step 5: Implement the Compose `SurfaceView` bridge** so preview/fullscreen/mini can attach/detach without recreating the engine.
- [ ] **Step 6: Wire the engine through Koin.**
- [ ] **Step 7: Run unit tests and compile/lint in CI.**
- [ ] **Step 8: Commit.**

### Task 4: Live routing, fallback, preview, fullscreen and radio

**Files:**
- Modify: `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/live/LiveScreen.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/shell/OwnTVShell.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/SolconPlaybackRouteTest.kt`

**Interfaces:**
- Produces: pure `SolconPlaybackRoute.decide(url, forceMpv): MEDIA3_MULTICAST | MPV_MULTICAST | EXISTING` used by Live routing.
- `LiveViewModel` exposes `liveOnMulticast: StateFlow<Boolean>`, `previewOnMulticast: StateFlow<Boolean>`, and `multicastEngine`.

- [ ] **Step 1: Write failing routing tests** proving multicast uses Media3 first, a forced compatibility choice uses mpv, and HTTP/HLS/DASH remain `EXISTING`.
- [ ] **Step 2: Verify RED.**
- [ ] **Step 3: Implement the pure route helper.**
- [ ] **Step 4: Route `playPreview()` to `multicastEngine` for recognized multicast URLs and leave all existing URLs on `LivePreviewEngine`.**
- [ ] **Step 5: Route fullscreen multicast to the same Media3 engine, unmuting/promoting an already-loaded preview instead of opening a second socket.**
- [ ] **Step 6: Add one bounded open watchdog**: on Media3 multicast error/no-open, close it and hand the same clear RTP/UDP URL to existing mpv once. Never loop Media3↔mpv automatically.
- [ ] **Step 7: Update `LiveScreen` and `OwnTVShell` surfaces/HUD/audio-session engine selection** so multicast preview, fullscreen, mini-player and audio-only radio all use `SolconMulticastEngine`; non-multicast behavior stays byte-for-byte routed as before.
- [ ] **Step 8: Ensure stop, fullscreen exit, app navigation, zapping, catch-up and engine toggling close multicast sockets and clear multicast state.**
- [ ] **Step 9: Run all unit tests + lint.**
- [ ] **Step 10: Commit.**

### Task 5: Repository safety, attribution and documentation

**Files:**
- Modify: `.gitignore`
- Create: `docs/providers/solcon.md`
- Create: `NOTICE-THIRD-PARTY.md`

**Interfaces:** None.

- [ ] **Step 1: Add ignore rules** for packet captures, local M3U/M3U8, provider-local config, `.env*`, P12/PFX/PEM/KEY, and Solcon-local JSON without ignoring checked-in examples/docs.
- [ ] **Step 2: Add MyIPTV MIT attribution** (`Copyright (c) 2024 Gang Chen`) and note the multicast transport is adapted, not copied verbatim.
- [ ] **Step 3: Document local clear-multicast setup and troubleshooting** without real captured Solcon addresses or entitlement data.
- [ ] **Step 4: Run the repo's hardcoded-literal checker/lint and tests.**
- [ ] **Step 5: Commit.**

### Task 6: CI verification and APK handoff

**Files:**
- Create temporarily on the feature branch if needed: `.github/workflows/solcon-dev.yml`

**Interfaces:** Produces a standard ARM APK (`arm64-v8a` + `armeabi-v7a`) as a GitHub Actions artifact.

- [ ] **Step 1: Run a fresh CI check** (`testStandardDebugUnitTest`, `lintStandardDebug`).
- [ ] **Step 2: Build `assembleStandardDebug` or the repo's standard debug artifact in GitHub Actions.**
- [ ] **Step 3: Download the exact successful-run APK artifact.**
- [ ] **Step 4: Verify the artifact ZIP/APK integrity and confirm `AndroidManifest.xml` + `classes*.dex` are present.**
- [ ] **Step 5: Put the verified APK at `/mnt/data/MyOwnTV-Solcon-Media3.apk`.**
- [ ] **Step 6: Only after fresh verification, return the APK link in chat.**
