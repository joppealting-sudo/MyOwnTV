# Solcon TV Integration Design

## Goal

Prepare MyOwnTV to act as a full Android TV front-end for a legitimate Solcon subscription, while keeping the public repository free of private network data, account credentials, device identifiers, DRM secrets, and provider-issued authorization material.

The first usable milestone must play clear Solcon multicast channels from a locally supplied M3U on Android TV through a native Media3 RTP/UDP path, preserve OwnTV's existing guide/radio/player behavior, and leave a clean authorization seam for provider-supported protected playback later.

## Existing foundation

MyOwnTV already provides the hard parts of a TV application: Live TV browsing, EPG UI, channel numbers, remote navigation, favorites/history, recordings, audio-only/radio presentation, mpv full-screen playback, ExoPlayer live preview, HLS/DASH support, diagnostics, profiles, and source imports.

OwnTV's shared database/sync/playback logic is consumed from `ahXN00/OwnTV_Core` as `core` and `player-core`. This repository therefore should not duplicate that engine wholesale. The Solcon multicast implementation is a focused app-side Media3 engine/transport extension that integrates with OwnTV's existing live routing.

## Chosen architecture

### 1. App-first provider integration

Add a focused Solcon package in the app rather than forking OwnTV_Core immediately.

Responsibilities:

- recognize multicast stream URLs safely;
- normalize RTP/UDP multicast URL forms used by local playlists;
- provide a native Media3 RTP/UDP multicast data source and live playback engine;
- keep ordinary HTTP/HLS/DASH channels on OwnTV's existing engine ladder;
- retain mpv as compatibility fallback where appropriate;
- expose a provider-authorization contract without implementing or emulating Solcon DRM;
- expose public-safe diagnostics only.

The Media3 transport is based on the MIT-licensed RTP implementation in `cgang/myiptv`, adapted to OwnTV's architecture and tests. Source attribution and the MIT license notice for the ported code must remain in the repository.

### 2. Multicast playback policy

Introduce a pure `SolconStreamPolicy` used by Live TV routing.

It will:

- detect `rtp://` and `udp://` multicast URLs;
- accept both `rtp://@224.x.x.x:port` and `rtp://224.x.x.x:port` style inputs;
- verify that the target address is in IPv4 multicast space before treating it as a provider multicast stream;
- classify such channels for the Solcon Media3 multicast engine;
- leave non-multicast streams untouched;
- never rewrite credentials, query parameters, or protected URLs.

For recognized multicast channels, OwnTV routes directly to the Media3 multicast engine instead of deliberately failing through the normal HTTP-oriented ExoPlayer path first. If the Media3 multicast engine cannot open a channel, the existing compatibility path may hand off to mpv; that fallback must not change behavior for ordinary HTTP/HLS/DASH sources.

### 3. Media3 RTP/UDP multicast engine

Port and adapt the useful pieces of MyIPTV's multicast implementation:

- `RtpPacket`: parse RTP v2 headers and expose payload bytes safely;
- `RtpTransport`: bind a `MulticastSocket`, select/join the correct network interface and group, receive UDP datagrams, strip RTP framing when present, and leave/close cleanly;
- bounded sequence/reorder handling so minor packet reordering does not immediately corrupt playback;
- `RtpDataSource`: expose the transport through Media3's `DataSource` contract;
- `RtpDataSourceFactory`: create independent data sources per tune;
- network-interface selection suitable for Android TV Ethernet/Wi-Fi without hardcoding a device interface name;
- `ProgressiveMediaSource` integration for MPEG-TS payloads.

The adapted implementation must support both RTP-over-UDP (`rtp://`) and raw UDP multicast (`udp://`) because provider playlists may use either notation. It must avoid unbounded queues, close sockets deterministically, and never log packet payloads or sensitive full URLs.

The port is not a verbatim package copy. Namespaces, lifecycle, diagnostics, tests and interface selection are adapted to MyOwnTV. The original MIT attribution is retained.

### 4. Radio

No separate radio player is needed. OwnTV already models audio-only media and has an audio now-playing UI.

A Solcon radio station is represented as an ordinary LIVE channel whose stream carries no video. The multicast Media3 engine must therefore correctly publish audio-only state so the existing radio/audio-only presentation remains useful. Provider-specific categories may use `Radio` as a group name in the private local playlist.

### 5. EPG and channel data

Do not commit a real Solcon lineup.

The user supplies channel data locally through OwnTV's existing M3U import flow. EPG remains OwnTV's existing XMLTV/EPG-source flow until an official Solcon guide endpoint is documented.

