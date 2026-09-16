# Solcon TV+ First-Class Source Integration Design

## Goal

Turn the Solcon work on `feat/solcon-tv` into a first-class OwnTV source reachable and usable from the Android TV UI. A legitimate Solcon subscriber must be able to sign in with subscription number + PIN, synchronize entitled TV/radio/EPG data into OwnTV's normal repositories, use those channels through normal Live/Guide/Radio/favorites/profile flows, restart the app without losing state, and receive either legitimate playback or a precise provider/DRM diagnostic.

Required journeys:

1. Install -> open OwnTV -> create/select profile -> no source -> Add source -> Solcon TV+ -> sign in -> synchronize -> Solcon becomes active OwnTV source -> normal Live/Guide/Radio UI.
2. More -> Settings -> Solcon TV+ -> account/status -> Synchronize now / Logout.

## Current failure

The branch already contains `SolconTvPlusAccountScreen`, `SolconTvPlusViewModel`, TV+ client/protocol/repository/session code, multicast/RTP code, stream policy, playback routing changes and Solcon strings/tests.

The feature is still not integrated end-to-end. `SettingsScreen` has no Solcon tab, no Solcon root row and no dispatch branch that renders `SolconTvPlusAccountScreen`, so the compiled screen is unreachable. `appModule` also does not bind the complete Solcon TV+ account/repository/client/session stack, so simply exposing the row would create a runtime DI failure.

The old branch design treated Solcon mainly as a multicast/local-playlist extension. This specification supersedes it. Multicast remains one legitimate playback route, but the product is an authenticated first-class OwnTV source.

## Core rule: use OwnTV architecture

Solcon-specific code owns only provider concerns: authentication, session state, catalog translation, just-in-time playback resolution, safe diagnostics and multicast transport.

After synchronization, Solcon content uses the same OwnTV systems as every other source:

- profile/source linking;
- channel/category persistence;
- active/default source state;
- channel numbers;
- Guide/EPG;
- Live TV;
- radio/audio-only UI;
- favorites;
- watch history;
- fullscreen/mini-player lifecycle;
- channel up/down and numeric direct tune where already supported.

Do not create a parallel Solcon favorites implementation, guide, profile system, radio app or player shell.

## Security and provider boundary

Use only the legitimate authenticated TV+ flow and data/routes returned for the user's authorized subscription/session.

Never hardcode or commit credentials or secrets. Never persist a PIN. Never log PINs, access/session tokens, cookies, signed private playback URLs, DRM license material, private keys or certificate private material. Do not extract DRM keys, spoof protected device identities, copy device-bound authorization from another device, bypass entitlement/device limits, or bypass DRM.

If Solcon returns standard Android Widevine information for this authorized device/session, pass it to OwnTV's existing Media3/MediaDrm path normally. If Solcon rejects the app/device or requires unavailable proprietary/device-bound provisioning, stop at that boundary and show a precise diagnostic. Clear legitimate multicast remains independently usable.

## Dependency injection and session lifecycle

Wire the full Solcon TV+ stack through Koin using existing OwnTV conventions. Required bindings include `SolconTvPlusViewModel`, `SolconTvPlusRepository`, `SolconTvPlusClient`, `SolconTvPlusSessionStore`/session provider and the dependencies required for playback resolution. `SolconMulticastEngine` remains application-scoped.

Prefer constructor references or named constructor parameters so same-typed dependencies cannot silently swap.

The session store is the only persistence location for provider session material. PINs exist only for the immediate login request. On process restart, session restoration may initialize the account as connected when stored session state exists; subsequent authenticated calls are authoritative and may invalidate an expired/rejected session.

## Settings integration

Add `SettingsTab.SOLCON` and a real root row under the app-level Settings group:

- title resource: `Solcon TV+`
- description resource: localized equivalent of `Login, subscription and synchronization`

Selecting it renders `SolconTvPlusAccountScreen` using the same Settings detail-screen pattern as existing destinations. Back returns to the root Settings context and restores focus to the Solcon row.

All visible strings are resources and must pass `verifyI18nLiterals`.

The account screen must be fully remote-operable: D-pad, OK/select and Back; numeric input for subscription/PIN; password masking for PIN; no touch/mouse dependency; no focus trap. Signed-out state focuses the first useful credential field. Connected state focuses `Synchronize now` or the primary action.

## First-run / no-source discovery

Extend OwnTV's existing no-source/add-source path so Solcon TV+ is directly discoverable. It must invoke the same account/setup implementation used from Settings, not a duplicate login flow.

After successful synchronization:

