# Fubo backend API (as used by Molotov 5.51) — validated contract

Reverse-engineered and **validated live** against `api-eu.fubo.tv` from an EU vantage point with the
test account, plus a decrypted on-device capture of the real Molotov 5.51 app. This is the contract
etincelle's `:data:fubo` layer targets.

> Account/geo notes: the account must be entitled (e.g. France 2/3/4/5 are available on the test
> account) and the **request egress IP must geolocate to an authorized country (FR)** — the content
> market follows the IP. `network_allowed` (from `/v3/location`) only gates VPN/proxy, not the market.

## Hosts

- `api-eu.fubo.tv` — **auth + content (page API)** for the EU region. (US: `api.fubo.tv`.)
- `images-eu.fubo.tv` — channel logos (`/channel-config-ui/station-logos/on-dark/france-2_black-fra-*.png`), page-manager art.
- `molotov-imgx.fubo.tv` — Molotov content artwork.
- DRM license servers: `irdeto.fubo.tv/licenseServer/widevine/v1/FuboTV/license`, `api.fubo.tv/v1/drm/getkey?scheme=widevine`.
- `services-api-eu.fubo.tv` does **not** resolve — ignore it (a dead constant in the apk).

## Required headers (THE key one is `x-application-id: molotov`)

Without `x-application-id: molotov` the backend doesn't treat the client as Molotov and returns
`NO_SERVICE_FOR_SUBSCRIPTION` / an empty page. Full set the app sends:

```
user-agent: MolotovTV/5.51.0 (Linux; U; ANDROID; fr-FR; <brand> Model/<model> OS/16)
x-application-id: molotov            # REQUIRED — Molotov tenant
x-client-version: 5.51.0
x-os: android        x-os-version: 16
x-device-app: android
x-device-platform: android_phone     # not just "ANDROID"
x-device-type: phone    x-device-group: mobile
x-device-brand/-model/-name: ...
x-preferred-language: fr-FR
x-is-user-request: true
x-supported-streaming-protocols: hls,dash
x-supported-features: ...,use_drm_v2_response,load_channels_in_guide,playback_template_v2,...
authorization: Bearer <access_token>     # on authed calls
x-profile-id: <profileId>                # on authed calls (note: x-user-id NOT sent on page calls)
x-device-id, x-ad-id, x-session-id, x-timezone-offset, accept-encoding: gzip
```
Geo/market is derived server-side from the egress IP (no `x-client-country` needed).

## Auth (host `api-eu.fubo.tv`)

- `PUT /v2/signin`  body `{"username","password"}` → `{type, payload:{...tokens}}` (envelope). The app uses this.
- `PUT /signin`     body `{"email","password"}`   → flat `{access_token,id_token,refresh_token,token_type,expires_in}` (also works; `expires_in`=36000s).
- `POST /refresh`   (Bearer refresh token) → new tokens.
- `GET /user`       → `{data:{id, countryCode, language, email, profiles:[{id,name,avatar,dob}], type, ...}}`.
- `GET /profile`    → profile detail.
- `GET /v3/location`→ `{network_allowed(bool), country_code, region_code, dma, postal, as_name, ...}`.
- `POST /logout`.
- Retrofit verbs (from smali, validated): `/signin`,`/logout` = **PUT**; `/refresh` = POST; `/user` = GET.

## Content — server-driven "page API" (papi)

Everything is a server-rendered page of sections/components; the client renders generically and
follows each component's `actions`.

- `GET /papi/v1/page/home` → page:
  ```
  { title:{text,type}, actions:{on_expired,on_poll}, id:"home", public_path:"/home",
    expires, next_polling,
    content:{ template:"catalog", sections:[ {type:"carousel", component_type:"card-wide|card-poster|square|...",
                                              title, context, components:[ <card> ], slug, group_id, size } ] } }
  ```
- Other pages: `GET /papi/v1/page/{slug}` — `channels`, `live-tv`, `films`, `series`, `sport`,
  `culture`, `divertissement`, `documentaires`, `enfants`, `informations`, `abonnements`,
  and grids `/papi/v1/page/home/grid/{id}`.
- Detail: `GET /papi/v1/program-details/channel/{channelId}` (template `program-details-single`,
  has `cast_url`, tabs, metadata{station_logo,title,...}), `GET /papi/v1/program-details/series/{id}`,
  `GET /papi/v1/broadcaster-details/{id}`.