A public sample playlist may contain only documentation-safe example multicast addresses; it must never contain captured Solcon addresses, account identifiers, signed URLs, or real entitlement metadata.

### 6. Authorization seam

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

### 7. Public-repository safety

Expand `.gitignore` to keep provider/network artifacts out of commits, including:

- `*.pcap`, `*.pcapng`, `*.eth`;
- local M3U/M3U8 files;
- local provider config directories;
- certificate/key containers such as P12/PFX/PEM/KEY;
- environment files;
- Solcon-local JSON/config files.

Do not commit the user's Arris MAC, serial, LAN addresses, captures, real channel list, real multicast map, or real provider-issued authorization artifacts.

Runtime logging must use channel names and redacted URLs only, following OwnTV's existing diagnostics conventions.

### 8. Diagnostics

Add public-safe diagnostics so the app can state:

- transport: multicast RTP / multicast UDP / HTTP-family / unknown;
- engine decision: Media3 multicast / existing ladder / mpv fallback;
- selected local network interface name only when needed for debugging (never MAC address or local IP by default);
- packet/reorder/drop counters as aggregate numbers only;
- authorization state: not provisioned / provider implementation available.

Diagnostics must never print credentials, query strings from protected URLs, certificate contents, packet payloads, local account data, MAC addresses, or full private configuration objects.

## Integration points

Primary files:

- `app/src/main/java/tv/own/owntv/provider/solcon/SolconStreamPolicy.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/SolconAuthorizationProvider.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpPacket.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpTransport.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpDataSource.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/RtpDataSourceFactory.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/MulticastNetworkSelector.kt`
- `app/src/main/java/tv/own/owntv/provider/solcon/multicast/SolconMulticastEngine.kt`
- corresponding unit tests under `app/src/test/java/.../provider/solcon/`
- `app/src/main/java/tv/own/owntv/features/live/LiveViewModel.kt`
- player DI/surface integration only where required by the existing OwnTV flow
- `.gitignore`
- `docs/providers/solcon.md`
- `NOTICE` or equivalent third-party attribution entry for the MyIPTV-derived MIT code

`LiveViewModel` must stay focused on routing/orchestration. RTP parsing, sockets, interface selection and Media3 source construction stay outside the ViewModel.

## Testing

Test-first requirements:

1. RTP multicast URLs classify as multicast.
2. UDP multicast URLs classify as multicast.
3. `@`-prefixed host syntax is accepted.
4. Unicast RTP/UDP addresses are not treated as Solcon multicast.
5. HTTP/HLS/DASH URLs remain on the existing engine path.
6. Malformed URLs fail closed without crashing.
7. RTP v2 packets with CSRC/extension/padding are parsed without leaking header bytes into the MPEG-TS payload.
8. Invalid/truncated RTP packets are rejected safely.
9. Sequence ordering/reorder logic is bounded and handles wraparound.
10. Raw UDP mode passes datagram payload through without RTP stripping.
11. DataSource open/read/close releases transport resources deterministically.
12. Interface selection prefers an active multicast-capable Ethernet/Wi-Fi interface and fails clearly when none is available.
13. The default authorization provider always reports `NotProvisioned` and never fabricates credentials.
14. Multicast channels route to the Media3 multicast engine; ordinary streams retain the existing ladder.
15. Media3 multicast failure can fall back without creating a fallback loop.
16. Audio-only multicast reaches OwnTV's audio-only/radio presentation state.
17. Existing LiveViewModel routing tests continue to pass.

GitHub Actions is the source of truth for compilation/tests because the current execution sandbox cannot resolve GitHub/Maven dependencies directly.

## Success criteria

The branch is ready when:

- the public repo contains no private provider/network artifacts;
- a locally imported clear RTP or UDP multicast channel opens through the native Media3 multicast engine;
- the ported transport retains MyIPTV MIT attribution;
- minor RTP reordering is handled with a bounded buffer and useful aggregate diagnostics;
- a multicast failure can fall back to mpv without affecting normal HTTP/HLS/DASH routing;
- regular OwnTV sources still use the normal engine ladder;
- audio-only multicast can flow through the existing radio/audio-only UI;
- EPG continues to use OwnTV's existing source system;
- protected playback has an explicit but inert provider-authorization seam;
- unit tests and GitHub Actions pass;
- no DRM bypass, key extraction, copied device authorization, packet payload logging, or provider secrets are introduced.
