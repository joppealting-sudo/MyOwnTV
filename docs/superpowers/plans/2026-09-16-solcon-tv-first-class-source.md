# Solcon TV+ First-Class Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Solcon TV+ implementation reachable and usable as a normal OwnTV Android TV source from first-run setup and Settings, with authenticated catalog/EPG sync, normal profiles/favorites/radio/live playback, safe diagnostics, and a verified Standard Debug APK.

**Architecture:** Solcon remains a provider adapter, not a parallel application. Authentication/session/catalog/playback resolution stay under `provider/solcon`, while synchronized data is persisted into OwnTV's normal source/channel/category/EPG tables and consumed by the existing Live/Guide/radio/favorites/profile UI. Settings and setup expose the same account screen; playback resolves `solcon-tvplus://live/<id>` just in time and routes clear HTTP, multicast, or standard Widevine through existing OwnTV engines without bypassing provider authorization.

**Tech Stack:** Kotlin, Jetpack Compose for TV, Koin 4, Room/core DAOs, OkHttp, Media3/ExoPlayer, Android MediaDrm/Widevine, Gradle 9/AGP 9, JUnit.

**Spec:** `docs/superpowers/specs/2026-09-16-solcon-tv-design.md`

## Global Constraints

- Work only on `feat/solcon-tv`.
- No hardcoded credentials, PINs, tokens, cookies, private DRM keys, private certificates, copied device identities, or DRM bypasses.
- User-visible text must be Android string/plural resources; do not weaken `verifyI18nLiterals`.
- Preserve existing OwnTV UI and navigation patterns; Android TV D-pad/OK/Back must work without touch.
- Solcon channels must use existing OwnTV source/profile/channel/EPG/favorites/history/radio/live models.
- Signed playback URLs and DRM/session material must remain ephemeral and must not be persisted in Room or logs.
- Logout clears the Solcon account session but preserves the last synchronized source/channel identities so favorites/history remain stable; authenticated playback then requires sign-in again.
- If Solcon requires unsupported proprietary/device-bound DRM provisioning, return an explicit diagnostic rather than attempting a workaround.

---

### Task 1: Make Settings navigation structurally testable and expose Solcon TV+

**Files:**
- Create: `app/src/main/java/tv/own/owntv/features/settings/SolconSettingsRoute.kt`
- Create: `app/src/test/java/tv/own/owntv/features/settings/SolconSettingsRouteTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/shell/components/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings_solcon.xml`

**Interfaces:**
- Produces: `internal object SolconSettingsRoute` with stable route key and resource ids used by Settings/search tests.
- Produces: `SettingsTab.SOLCON` and a real `SolconTvPlusAccountScreen` dispatch branch.

- [ ] **Step 1: Add failing navigation tests**

Test that `SolconSettingsRoute.key == "solcon_tvplus"`, title/description resources exist, and the Settings navigation catalogue declares the Solcon route as a real destination rather than a dead string.

- [ ] **Step 2: Verify RED in CI**

Push only the new test/route expectation and confirm `testStandardDebugUnitTest` fails because the production route object/destination is missing.

- [ ] **Step 3: Implement the Settings route**

Add `SettingsTab.SOLCON`, import `SolconTvPlusAccountScreen`, dispatch it with `onBack = { tab = ROOT }`, add an APP-group root row using `tabRowKey(SettingsTab.SOLCON)`, add Settings search entry, and add localized title/description/keywords in `strings_solcon.xml`.

- [ ] **Step 4: Verify GREEN**

Confirm the route test and full unit suite pass in CI.

---

### Task 2: Wire the Solcon account/session stack into Koin

**Files:**
- Create: `app/src/test/java/tv/own/owntv/di/SolconDependencyGraphTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/di/AppModule.kt`
- Possibly modify the existing network module only if no general `OkHttpClient` binding can satisfy `SolconTvPlusClient`.

**Interfaces:**
- Produces app-scoped `SolconTvPlusSessionStore`, `SolconTvPlusClient`, `SolconTvPlusRepository`.
- Produces `SolconTvPlusViewModel` Koin binding.

- [ ] **Step 1: Add a failing Koin declaration test**

Use Koin's module verification/check API or constructor-level binding test to prove the account ViewModel graph can resolve from `appModule` + existing modules.

- [ ] **Step 2: Verify RED**

Confirm failure names the missing Solcon binding(s).