- **Show detail (validated 2026-06-14):** a show card's `on_click` is `GET /papi/v1/program-details/program/{id}`.
  Fetch `?tabID=id-tab-about` to get, in one call, `content.metadata` (type `program-details-metadata`:
  `title`, `subtitle` ["2h 12m • 2004" = duration • year, or "S8 E24 …"], `description` [synopsis],
  `artwork`, `station_logo`, `tags` [DIRECT/REPLAY/VOD], `ctas`) **and** the "À propos" tab's `about`
  component (`description` = "Réalisé par … avec …" director+cast; `about_fields` = Genre / Année de
  sortie / Classification). Tabs are "À voir aussi" (carousels) + "À propos"; `cast_url` just re-fetches
  the same page with `transient=true`. The description sometimes already ends with the credits — strip
  that tail so the cast is not shown twice. `metadata.artwork.url` is a generic
  `molotov-imgx.fubo.tv/arts/up/default-backdrop` placeholder (a 200 but content-less image) when the
  title has no real poster — treat any `/arts/up/default-` URL as no artwork.
- Components/cards carry `actions` (`on_click`/`on_expired`/`on_poll`) → `{endpoint:{method,url}, type, template, ...}`.
  Action types seen: `navigation`, `refresh_page`, `tracking`. Card types: `card-wide`, `card-poster`,
  `square`, `chip-navigation`, `tab`, `picture`, `progress_bar`, `tag`, `text`, `program-details-metadata`.
- Live channel IDs (FRA): France 2 = **600019**, France 4 = 600018, France 5 = 600042, Arte = 600034,
  BFMTV = 600035, CNEWS = 600002, LCP = 600008, CSTAR = 600038. (France 3 ≈ another 6000xx.)

## Live guide (EPG) — VALIDATED live

`GET /epg` on `api-eu.fubo.tv` (NOT the dead `services-api-eu.fubo.tv`):

- Params: `startTime`, `endTime` (**RFC3339 UTC**, e.g. `2026-06-13T19:10:00Z` — epoch/ms are
  rejected with "Timestamp is not in RFC3339 UTC format"), `limit` (number of **channels**),
  `ignoreEmpty=true`. Authorization + the standard `x-application-id: molotov` set are required.
- Response:
  ```
  { response:[ { type:"channelWithProgramAssets",
                 data:{ channel:{ id, name, displayName, logoOnDarkUrl, logoOnWhiteUrl, callSign, videoQuality },
                        network:{...},
                        programsWithAssets:[ { program:{ programId, title, heading, subheading,
                                                         horizontalImage, verticalImage, rating, ... },
                                              assets:[ { assetId:"LIVE_xx',  type:"stream", playbackDurationSec,
                                                         channel:{ id, ... },
                                                         accessRights:{ startTime, endTime, ... },  # airing window, RFC3339 UTC
                                                         accessRightsV2:{...} } ] } ] } } ],
    metadata:[ { type:"paginationState", data:{ totalResults, currentOffset, nextOffset } } ] }
  ```
- `data.channel.id` is the live channel id (e.g. France 2 = `600019`) → feeds the live VAPI call, so
  a guide programme tap resolves to live playback. Airing times live on `assets[].accessRights`.
  ~102 channels total; page with `limit`/`nextOffset`.

## Playback — VALIDATED end-to-end (captured live, France 2)

Tapping a free live channel triggers the playback (VAPI) call on `api-eu.fubo.tv`:

- **LIVE:** `GET /vapi/asset/v1?channelId={channelId}&type=live&wants_trackers=true`
- **VOD/replay:** `GET /vapi/asset/v1?id={VOD_xxxxx}&type=vod&wants_trackers=true`
- Extra headers on this call: `x-drm-scheme: widevine`, `x-user-id`, and the `use_drm_v2_response`
  feature (so the response includes `drm_v2`). Pre-play, the app also re-checks `GET /v3/location`.

Response (`200`, ~13 KB), key fields:
```
stream: { url (tokenized DASH .mpd, e.g. prod-ssai-ftv-up.akamaized.net/.../france2/...hdreadyshft.mpd
                with ?aws.sessionId & ?hdnts= Akamai token), packagingProtocol:"dash",
          drmProtected:true, drmProvider:"drmtoday", live:true, manifestUnloadSec, scrubOffset,
          allowedPauseSec, unskippableAds }
drm_v2: { scheme:"widevine", license:{ url:"https://lic.drmtoday.com/license-proxy-widevine/cenc/?specConform=true",
          headers:{ "Content-Type":"application/octet-stream", "x-dt-auth-token":"<JWT>" }, auth:false, persist:false } }
drm   : { provider:"drmtoday", scheme:"widevine", licenseUrl(same), licenseUrlHeaders, token, drmContentId }  # v1 fallback
heartbeat:    { url:"https://api-eu.fubo.tv/vapi/heartbeat/v2?channelId={id}&id=LIVE_xxxxx&type=live" }
concurrency:  { heartbeatUrl:"https://api-eu.fubo.tv/heartbeat?player_session_id=@" }
accessRightsV2:{ live:{startTime,endTime,scrubbingRights}, startover, lookback, dvr, vod }
liveURL, startoverURL, nextProgramURL, csaiUrl(ads), thumbnails, timeshifting, tracking, npaw, channel, program
```

