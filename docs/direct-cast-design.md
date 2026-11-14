# Direct casting: design

Status: proposal, not implemented.

## Problem

On some phones Play Services stops finding Chromecasts: no device appears in etincelle, nor in
Android's own cast screen, until Wi-Fi is turned off and on again. Observed on the Edge 20 Pro
(Android 13) with the Chromecast `jardin`, 2026-11-14:

- jardin advertises `_googlecast._tcp` and `_9527437F._sub._googlecast._tcp` and answers every query
  the phone sends, within about 300 ms, with well-formed packets (captured on a wired host).
- Play Services keeps re-asking for jardin's SRV record with queries whose answers are multicast,
  and never lists the device. Its own unicast-answer (QU) queries do not help either.
- A query sent by the app from an ordinary ephemeral port gets jardin's full answer (PTR, SRV, TXT,
  A) back on that port in under 100 ms, every time ("legacy unicast", RFC 6762 section 6.7).
- Sending a QU query from port 5353 so that the unicast answer reaches Play Services' socket was
  tried and does not work.

So the app can find Chromecasts by itself, but the Cast SDK can only connect to devices Play
Services discovered; there is no API to hand it a device found elsewhere.

## Goal

When Play Services does not see a Chromecast that the app finds by itself, etincelle still lists it
and casts to it, with the same features as today: live, start-over, catch-up and recordings; play,
pause, rewind, forward, seek bar, back to live; the programme bar; volume keys; stop; return to the
phone; switching device; recovery on stream errors.

Non-goals for the first version:

- Android's system cast notification and lock-screen controls (provided by Play Services today).
- Resuming a direct session after the app has been killed.
- Replacing the Play Services path. It stays the default whenever it sees the device.

## Overview

Three new pieces in `core:cast`, and an integration step in `CastPlayerController`:

1. `CastScanner`: finds Chromecasts with legacy-unicast mDNS queries.
2. `CastChannel`: a client for the Cast v2 protocol (TLS on port 8009), with device authentication.
3. `DirectCastPlayer`: a media3 `Player` driving the receiver over that channel, so the existing
   player UI, the programme bar and `LivePlayback` work unchanged.

The receiver (`9527437F`, a standard CAF receiver) needs no change: it accepts LOAD, PLAY, PAUSE,
SEEK, STOP and status requests on the standard media namespace from any sender.

## 1. Discovery: `CastScanner`

- On the existing idle rediscovery tick (every 12 s, foreground only, not casting) send one query
  from a fresh IPv4 socket bound to port 0, to `224.0.0.251:5353`, for the PTR records of
  `_googlecast._tcp.local` and `_<appId>._sub._googlecast._tcp.local`. Read answers for 1.5 s, then
  close the socket.
- The socket is bound to the Wi-Fi network (`ConnectivityManager`, `Network.bindSocket`), so the
  query never goes out over mobile data.
- Parse PTR, SRV (port), TXT (`id`, `fn` name, `md` model, `ca` capabilities) and A (address).
  Only devices advertising the receiver's subtype are kept, as with Play Services' filter.
- A device not seen for three rounds (about 40 s) is dropped.
- No multicast lock and no new permission: the answers are unicast.
- The parser is a pure function, unit-tested with the packets captured from jardin.

Picker merge in `CastController`: a device is listed once. The Play Services route wins when it has
the same device (matched by the Cast device id, from the TXT `id` and from `CastDevice.deviceId` in
the route extras; to be checked on hardware, with the name plus address as fallback). Direct
devices get a route id prefixed `direct:` so `connectTo` knows which path to take.

## 2. Protocol: `CastChannel`

Transport:

- TLS to `<address>:8009`. Chromecasts use a self-signed certificate, so the TLS layer cannot verify
  it. Identity is checked by device authentication instead (below), and nothing sensitive is sent
  before it succeeds.
- Messages are a 4-byte big-endian length followed by a `CastMessage` protobuf (protocol version,
  source id, destination id, namespace, payload type, UTF-8 or binary payload). The encoder and
  decoder are written by hand (varints and length-delimited fields), with no protobuf dependency.
