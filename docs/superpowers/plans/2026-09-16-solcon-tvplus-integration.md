# Solcon TV+ account integration — implementation plan

## Goal

Turn the existing Solcon multicast work into a usable OwnTV provider integration that keeps OwnTV's UI and playback architecture, while adding the legitimate Solcon TV+ account flow used by the official app: subscription number + PIN -> device session -> account-specific catalog/EPG/playback metadata. Never embed or copy third-party app secrets, DRM private material, device certificates, or user credentials into the public repository.

## Constraints

- Repository is public. No real subscription numbers, PINs, cookies, tokens, signed URLs, captures, MACs, serials, certificate material, Firebase/API secrets, or extracted provider secrets may be committed or logged.
- Authentication is a normal user login and device-session creation only. If protected playback requires provider/device-bound DRM beyond the legitimate session and standard Android Widevine APIs, report the unsupported boundary instead of bypassing it.
- Keep existing OwnTV UI and database models where possible. Do not fork OwnTV_Core solely to add a new SourceType.
- Existing RTP/UDP Media3 multicast engine remains the preferred path for explicit multicast URLs and is fully wired into preview/fullscreen/zapping.
- The feature branch `feat/solcon-tv` is the isolated implementation workspace.

## Architecture

### 1. Protocol layer

Add `provider/solcon/tvplus/SolconTvPlusProtocol.kt` containing pure, unit-testable protocol helpers:

- Primary API root: `https://api-avs67.tv.prod.itvavs.prod.aws.kpn.com/101/1.5/A/nld/kpn`
- Compatibility API root: `https://api-avs67.tv.prod.itvavs.prod.aws.kpn.com/101/1.2.0/A/nld/pctv/kpn`
- Login endpoint: `/USER/SESSIONS/`
- Catalog endpoint: `/TRAY/LIVECHANNELS?orderBy=orderId&sortOrder=asc&from=0&to=999&dfilter_channels=subscription`
- EPG endpoint builder for a bounded time window.
- Playback endpoint builder for `/CONTENT/VIDEOURL/LIVE/...`.
- Build `credentialsStdAuth` login JSON with a persistent per-install device id and Android-TV-like device metadata. No provider secret is required.
- Parse `resultCode`, result containers, session token/cookie/device-session fields without logging their values.
- Compatibility fallback is allowed only for transport/endpoint incompatibility (404/405/unsupported shape), never for an authentication rejection.

### 2. Secure session storage

Add `SolconTvPlusSessionStore.kt`:

- Generate and persist one opaque random install device id.
- Store session token/cookie and account identifier only in app-private encrypted preferences using Android Keystore AES-GCM.
- Do not persist PIN after a successful login. Re-authentication asks for PIN if the server session can no longer be renewed.
- Expose redacted diagnostics only (`loggedIn`, API generation, expiry if known).

### 3. HTTP client

Add `SolconTvPlusClient.kt` using OwnTV's existing `OkHttpClient`:

- `login(subscriptionNumber, pin)`
- `validateSession()`
- `logout()` local session clear
- `liveChannels()`
- `epg(startMs, endMs, channelIds)`
- `resolveLivePlayback(channelId, assetId?)`

The client maintains its own request headers/cookie values from `SolconTvPlusSessionStore`; it must not modify the global OwnTV cookie jar. Errors are mapped to typed outcomes (`InvalidCredentials`, `DeviceLimit`, `Network`, `Protocol`, `NotAuthenticated`, `ProtectedPlaybackUnsupported`).

### 4. Provider repository and OwnTV database mapping

Add `SolconTvPlusRepository.kt`:

- Create/reuse one synthetic OwnTV source row with URL `solcon-tvplus://account` and `SourceType.M3U` only as a DB grouping key; generic M3U sync must never be run on it.
- Link the source to the active OwnTV profile.
- Fetch the account-specific live channel catalog and map it into existing `CategoryEntity` + `ChannelEntity` rows.
- Store synthetic playback URLs as `solcon-tvplus://live/<remoteId>`; real signed playback URLs/tokens are resolved only at tune time and never persisted.
- Preserve provider channel number, remote id, logo and EPG id.
- Classify radio/audio services into a `Radio` category when provider metadata identifies them; OwnTV's existing audio-only playback UI remains responsible for presentation.
- Import bounded EPG rows into `EpgChannelEntity` and `EpgProgrammeEntity` using the existing core DAO APIs.
- Mark source `lastSyncAt` after a complete catalog sync.

### 5. Playback integration

Extend the current Solcon routing seam:

- `SolconStreamPolicy` recognizes `solcon-tvplus://live/<id>` as a provider-resolved stream, distinct from RTP/UDP multicast.
- `LiveViewModel` resolves these provider URLs immediately before playback.
- Resolver output can be:
  - a clear/ordinary HTTP(S)/DASH/HLS URL -> existing OwnTV player ladder;
  - a standard Widevine manifest + license configuration -> existing Media3 DRM path using Android Widevine;
  - unsupported provider/device DRM -> visible error; no bypass attempt.
- Do not persist the resulting signed stream URL/license headers.
- Keep existing direct `rtp://` / `udp://` channels routed through `SolconMulticastEngine`.

### 6. Login and source UI

Add TV-safe `SolconTvPlusLoginScreen.kt` + `SolconTvPlusViewModel.kt`:

- Subscription number field.
- Masked PIN field.
- Login button, progress state, readable provider/network errors.
- After successful login: catalog + EPG sync, then return to OwnTV.
- Existing OwnTV controls/components and focus behavior are reused.

Extend `AddSourceChooserScreen` with a third `Solcon TV+` card and `onSolcon` callback.

Wire it into both:

- first-run `SetupWizard`;
- Settings -> Sources -> Add Source (`ManageSourcesScreen`).

Re-opening Solcon TV+ while already logged in should offer refresh/re-login rather than create duplicate source rows.

### 7. Diagnostics/privacy

Add redacted diagnostic status that may show:

- logged in yes/no;
- API endpoint generation;
- channel/EPG counts;
- last sync time;
- playback route type.

Never show/log PIN, subscription number in full, token, cookie, signed stream query, license auth header, private keys, or device certificate material.

### 8. Tests (TDD)

Add/extend tests before each production behavior:

1. `SolconStreamPolicyTest`: `solcon-tvplus://live/123` classifies as TV+ provider route and malformed ids do not.
2. `SolconTvPlusProtocolTest`: exact `credentialsStdAuth` JSON shape; endpoint builders; auth rejection must not trigger legacy fallback; parsing tolerates missing optional session fields but rejects unusable login responses.
3. `SolconTvPlusCatalogParserTest`: maps representative provider containers to TV/radio channel models, channel numbers and EPG ids.
4. `SolconTvPlusPlaybackParserTest`: recognizes clear stream, standard Widevine metadata, and unsupported protected response without exposing secrets in `toString()`.
5. `SolconTvPlusRedactionTest`: diagnostics redact account/session/URL secret values.

Run branch CI after RED tests, then after implementation. Final gate is full unit tests + lint + `assembleStandardDebug` + uploaded APK artifact.

## Completion criteria

- Existing OwnTV functionality still compiles and tests.
- Multicast preview/fullscreen/zapping wiring from `bbb5375` is included in the built APK.
- Solcon TV+ is selectable from onboarding and Settings source management.
- Login code uses only user-entered subscription number/PIN and a locally generated device id.
- Successful account sync can populate OwnTV live channels and EPG without persisting signed playback URLs.
- Tune-time provider playback resolution is integrated with existing Media3/OwnTV playback and standard Widevine when the server response provides ordinary Widevine metadata.
- No copied provider secrets or user secrets are present in Git diff.
- CI unit tests, lint and standard debug APK build are green and the resulting APK is returned in chat.

## Verification commands (CI)

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:lintStandardDebug
./gradlew :app:assembleStandardDebug
```
