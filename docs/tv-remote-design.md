# Remote control of the TV app

The phone drives the etincelle TV app over the local network, the way it drives a Chromecast, with a
protocol of our own (module `core:remote`) so that every part of it is under our control.

## Discovery

While its remote control runs (see "Always reachable"), the TV app listens on UDP port 47811. The phone
broadcasts `etincelle-discover\n1` there from an ephemeral port, during each of its scans for Chromecasts
(every 20 s or so while idle in the foreground, and one after the other while the list of devices is
open, a cast running or not), and also
sends it to each address of its subnet (up to a /22), as some TVs receive no broadcast or multicast at
all (seen on a Google TV Streamer). Each TV app answers directly with
`etincelle-tv\n2\n<port>\n<install id>\n<TV name>`, the port being TCP 47811 unless something else
holds it: a phone that listed the TV before its server last started still connects. As its server
starts, the TV app also broadcasts that answer
once, unasked, to port 47811, where the phone listens while it is in the foreground: a TV app opened
meanwhile joins the list at once, even while the phone is connected and so not scanning (a Streamer can
send broadcasts, it only fails to receive them). Android's own service
announcer (NSD) was tried first: on Android 14 it only answers by multicast, which some phones stop
receiving, and it wants service names unique across all types, which the TV's own remote service
already takes. A direct answer always arrives, as with the legacy unicast queries used for Chromecasts.
The phone lists each TV app as "<name> (appli TV)", route id `tv:<install id>`, next to the Chromecasts
as "<name> (Chromecast)": a TV and its Chromecast often share a name.

## Always reachable

