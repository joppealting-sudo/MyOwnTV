# Solcon TV Integration Design

## Goal

Prepare MyOwnTV to act as a full Android TV front-end for a legitimate Solcon subscription, while keeping the public repository free of private network data, account credentials, device identifiers, DRM secrets, and provider-issued authorization material.

The first usable milestone must play clear Solcon multicast channels from a locally supplied M3U on Android TV, preserve OwnTV's existing guide/radio/player behavior, and leave a clean authorization seam for provider-supported protected playback later.

## Existing foundation

MyOwnTV already provides the hard parts of a TV application: Live TV browsing, EPG UI, channel numbers, remote navigation, favorites/history, recordings, audio-only/radio presentation, mpv full-screen playback, ExoPlayer live preview, HLS/DASH support, diagnostics, profiles, and source imports.

OwnTV's shared database/sync/playback logic is consumed from `ahXN00/OwnTV_Core` as `core` and `player-core`. This repository therefore should not duplicate that engine unless a provider feature truly cannot be implemented at the app boundary.

## Chosen architecture

### 1. App-first provider integration

Add a small Solcon-specific package in the app rather than forking OwnTV_Core immediately.

Responsibilities:

- recognize multicast stream URLs safely;
- normalize RTP/UDP multicast URL forms used by local playlists;
- route multicast live channels to mpv, which already handles raw IPTV transports better than the current ExoPlayer preview path;
- keep ordinary HTTP/HLS/DASH channels on OwnTV's existing engine ladder;
- expose a provider-authorization contract without implementing or emulating Solcon DRM;
- expose public-safe diagnostics only.

This is deliberately smaller than copying `LivePreviewEngine`. A separate Media3 RTP engine can be added later if real-device testing shows mpv tune time is unacceptable.

### 2. Multicast playback policy

Introduce a pure `SolconStreamPolicy` used by Live TV routing.

It will:

- detect `rtp://` and `udp://` multicast URLs;
- accept both `rtp://@224.x.x.x:port` and `rtp://224.x.x.x:port` style inputs;
- verify that the target address is in IPv4 multicast space before treating it as a provider multicast stream;
- classify such channels as mpv-first;
- leave non-multicast streams untouched;
- never rewrite credentials, query parameters, or protected URLs.

For multicast channels, OwnTV should skip the ExoPlayer-first attempt and open directly on mpv. This avoids a predictable ExoPlayer failure before fallback and reduces tune delay.

### 3. Radio

No new radio player is needed. OwnTV already models audio-only media and has an audio now-playing UI.

A Solcon radio station is represented as an ordinary LIVE channel whose stream carries no video. The existing player detects audio-only media and presents it accordingly. Provider-specific categories may use `Radio` as a group name in the private local playlist.

### 4. EPG and channel data

Do not commit a real Solcon lineup.

The user supplies channel data locally through OwnTV's existing M3U import flow. EPG remains OwnTV's existing XMLTV/EPG-source flow until an official Solcon guide endpoint is documented.

A public sample playlist may contain only documentation-safe example multicast addresses; it must never contain captured Solcon addresses, account identifiers, signed URLs, or real entitlement metadata.

### 5. Authorization seam

Add a provider-neutral `SolconAuthorizationProvider` contract with a default `NotProvisioned` implementation.

It exists so provider-supported authorization can later be connected without spreading Solcon-specific logic through `LiveViewModel` or player UI.

Allowed future implementations may consume credentials/certificates explicitly issued by Solcon for this client/device. The public repository must not include:

- private keys;
- client certificate private material;
- copied Arris credentials;
- control words or descrambling keys;
- bearer/session tokens;
- account PINs/passwords;
- signed HLS URLs captured from another device.

The default implementation performs no authorization and cannot decrypt protected channels.

### 6. Public-repository safety

Expand `.gitignore` to keep provider/network artifacts out of commits, including:

- `*.pcap`, `*.pcapng`, `*.eth`;
- local M3U/M3U8 files;
- local provider config directories;
- certificate/key containers such as P12/PFX/PEM/KEY;
- environment files;
- Solcon-local JSON/config files.

Do not commit the user's Arris MAC, serial, LAN addresses, captures, real channel list, real multicast map, or real provider-issued authorization artifacts.

Runtime logging must use channel names and redacted URLs only, following OwnTV's existing diagnostics conventions.

### 7. Diagnostics

Add a small public-safe classification helper so diagnostics can state:

- transport: multicast RTP / multicast UDP / HTTP-family / unknown;
- engine decision: mpv-first / existing ladder;
- authorization state: not provisioned / provider implementation available.

Diagnostics must never print credentials, query strings from protected URLs, certificate contents, local account data, or full private configuration objects.

## Integration points

Primary files:

- `app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/SolconAuthorizationProvider.kt`
- `app/src/test/java/tv/own/owntv/provider/solcon/SolconStreamPolicyTest.kt`
- `app/src/test/java/tv/own/owntv/provider/solcon/SolconAuthorizationProviderTest.kt`
- `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt`
- `.gitignore`
- `docs/providers/solcon.md`

`LiveViewModel` changes stay intentionally small: when a channel is recognized as local multicast, the engine preference passed to the existing ladder becomes mpv-first. All existing history, guide, zap, recording, channel-number, audio-only and HUD behavior remains unchanged.

## Testing

Test-first requirements:

1. RTP multicast URLs classify as multicast.
2. UDP multicast URLs classify as multicast.
3. `@`-prefixed host syntax is accepted.
4. Unicast RTP/UDP addresses are not treated as Solcon multicast.
5. HTTP/HLS/DASH URLs remain on the existing engine path.
6. Malformed URLs fail closed without crashing.
7. The default authorization provider always reports `NotProvisioned` and never fabricates credentials.
8. Existing LiveViewModel routing tests continue to pass.

GitHub Actions is the source of truth for compilation/tests because the current execution sandbox cannot resolve GitHub/Maven dependencies directly.

## Future extension: Media3 RTP preview

If real Philips testing shows mpv multicast zapping is too slow, port the small MIT-licensed RTP transport concepts from `cgang/myiptv` into a focused OwnTV module:

- multicast socket/interface selection;
- RTP header parsing;
- sequence/reorder handling;
- Media3 `DataSource` + `ProgressiveMediaSource`.

This is phase two, not a prerequisite for clear-channel playback. It should be added only after the simpler mpv-first path has been measured on the Philips.

## Success criteria

The branch is ready when:

- the public repo contains no private provider/network artifacts;
- a locally imported clear multicast channel routes directly to mpv;
- regular OwnTV sources still use the normal engine ladder;
- audio-only multicast can flow through the existing radio/audio-only UI;
- EPG continues to use OwnTV's existing source system;
- protected playback has an explicit but inert provider-authorization seam;
- unit tests and GitHub Actions pass;
- no DRM bypass, key extraction, or copied device authorization is introduced.
