# etincelle

[![License: BSD-2-Clause](https://img.shields.io/badge/License-BSD%202--Clause-blue.svg)](LICENSE)
![Platform: Android 16](https://img.shields.io/badge/platform-Android%2016%20(API%2036)-3DDC84?logo=android&logoColor=white)
![minSdk 23](https://img.shields.io/badge/minSdk-23-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-%2B%20TV-4285F4?logo=jetpackcompose&logoColor=white)

Native Android application (targeting **Android 16 / API 36**) to watch **Molotov** through the
**Fubo** backend that the current Molotov app (5.51) uses, wearing the look of the older Molotov
**4.27** UI. It runs on both **phone/tablet** and **Android TV** and plays content on-device with
**Widevine DRM** via AndroidX Media3.

> Unofficial project, not affiliated with Molotov or Fubo. A paid account with the **Molotov
> Extra** add-on on Fubo is required, and the backend is geo-restricted to the EU.

## Screenshots

Phone (Jetpack Compose, Material 3):

<p>
  <img src="docs/screenshots/phone-home.png" width="240" alt="Phone — home with live channels and rails">
  <img src="docs/screenshots/phone-guide.png" width="240" alt="Phone — live guide (EPG)">
  <img src="docs/screenshots/phone-search.png" width="240" alt="Phone — search results">
</p>

Android TV (Compose for TV, D-pad):

<img src="docs/screenshots/tv-browse.png" width="640" alt="Android TV — browse with scrolling top tabs and channel rails">

## Status

Working phone app. It **signs in to the Fubo (Molotov) backend, renders the home page** (the
server-driven `papi` catalog of carousels: live channels with lock badges for paid ones, plus
"Que voulez-vous regarder ?", "En direct à la TV", etc.) in the Molotov-4.27 dark style, and
**plays a tapped free channel on-device** with Widevine DRM (via DRMtoday) through AndroidX
Media3 — verified end-to-end on an Android 16 device. It is **browsable**: bottom-nav tabs
(Accueil / Direct / Films / Séries) and a back stack, so any non-channel card navigates into its
own `papi` page (categories, "Voir tout", program/series detail) rendered with the same rails.
It plays **VOD and replays** too (movies and series episodes, via the `type=vod` path) and
**resumes them where you left off** (the playback position is remembered locally per title) and has a
**Search** tab (`/papi/v1/search`) whose results render as rails. A **Guide** tab renders the live
EPG (`/epg`): one rail per channel showing its current and upcoming programmes with start times, and
tapping a programme watches that channel live. An **Android TV** app (Compose
for TV: D-pad-navigable rows, focus highlight, a scrolling top-tab bar with the same
Accueil / Direct / Guide / Films / Séries / Recherche tabs) shares the same data/player layer. The
full backend contract is in [`docs/fubo-api.md`](docs/fubo-api.md). The **session persists** across
launches (DataStore, with the access/refresh tokens **encrypted at rest** via AES-GCM and a
hardware-backed Android Keystore key), the access token **auto-refreshes** on a 401, and transient
network blips — including the backend's intermittent `5xx`/`404` hiccups on a GET — are retried once,
with failures surfaced as plain French messages rather than raw HTTP codes; a **logout** control on
both apps clears the session and returns to the sign-in screen.
Recordings (DVR) are deferred: the endpoint
is mapped (`/dvr/v2/list`, see the API doc), but the test account carries no DVR entitlement, so a
recordings UI can only be built and verified against an account that has one.

## Installation

Download the signed APK for your device from the
[latest release](https://github.com/renaudallard/etincelle/releases/latest):

- **Phone / tablet** — `etincelle-<version>-mobile.apk`
- **Android TV** — `etincelle-<version>-tv.apk`

Both run on Android 6.0+ (`minSdk 23`) and are self-signed, so Android asks you to allow installing
from an unknown source. Signing in needs a paid **Molotov Extra** account on Fubo and an EU
connection.

**Phone / tablet**

1. Download `etincelle-<version>-mobile.apk` on the device, or install over USB with
   `adb install etincelle-<version>-mobile.apk`.
2. Approve "Install unknown apps" for your browser/file manager if prompted.
3. Open **etincelle** and sign in.

**Android TV** (no browser, so sideload it)

- Over adb: `adb connect <tv-ip>` then `adb install etincelle-<version>-tv.apk`, or
- copy the APK to a USB stick, or push it with a sideload helper (e.g. *Send files to TV*,
  *Downloader*), and open it.

The phone and TV builds are separate packages (`it.allard.etincelle` and `it.allard.etincelle.tv`),
so you can install both side by side. To build the APKs yourself instead, see
[Building](#building) below.

## Project layout

```
etincelle/
├── app-mobile/             phone/tablet app (Compose, Material3, Media3)
├── app-tv/                 Android TV app (Compose for TV, D-pad browse + play)
├── core/
│   ├── designsystem/       Molotov-4.27 dark theme tokens + shared Compose components
│   ├── model/              pure-Kotlin domain types (PlaybackSource, DrmSpec, UserSession)
│   ├── domain/             the MolotovRepository interface (backend abstraction)
│   ├── network/            OkHttp/Retrofit/Moshi + Fubo interceptors + session + TokenStore
│   ├── player/             Media3 mapping: DrmSpec -> Widevine MediaItem
│   └── ui/                 shared MainViewModel/UiState (presentation logic for both apps)
├── data/
│   └── fubo/               FuboRepository (implements MolotovRepository) + DTOs/mappers + DI
└── gradle/libs.versions.toml   version catalog
```

Both apps render the same `papi` catalog and play the same streams; only the Compose UI layer
differs (Material3 + bottom nav on phone, Compose-for-TV rows + D-pad focus on TV). The presentation
layer (`core:ui`) depends only on the `core:domain` `MolotovRepository` interface, so the backend
stays swappable. Unit tests cover the interceptors, the DTO→domain/DRM mappers, and the token
refresh. DI is a small manual `AppContainer` (Hilt would be over-engineering for a one-ViewModel,
one-backend app; it can be added if the graph grows).

## Building

Requirements on the build host:

- Android SDK with `platforms;android-36` and `build-tools;36.0.0`
  (`sdkmanager "platforms;android-36" "build-tools;36.0.0"`).
- **JDK 21** for Gradle (AGP 8.x does not support JDK 25). The Gradle daemon JDK is pinned in
  `gradle.properties` via `org.gradle.java.home`; adjust the path for your machine.
- The Gradle wrapper (Gradle 8.x) is committed; use `./gradlew`, not a system Gradle.

```bash
./gradlew :app-mobile:assembleDebug      # phone/tablet debug APK
./gradlew :app-tv:assembleDebug          # Android TV debug APK
```

Set the SDK location in `local.properties` (`sdk.dir=/path/to/Android/Sdk`).

## Toolchain

Kotlin 2.4, AGP 8.12, Gradle 8.x, Jetpack Compose (BOM 2026.05), Compose for TV (`androidx.tv`),
AndroidX Media3 (added in the playback milestone). `compileSdk`/`targetSdk` = 36, `minSdk` = 23.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
