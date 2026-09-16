# Solcon TV+ First-Class Source Integration Design

## Goal

Turn the existing Solcon TV+ work on `feat/solcon-tv` into a real first-class OwnTV source that is reachable from the Android TV UI, authenticates a legitimate Solcon subscription, synchronizes the user's entitled catalog into OwnTV's existing source/channel/EPG architecture, restores that state after restart, and routes playback through the appropriate existing or Solcon-specific engine without bypassing DRM or provider authorization.

The built Standard Debug APK must support both of these user journeys:

1. First-run/no-source: create or select a profile -> choose Solcon TV+ -> enter subscription number and PIN -> authenticate -> synchronize -> return to normal OwnTV UI with the Solcon source active.
2. Existing profile: More -> Settings -> Solcon TV+ -> inspect account/sync state, synchronize again, or sign out.

## Current branch state and failure

The branch already contains substantial Solcon implementation:

- `SolconTvPlusAccountScreen`
- `SolconTvPlusViewModel`
- `SolconTvPlusClient`
- `SolconTvPlusProtocol`
- `SolconTvPlusRepository`
- `SolconTvPlusSessionStore`
- `SolconStreamPolicy`
- `SolconMulticastEngine` and RTP/UDP transport code
- Live playback routing changes
- Solcon-specific strings and provider unit tests

The current APK nevertheless leaves Solcon unreachable because the application integration is incomplete.

The first confirmed navigation defect is in `SettingsScreen`: the settings tab enum has no Solcon destination, the root settings data has no Solcon row, and the screen dispatcher has no branch that renders `SolconTvPlusAccountScreen`. Therefore a compiled account screen cannot be reached through More -> Settings.

The branch also does not currently bind the TV+ account ViewModel/repository/client/session stack in `appModule`, so exposing the screen without finishing DI would only move the failure to runtime.

The previous design for this branch treated Solcon primarily as a multicast/local-playlist extension. That design is superseded by this specification. Multicast remains a supported playback route, but the provider is now an authenticated first-class OwnTV source.

## Architectural principles

### Reuse OwnTV, do not build a Solcon sub-app

Solcon-specific code is responsible only for provider authentication, catalog translation, playback resolution, provider diagnostics, and multicast transport.

After synchronization, Solcon channels must behave as ordinary OwnTV channels and flow through the same systems used by other sources:

- profiles and profile-source linking
- channel repository/database
- categories
- channel numbers
- Guide/EPG
- favorites
- watch history
- radio/audio-only presentation
- live browsing
- fullscreen player
- channel zapping
- numeric channel entry where already supported
- app restart/session restoration

There must not be a parallel Solcon favorites database, separate Solcon guide UI, separate Solcon radio app, or isolated Solcon player shell.

### Legal and security boundary

The integration may use only the legitimate authenticated Solcon TV+ flow and data made available to the user's subscription/device session.

The implementation must not:

- hardcode account credentials;
- commit secrets;
- log PINs, bearer/access tokens, cookies, signed playback URLs, DRM headers, DRM material, private keys, or client-certificate private material;
- extract or copy private DRM keys;
- spoof protected device identities;
- copy protected authorization material from another device;
- bypass DRM, entitlement checks, device limits, or provider provisioning.

If authenticated playback returns ordinary Android Widevine information for an authorized session/device, OwnTV may pass that information to Media3/MediaDrm normally. If Solcon rejects the app/device or requires unavailable proprietary/device-bound provisioning, OwnTV must expose a precise diagnostic and stop there.

Clear multicast playback is independent from protected-content support and must remain usable when the network and entitlement data legitimately expose such a route.

## Provider lifecycle and dependency injection

The Solcon TV+ stack is application-scoped and wired through Koin using the repository's existing patterns.

Required bindings include the components needed by:

- `SolconTvPlusViewModel`
- `SolconTvPlusRepository`
- `SolconTvPlusClient`
- `SolconTvPlusSessionStore` / session provider
- playback resolver/session lookup dependencies
- existing `SolconMulticastEngine`

Bindings must use constructor references or named parameters where practical so dependency order cannot silently break at runtime.

The session store is the only persistence layer for provider session material. PINs are submitted transiently and never persisted. Session restoration on process restart initializes the ViewModel as connected only when the stored session is still structurally present; failed authenticated calls may invalidate that state according to provider responses.

## Settings integration