1. a stable Solcon `SourceEntity` exists;
2. it is linked to the active profile through OwnTV's normal profile-source relation;
3. synchronized channels/categories/EPG live in normal OwnTV tables;
4. the active/default source state points to Solcon when this is the profile's first source;
5. shell/source observers refresh;
6. `Geen bron` / `No source` disappears because underlying source state changed, not because the top bar was special-cased;
7. normal Live/Guide/Radio screens consume the data.

Add a regression test specifically preventing `SolconTvPlusAccountScreen` from existing without a reachable UI route.

## Authentication UI states

Support:

- signed out;
- signing in;
- synchronizing;
- connected;
- invalid credentials;
- provider device/session limit;
- network failure;
- provider/protocol failure;
- empty/unusable catalog;
- partial EPG synchronization warning.

Successful login immediately synchronizes in the setup path. Connected accounts expose `Synchronize now` and `Logout`.

### Logout semantics

Logout clears/invalidate all persisted Solcon session material and account UI authentication state, but does **not** delete the previously synchronized OwnTV source/catalog. This preserves stable channel ids, favorites/history and offline browsing metadata.

After logout, authenticated TV+ playback resolution returns a typed `sign in required` result until the user reconnects. The cached source may remain visible because it is still a configured source, but the account/diagnostics UI clearly reports signed-out state. Re-login and synchronization reuse the same source and stable remote channel ids.

Explicit source deletion remains the existing OwnTV Manage Sources operation and follows normal source deletion semantics.

## Catalog/source mapping

`SolconTvPlusRepository` remains the adapter from provider protocol objects to OwnTV persistence.

Use a stable secret-free synthetic provider URL such as the existing `solcon-tvplus://account` to identify the source.

Each entitled channel maps into normal `ChannelEntity`/category/EPG structures while preserving data returned by Solcon where available:

- provider channel id;
- channel number;
- name;
- logo;
- TV vs radio;
- provider ordering;
- EPG identifier;
- catch-up/start-over/replay capability;
- non-secret entitlement/protection classification needed for routing.

Store a stable internal playback reference such as `solcon-tvplus://live/<provider-id>`, never a transient signed stream URL. Resolve private URLs/headers/DRM information just in time.

Re-sync updates channels by stable remote id so favorites/history remain attached, and removes stale rows only for this Solcon source. A partial EPG failure must not discard a valid channel catalog. A failed sync keeps the last known good catalog and exposes failure/partial state.

## Active source and shell state

Audit the actual repository/ViewModel chain that drives:

- `SettingsRepository.defaultSourceId` / equivalent active source state;
- profile source selection;
- top-right `sourceSummary`;
- Live/Guide source observers.

When Solcon is the first successfully synchronized source for a profile, set it through the same OwnTV active/default-source mechanism used by normal source setup. Do not hardcode the shell label.

## Guide / EPG

Write Solcon guide data into OwnTV's existing EPG tables. The normal Guide must show it; there is no Solcon-only guide.

Where provider data exists, preserve current/next programs, timeline/grid entries, channel relationships, title/description and replay/start-over metadata. Track EPG completeness separately from catalog success so diagnostics can report `complete`, `partial` or `unavailable`.

## Radio

Map Solcon radio stations into OwnTV's normal live/audio channel model. They use the same source/session/favorites/profile/playback architecture as TV and appear in the existing radio/audio-only experience. Do not create a separate radio source.

## Playback routing

All tunes start from the normal OwnTV channel selection path.

### TV+ provider reference

For `solcon-tvplus://live/<id>`, resolve playback through the authenticated Solcon repository immediately before playback.

### Clear HTTP/HLS/DASH

If the authenticated response supplies a clear ordinary stream and legitimate request headers, use OwnTV's existing Media3/ExoPlayer playback infrastructure.

### Standard Widevine

If the response supplies standard Widevine license information and the Android device/session is authorized, translate it to OwnTV's existing `DrmConfig` and use normal Media3/MediaDrm handling.

### Unsupported protected content

If Solcon requires unavailable proprietary/device-bound provisioning, private certificates, an unsupported DRM mode or rejects this client/device, return a typed unsupported result and show a useful provider/provisioning diagnostic. Do not fall back to fake or bypass playback.

### Clear RTP/UDP multicast

For legitimate IPv4 multicast `rtp://` or `udp://` routes, use `SolconMulticastEngine`. `SolconStreamPolicy` must accept supported multicast syntax but reject malformed and ordinary unicast RTP/UDP destinations.

### No simulated success

Production must never silently substitute fake/simulated playback. Existing OwnTV engine fallback is allowed only when it is a real supported engine for the same legitimate stream.