- [ ] **Step 3: Add minimal bindings**

Bind session store from `androidContext()`, client from the existing app `OkHttpClient`, repository from DAOs/settings, and ViewModel using constructor-safe Koin DSL.

- [ ] **Step 4: Verify GREEN**

Run the DI test and unit suite.

---

### Task 3: Expose the same Solcon account flow from first-run / Add source

**Files:**
- Create: `app/src/main/java/tv/own/owntv/features/setup/SourceSetupChoice.kt`
- Create: `app/src/test/java/tv/own/owntv/features/setup/SourceSetupChoiceTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/setup/AddSourceChooserScreen.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/setup/SetupWizard.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/settings/ManageSourcesScreen.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusAccountScreen.kt`
- Modify: `app/src/main/res/values/strings_solcon.xml`

**Interfaces:**
- `AddSourceChooserScreen(..., onSolcon: () -> Unit, ...)`.
- `SolconTvPlusAccountScreen(onBack: () -> Unit, onSynchronized: (() -> Unit)? = null, ...)` invokes `onSynchronized` after successful sync when used by setup.

- [ ] **Step 1: Add failing chooser/catalogue tests**

Assert the setup source-choice catalogue includes `SOLCON_TV_PLUS` as a first-class choice and that no-source setup can navigate to it.

- [ ] **Step 2: Verify RED**

Confirm tests fail because the chooser has only Remote/Manual.

- [ ] **Step 3: Implement chooser + wizard states**

Add a Solcon card to the existing TV-friendly chooser. Extend `SetupWizard.Step` with a Solcon account step. Settings -> Manage Sources -> Add source must use the same chooser and account screen, not duplicate login logic.

- [ ] **Step 4: Complete successful setup**

When Solcon sync succeeds in onboarding, call the wizard's normal `vm.finish(onDone)` path. In Manage Sources, return to the list and let its existing source observation refresh.

- [ ] **Step 5: Verify GREEN**

Run chooser/navigation tests and full unit suite.

---

### Task 4: Make successful sync activate the real OwnTV source and preserve stable identity

**Files:**
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepositoryIntegrationTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt`
- Modify only existing settings/source APIs if necessary to set the default source through normal OwnTV mechanisms.

**Interfaces:**
- `sync(...)` must create/link one stable source, keep stable channel ids by `remoteId`, preserve channel numbers/radio mapping, remove only stale Solcon rows, populate EPG, mark sync time, and set the provider source as the default/active source when the active profile previously had no default source.

- [ ] **Step 1: Add repository integration tests**

Cover source creation/linking, channel number/name/logo/order preservation, TV vs radio categories, EPG mapping, stale-row removal, stable row ids on resync, and default-source activation when no source is selected.

- [ ] **Step 2: Verify RED**

Confirm at least the source-activation assertion fails on the current repository.

- [ ] **Step 3: Implement activation using `SettingsRepository`**

After a successful first sync, if no valid default source exists for the active profile, set the new Solcon source id as default through the same settings API used elsewhere. Do not special-case the shell's `No source` label.

- [ ] **Step 4: Keep partial EPG non-fatal**

Preserve a successful catalog when one or more EPG batches fail and return `epgComplete=false`.

- [ ] **Step 5: Verify GREEN**

Run repository tests and full unit suite.

---

### Task 5: Harden account/session/logout and diagnostics state

**Files:**
- Create: `app/src/main/java/tv/own/owntv/provider/solcon/SolconDiagnostics.kt`
- Create: `app/src/test/java/tv/own/owntv/provider/solcon/SolconDiagnosticsTest.kt`
- Create/extend: `app/src/test/java/tv/own/owntv/features/settings/SolconTvPlusViewModelTest.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusViewModel.kt`
- Modify: `app/src/main/java/tv/own/owntv/features/settings/SolconTvPlusAccountScreen.kt`
- Modify: `app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusClient.kt`
- Modify: `app/src/main/java/tv/own/owntv/provider/solcon/tvplus/SolconTvPlusRepository.kt`
- Modify: `app/src/main/res/values/strings_solcon.xml`

**Interfaces:**
- Safe diagnostics exposes only session state, last sync timestamp, TV/radio counts, EPG status, route/multicast status, and typed error categories.
- Logout clears only session material and ViewModel connected state; synchronized source rows remain.

- [ ] **Step 1: Add failing ViewModel/diagnostics tests**

Cover failed auth, failed sync, session-restored initial state, logout, sync summary persistence in UI state, and redaction guarantees.

- [ ] **Step 2: Verify RED**

Confirm current ViewModel lacks required diagnostic/last-sync state.

- [ ] **Step 3: Implement safe diagnostic model and screen rendering**

Add localized labels for login state, last sync, TV/radio counts, EPG partial/complete, multicast availability, and last playback route/error category. Never render token/cookie/PIN/full protected URL values.

- [ ] **Step 4: Verify GREEN**

Run diagnostic/ViewModel tests.

---

### Task 6: Finish normal Live playback routing for TV+ clear, multicast and DRM responses

**Files:**
- Extend: `app/src/test/java/tv/own/owntv/provider/solcon/SolconPlaybackRouteTest.kt`
- Extend/create Live routing tests under `app/src/test/java/tv/own/owntv/features/live/`
- Modify: `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt`
- Modify only provider/playback helpers as required.

**Interfaces:**
- `solcon-tvplus://live/<id>` resolves through `SolconTvPlusRepository.resolveLive` before player start.
- Clear HTTP/HLS/DASH -> normal OwnTV/Media3 route.
- Valid clear RTP/UDP multicast -> `SolconMulticastEngine`.
- Widevine -> existing OwnTV DRM configuration.
- Unsupported protected playback -> typed user-visible diagnostic, no fake/simulated fallback.