`SettingsScreen` gains a dedicated `SettingsTab.SOLCON` destination and a root settings row under the app-level settings group.

Visible copy:

- title: `Solcon TV+`
- description: `Login, subscription and synchronization` or the localized equivalent

All visible strings use Android resources and pass the existing `verifyI18nLiterals` enforcement.

Selecting the row opens `SolconTvPlusAccountScreen` in the same detail-screen pattern as other Settings destinations. Back returns to the exact root settings context and restores focus to the Solcon row.

The account screen supports Android TV remote navigation only:

- D-pad traversal among subscription number, PIN, sign-in/sync/logout/back controls;
- OK/select activation;
- Back always escapes the screen or keyboard state without trapping focus;
- numeric keyboard/input type for subscription and PIN;
- password masking for PIN;
- no mouse or touch requirement.

The existing account screen should be reused and adjusted rather than replaced unless audit findings prove a component violates OwnTV focus conventions.

## First-run and no-source discovery

Solcon must also be visible when the active profile has no usable source.

The existing no-source/add-source flow is extended so Solcon TV+ appears as a normal source option. Selecting it enters the same account/setup state used by Settings rather than introducing a second authentication implementation.

After successful sync:

1. a Solcon `SourceEntity` exists;
2. it is linked to the active profile through the normal profile-source relation;
3. its synchronized channels exist in the normal OwnTV channel tables;
4. the active profile/source summaries refresh;
5. the shell no longer remains in `No source` / `Geen bron` state;
6. the user returns to or can navigate directly into normal Live/Guide/Radio UI.

A navigation/integration regression test must make it impossible for the APK to contain `SolconTvPlusAccountScreen` while having no reachable UI route to it.

## Authentication and session behavior

The account screen exposes these states:

- signed out;
- signing in;
- synchronizing;
- connected;
- authentication failure;
- device/session-limit failure;
- network failure;
- protocol/provider failure;
- empty or unusable catalog;
- partial synchronization warning where applicable.

Successful sign-in immediately proceeds into synchronization for setup flows. Settings may also expose `Synchronize now` for an already connected account.

Logout must:

- invalidate/remove stored Solcon session material;
- clear account UI state;
- leave no reusable sensitive provider state in logs;
- make subsequent protected/authenticated playback resolution fail cleanly until login occurs again.

Existing synchronized channel rows may either remain as unavailable cached catalog data or be removed according to current OwnTV source-removal conventions, but behavior must be deterministic and covered by tests. The preferred behavior is to retain the source/catalog only if OwnTV already retains disconnected source metadata elsewhere; otherwise remove/unlink it consistently with existing source deletion semantics. The implementation plan must resolve this by following the existing source lifecycle rather than inventing a Solcon-only rule.

## Source registration and data mapping

`SolconTvPlusRepository` remains the adapter between the provider protocol and OwnTV persistence.

The provider source is represented by a stable synthetic provider URL such as the existing `solcon-tvplus://account`, which identifies the source without containing secrets.

Each synchronized provider channel maps into `ChannelEntity` and preserves, where available:

- remote/provider channel id;
- channel number;
- channel name;
- logo;
- TV versus radio classification;
- sort/order information;
- EPG identifier;
- catch-up/start-over/replay capability metadata;
- entitlement/protection metadata needed for later playback resolution, stored only when safe and non-secret.

Signed playback URLs, transient cookies, access tokens, and DRM license material must not be stored in Room. Those are resolved just in time.

Synchronization updates existing rows by stable remote ids and removes stale provider rows in the same source without damaging unrelated OwnTV sources.

Partial EPG failure must not discard an otherwise valid channel catalog. Catalog failure must report useful state while keeping the last known good data when that matches existing OwnTV sync semantics.

## Source activation and shell state

Creating/linking the provider source is not enough: the normal application state must observe it.

The implementation must trace and update whichever existing repository/ViewModel drives:

- active source id;
- profile source selection;
- source summary in the top-right shell;
- Live/Guide content refresh after source sync.

After first successful Solcon sync, `sourceSummary` must resolve to the real source rather than continue rendering `shell_no_source`.

Do not special-case the top bar to display `Solcon TV+`; fix the underlying OwnTV source/profile state so the existing summary naturally reports the configured source.

## EPG / Guide

Solcon EPG is written into OwnTV's existing EPG tables using the synchronized provider channel mapping.

