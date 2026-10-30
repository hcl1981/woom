# Woom – a simple Android app for WiiM devices

Prebuilt app: `../Woom.apk` (Android 9 or newer). Build: `./gradlew assembleRelease`, tests: `./gradlew testDebugUnitTest`.

## Usage

- **Gear icon, top right:** add devices (found automatically on the Wi-Fi, otherwise by IP), remove, reorder, change the abbreviation (2 characters), show/hide inputs.
- **Station bar:** up to 5 device buttons. Press and hold, then drag to reorder the station list.
  - Tap a device → plays the station.
  - If the station is already playing on another device → the tapped device plays **in sync** (multiroom group).
  - Tap a device that is currently playing the station (filled button) → only that device goes silent.
    If it is the group leader, another member takes over the station (short pause ~1 s) and the rest join it.
    Leader/member is queried directly from the device for this, not taken from the last polled state.
- **Slider icon in the bar:** name, stream URL, image URL, which device buttons appear, transfer the station as a **favourite (preset)** to one or more devices, order, delete.
- **Add station:** button below the stations. At the top, a search in the free station directory radio-browser.info
  (fills in name, stream URL and logo); missing logos are looked up once per start via the stream URL.
- **Volume & inputs** (tap to expand/collapse, collapsed at start): per device mute (`setPlayerCmd:mute:0|1`), volume slider, inputs to switch between,
  and a settings icon (name, abbreviation, shown inputs – only in Woom, not on the device).
- Names that are too long scroll as marquee text.

- **Device audio** (first bar, can be switched off in the settings): plays the phone's sound on one WiiM over Bluetooth. Android lets apps neither pair nor connect audio nor read the phone's address, so the first time the user adds the WiiM in the Android Bluetooth settings; afterwards the WiiM connects itself. The phone is found by its Bluetooth name in the WiiM's list of known sources.

## Languages

System language automatically (from Android 13 also selectable per app): English (default), German, French, Spanish,
Italian, Dutch, Polish, Portuguese, Japanese, Chinese (simplified). Texts in `res/values*/strings.xml`.

## Tech

Kotlin + Jetpack Compose. Data is stored locally in `woom.json` in the app storage.
Protocol, data model and control logic (`WoomViewModel`) live in `../shared/src` and are also used by the PC version (`../desktop`).
Protocol: see `../docs/`. Playback via PlayQueue `CreateQueue` (with name/logo), fallback `setPlayerCmd:play:<url>`;
group `multiroom:JoinGroup` / `LeaveGroup` / `Ungroup`; inputs `setPlayerCmd:switchmode:<name>`;
presets via `GetKeyMapping`/`SetKeyMapping` (only the chosen slot is replaced). Status polled every 3 s while the app is visible.

## Device test status (as of 2026-10-29)

Verified: device discovery, status, `setPlayerCmd:play`/`stop`, reading and **writing** presets
(`LiveDeviceTest`, writes to an empty slot and restores the list afterwards),
input list via `getAudioInputCapbility` (Ultra: incl. `udisk`; Sound: `auxIn`; Mini does not know the command → fallback to `plm_support`).

Also verified (Ultra, volume 10): playback via `CreateQueue` (device shows name + logo,
`vendor: CustomRadio`, starts after ~1 s), switching input (`switchmode:line-in` → `mode 40`, marked in the app), stop.

Grouping verified (Ultra + Sound): `JoinGroup` → follower `group=1`, `master_uuid` = leader, `getSlaveList` lists it;
`LeaveGroup` and `Ungroup` → `group=0`. The follower always reports `status:"stop", mode:99` in `getPlayerStatus` – even while it
is playing along; the app therefore derives its state from the leader.
A playing station is detected via AVTransport `TrackURI` (stays the stream URL while the title is replaced by programme info;
when stopped it only shows `SONGLIST-NETWORK`).

Live tests: `WOOM_LIVE_IP=<ip> WOOM_LIVE_SLOT=<empty slot> ./gradlew testDebugUnitTest --tests '*LiveDeviceTest*'`
(audible part additionally with `WOOM_LIVE_AUDIBLE=1 WOOM_LIVE_VOLUME=10`).