- Payloads are JSON (`org.json`, already used), except device authentication, which is binary.
- One reader coroutine on `Dispatchers.IO`; writes are serialized; events are posted to the main
  thread.

Namespaces:

| Namespace | Use |
|---|---|
| `urn:x-cast:com.google.cast.tp.connection` | CONNECT and CLOSE, to the platform (`receiver-0`) and to the app's transport id |
| `urn:x-cast:com.google.cast.tp.heartbeat` | PING every 5 s, answer PONG; three missed answers end the session |
| `urn:x-cast:com.google.cast.receiver` | LAUNCH, GET_STATUS, STOP, SET_VOLUME (level and mute) |
| `urn:x-cast:com.google.cast.media` | LOAD, PLAY, PAUSE, SEEK, STOP, GET_STATUS; MEDIA_STATUS updates |
| `urn:x-cast:com.google.cast.tp.deviceauth` | device authentication challenge and response |

Device authentication:

- Right after the TLS handshake, send an `AuthChallenge` with a random nonce. The device answers
  with its certificate, intermediate certificates and a signature over the nonce and the TLS
  certificate it presented.
- Verify that the chain leads to Google's Cast root certificates (public, embedded in the app,
  taken from Chromium's `components/cast_certificate`) and that the signature matches the TLS
  certificate of this connection. On failure the connection is closed and the device is not used.
- This is what lets the app send the DRM token and, for continue-watching, the access token, without
  trusting whatever answers on the LAN.

Session steps:

1. Connect, authenticate, CONNECT to `receiver-0`, start heartbeats.
2. GET_STATUS. If `9527437F` is already running (another sender), join its transport id;
   otherwise LAUNCH it and wait for the RECEIVER_STATUS that carries the transport id.
3. CONNECT to the transport id, then send LOAD with the same media information and customData as
   today. `FuboCastMediaItemConverter`'s customData code is moved to a pure function used by both
   paths, so the receiver contract has one definition.
4. Follow MEDIA_STATUS (broadcast on changes) and send GET_STATUS every 5 s for the position.
5. Stop: media STOP (the receiver then ends the cast, as with Play Services). Return to the phone:
   read the position, STOP, CLOSE.

## 3. Player: `DirectCastPlayer`

A media3 `SimpleBasePlayer` (available in 1.7.1) backed by the channel:

- State from MEDIA_STATUS: `playerState` (IDLE, BUFFERING, PLAYING, PAUSED) to media3 states and
  `playWhenReady`; `idleReason` ERROR to a player error, which feeds the existing recovery path.
- Position: the last `currentTime`, advanced by the elapsed time times `playbackRate` between
  updates, so the bar moves smoothly.