## Live/fullscreen/zapping

Target flow:

`ChannelEntity -> LiveViewModel -> optional Solcon resolver -> selected legitimate engine -> LiveScreen/preview -> fullscreen -> existing zap controls`.

Solcon channels remain part of the normal Live channel list and zap source. CH+/CH-, numeric tune, fullscreen transitions, media-session ownership, mini/audio presentation and Back behavior reuse existing OwnTV mechanisms.

Provider parsing/network calls remain outside `LiveViewModel`; the ViewModel orchestrates typed results and player state only.

## Profiles, favorites and history

No Solcon-specific profile/favorite/history implementation is added. Stable source/channel identities and normal profile-source linking make existing OwnTV behavior apply automatically. Tests must prove source linking and stable channel ids survive re-sync/restart so user data is not orphaned.

## Safe diagnostics

Expose non-secret Solcon operational state in the account screen or existing diagnostics conventions:

- signed in/out/session unavailable;
- last successful sync timestamp;
- TV count;
- radio count;
- EPG complete/partial/unavailable;
- last playback route category;
- multicast availability;
- useful network/interface state without MAC/private address disclosure;
- last provider/device authorization failure category;
- last sync/playback error category.

Never display or log PINs, tokens, cookies, private signed URLs, license response bodies, DRM keys, private certificates or provider private blobs. URLs with private query/auth material are redacted.

## Required tests

Add integration coverage beyond isolated provider classes:

1. Settings root exposes Solcon TV+.
2. Selecting it dispatches the Solcon account screen.
3. No-source/add-source exposes Solcon TV+.
4. Successful login+sync creates and links a real OwnTV source.
5. First source becomes active/default and shell state no longer reports no source.
6. Catalog mapping preserves id/name/number/logo/order.
7. TV vs radio mapping is correct.
8. EPG channel/programme mapping reaches OwnTV tables.
9. Re-sync preserves stable rows and removes only stale Solcon rows.
10. Invalid authentication produces correct state and stores no PIN.
11. Sync failure retains last-known-good catalog and reports failure.
12. Session restores after recreation when valid stored state exists.
13. Logout clears session, keeps cached source/catalog and makes authenticated playback return sign-in-required.
14. TV+ internal references resolve before playback.
15. Clear authenticated HTTP/HLS/DASH uses normal Media3 route.
16. Valid multicast uses `SolconMulticastEngine`.
17. Malformed/unicast RTP/UDP is not misclassified.
18. Standard Widevine maps to OwnTV DRM config.
19. Unsupported/proprietary protected content returns precise unsupported diagnostics and no fake playback.
20. Solcon channels retain normal zapping/direct-tune identity.
21. Source/session/channel state restores after app restart.
22. Navigation regression test prevents an unreachable compiled account screen.
23. User-visible literals pass `verifyI18nLiterals`.

Use pure/unit tests for navigation definitions and routing where possible, repository/database tests for mapping, and at least one Android/UI integration test for the real Settings route/focusable destination.

## Verification and APK

Before completion run on the final branch head:

```bash
./gradlew testStandardDebugUnitTest
./gradlew lintStandardDebug
./gradlew assembleStandardDebug
```

Run the repository's relevant i18n verification task, including `verifyI18nLiterals`. Fix failures properly; do not weaken checks.

For the fresh Standard Debug APK:

- verify the APK is a valid ZIP/APK archive;
- report final commit SHA;
- report exact APK filename;
- report byte size;
- report SHA-256;
- report unit-test result;
- report i18n result;
- report lint result;
- report build result.

GitHub Actions may supplement verification but does not replace checking that the actual UI route/source lifecycle exists.

## Runtime boundary

The final report must distinguish automated/build verification from behavior that requires real credentials, Solcon network access or provider-side DRM authorization.

If the TV+ API/DRM service refuses this app/device, finish and verify everything else, expose the exact provider/provisioning failure, do not attempt a bypass, and report the runtime boundary and evidence. Never claim protected-channel playback without evidence from an authorized real-device session.

## Definition of done

Done means the final built APK supports:

Install -> open OwnTV -> create/select profile -> no source -> choose Solcon TV+ -> enter subscription number + PIN -> authenticate -> synchronize -> Solcon becomes active OwnTV source -> normal channels/Guide/radio appear -> select channel -> legitimate resolver chooses route -> playback starts or precise DRM/provisioning diagnostic appears -> channel switching works -> restart -> source/session/channel state restores.

And:

More -> Settings -> Solcon TV+ -> account/status/synchronize/logout.

Existing OwnTV functionality and TV-friendly design/navigation must not regress.