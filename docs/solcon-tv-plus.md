# Solcon TV+ in MyOwnTV

MyOwnTV is OwnTV plus one addition: a Solcon TV+ subscription as a playlist. This page covers what that
addition does, how to try a build on the TV, and how to take in OwnTV updates without losing it.

## What it does

- **Sign in** with the subscription number and PIN from Settings → Solcon TV+, from setup's *Add a source*,
  or from Settings → Manage sources.
- **Sync** TV channels, radio stations and a 9-day guide window (a week back, two days ahead) into an ordinary
  OwnTV playlist named *Solcon TV+*. Live TV, the Guide, favorites, history and channel numbers work as they
  do for any playlist. OwnTV re-syncs by itself when it comes to the front and the last sync is over
  12 hours old; *Sync now* does it on demand.
- **Play** each channel by asking Solcon for its stream when you tune in. Channels are stored as
  `solcon-tvplus://live/<channel>[/<asset>]` and never as a signed link.

  | Solcon hands out | OwnTV plays it with |
  | --- | --- |
  | A clear HTTP stream (HLS, DASH) | ExoPlayer, like any playlist stream |
  | A Widevine-protected stream | ExoPlayer with OwnTV's normal DRM handling |
  | Multicast (`rtp://`, `udp://`) | mpv, with a Wi-Fi multicast lock while the app is open |
  | Protection OwnTV cannot play | Nothing: a message says so |

- **Explain problems** on the Solcon TV+ screen: session, last sync, whether the guide loaded fully, which
  server was used, the network type, the last channel's playback route, and the last failure with its step,
  HTTP status and Solcon's own error code.

## What it does not do (yet)

- No catch-up, restart or replay for Solcon channels, and no recording of Solcon or multicast channels.
  These options are hidden for those channels, not broken.
- The Live TV preview pane shows a multicast channel's logo and guide rather than video. Press OK to watch.
- **Not yet tried against the real service.** The Solcon protocol code was written without a test account.
  The first real sign-in may show a difference. If it does, the Status card's *Last problem* line (step,
  HTTP status, error code) is what to report.

## Privacy

- The PIN is sent to Solcon once, to sign in. It is never stored or logged.
- The session Solcon returns is stored encrypted with an Android Keystore key. If that key is lost, for
  example after restoring to another TV, the session is dropped and you sign in again.
- Signed stream links, licence details and raw Solcon responses are never written to the database or the
  diagnostics. Diagnostics hold only fixed categories, counts and times.

## Trying a build on the TV

Every pull request builds an APK. Open the pull request's **Checks** tab (or *Actions* → *PR APK* → the
run), and download the artifact `MyOwnTV-pr<number>-<commit>.apk` from the bottom of the run page. GitHub
hands it over zipped, so unzip it first, then install it with a sideloading app or `adb install`. Artifacts
are kept for 14 days.

Without signing secrets, these are debug builds with a new signature on every run. Android then refuses to
install one over another, so you have to uninstall first, which clears the app's data. To keep data between
builds, add these repository secrets (Settings → Secrets and variables → Actions) with your own release key:

`KEYSTORE_BASE64` (the keystore file, base64-encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Keep that keystore safe and out of the repository. An APK signed with it only updates an install signed
with the same key.

## Taking in OwnTV updates

OwnTV changes often, so merge it in regularly rather than all at once:

```bash
git remote add upstream https://github.com/ahXN00/OwnTV.git   # once
git fetch upstream
git merge upstream/main
```

- **Merge; don't rebase or squash.** Merge pull requests that bring in OwnTV with *Create a merge commit*.
  A squash merge hides OwnTV's commits from git, and every later update then conflicts all over again.
- Most Solcon code has files of its own: `provider/solcon/`, `features/settings/Solcon*`,
  `di/SolconModule.kt`, `strings_solcon.xml` (English) and `values-nl/strings_solcon.xml`.
- The places where it touches OwnTV's own files, which is where conflicts will show up:
  - `LiveViewModel.kt`: Solcon's tuner host, preview and playback failures
  - `OwnTVShell.kt`: the Solcon failure message, and hiding *Record* for Solcon channels
  - `SettingsScreen.kt`: the Solcon TV+ tab, row and search entry
  - `SetupWizard.kt`, `AddSourceChooserScreen.kt`, `ManageSourcesScreen.kt`: the Solcon TV+ entry points
  - `LiveScreen.kt`, `EpgViewModel.kt`: *Record* hidden for Solcon channels
  - `AndroidManifest.xml`: the multicast permissions
- After a merge, run the checks CI runs: `./gradlew testStandardDebugUnitTest lintStandardDebug` and
  `python3 tools/i18n/check_hardcoded_strings.py verify --bootstrap`.