- Live: a single-window timeline marked live, built from the status' `liveSeekableRange`
  (start and end), so `LivePlayback.behindLiveEdgeMs`, `castRewindOffsetMs`, `liveBarGeometry` and
  `seekToFraction` work as they do with media3's `CastPlayer`. Whether the receiver's player time is
  epoch-based (which would give the timeline a wall-clock anchor and a better programme bar than
  today's cast path) is to be measured; parity with `CastPlayer` is the baseline.
- Commands: `setPlayWhenReady` to PLAY or PAUSE, `seekTo` to SEEK (`currentTime` = window start +
  position), `stop` to media STOP, `setMediaItem` and `prepare` to LOAD.
- Volume and mute through the receiver namespace, for the phone's volume keys.

## 4. Integration

As built (step 4), slightly simpler than first planned:

- The direct path has its own small branches in `CastPlayerController` instead of a `RemoteSession`
  interface over both paths, so the Play Services cast code is left exactly as it was.
- `DirectCastPlayer` is the current player during a direct cast, so the cast bar, the full-screen cast
  controls, the connect animation and the volume overlay behave as on the Play Services path.
- Volume keys: while a direct cast is up they drive the receiver volume (SET_VOLUME). A Chromecast
  reporting `fixed` volume still applies the level in software (checked on jardin).
- Switching device to or from a direct cast goes through the phone: playback comes back to it where
  the TV was ("Cet appareil"), then it is cast to the new device, which picks up from there. A Play
  Services cast ends asynchronously, so the direct connect waits for playback to be back on the phone.
- Recovery: the receiver handles its own errors (it reloads its cached request, reported as IDLE
  INTERRUPTED), so, as on the Play Services path, a stall of 30 s or a reported error makes the phone
  resolve a fresh stream and load it again, within the same retry budget.
- A dropped connection (an I/O error, not a stop on the TV) is rejoined: reconnect to the same device
  and join the receiver still running, at 1, 3 and 6 s, then give up. Returning to the foreground no
  longer clears the state of a direct cast. Back on the root screen only backgrounds the app (Android
  12 and later), so the session survives it; a killed process does not (not a goal).

## 5. Security

- Nothing listens on the phone; all connections are outgoing, on the Wi-Fi network only.
- No token is sent before device authentication has succeeded.
- Input from the network (mDNS packets, Cast messages) is parsed with explicit bounds checks: length
  prefix capped (64 KiB), name compression pointers only backwards with a depth limit, record
  lengths checked against the packet.

## 6. Testing

Unit tests: mDNS parsing (captured jardin packets, plus truncated and looping packets), Cast message
framing (round trip, oversized and short frames), MEDIA_STATUS to player state and live timeline
mapping, customData built identically for both paths.

On the Edge 20 Pro with jardin, on the direct path (Play Services blind) and, after a Wi-Fi toggle,
on the Play Services path:

- discovery and picker; connect animation; live cast; start-over; catch-up; recording
- play, pause, rewind 10 s, forward 30 s, seek bar, back to live, programme bar and times
- volume keys and mute; stop; return to the phone at the right position; switching device
- app to the background and back; receiver stream error recovery; Wi-Fi drop during a cast

## 7. Plan

Each step is reviewed, tested on the device and committed on its own:

1. Discovery and picker: direct devices appear; connecting is not wired yet.
2. Channel, device authentication, launch and LOAD: casting starts on the TV.
3. `DirectCastPlayer`: controls, position and programme bar.
4. `RemoteSession` integration: volume, stop, return to the phone, transfer, recovery, background.
5. README update, and the on-device test pass above.

Estimated size: about 1,200 lines of Kotlin plus tests, mostly in `core:cast`.

## Risks and open points

- Device authentication follows Open Screen's sender (`cast/sender/channel/cast_auth_util.cc`): the
  device signs the nonce followed by its TLS certificate (RSA PKCS#1 v1.5, SHA-256 or SHA-1), its
  chain must lead to the Cast Root CA or the Eureka Root CA, and its TLS certificate may live at
  most 4 days. Checked on jardin (chain: device, Chromecast ICA 21 (ATV), Cast Root CA; TLS 1.3; a
  2-day TLS certificate).
- On a LAUNCH, newer firmware first sends LAUNCH_STATUS and a RECEIVER_STATUS without the app; the
  session waits for the status that lists it. The app's transport id equals its session id.
- Matching a direct device with its Play Services route by id needs checking on hardware.
- The receiver's live time is not epoch-based: `liveSeekableRange` runs from about 0 to the 4 h window
  length and both ends move forward together; playing sits about 1.5 s behind its end. The window's
  wall-clock start is therefore estimated from the phone's clock (the edge being now), which places
  the programme bar to within the stream's few seconds of delay (checked on jardin).
- Receiver statuses only come on changes, and `playbackRate` can read 0 while PLAYING: the player
  takes the speed from `playerState`, extrapolates between statuses and asks for one every 5 s.
- The receiver applies `live_rewind_sec` on PLAYER_LOAD_COMPLETE, before playback starts, and the
  player then joins the live edge, losing it (seen on jardin with the deployed receiver). The direct
  player therefore seeks the TV behind the edge itself once it plays with a known window; the same
  receiver behaviour probably affects a rewound Play Services cast, not checked.
- A Play Services session and a direct session on the same receiver at once (two senders): the last
  LOAD wins, as with two phones today.