**DRM = Widevine via DRMtoday** (Irdeto's DRMtoday — NOT the `irdeto.fubo.tv`/`getkey` the static smali
suggested). The token rides the **`x-dt-auth-token`** request header. This maps directly onto Media3:
```kotlin
MediaItem.Builder().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_MPD)
  .setDrmConfiguration(DrmConfiguration.Builder(C.WIDEVINE_UUID)
     .setLicenseUri(drm_v2.license.url)
     .setLicenseRequestHeaders(drm_v2.license.headers)   // incl. x-dt-auth-token + Content-Type
     .build()).build()
```
Keep-alive: the `heartbeat`/`concurrency` POSTs are **NOT required to keep a stream alive** —
measured live, the France 2 manifest kept returning `200` for 3+ minutes with zero heartbeats
(`manifestUnloadSec:300` is just the live-manifest reload cadence, which Media3 handles natively;
`allowedPauseSec:7200`). The heartbeat (`/vapi/heartbeat/v2`, body `{progress, pdt, passback}`) and
concurrency POSTs exist for analytics, server-side watch progress (continue-watching), and
multi-device concurrency counting — none of which kill playback. So the app omits them in v1.

**Free vs paid:** France 2/3/4/5 etc. play directly. Channels like TF1 are **locked behind the paid
"Molotov Extra" add-on** — their detail page shows upsell CTAs and `locked` markers instead of a player.

## Recordings (DVR) — endpoint known, gated by a DVR entitlement

Recordings are **not** a `papi` page (no `my-stuff`/`recordings`/`mes-videos` slug exists — those all
return `400 dynamic path not found`). They come from the knowledge-graph DVR service:

- `GET /dvr/v2/list?sort={startTime|date}&status={all|recorded|recording|scheduled}&limit=&offset=`
  on `api-eu.fubo.tv`, with the `Authorization: Bearer` header (and the standard `x-application-id:
  molotov` set). `sort`/`status` are **required** — omitting them returns `400 invalid DVR query`.

The shared test account has the Molotov Extra add-on but **no DVR quota**, so every valid request
returns `400 {"error":{"message":"dvr service failed: invalid DVR quota entitlement"}}`.

**Validated with recordings (2026-06-14):** `GET /dvr/v2/list?sort=date&status={recorded|scheduled|recording}`
returns `200 { response:[ <recording> ], metadata:[ {type:"paginationState", data:{totalResults,
currentOffset, nextOffset}} ] }`. WARNING: `status=all` returns an **empty** `response` (a backend quirk);
query the specific statuses (`recorded`, `scheduled`, `recording`) and merge. Each recording is a
**`programWithAssets`** — the SAME shape as the EPG: `data.program{programId ("contentId_variant"), heading
(show name), subheading (S/E), shortDescription, horizontalImage/verticalImage, rating, type:"episode",
metadata{episodeNumber, seasonNumber, seriesId}}` + `data.assets[]{assetId:"LIVE_xxxxx", type:"dvr",
channel{id,name,logoOnDarkUrl}, playbackDurationSec, accessRights{startTime,endTime}}`. So a recordings list
reuses the EPG DTOs; the playable is the `type:"dvr"` asset (its exact vapi playback type is still TBD).

**Scheduling a recording** comes from a live/upcoming programme's `program-details` CTAs (full account
only; the entitlement-less account gets the `papi/v1/page/abonnements-options-enregistrement` upsell):
- Episode: `POST /action/v1/add-recording` — body `{action_name:"add-recording", params:{asset_id:"LIVE_xxxxx",
  is_upcoming:"<bool>"}, metadatas:{"asset.asset_id":"LIVE_xxxxx"}, callback_actions:[<refresh the detail>]}`.
- Series: `POST /action/v1/record-new-episodes` (same shape). Labels "Enregistrer l'épisode"/"Enregistrer la série".
- The record target is a **`LIVE_…` airing asset**, so recording applies to live/upcoming programmes, not VOD.

## Decrypted-capture pipeline (reusable)

The app has **no certificate pinning** and no network-security-config (trusts only system CAs). To
capture decrypted traffic on the non-rooted device:
1. Generate a mitmproxy CA (`mitmdump` first run → `~/.mitmproxy/mitmproxy-ca-cert.pem`).
2. Repackage `tv.molotov.app`: decode base.apk resources-only (`apktool d -s`), add
   `res/xml/network_security_config.xml` (trust-anchors incl. `@raw/<mitm CA>`) + the
   `android:networkSecurityConfig` manifest attr, rebuild (apktool 3.x; install the device's
   `framework-res.apk` so API 36 manifest attrs resolve; remove the `<uri-relative-filter-group>`),
   then re-sign base + all splits with one debug key and `adb install-multiple`.
3. Run `mitmdump -s capture.py --set block_global=false` on the **FR-egress** host; set the device's
   `settings put global http_proxy <host>:8080`. The bundled CA makes the app trust mitmproxy.
