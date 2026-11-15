# Remote control of the TV app

The phone drives the etincelle TV app over the local network, the way it drives a Chromecast, with a
protocol of our own (module `core:remote`) so that every part of it is under our control.

## Discovery

While its activity is started, the TV app listens on UDP port 47811. The phone broadcasts
`etincelle-discover\n1` there from an ephemeral port, during each of its scans for Chromecasts, and each
TV app answers directly with `etincelle-tv\n1\n<port>\n<install id>\n<TV name>`. Android's own service
announcer (NSD) was tried first: on Android 14 it only answers by multicast, which some phones stop
receiving, and it wants service names unique across all types, which the TV's own remote service
already takes. A direct answer always arrives, as with the legacy unicast queries used for Chromecasts.
The phone lists each TV app as "<name> (appli TV)", route id `tv:<install id>`.

## Authentication

Both apps derive the same 32-byte key from the account they are signed in to:
SHA-256 of `etincelle-remote-v1`, the user id and the sorted profile ids. Profile ids are random UUIDs
that only a device signed in to the account can read, and they never cross the local network.

Over TCP, frames are a 4-byte big-endian length and UTF-8 text (at most 64 KiB).

1. TV: `etincelle-remote\n1\n<tv nonce>`
2. Phone: `auth\n<phone nonce>\n<HMAC(key, "phone|tvNonce|phoneNonce")>`
3. TV: `welcome\n<HMAC(key, "tv|tvNonce|phoneNonce")>`, or `denied` and it closes.

Each side checks the other's proof, so neither talks to a device of another account. Then every message
is `seq\nmac\npayload`, the mac being HMAC-SHA256 under `HMAC(key, "session|tvNonce|phoneNonce")` over
the direction, the sequence number and the payload: a message can be neither forged nor altered,
replayed or reordered. The TV keeps the latest phone that proves the key.

## Messages

JSON objects with a type `t` (see `RemoteMessages`). The phone sends `play` (kind live, vod or
recording, id, position, rewind behind live), `pause`, `resume`, `seek`, `stop`, `key` (up, down, left,
right, ok, back, dispatched through the TV activity as remote presses) and `volume` (up, down, mute).
The TV sends `status` every second: state, title, position and duration (within the rewind window on a
live stream), live, what plays, the number of the last `play` it handled (each `play` carries one) and,
when nothing plays, the error that stopped it, so the phone can tell a failed request from a stale error.

The TV resolves streams with its own sign-in, so nothing is passed but ids; it shows a programme's page
under the player and plays the next episode itself, so the phone shows no next-episode slate for it.
On the phone, `TvRemotePlayer` turns the statuses into a media3 player, so the cast controller, the
notification and the lock screen drive the TV unchanged, and the cast bar opens a remote screen.