The server belongs to the process (`TvRemote`), not to the activity, and `RemoteKeepAliveService`, a
`connectedDevice` foreground service, keeps the process running with nothing in front. Once the app may
display over other apps (`SYSTEM_ALERT_WINDOW`, granted by the user from the TV app's settings), the
service never stops: it starts with the TV (`BOOT_COMPLETED`) and after an update of the app
(`MY_PACKAGE_REPLACED`), both of which may start a foreground service from the background, and is sticky,
so Android starts it again after ending the app to free memory (seen on Android 14; not tried on
Android 15 and later, which are stricter about starting a foreground service from the background, and
where the service would then only come back at the next start of the TV or of the app). A phone then
finds the TV app whatever
the TV shows, and asleep: a Chromecast with Google TV keeps its Wi-Fi and its processes running while
asleep, and answers within half a second.

A play command, a key, or a resume of what it plays, arriving with the activity not in front brings it
back, or opens it when there is none (the play command then waits for the activity to resume, and a
resume plays once it is back in front, not behind what the TV shows meanwhile). The activity is `singleTask`, so the
launcher or a link from the home screen comes to that same one: the server drives a single screen. The activity declares
`android:turnScreenOn`, and sets it again before coming back, which wakes the TV and ends the
screensaver. Android 14 blocks such a start from the background unless the app may display over other
apps (`BAL_ALLOW_SAW_PERMISSION`), hence the permission. Verified on a Chromecast with Google TV: a wake
lock with `ACQUIRE_CAUSES_WAKEUP` needs `TURN_SCREEN_ON`, denied by default with no settings page on
Google TV; a plain activity start is `BAL_BLOCK`; and turn-screen-on set from `onCreate` comes too late to
wake a sleeping TV, it has to be in the manifest.

A TV set woken this way powers on while playback starts. On a Google TV Streamer its HDMI link drops
and comes back about four seconds in, Android broadcasts `ACTION_AUDIO_BECOMING_NOISY`, and the player,
which pauses on it as for unplugged headphones, would stay paused: within 30 s of a play command from
the phone it plays on instead.

With no activity nothing has read the saved sign-in, so the server reads it itself before working out the
account key, which it works out again every five minutes at most, as the profiles it is made of may
change while it runs; sooner after a phone was refused, which is what a phone holding a newer key looks
like, but then no more than every ten seconds. Signing out drops the key and the connected phone at once. The status keeps going to the phone, idle when there is no activity: its read
timeout would otherwise drop a silent TV.

Without the permission the server only runs while the activity is started or behind the screensaver:
`onStop` waits 3 s for `ACTION_DREAMING_STARTED`, and stops the server without it (the app was left) or
on `ACTION_SCREEN_OFF` (the TV went to sleep); an activity that is finishing, closed with Back, does not
wait. A command arriving behind the screensaver is still taken,
and plays once the TV is woken.

## Authentication

Both apps derive the same 32-byte key from the account they are signed in to:
SHA-256 of `etincelle-remote-v1`, the user id and the sorted profile ids. Profile ids are random UUIDs
that only a device signed in to the account can read, and the remote control never sends them. (A cast
does pass them, with the access token, to the Chromecast, over TLS: the key is no more secret than that.)

Over TCP, frames are a 4-byte big-endian length and that many bytes (at most 64 KiB); the handshake's
are UTF-8 text.

1. TV: `etincelle-remote\n2\n<tv nonce>`
2. Phone: `auth\n<phone nonce>\n<HMAC(key, "phone|tvNonce|phoneNonce")>`
3. TV: `welcome\n<HMAC(key, "tv|tvNonce|phoneNonce")>`, or `denied` and it closes.

Each side checks the other's proof, so neither talks to a device of another account. Then every message
is encrypted with AES-256-GCM under `HMAC(key, "session|tvNonce|phoneNonce")`, its 12-byte nonce made of
the direction (1 from the TV, 2 from the phone) and the message's sequence number, which is not sent: a
message can be neither read by others on the network, nor forged, altered, replayed or reordered. The TV
takes one handshake at a time per address (four in all).

One phone drives the TV: the latest to have asked for something. A phone that proves the key waits
beside it, in the place of the one that waited there before, hears the status like the driving one, and
takes its place with its first command, not by connecting. A phone app reconnects to the TV by itself
when it is reopened (see below), and would otherwise take the TV from the phone someone is driving it
with, whose cast bar and notification would go while the TV played on. Each of the two has its own
sending thread, so that one that stopped reading while keeping its connection does not hold up the
status of the other.

This is version 2 of the protocol. Version 1 (2026.11.0 to 2026.11.4) sent `seq\nmac\npayload` in the
clear, authenticated but readable on the network. The version is in the discovery answer and in the
greeting: a phone and a TV app of different versions do not list each other.

## Messages

JSON objects with a type `t` (see `RemoteMessages`). The phone sends `play` (kind live, vod or
recording, id, position, rewind behind live), `pause`, `resume`, `seek`, `stop`, `key` (up, down, left,
right, ok, dispatched through the TV activity as remote presses; back, handed to the back navigation,
which a Back key event no longer reaches on Android 16) and `volume` (up, down, mute).
The TV works its `status` out every second but only sends it when it tells the phone something new
(`StatusPacer`): another state, title or programme, a position that is not where playback would have
taken it (a seek), or what came of a command of the phone, even nothing. Otherwise one goes every ten
seconds, and the phone takes a TV silent for thirty as gone. Each status received keeps the phone from
sleeping, which is why they are spared: once a second, Android ended up restricting the phone app for
its battery use. They go one at a time (none is queued while the last has not left, and one that could
not leave is sent again at the next look): state
(buffering, not idle, while what it plays is loading or being brought back after an error), title, position and duration (within the rewind window on a
live stream), live, what plays, the number of the last `play` it handled (each `play` carries one) and
the error it ended on, if any, so the phone can tell a failed request from a stale error. The error is
given whether something else plays or not: a request that fails over a programme still playing leaves
that programme on.

The TV resolves streams with its own sign-in, so nothing is passed but ids; it shows a programme's page
under the player and plays the next episode itself, so the phone shows no next-episode slate for it.
A recording the phone plays from the page of its episode is asked for as that page: the TV finds the
recording there as the phone did, and with it the episode that follows, which the recording alone does
not name. Its status then names the recording, which the phone takes for what it asked for.
On the phone, `TvRemotePlayer` turns the statuses into a media3 player, working out where playback has
got to between two of them, so the cast controller, the notification and the lock screen drive the TV
unchanged, and the cast bar opens a remote screen.

The link costs the phone battery for as long as it is up, and the TV app may run for days. So when the
phone app leaves the screen, the link is only kept, past the five seconds that what was tapped just
before leaving may take to reach the TV, while the TV plays what the phone started (what it
sent, or what the TV went on to from there, such as the next episode), which the notification then
drives. Otherwise the phone closes it, also later when that playback stops, or has been paused or ended
for ten minutes (a TV switched off pauses what it plays, and nothing closes a programme that has ended),
and connects to the same TV again by itself when the app comes back, without a message should the TV be
gone by then. The remote
screen stays up across that reconnection.