- [ ] **Step 1: Add failing end-to-end route tests**

Test provider reference resolution, clear authenticated stream route, multicast route, Widevine route, unauthenticated failure, and unsupported proprietary protection.

- [ ] **Step 2: Verify RED**

Confirm missing/incomplete route(s) fail.

- [ ] **Step 3: Implement minimal routing fixes**

Keep provider/network work out of the player UI; LiveViewModel orchestrates and existing engines own playback.

- [ ] **Step 4: Verify zapping/direct-tune identity remains normal**

Existing channel ids/source ids remain stable and zap list continues to use the normal channel collection.

- [ ] **Step 5: Verify GREEN**

Run Live/provider tests and full unit suite.

---

### Task 7: Add regression coverage for the exact previous failure and i18n enforcement

**Files:**
- Create: `app/src/test/java/tv/own/owntv/features/settings/SolconNavigationRegressionTest.kt`
- Add Android instrumentation test if the existing test harness can compose Settings cheaply: `app/src/androidTest/java/tv/own/owntv/features/settings/SolconSettingsNavigationTest.kt`
- Modify: `.github/workflows/solcon-dev.yml`
- Modify: `app/src/main/res/values/strings_solcon.xml`

**Interfaces:**
- Regression test must fail if the account screen exists but the Settings route or no-source route disappears.
- CI must explicitly run the repository's i18n verification task in addition to unit tests/lint/build.

- [ ] **Step 1: Write regression tests first**

Use structural route/catalogue assertions plus one UI/instrumentation navigation test where supported.

- [ ] **Step 2: Verify RED if any route contract is still incomplete**

- [ ] **Step 3: Fix all remaining navigation/focus/i18n issues**

Ensure account entry/connected states have a deterministic first focus target and Back never traps focus.

- [ ] **Step 4: Add `verifyI18nLiterals` to Solcon CI**

Run it before unit tests/lint/build; do not disable the checker.

- [ ] **Step 5: Verify GREEN**

Confirm all tests and i18n verification pass.

---

### Task 8: Final verification, APK and report

**Files:**
- Modify `.github/workflows/solcon-dev.yml` only if needed to expose final checksum/size as artifact/log output.

- [ ] **Step 1: Run final required commands on branch head**

```bash
./gradlew verifyI18nLiterals
./gradlew testStandardDebugUnitTest
./gradlew lintStandardDebug
./gradlew assembleStandardDebug
```

- [ ] **Step 2: Verify APK archive**

Stage the exact Standard Debug APK, verify ZIP/APK integrity, calculate byte size and SHA-256.

- [ ] **Step 3: Download the CI APK artifact**

Use the successful `feat/solcon-tv` workflow artifact and make it available to the user.

- [ ] **Step 4: Final report**

Report final commit SHA, APK filename, size, SHA-256, unit-test result, i18n result, lint result, build result, and any real-device Solcon/DRM boundary that automated tests cannot prove.