The normal Guide consumes this data. There is no Solcon-specific Guide screen.

Where provider data exists, support:

- current programme;
- next programme;
- timeline/grid population;
- channel number/name/logo relationships;
- programme title/description;
- replay/start-over capability metadata.

EPG synchronization reports completeness separately from catalog success so the account/diagnostic screen can distinguish `channels synchronized, EPG partial` from a total sync failure.

## Radio

Provider radio channels map into OwnTV's existing live/audio channel model and appear in the normal radio/audio-only experience.

Radio uses the same source, session, favorites, profile, and playback-resolution architecture as TV channels. No parallel radio source is created.

## Playback resolution

Playback has one normal OwnTV entry point. A selected channel is inspected/resolved before the player is started.

The routing rules are:

### Provider reference

Channels synchronized from TV+ store a stable internal reference such as `solcon-tvplus://live/<id>` rather than an ephemeral stream URL.

At tune time the Solcon repository resolves that id through the authenticated session.

### Clear provider HTTP/HLS/DASH response

If TV+ returns an ordinary clear playback URL plus legitimate headers, route it through OwnTV's existing Media3/ExoPlayer playback infrastructure. Do not build another HTTP player.

### Widevine response

If TV+ returns standard Widevine information and the current Android device/session is authorized, translate it to OwnTV's existing `DrmConfig`/Media3 DRM path and let Android MediaDrm perform normal license/provisioning behavior.

No DRM bypass or emulation is permitted.

### Unsupported protected response

If Solcon requires proprietary/device-bound provisioning, a private certificate, unsupported DRM mode, or rejects the client/device, return a typed unsupported/diagnostic result. The UI must show a useful message that identifies the boundary instead of silently failing or pretending playback succeeded.

### Clear home-network multicast

For legitimate `rtp://` or `udp://` multicast routes, use the existing `SolconMulticastEngine` and its Media3 RTP/UDP transport.

`SolconStreamPolicy` remains responsible for detecting valid multicast destinations and must not classify arbitrary unicast RTP/UDP addresses as provider multicast.

### No fake fallback

Production must never silently substitute simulated/fake playback. Existing compatibility fallback behavior may be used only when it represents a real supported OwnTV engine for the same legitimate stream.

## Live TV, fullscreen and zapping

The completed flow is:

`OwnTV ChannelEntity -> LiveViewModel -> Solcon resolution when needed -> selected playback engine -> LiveScreen/preview -> fullscreen player -> existing zap controls`.

Solcon channels must use the same `LiveViewModel` channel list and zap source as normal OwnTV channels.

Channel up/down, numeric direct tune, fullscreen transitions, mini/audio state, playback session ownership, and normal Back behavior must continue to use existing OwnTV mechanisms.

Any current Solcon-specific routing added to `LiveViewModel` must be audited for duplicated state or paths that bypass the normal player lifecycle. Provider-specific parsing and network calls stay outside the ViewModel.

## Favorites, profiles and history

No new Solcon-specific implementations are added for:

- profiles;
- favorites;
- watch history;
- channel ordering/numbers;
- parental/profile behavior;
- existing settings.

Because synchronized channels use ordinary OwnTV entities, these features should work automatically. Integration tests must prove that the provider source is linked to the active profile and that synchronized channel identity remains stable across re-sync/restart so favorites/history are not orphaned.

## Diagnostics

Add a safe Solcon diagnostic model/view into the account screen or existing diagnostics conventions showing only non-secret operational facts:

- signed in / signed out / session unavailable;
- last successful synchronization timestamp;
- TV channel count;
- radio channel count;
- EPG complete/partial/unavailable;
- last selected playback route;
- multicast availability;
- selected network-interface state where useful;
- provider/device authorization failure category;
- last sync/playback error category.

Never display or log:

- subscription PIN;
- access/session tokens;
- cookies;
- signed private playback URLs;
- license-response bodies;
- DRM keys or key ids when sensitive;
- certificate/private-key material;
- provider private configuration blobs.

Diagnostics must redact full URLs if they contain query parameters or credentials.

## Remote UX and focus

The implementation must follow the existing TV focus system rather than Compose mobile defaults.

Required behavior:

- Settings root row can be reached by D-pad;
- entering Solcon focuses the first useful control;
- text fields can invoke Android TV numeric input;
- D-pad Down/Up escapes text fields and reaches actions;
- connected state focuses `Synchronize now` or the primary action;
- Back from screen returns to Settings/no-source flow;
- Back from IME dismisses it before trapping the user;
- focus returns to the opening row after closing the screen;
- no action depends on touch.

## Tests

Testing is integration-first around the exact previous failure mode, in addition to provider unit tests.

At minimum add or extend tests for:

1. Settings root contains a Solcon TV+ route.
2. Selecting that route dispatches `SolconTvPlusAccountScreen`.
3. No-source/add-source flow exposes Solcon TV+.
4. Successful login followed by sync creates/links a real OwnTV source.
5. Successful sync causes active/source summary state to stop reporting no source.
6. Catalog mapping preserves remote id, name, number, logo and ordering.
7. TV and radio map to the intended normal OwnTV categories/experience.
8. EPG channel/programme mapping is written to OwnTV EPG tables.
9. Re-sync updates existing stable rows and removes stale Solcon rows only.
10. Failed authentication returns the correct account state and persists no credentials.
11. Failed synchronization preserves/report states according to normal source-sync semantics.
12. Session state restores after ViewModel/process recreation when valid session data exists.
13. Logout removes session state and subsequent authenticated resolution is unavailable.
14. `solcon-tvplus://live/<id>` routes through provider resolution before playback.
15. Clear authenticated HTTP/HLS/DASH routes to the normal Media3 path.
16. Valid clear RTP/UDP multicast routes to `SolconMulticastEngine`.
17. Unicast/malformed RTP/UDP does not get misclassified as Solcon multicast.
18. Standard Widevine response maps to OwnTV's DRM config.
19. Unsupported/proprietary protected content returns a precise unsupported diagnostic and never falls back to fake playback.
20. Solcon channels participate in normal zapping/direct tune identity.
21. Source/session/channel state restores after app restart.
22. Navigation regression test proves the account screen cannot exist without at least one real route.
23. All new user-visible literals are resources and `verifyI18nLiterals` passes.

Where Compose UI tests are too expensive for all logic, split the problem: pure navigation/catalog definitions get unit tests, repository/database mapping gets repository tests, and at least one Android/UI integration test covers the real Settings route and focusable account destination.

## Verification and APK

Before completion, run on the final branch head:

```bash
./gradlew testStandardDebugUnitTest
./gradlew lintStandardDebug
./gradlew assembleStandardDebug
```

Also run the repository's relevant i18n verification task, including `verifyI18nLiterals` when that is the task name exposed by the build.

Failures must be fixed rather than suppressed. Do not disable or weaken i18n/lint checks to make the branch green.

For the final APK:

- use the newly built Standard Debug artifact;
- verify it is a valid APK/ZIP archive;
- calculate byte size;
- calculate SHA-256;
- report exact commit SHA;
- report exact APK filename;
- report unit-test result;
- report i18n result;
- report lint result;
- report build result.

GitHub Actions may supplement local verification, but a green workflow is not sufficient if the actual UI route/source lifecycle is still absent.

## Runtime boundary reporting

The final completion report must distinguish what was verified by automated/local build testing from what requires real Solcon credentials, a Solcon network, or provider-side authorization.

If the TV+ API or DRM path refuses this app/device, the implementation is still considered complete only if:

- authentication/catalog/source/UI integration is otherwise finished;
- clear legitimate playback routes work where available;
- the protected-content failure is surfaced as a precise provider/provisioning diagnostic;
- no bypass was attempted;
- the exact runtime boundary and evidence are reported.

Do not claim successful protected-channel playback without evidence from an authorized real-device session.

## Definition of done

The feature is done only when the final built APK implements this functional path:

Install APK -> open OwnTV -> create/select profile -> no source configured -> choose Solcon TV+ -> enter subscription number + PIN -> authenticate -> synchronize subscription -> Solcon becomes active OwnTV source -> normal Live/Guide/Radio screens consume synchronized data -> select a channel -> playback resolver chooses the appropriate legitimate route -> playback starts or a precise DRM/provisioning diagnostic is shown -> channel switching remains functional -> restart app -> session/source/channel state restores.

And this alternate path is also functional:

More -> Settings -> Solcon TV+ -> account/status/synchronize/logout.

Existing OwnTV functionality must not regress, and the existing OwnTV design system/navigation must remain the primary UI.