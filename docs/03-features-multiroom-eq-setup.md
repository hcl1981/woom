# WiiM Home 3.6.11: multiroom, EQ/audio, capabilities, presets/cloud, OOBE

This report is based on analysis of the official WiiM Home 3.6.11 app.
- Paths that start with `assets/` refer to files bundled with the official app.
- "httpapi" means `GET http(s)://<IP>/httpapi.asp?command=<cmd>`.
  - HTTPS is used when getStatusEx `security` is not `http`.
  - Port 4443 is used when `gc4aVer` is present. See §3.6 for the client certificate.
- A few commands are sent as a POST to `/httpapi.asp` with the form body `command=<cmd>`.


## 0. Quick summary for a local-only controller

- **Transport.** Use httpapi on the device, plus three UPnP services: AVTransport, RenderingControl and `urn:schemas-wiimu-com:service:PlayQueue:1`.
  - HTTPS devices may need the bundled client certificate (§3.6).
  - Everything below is LAN-only. The cloud is only needed for OTA checks, telemetry, catalogue browsing and the room-correction upload.
- **Multiroom.**
  - Join: send `multiroom:JoinGroup:IP=<master>:uuid=<master uuid>` **to the slave**.
  - Leave: `multiroom:LeaveGroup` (to the slave) or `multiroom:SlaveKickout:<ip>` (to the master).
  - Dissolve: `multiroom:Ungroup` (to the master).
  - Read state from getStatusEx `group`/`master_uuid` and the master's `multiroom:getSlaveList`.
  - Group volume is client-side: show the max of the members, apply the same delta to each member.
- **EQ.**
  - Per-source EQ v2 over `EQGetLV2SourceBandEx`, `EQChangeSourceFX` (on), `EQSourceOff` (off) and `EQSetLV2SourceBand`.
  - Presets via `EQv2GetNewList`, `EQv2SourceLoad` and `EQSourceSave`.
  - LV2 pluginURIs: `…/caps/Eq10HP`, `Eq4p`, `EqNp`.
  - PEQ params are `a_freq`, `a_gain`, `a_q`, `a_mode` … with mode -1/0/1/2/3/5.
- **Capabilities.** Use the getStatusEx `project`, `capability` (bit 3 = volume limit, bit 19 = `plm_support` valid), `plm_support` (input bits), `EQVersion`, `wmrm_*`, and `getFeatureCapbility` / `GetAcousticCapability` on new firmware.
- **Presets.**
  - Read and write with PlayQueue `GetKeyMapping` / `SetKeyMapping` (XML).
  - Play with `MCUKeyShortClick:<1-based n>`.
  - Push playback through PlayQueue `CreateQueue` (+ `PlayQueueWithIndex`). The app never uses `setPlayerCmd:play:<url>`. This app does not show whether the firmware still accepts it.
- **OOBE.**
  - BLE: GATT service `01000100-4C69-6E6B-506C-617947415454`, characteristic `02000100-4C69-6E6B-506C-617943484152`, framing `"PL" + cmd + len + JSON`, password sent as plain hex.
  - SoftAP: `wlanConnectApEx` on 10.10.10.254.

---

## 1. Multiroom (MRM)

### 1.1 Group state in `getStatusEx`

| key | meaning |
|---|---|
| `group` | int. **0 = standalone or master, >0 (1) = slave.** The app refuses to join a device to a target master whose `group > 0` ("target can not be Master"). |
| `master_uuid` | On a slave, the master's getStatusEx `uuid` (24-hex form, see 1.6). |
| `GroupName` | Group name string. |
| `group_type` | Group kind. Also present in the getSlaveList reply. 1 = normal ad-hoc group, 2 = stereo pair, 3 = home-theater/surround (see 1.5). |
| `group_prefer` | int, rarely used. |
| `sub_master_uuid` | For WiiM Sub: the uuid of the main unit it is paired to. |
| `wmrm_version`, `wmrm_sub_ver` | MRM protocol version. "4.2" or higher is the current WiiM protocol; "4.0" and "2.0" are older. Mixing 4.2+ with pre-4.2 devices in one group is refused by the app (error toast). |
| `wmrm_capability` | decimal int. Bit 2: Mini wireless-sub support. Bit 5: home-theater / stereo / center / surround setup. Bit 6: "connection check" step. |
| `volume_control` | **1 = fixed volume** (line-out fixed). The device is excluded from group volume and can't be muted from the group panel. |
| `max_volume` | Volume limit (0-100). |
| `eth2`, `apcli0` | Wired and Wi-Fi-client IPs. Used in the legacy `ConnectMasterAp` command. |
| `WifiChannel`, `ssid`, `wifiMode` | Used by the legacy Wi-Fi-direct join. |
| `delay_main_sub` | Main/sub delay string. |
| `hardware` | **`A31`/`A28` = legacy Wi-Fi-direct MRM. Anything else ("A98" class) = router-based MRM.** All WiiM devices are "A98". |

App-side model: the app marks each device as master or slave, and on a slave it also remembers the master's uuid. It matches a device as a slave of master M when either:
- the slave's getStatusEx `master_uuid` matches M's uuid, or
- the master uuid remembered for the slave matches it.

The comparison is a fuzzy substring match after normalising.

### 1.2 Slave list: `multiroom:getSlaveList` (sent to the master)

```json
{"slaves":2, "wmrm_version":"4.2", "group_type":1,
 "slave_list":[{"name":"Kitchen","ssid":"WiiM Mini-XXXX","uuid":"FF31F0...", "ip":"192.168.1.23",
                "mask":0, "volume":35, "mute":0, "channel":0, "version":"4.6...", "battery":..,
                "project":"Muzo_Mini", "group_channel":..}]}
```

- `mask` 0 → "unmask".
- `channel`: 0 = stereo, 1 = left, 2 = right (and see 1.5).
- `"wmrm_version"` appears in the body on WiiM firmware. The app's ungroup poller relies on it.

### 1.3 Forming and dissolving groups

**Join, router mode (WiiM / A98 master).** Send this **to the slave**:

```
multiroom:JoinGroup:IP=<masterIP>:uuid=<master getStatusEx uuid>
```

The app then polls the **slave's** `getStatusEx` every 1 s (timeout 10 s) until `group > 0 && master_uuid == <master uuid>`. A31 slave + A98 master → error "A31 can not join to A98".

**Join, legacy Wi-Fi-direct mode (A31/A28 master).**
1. The app reads the master's `getNetwork`. The reply contains `securemode` ("0" = open, "1" = WPA), `psk` and `softap_enable`.
2. It sends this to the **slave**:

```
ConnectMasterAp:ssid=<HEX(UTF-8 master ssid), upper>:ch=<master WifiChannel>:auth=WPA2PSK|OPEN:encry=AES|NONE:pwd=<psk upper>:chext=0:JoinGroupMaster:eth<master eth2>:wifi<master apcli0>:uuid<master uuid>
```

- The `ssid=…:` part is only added when `softap_enable == "0"`.
- `auth=OPEN` and `encry=NONE` are used when the password is empty.
- Note the missing `:`/`=` after `eth`, `wifi` and `uuid`. That is literal.
- The slave joins the master's own hotspot (Wi-Fi-direct). A WiiM-only controller can ignore this path.

**Leave / kick.**
- A98 slave: send `multiroom:LeaveGroup` **to the slave**. Success is confirmed when the slave's `getStatusEx` shows `group == 0`.
- A31: send `multiroom:SlaveKickout:<slaveIP>` **to the master**.

**Ungroup all.** Send `multiroom:Ungroup` to the master. The app then polls `multiroom:getSlaveList` up to 7 × 5 s until the body contains `wmrm_version` and `slave_list` is empty. After that it resets each member locally to standalone master (`group=0`, no remembered master).

**Batch operations.** The app picks a 4.2+ path or a 4.0/2.0 path based on `wmrm_version`. Both diff the selected list against the current slaves, then run a join task per new device and an exit task per removed device.

### 1.4 Volume, mute, channel and name of individual members

When the slave is WiiM ("A98"), the app **talks UPnP directly to the slave**. Otherwise it sends httpapi to the master, naming the slave's IP:

| function | A98 (direct, slave's own UPnP) | legacy (httpapi to master) |
|---|---|---|
| slave volume | RenderingControl `SetVolume(InstanceID=0, Channel="Single", DesiredVolume=v, ControlSource="WiiMApp")`. **Note the non-standard `Channel="Single"` and the extra `ControlSource` argument.** | `multiroom:SlaveVolume:<slaveIP>:<0-100>` |
| slave channel | RenderingControl `SetChannel(InstanceID=0, Channel="Master", DesiredChannel=<n>)` (custom action) | `multiroom:SlaveChannel:<slaveIP>:<n>` |
| slave mute | RenderingControl `SetMute` on the slave | — |
| slave name | httpapi `setDeviceName:<urlenc>` on the slave, or `setHexDeviceName:<HEX>` if the name contains `#` | `multiroom:SlaveDeviceName:<slaveIP>:<urlenc name>` |

This split applies only when the phone is **not** connected to a LinkPlay hotspot. The hotspot is recognised by BSSID OUI `00:22:6c`, `00:25:92` or `02:22:6c`. In hotspot mode the app skips the httpapi-to-master path and uses UPnP on everything.

**Group volume logic:**
- **Displayed group volume = max(volume of non-fixed, non-muted members).** If every member is fixed-volume, the master's volume is shown.
- **Changing group volume applies the same delta to each member.** The new value is `memberVol + delta`, clamped to 0..100. The master is set via its own UPnP `SetVolume`. Slaves go through the per-member slave-volume path above. Members with `volume_control == 1`, or that are muted while the group isn't all muted, are skipped.
- The app rescales the step so that members already at 0 or 100 don't absorb it.
- The firmware has no single "group volume" command. **A controller must fan out per-member volume itself.**
- Playback commands go only to the master. The slaves follow automatically.

### 1.5 Persistent "named groups", stereo pair and surround (WiiM `wmrm_version` ≥ 4.2)

| command | notes |
|---|---|
| `multiroom:getnamegrouplist` | Returns a named-group JSON (shape below). |
| `multiroom:savenamegrouplist:<json>` | Sent as a **POST** body `command=multiroom:savenamegrouplist:{…}`. |
| `multiroom:setcurrentnamegroup:<urlenc json>` | `{"group_id":"<id>","slavelist":[{"ipv4":"192.168.1.x","uuid":"<24hex>"}]}`. Activates a saved group, so the device forms it itself. `{"group_id":"-1"}` disables it. |
| `multiroom:NameGroupPingTest:<urlenc {"duration":10}>`, `multiroom:GetNameGroupPingTestResult`, `multiroom:GetNameGroupPingSuggestion` | Network-quality "connection check" (`wmrm_capability` bit 6). |

Named-group JSON:

```json
{"updater":"…","current_group_id":"<UUID-UPPER>",
 "grouplist":[{"group_name":"Living room","group_id":"<UUID-UPPER>","group_type":1,"update_time":"<unix s>",
               "channel":0,
               "slavelist":[{"name":"…","uuid":"<uuid>","ipv4":"192.168.1.x","channel":1}]}]}
```

- `group_type`: 1 = temporary/normal group, 2 = stereo pair, 3 = home theater.
- **Channel codes** (master `channel` and slave `channel`):

| code | meaning |
|---|---|
| 0 | full/stereo |
| 1 | left |
| 2 | right |
| 3 | center |
| 6 | surround-left |
| 7 | surround-right |
| 10 | one device as surround L+R |

  Stereo lead/slave use 1 or 2 depending on which speaker the user picked as left.

**Group audio-delay and buffering:**
- `multiroom:get_source_cache_time` / `multiroom:set_source_cache_time:{"cache_time":[{"<source e.g. HDMI>":<ms>}]}`. Surround setup sets `{"HDMI":70}`.
- `multiroom:ConfigGet:realtime_cache_limit` / `multiroom:ConfigSet:realtime_cache_limit:<n>`.
- `multiroom:ConfigGet:leadtime` / `multiroom:ConfigSet:leadtime:<n>`.
- `GetSyncPlayExtraDelay` / `SetSyncPlayExtraDelay:<n>`, and `getMainSubExtraDelay` (main/sub sync).
- `setDigitalAudioMultiChnlOutput:<n>` (surround configuration).

**Wireless subwoofer pairing:**
- `multiroom:subwooferGetPairInfo` → `{"subwoofer":[{"online":1,"name":"…","uuid":"…","type":"…","key":"…"}]}`
- `multiroom:subwooferPair:<urlenc {"name","uuid","type","ip"}>`
- `multiroom:subwooferForget:<urlenc {"uuid": "<24hex>"}>`

**Other.** `audio_cast:*` (`get_speaker_list`, `scan_speaker`, `speaker_enable:%s`, `speaker_set_volume:%s:%d`, `speaker_stereo_group_enable:%s`, `speaker_set_transcode_profile:%s` …) manages "Audio Cast" output to third-party speakers.

### 1.6 UUID forms

- getStatusEx `uuid` is 24 hex chars, for example `FF31F09E1A5A6B5D2D8D5A3C`.
- The UPnP UDN is `uuid:XXXXXXXX-XXXX-XXXX-XXXX-XXXXYYYYYYYY`. It is formed from chars 0-8, 8-12, 12-16, 16-20 and 20-24 of the 24-hex form, followed by **the first 8 chars repeated**.
- Converting back strips `uuid:` and the dashes, then takes the first 24 chars.
- Use the 24-hex form in all `multiroom:` JSON.

---

## 2. EQ, PEQ and audio features

### 2.1 EQ plug-ins and versions

The EQ engine is LV2-style. Plug-ins are selected by `pluginURI`:

| pluginURI | use |
|---|---|
| `http://moddevices.com/plugins/caps/Eq10HP` | 10-band graphic EQ (GEQ) |
| `http://moddevices.com/plugins/caps/Eq4p` | old 4-band PEQ |
| `http://moddevices.com/plugins/caps/EqNp` | N-band PEQ (used when `EQVersion` ≥ 3.0.99) |

The app uses EqNp if `EQVersion ≥ 3.0.99`, otherwise Eq4p. The old PEQ UI caps bands at 4.

`EQVersion` is from getStatusEx and defaults to "2.0". Feature gates:

| EQVersion | gate |
|---|---|
| ≥ 2.0 | mixed GEQ/PEQ UI; otherwise the legacy EQ UI |
| ≥ 3.0.99 | EqNp |
| ≥ 3.0.101 | `EQv2SourceLoad` instead of `EQv2Load` |
| ≥ 4.3 | `EQv2GetNewList` with `{"pluginURI":…}` JSON, **L/R split channel EQ** (`EQBandL`/`EQBandR`, `channelMode:"L/R"`) |

HALLAUDIO with `EQ_support=="Eq10HP_ver_1.0"` → GEQ-only.

**`GetAcousticCapability`** (httpapi) returns:

```json
{"Version":"…","EQBlock":{"Version":"…","Blocks":[{"id":0,"type":"…"}]},"GEQ":{"Version":"…"},
 "PEQ":{"Version":"…","Filters":["LS","PK","HS","LP","HP"]},
 "RC":{"Version":"1.1","LevelMatch":{"Version":"…"},"TimeAlign":{"Version":"…"}},
 "SubLPF":{"Version":"…"},"DynamicBass":{"Version":"…","Min":0,"Max":10,"Step":1},
 "EQChannel":{"Version":"…"},"EQHeadroom":{"Version":"…"},"Evaluation":{"Version":"…"},
 "HeadphoneEQ":{"Version":"…"},"MainProtect":{"Version":"…"},
 "OutputDelay":{"Version":"…","EnableMicroDelay":true,"PerOutputDelay":true,"MinDelayUs":0,"MaxDelayUs":…,"StepDelayUs":…},
 "SoundMode":{"Version":"…","DefaultMode":"…","Modes":["…"]},
 "VoiceLevel":{"Version":"…","DefaultLevel":"…","Levels":["…"]}}
```

How the app uses it:
- PEQ `LP`/`HP` filter types are offered only if `PEQ.Filters` contains them.
- Room-correction "DynamicEQ" needs `RC.Version ≥ 1.1`.
- `EnableMicroDelay` controls the output-delay UI.
- Sub level-match needs `RC.LevelMatch`.

### 2.2 EQ scopes: `EQLevel` and `source_name`

- `"EQLevel":1` = **per-input-source EQ**. Each input has its own EQ.
- `"EQLevel":2` = **output-level EQ**. It is used from the Audio Output page, and, on devices that support it, for room-correction files.
- In the EQLevel 2 payloads the app sends `source_name:""`.

`source_name` values (the mode number is getPlayerStatus `mode`):

| source_name | mode | source_name | mode |
|---|---|---|---|
| `wifi` | -1 / 9999 | `XLR` | 9 |
| `line-in` | 1 | `HDMI` | 10 |
| `bluetooth` | 2 | `CD` | 11 |
| `external_usb` | 3 | `extern_TFcard` | 13 |
| `optical` | 4 | `PCUSB` | 15 |
| `RCA` | 5 | `phono` | 16 |
| `co-axial` | 6 | `DAB` | 17 |
| `FM` | 7 | `optical2` | 18 |
| `line-in2` | 8 | `co-axial2` | 19 |
| | | `ARC` | 22 |
| | | `auxIn` | 10001 |

### 2.3 EQ commands (httpapi)

`<P>` stands for the pluginURI and `<S>` for the source_name.

| command | payload | purpose |
|---|---|---|
| `EQGetLV2SourceBandEx:{"EQLevel":1,"source_name":"<S>","pluginURI":"<P>"}` | — | **Read current EQ state and bands** for a source. `EQGetLV2SourceBand:` is the older form. |
| `EQChangeSourceFX:{"EQLevel":1,"source_name":"<S>","pluginURI":"<P>"}` | — | **Enable EQ** / switch the active plug-in (GEQ↔PEQ) for that source. Success = JSON `"status":"OK"`. A body of `unknown command` means not supported. |
| `EQSourceOff:{"EQLevel":1,"source_name":"<S>","pluginURI":"<P>"}` | — | **Disable EQ** for that source. |
| `EQSetLV2SourceBand:{…}` | see below | **Set band parameters** (live). |
| `EQv2GetList:<P>` (EQVersion < 4.3), `EQv2GetNewList:{"pluginURI":"<P>"}` or `{"EQLevel":2,"source_name":"<S>","pluginURI":"<P>"}` (≥ 4.3) | — | Preset and custom list. |
| `EQv2SourceLoad:{"EQLevel":1,"source_name":"<S>","pluginURI":"<P>","Name":"<preset>"}` | — | Load a named preset or custom EQ. Before 3.0.101 it was `EQv2Load:{"pluginURI":"<P>","Name":"<preset>"}`. |
| `EQSourceSave:{"EQLevel":2,"source_name":"","channelMode":"Stereo\|L/R","pluginURI":"<P>","Name":"<name>","UpdateAt":"<ms>"}` | — | Save the current bands as a custom preset. Legacy form: `EQSave:<name>`. |
| `EQv2Rename:{"EQLevel":2,"pluginURI":"<P>","Name":"old","newName":"new","UpdateAt":"<ms>"}` | — | Rename. |
| `EQv2Delete:{"EQLevel":2,"pluginURI":"<P>","Name":"<name>"}` | — | Delete. |
| `EQMoveMode:%s`, `EQMoveRCModes:{"EQLevel":1,"newLevel":2,"pluginURI":"…EqNp"}` | — | Migrate saved modes or room-correction profiles between levels. |
| `EQGetModeParam:{"EQLevel":2,"pluginURI":"…EqNp","Name":"<name>"}` | — | Read a saved profile's bands without loading it. |
| `EQSetChannelMode:<json>` | — | Stereo vs L/R editing mode. |
| `EQGetLV2Band:<P>`, `EQSetLV2Band:{"pluginURI":"<P>","EQBand":[…]}` | — | Non-source (global) variants. |
| Legacy (pre-EQv2) | — | `EQGet`, `EQOn`/`EQOff`, `EQEnable`/`EQDisable`, `EQGetList`, `EQLoad:<name>`, `EQGetBand`, `EQSetBand:<json>`, `EQSave:<name>`, `EQChangeFX:<json>`. |

There is no `EQGetStat` command. EQ on/off state is the `"EQStat":"On"|"Off"` field in the `EQGetLV2SourceBand(Ex)` reply; the app tests `EQStat == "On"`.

**Band payload for `EQSetLV2SourceBand`.** `EQBand` is a list of `{"param_name":..,"value":..}`:

```json
{"EQLevel":1,"source_name":"wifi","pluginURI":"http://moddevices.com/plugins/caps/EqNp",
 "channelMode":"Stereo",
 "EQBand":[{"param_name":"a_mode","value":1},{"param_name":"a_freq","value":125.0},
           {"param_name":"a_q","value":0.7},{"param_name":"a_gain","value":-3.0}, …]}
```

- For **L/R** (EQVersion ≥ 4.3): `"channelMode":"L/R","EQBandL":[…],"EQBandR":[…]`.
- Room correction "multi" adds `"EQBandC"` (centre) (§2.5).
- **PEQ parameter names** are `<letter>_freq|_gain|_q|_mode`. The letter is the band index: `a_`=band 0, `b_`, `c_` … `j_` for 10 bands. The output EQ goes up to at least `n_`.
- **Filter mode values:**

| value | filter |
|---|---|
| -1 | `noop` / OFF |
| 0 | `lowshelf12` (LS) |
| 1 | `peaking12` (PK) |
| 2 | `highshelf12` (HS) |
| 3 | `lowpass12` (LP) |
| 5 | `highpass12` (HP) |

- Default 10-band PEQ: PK at 30, 62.5, 125, 250, 500, 1k, 2k, 4k, 8k and 16k Hz. Frequency is clamped to ≤ 22000.
- **GEQ (Eq10HP)** uses the same `{"param_name","value"}` list. The param names come from the device's own `EQGetLV2SourceBandEx` reply, and the app echoes them back.

**Band-info reply:**
```json
{"status":"OK","source_name":"wifi","pluginURI":"…","Name":"Flat","EQStat":"On","channelMode":"Stereo",
 "EQBand":[{"index":0,"param_name":"a_freq","value":30.0},…],"EQBandL":[…],"EQBandR":[…]}
```

**List reply** (`EQv2GetList` / `EQv2GetNewList`):
```json
{"preset":[{"Name":"Flat","Type":"…","channelMode":"Stereo","UpdateAt":"…","rc_output":"…"}],
 "custom":[{"Name":"My EQ","Type":"RC|…", …}]}
```
Entries with `Type:"RC"` are room-correction profiles. Before 4.3, entries may be bare strings.

### 2.4 `peqweb` asset (`assets/peqweb/`, about 590 KB)

`peqweb` is a static **Next.js export that only draws the PEQ frequency-response graph**. It does no networking. The official app loads it in a WebView from `https://appassets.androidplatform.net/assets/peqweb/index.html` (WebViewAssetLoader).

JS bridge:
- **Native → JS**:
  - `javascript:setEqData('<json>')`. `<json>` is a list of band objects: `[{"id":0,"name_start":"a_","type":"peaking12","frequency":125.0,"gain":-3.0,"Q":0.7,"bypass":false},…]`.
  - `javascript:setSelectedIndex(<n>)`. `-999` = show all bands.
- **JS → native**: interface `window.android_peq` with `@JavascriptInterface` methods:
  - `webInitComplete()`
  - `eqChanged(String json)`: the same `[{type,frequency,gain,Q}]` shape, sent when the user drags a node.
  - `selectedIndexChange(String n)`
- On iOS the same names go through `window.webkit.messageHandlers.{webInitComplete,eqChanged}`.
- Accepted types: `lowshelf12`, `highshelf12`, `peaking12`, `lowpass12`, `highpass12`, `noop`.
- A desktop controller can reuse it as-is, or reimplement the biquad response curve.

`assets/csv/*.csv` (BK_Target, Harman_target, Music_target, Zero_target, sub_pro_target, target_auto) are room-correction **target curves**. Each has 696 rows of `frequency,raw` (dB) from 20 Hz upward.

`assets/surround/data.json` is a **Lottie animation** (v5.12.1, plus `images/img_0.png`) used by the stereo and surround setup screens. It contains no DSP data.

### 2.5 Room correction

**Measurement and filter design run on the phone**, in the app's native library `libwiimundk`:
- The phone mic records while the device plays `room_correction_sweep-3.wav` or `refpink_-3dB.wav`.
- Raw data is uploaded to S3 through `/autoEQ/getS3Key|getErrorS3Key|getCheckS3Key|getAppS3Key`. This upload is diagnostics only and isn't needed to apply EQ.

Commands sent to the device:

| command | JSON |
|---|---|
| `RoomCorrSet:` (stereo) | `{"EQLevel":2,"pluginURI":"<P>","EQBand":[…],"UpdateAt":"<ms>","rc_output":"<output>"}` |
| `RoomCorrSetLR:` | same with `"EQBandL"` and `"EQBandR"` |
| `RoomCorrSet:` (3-channel) | `"channelMode":"multi"` with `EQBandL`/`EQBandR`/`EQBandC` |
| `RoomCorrSetMode:` | `{"Mode":"<DynamicEQ code>"}` |
| `getRoomCorrection` / `setRoomCorrection:` | `{"delay":"0.000"}` |
| `EQGetRCSubCal` / `EQSetRCSubCal:` | `{"delayMs":"0.000","levelDb":"0.000","Time":<ms>}`. Sub level/delay calibration. |

### 2.6 Subwoofer (built-in sub-out and WiiM Sub)

**Local sub-out (`setSubLPF`).** `setSubLPF:<key>:<value>`. The keys are:

| key | example |
|---|---|
| `status:` | `setSubLPF:status:1` (on/off) |
| `cross:` | `setSubLPF:cross:80` (crossover Hz) |
| `phase:` | `setSubLPF:phase:0` or `180` |
| `level:` | `setSubLPF:level:-3` (dB, from slider − offset) |
| `sub_delay:` | `setSubLPF:sub_delay:<ms>` |
| `main_filter:` | `setSubLPF:main_filter:0` or `1` (high-pass the mains) |
| `sub_filter:` | `setSubLPF:sub_filter:0` or `1` |
| `default` | `setSubLPF:default` (reset) |

`getSubLPF` returns:
```json
{"status":1,"cross":80,"phase":0,"level":0,"delay":0.0,"sub_delay":0.0,"main_filter":1,"sub_filter":1,"plugged":1,"output_mode":0,"mix_sub":0,"delay_main_sub":""}
```

**Wireless WiiM Sub (MRM sub).** Sent to the main unit:
- `getMRMSubLPF:{"uuid":"<sub 24hex>"}` → `{"version":"…","devList":[{"uuid":"…","type":"…","SubLPF":{…same fields…}}]}`
- `setMRMSubLPF:{"uuid":"<sub>","SubLPF":{"cross":80}}`. Use one key per call. `{"uuid":"…","SubLPF":"default"}` resets. A `cross` change omits `uuid`.

Pairing commands are in §1.5.

### 2.7 Other audio settings (httpapi)

| feature | command(s) | notes |
|---|---|---|
| Balance | `getChannelBalance` → e.g. `"0.0"`; `setChannelBalance:<-1.0…1.0>` | The UI slider is ±100, divided by 100. |
| Audio channel mode | `getChannelMode`; `setChannelMode:<n>` | 0 = Stereo/Off, 1 = Left, 2 = Right, 3 = Mono, 4 = Reversal (L/R swap). |
| Fixed volume / line-out level | `setVolumeControl:<0\|1>` | 1 = fixed, 0 = variable. getStatusEx `volume_control`. |
| Volume limit | `setMaxVolume:<0-100>` | getStatusEx `max_volume`. Supported if capability bit 3 or `FEATURE_VOLUME_LIMIT`. |
| Crossfade / fade effects | `GetFadeFeature`, `SetFadeFeature: <0\|1>` (literal space after the colon) | |
| Bass / treble (legacy tone) | `EQSet:Bass:<n>`, `EQSet:Treble:<n>`; read with `EQGet` | |
| Dynamic bass | `EQGetDynamicBass`, `EQSetDynamicBass:{"on":1, "level":<n>}` | The range is in the `GetAcousticCapability` reply, `DynamicBass` Min/Max/Step. |
| Headroom | `EQGetHeadroom`, `EQSetHeadroom:{"Mode":"Auto"\|"Off"}` | |
| Sound mode (Bar) | `EQGetSoundMode`, `EQSetSoundMode:<mode>` | The list is in the `GetAcousticCapability` reply, `SoundMode.Modes`. |
| Dolby / DAP | `get_soundeffect:dap`, `set_soundeffect:dap:<mode lower>`, `EQGetVoiceLevel:{}`, `EQSetVoiceLevel:{"Level":"<l>"}` | |
| DTS Virtual:X | `get_virtualx_enable`, `set_virtualx_enable:<0\|1>` | |
| Surround and height channel gains (Bar) | `EQGetChannels:{"profile":"<mode>","channels":[5,6]}` (height uses `[3,4]`); `EQSetChannels:{"profile":"<mode>","channels":[{"ch":5,"gain_db":x},{"ch":6,"gain_db":x}]}` | |
| Per-source pre-gain | `getPlayModeGainConfig`, `setPlayModeGainConfig: <json list>`; `getPlayModeVolumeEnable` / `setPlayModeVolumeEnable:`, `getPlayModeVolumeValue` / `setPlayModeVolumeValue:` (per-source volume memory) | |
| Audio output hardware | `getNewAudioOutputHardwareMode`, `setAudioOutputHardwareMode:<n>` (WiiM non-Mini) or `GNOTIFY=KeyEvent:<n>` (Mini) | |
| Output voltage (Vrms) | `getAudioOutputVrmsSupportList:{"ver":"1.1"}`, `getOutputVoltage`, `setOutputVoltage:<index>` | |
| DAC digital filter | `getDigitalFilterTypeSupportList`/`getDigitalFilterType`/`setDigitalFilterType:<id>`; newer `getOutputDigitalFilterTypeSupportList`/`setOutputDigitalFilterType:<json>` | |
| Max-capability (output resolution) | `<prefix>SpdifOutMaxCap`, `CoaxOutMaxCap`, `UacOutMaxCap`, `LineOutMaxCap`, `HeadphoneOutMaxCap`, `SpeakerOutMaxCap`, `AuxOutMaxCap`, `AuxInMaxCap`/`LineInMaxCap` | |
| MQA | `getMQAReceiverCap`, `setMQAReceiverCap:<n>` | |
| SPDIF | `getSpdifOutSwitchDelayMs`/`setSpdifOutSwitchDelayMs:<ms>`; `getSpdifInNoiseRemove`/`setSpdifInNoiseRemove:<n>` | |
| Input auto-sense | `getAutoSenseEnable`/`setAutoSenseEnable:`, `setSpdifAutoSenseEnable:%d`, `setHDMIAutoSenseEnable:%d`, `setAuxAutoSenseEnable:%d`, `setInputmodeAutoSense:[{"setAutosenseMode":"<mode>","autosenseEnable":0\|1}]` | |
| Inputs on/off | `getAudioInputEnable`; `setAudioInputEnable:{"ver": "1.0","audioInput":[{"mode":"…","enable":1}]}` | |
| Phono | `PHONO_MODE_SWITCH_GET` / `PHONO_MODE_SWITCH_SET:<n>` | |
| Soft mute | `getSoftMute` / `setSoftMute:<n>` | |
| Volume step | `get_button_volume_step`/`set_button_volume_step:`, `get_cec_volume_step`/`set_cec_volume_step:`, `get_remote_volume_step`/`set_remote_volume_step:` | |
| Loudness | **None.** The only "loudness" setting is Spotify's normalisation option in the Spotify settings page. | |

Most `set*` commands reply with the plain body `OK` (the app expects "OK" or "Failed").


---

# Capability detection and presets/cloud

Paths that start with `assets/` refer to files bundled with the official app.

---

## 3. Device capability detection

The app combines four mechanisms:

1. **Project string** (`getStatusEx.project`) matched against hard-coded checks and the bundled JSON assets. This is the main mechanism.
2. **Hex bitmasks** in `getStatusEx`: `capability`, `cap1`, `plm_support`, `languages`, and the decimal `wmrm_capability`.
3. **Version strings** in `getStatusEx`: `EQVersion`, `wmrm_version`/`wmrm_sub_ver`, `ota_api_ver`, `HiFiSRC_version`, `deviceSettingVer`, `audioInputModeVer`, `audioOutputModeVer`, and others.
4. **On-device capability queries** on newer firmware: `getFeatureCapbility`, `getAudioInputCapbility`, `getAudioOutputCapbility`, `GetAcousticCapability`, and the UPnP `StreamServicesCapability`.

Nothing in this area comes from the cloud. Both JSON configs ship only as app assets: `device_config_info.json` and `device_settings_config.json`. A third asset, `project_config.json`, holds only FAQ/legal URLs and can be ignored.

### 3.1 getStatusEx parsing

Hex fields are parsed by stripping a leading `0x`, parsing base 16, and masking with `0x7FFFFFFF`. On a parse error the app uses `0x7ffffffe`.

| JSON key | Parse / notes |
|---|---|
| `capability` | hex |
| `cap1` | hex |
| `plm_support` | hex |
| `streams` | hex. Parsed but no bit test exists in the app. |
| `streams_all` | hex. The app also keeps it as a reversed binary string. Unused elsewhere. |
| `external` | hex. Read but never tested. |
| `languages` | hex. Voice-prompt language mask, see below. |
| `preset_key` | int, see section 4.1 |
| `wmrm_capability` | **getInt (decimal)** |
| `wmrm_version`, `wmrm_sub_ver` | strings; multiroom / home-theater gating |
| `EQ_support`, `EQVersion` | strings; EQ UI gating (`EQVersion` defaults to "2.0") |
| `HiFiSRC_version` | string; SPDIF output resolution UI (≥ "1.0"; fixed resolution ≥ "1.1") |
| `security`, `security_version`, `security_capabilities.aes_ver` | → "http" / "https" / "https 2.0" / "https 3.0"; selects transport and client certificate, see 3.6 |
| `gc4aVer` | presence marks a Google Cast device. The httpapi port becomes 4443, `FW_Release_version` overrides `firmware`, and `Release` is reformatted |
| `max_volume`, `volume_control`, `audio_channel_config`, `group`, `master_uuid`, `group_type`, `upnp_version`, `uart_pass_port`, `communication_port`, `ota_api_ver`, `mqtt_support`, `privacy_mode`, `BleRemote*`, `ModuleColorNumber` (device colour → image), `passthrough_support_ver`, `ble_active`, `LightApiVersion`, `service_version`, `new_tunein_preset_and_alarm`, `tidal_version`, `alexa_ver` | plain copies |

The app also keeps the full raw JSON and reads some keys from it directly, with numeric version comparison where a minimum version is required. Keys read this way: `deviceSettingVer`, `audioInputModeVer`, `audioOutputModeVer`, `audiocast`, `udisk*`, `files_db`, `umount`.

### 3.2 Bit tests

```
capability bit i
cap1 bit i
languages bit i
plm_support bit i   -> ONLY IF capability bit 19 is set and plm_support >= 0
wmrm_capability     -> generic (x >> i) & 1
volume limit        -> capability bit 3
audio-format masks  -> LPCM=0 AC3=1 EAC3=2 DTS=3 DTSCD=4 DTSHD=5 TRUEHD=6 PURE_MLP=7 DTSUHD=10
```

#### Decoded bits (only the bits the app actually tests)

**`capability`**

| bit | meaning |
|---|---|
| 3 | **Volume limit (max volume)** supported. Shows the "volume limit" row. On new firmware the same row is driven by `FEATURE_VOLUME_LIMIT`. |
| 19 | **`plm_support` is valid.** All `plm_support` tests are gated on this bit. |

**`plm_support`** (playback-mode / input-source mask, requires capability bit 19). The app builds its source menu from it.

| bit | switchmode / mode name |
|---|---|
| 1 | line-in (aux) |
| 2 | bluetooth |
| 3 | USB (external_usb / PCUSB / USB DAC) |
| 4 | optical |
| 5 | RCA |
| 6 | co-axial |
| 7 | FM radio |
| 8 | second line-in |
| 9 | XLR |
| 10 | HDMI (ARC) |
| 11 | CD |
| 13 | TF/SD card (also requires `tfMode`) |
| 16 | phono (WiiM Ultra adds it unconditionally) |
| 21 | not an input: **digital (SPDIF) output resolution / "bit-perfect" setting.** The menu needs `HiFiSRC_version` ≥ 1.0 as well as this bit. |

Overrides: `Wireless_Box_E` offers only Cloud + BT, and `Uni_Box_S3` offers Cloud + BT + line-in. USB-disk playback is shown only if `usbMode` is set.

The app's own source-name → mode-index map matches the `mode` numbers in getPlayerStatus: line-in 1, bluetooth 2, USB 3, optical 4, RCA 5, co-axial 6, FM 7, line-in2 8, XLR 9, HDMI 10, CD 11, TF 13, phono 16, DAB 17, second optical 18, second co-axial 19, ARC 22, songlist-local 9999.

**`cap1`**

| bit | meaning |
|---|---|
| 17 | **Bluetooth source/transmitter (BT server) present.** Before a factory reset the app clears the BT discovery result and stops the BT server (server id 8). |

**`languages`** (voice-prompt languages, offered as a selection list)

| bit | 1 | 2 | 3 | 5 | 6 | 7 | 8 | 12 |
|---|---|---|---|---|---|---|---|---|
| language | Chinese | English | German | Spanish | French | Portuguese | Italian | Japanese |

**`wmrm_capability`** (decimal int)

| bit | meaning |
|---|---|
| 2 | Wireless-subwoofer pairing (WiiM Mini path). Only used when the device is a Mini. |
| 5 | **Home-theater pairing** (stereo pair, center, surround, wireless sub setup). |
| 6 | Network "connection check" step before finishing surround setup. |

Related version gates:
- Subwoofer pairing requires `wmrm_sub_ver ≥ 7`, or bit 2 on a Mini. The SUB itself is always handled. When this gate passes, the app calls `multiroom:subwooferGetPairInfo`.
- Multiroom feature checks: `wmrm_version ≥ 4.3` is combined with `wmrm_sub_ver ≥ 2`, `≥ 5`, or `≥ 6` depending on the feature. `group_type` 2 or 3 means a home-theater group.

### 3.3 Project / pid mapping

- `assets/device_config_info.json` contains `{"device_info":[{project:[...], pid:[...], name, images:[{color, color_name, audio_output_info, chromecast_info, ble_info, remote_info, multiroom_info}]}]}`. It provides UI images and names only.
  - Lookup first tries exact match on `project` or `pid`, then a substring match on project. Image colour comes from `ModuleColorNumber`.
  - Full pid table:

    | pid | project | pid | project |
    |---|---|---|---|
    | 143 | WiiM_Pro_with_gc4a, WiiM_Pro_CN | 88 | WiiM_Pro_No_MFI |
    | 208 | WiiM_Pro_Plus(_CN) | 87 | WiiM_Pro_Plus_No_MFI |
    | 220 | WiiM_Ultra(_CN/_Demo) | 216 | WiiM_AMP, WiiM_AMP_Castlite |
    | 86 | WiiM_AMP_No_MFI | 227 | WiiM_Amp_4layer(_CN) |
    | 223 | WiiM_Amp_Pro(_CN) | 232 | WiiM_Amp_Ultra(_CN/_Demo) |
    | 89 | WiiM_CI_AMP | 221 | WiiM_CI_MOD_S |
    | 222 | WiiM_CI_MOD_A80 | 45 | Muzo_Mini (WiiM Mini) |
    | 233 | WiiM_SUB / WiiM_Sub_Pro | 239 | Vibelink_Amp |
    | 240 | WiiM_Sound(_CN/_Demo) | 247, 254 | WiiM_Sound_Lite, WiiM_SoundLite_Demo |
    | 200 | WiiM_Sound_Lite_V2/_CN | 245, 253 | WiiM_Bar(_CN/_Demo) |
    | 106 | WiiM_Light | 203 | _Monolith |
    | 219 | _Mini | 225 / 226 | AudioPro_A10MKII_WiiM / AudioPro_C10MKII_WiiM |
    | 243/244/241/235/236/237/234/238/242 | AudioPro_A10MkII_W / C10MkII_W / A15_W / A28_W / A38_W / A48_W / C5MkII_W / C20_W / D-2_W | 228 / 229 / 230 | Stream_Box_E / Wireless_Box_E / Uni_Box_S3 |
    | (none) | HALLAUDIO_Gen1 | | |

- **Hard-coded product-family checks** (about 55). Each accepts either a project substring or the pid string. Examples:
  - WiiM Ultra → contains "WiiM_Ultra" or "220"
  - WiiM Amp Pro → "WiiM_Amp_Pro" or "223"
  - Amp family = Amp, Castlite, CI MOD A80, Amp Pro, Amp Ultra, or CI AMP
  - "WiiM, not Mini" = Pro family, Amp family, Sound, Bar
  - "MUZO home" = any WiiM/AudioPro-WiiM/StreamBox device
  - Chromecast support = "WiiM, not Mini" or AudioPro-WiiM
  - China variant = project ends with "_CN"
  - Wi-Fi chip fault devices = MAC prefix FF970016 / FF98F09C / FF98FCDE

  Separate checks exist for legacy (non-WiiM) AudioPro products.
- **Settings visibility** comes from `assets/device_settings_config.json` → `{"supportSettings":[{type, supportProduct[], unSupportProduct[], group, name, icon}], "settingsGroups":[...]}`:
  - If `supportProduct` is non-empty, it is a whitelist matched by **exact project string** (list contains). Otherwise the setting is supported unless `project ∈ unSupportProduct`.
  - A slave device, unless it is a MUZO-home project, shows only `deviceInfo` and `syncAudio`.
  - When `deviceSettingVer ≥ 1.0`, these JSON entries are first forced to unsupported and then re-enabled from the device's `getFeatureCapbility` list: `fixedVolume`, `outLevel`, `volumeLimit`, `AudioChannel`, `VolumeBalance`, `fadeEffects`, `statusLight`, `touchControls`, `overrideVolumeButtons`, `volumeLight`, `knobVolumeStep`. Each maps to the corresponding `FEATURE_*` name (§3.4).
  - Setting types: rename, deviceInfo, alarm, preset, networkStatus, standbyMode, Remote, screenSettings (Ultra/Amp Ultra/Sound/Bar only), EQ, roomCorrection, dynamicBass, highPassFilter, audioSettings, GroupAudioDelay, audioOutput, audioInput, subwoofer, wirelessSub, addSubwoofer, syncAudio, surround_audio / height_audio (Bar only), alexaSettings, chromecastAudio, squeezelite, hiPlay, qPlay3, enableTheater, centerSetup, surroundSetup, stereoPairSetup, moreStreamingServices, trigger, statusLight, knobVolumeStep, PromptSound, touchControls, volumeLight, overrideVolumeButtons, reboot.

### 3.4 On-device capability commands (newer firmware)

All of these go over httpapi. The `get*Capbility` calls are sent only if `deviceSettingVer ≥ 1.0`.

| command | response shape |
|---|---|
| `getFeatureCapbility` | `{"ver":"…","feature":[{"name":"FEATURE_VOLUME_LIMIT"},…]}`. Known names: FEATURE_FIXED_VOLUME, _AUX_OUT_LEVEL, _LINE_OUT_LEVEL, _VOLUME_LIMIT, _AUDIO_CHANNEL, _VOLUME_BALANCE, _FADE_EFFECTS, _STATUS_LIGHT, _TOUCH_CONTROLS, _OVERRIDE_VOLUME_BUTTONS, _VOLUME_INDICATOR, _SUBWOOFER, _BUTTON_VOLUME_STEP, _CEC_VOLUME_STEP. |
| `getAudioInputCapbility` | `{"ver":"…","audioInput":[{"index":N,"mode":"<name>"}]}` |
| `getAudioOutputCapbility` | `{"ver":"…","audioOutput":[{"index":N,"mode":"<name>"}]}` |
| `getAudioInputEnable` | `{"ver":"1.0","audioInput":[{"mode":"…","enable":0/1}]}` |
| `setAudioInputEnable:{"ver": "1.0","audioInput":[{"mode":"…","enable":1},…]}` | — (setter) |

Other version gates:

- **EQ.** The UI choice depends on `EQVersion`:
  - ≥ 2.0 → mixed EQ UI; otherwise the legacy EQ UI.
  - `EQ_support=="Eq10HP_ver_1.0"` on HALLAUDIO → graphic EQ.
  - ≥ 3.0, 3.0.99 (NewPEQ), 3.0.101, 3.1, 4.1, 4.2, 4.3 unlock progressively more features.

  The `GetAcousticCapability` reply adds its own version keys: `RC.version` ≥ 1.1, `RC.LevelMatch`, `RC.TimeAlign`, `Evaluation`, `MainProtect`, `EQBlock`, `PEQ.filters` contains "LP"/"HP", and `OutputDelay.enableMicroDelay`. Those details belong to the EQ section.
- **OTA**: `ota_api_ver ≥ 3.0` → `getMvRemoteUpdateDeviceOtaInfo`; ≥ 4.0 → new check-for-update flow.
- **Audio I/O UI versions**: `audioOutputModeVer` (1.3, …) and `audioInputModeVer` (1.0 / 1.1 / 1.3) choose the newer output/input UI.
- **Streaming services**: UPnP `RenderingControl#StreamServicesCapability(InstanceID=0, AppVersion="1.0")` returns JSON `{"version", "encrypt", "streamServices":[{id, active, configurable, removable, brand, model, hw, version, quality[], audioSurround[], replayGain}]}`.
  - Service ids: tunein, newTuneIn, iheartaudio, deezer, Deezer2, Qobuz, QobuzConnect, TidalConnect, SpotifyConnect, LinkplaySpotify, prime, Plex, Roon, SoundCloud, soundmachine, CalmRadio, RadioParadise2, Pandora2, KKBOX, HotMix, HiPlay, Qplay3, AudioCast, AVS_MRM, Samba, Squeezelite, Soundtrack.
  - `version` here is the "service version". **If it is < 1.2, `CreateQueue` does not auto-play** and the app follows it with `PlayQueueWithIndex`.
  - Enable or disable a service with httpapi `SetStreamServicesCapability:Active:<id>` or `SetStreamServicesCapability:Deactive:<id>`.

### 3.5 Recommended detection strategy for an alternative controller
1. Call `getStatusEx`. Store the raw JSON. Read `project`, `capability`, `cap1`, `plm_support`, `EQVersion`, `wmrm_*`, `security`, `gc4aVer`, `preset_key`, `HiFiSRC_version`, `deviceSettingVer`, `audioInputModeVer`, and `audioOutputModeVer`.
2. Inputs: if `deviceSettingVer` is set, prefer `getAudioInputCapbility`. Otherwise use the `plm_support` bits (gated by capability bit 19).
3. Volume, limit, balance, fade, and LED features: if `deviceSettingVer ≥ 1.0`, use `getFeatureCapbility`. Otherwise use `device_settings_config.json` keyed by project.
4. Copy `device_config_info.json` only if you want product names and images.

### 3.6 Transport note (affects every local command)

- `security=="http"` (or missing) → `http://IP/httpapi.asp?command=`.
- Otherwise → `https://IP/httpapi.asp?command=`, or `https://IP:4443/…` when `gc4aVer` is present. The app's hostname verifier accepts everything.
- TLS client authentication uses a bundled PKCS12 client certificate:
  - `security=="https"` → `assets/certificate_old`
  - "https 2.0" / "https 3.0" → `assets/certificate_new`
  - The keystore password comes from the app's native library.
- Firmware that requires HTTPS may therefore expect this client certificate. Extracting the password requires the native `.so`. That is outside this section's scope.

---

## 4. Presets and source/streaming handoff

### 4.1 Preset count
- `preset_key` comes from getStatusEx. 0 or missing means 6.
- For legacy AudioPro projects it is overridden to 4, 5, or 6.
- The app then always increments `preset_key` internally. The UI shows `preset_key - 1` slots, i.e. the device's original value.
- The keymapping XML declares `<MaxNumber>33</MaxNumber>`.

### 4.2 Reading and writing presets (UPnP only; the app never uses httpapi `getPresetInfo`)

Service `urn:schemas-wiimu-com:service:PlayQueue:1` (named "PlayQueue"; the app ships an embedded SCPD for it). The app uses two actions:

- `GetKeyMapping()` → returns `QueueContext` (string, XML).
- `SetKeyMapping(QueueContext=<xml>)`.

Read format. The app normalises `<KeyN>` to `<Key>` before SAX parsing:

```xml
<?xml version="1.0"?><KeyList><ListName>KeyMappingQueue</ListName><MaxNumber>33</MaxNumber><Key0/>
 <Key1><Name>…</Name><Url>…</Url><Metadata>…(escaped DIDL-Lite)…</Metadata><PicUrl>…</PicUrl>
       <Source>Tidal|newTuneIn|Qobuz|…</Source><RoutineId>Empty|…</RoutineId><ContentType>…</ContentType>
       <Shuffle>0</Shuffle><Loop>0</Loop></Key1>
 <Key2>…</Key2> …
</KeyList>
```

Write format:
- The app has a normal builder and alternative builders; one alternative adds `<ActionFlag>1</ActionFlag>`. Each key is serialised individually.
- An empty slot is `<KeyN><RoutineId>Empty</RoutineId></KeyN>`.
- Text is XML-escaped.
- `Url` and `Metadata` are written only for radio-like presets or when the preset does not need a backup.

Two kinds of preset:
1. **Single URL / radio** (no backup needed): `<Url>` + `<Metadata>` (DIDL-Lite) are stored inline in the key.
2. **Playlist / album** (backup needed):
   - The app first calls `PlayQueue#BackUpQueue(QueueContext=<PlayList xml>)`. The queue is named `<title>_#~<timestamp>`, with a date-time timestamp.
   - It then calls `SetKeyMapping` with a key that holds only Name/Source/PicUrl/… (no Url). The device plays the backed-up queue by name.
   - Order: `BackUpQueue` first, then `SetKeyMapping`.
   - Alarms follow the same pattern (`SetAlarmQueue`).
- Spotify presets use `PlayQueue#SetSpotifyPreset(KeyIndex=<n>)`. The device stores the current Spotify Connect context.

### 4.3 Playing a preset
- httpapi `MCUKeyShortClick:<n>`, where **n is 1-based** (UI index + 1).
  - Full URL: `http(s)://IP/httpapi.asp?command=MCUKeyShortClick:3`.

### 4.4 How the app hands content to the device
The app never uses `setPlayerCmd:play:<url>`, `playlist:`, or `SetAVTransportURI`. The only `setPlayerCmd` it sends is `setPlayerCmd:switchmode:<mode>`. All browsing and playback is handed over through **UPnP PlayQueue**:

1. `PlayQueue#CreateQueue(QueueContext=<PlayList xml>)`.
2. If service version < 1.2, follow with `PlayQueue#PlayQueueWithIndex(QueueName=<ListName>, Index=<n>)`.
3. Transport is AVTransport `Play/Pause/Stop/Next/Previous/Seek` with the extra `ControlSource` arg, plus `GetInfoEx`. RenderingControl handles `SetVolume/SetMute/SetChannel`. Loop mode is set with `PlayQueue#SetQueueLoopMode(LoopMode)`.
4. Other queue editing: `AppendTracksInQueue(Ex)`, `RemoveTracksInQueue`, `MoveTracksInQueue`, `BrowseQueue(Ex)`, `GetQueueIndex`, `DeleteQueue`, `ReplaceQueue`, `SetQueuePolicy`, `TakePlayControl(Source, Switch)`.

PlayList XML:

```xml
<?xml version="1.0" ?><PlayList><ListName>{title}</ListName><ListInfo>
  <SourceName>{source e.g. Tidal/Qobuz/newTuneIn}</SourceName><SearchUrl>{optional cloud search url}</SearchUrl>
  <TrackNumber>{n}</TrackNumber>[<TotalNumber>n</TotalNumber>][<UserId>..</UserId>] {optional extra list XML}
  <PicUrl>..</PicUrl><StreamType>{list type}</StreamType><StreamId>{list id}</StreamId><ShowName>..</ShowName>
  [<ContentType>radio</ContentType><Radio>1</Radio>][<PlayMedium>..</PlayMedium>][<OriginSource>..</OriginSource>]
  <Quality>..</Quality> | <requestQuality>..</requestQuality> [<EnableReplayGain>..</EnableReplayGain><ReplayGainType>..</ReplayGainType>]
  <StartPlayIndex>{i}</StartPlayIndex>[<RealIndex>..</RealIndex>]          (play)  — or —
  <Group_name>WiimuCustomList</Group_name>[<AlarmPlayIndex>..]             (preset/alarm backup when no SearchUrl)
</ListInfo><Tracks>
  <Track1><Source>..</Source><Id>{trackId}</Id><URL>{playUrl}</URL>{optional extra track XML}<Metadata>{escaped DIDL-Lite}</Metadata></Track1> …
</Tracks></PlayList>
```

- DIDL-Lite uses namespaces `song:` (`www.linkplay.com/song/`) and `custom:`.
- `res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;"` is hard-coded.
- For a local-only controller, a plain HTTP stream or file is enough. Use a `CreateQueue` with `<SourceName>` set to anything (the app's custom lists omit SearchUrl), one `<TrackN><URL>…</URL>`, and `<StartPlayIndex>1</StartPlayIndex>`, then call `PlayQueueWithIndex` if needed.
- For phone-local files the app runs its own HTTP media server (`http://%s:8200` pattern) and passes those URLs.
- Streaming-service tracks pass `Source` = service id plus service-specific `<Id>`. The device resolves the stream itself with the account logged in on the device (`PlayQueue#UserLogin/GetUserInfo`).

Other UPnP services seen in the app's embedded SCPDs:
- AVTransport extensions: `GetInfoEx` (returns SlaveFlag, SlaveList, PlayMedium, TrackSource, …), `GetPlayType`, `SetPlayMode`, `SeekForward/Backward`, `SetPDAClientCfg`, `GetPDADeviceInfo`.
- RenderingControl extensions: `StreamServicesCapability`, `GetSimpleDeviceInfo`, `GetControlDeviceInfo`, `MultiPlaySlaveMask`, `SetAlarmQueue`, `GetAlarmQueue`, `DeleteAlarmQueue`, `SetDeviceName`, `AirplayAutoSyncDelay`, `AutoSyncDelaySub`.
- QPlay: `InsertTracks`, `QPlayAuth`, `SetNetwork`, ….
- LightManager.

### 4.5 Cloud endpoints and auth (none are needed for core local control)

| host | use |
|---|---|
| `https://cloud-api.linkplay.com/prod/ota/{check?fwUid=<uuid[0:8]>&mcuName=<project>, betaList, joinBeta, removeBeta, getReleaseNote}` | firmware update check (POST JSON `[{mcuName,fwUid,version:"1.0",language,fwVersion,deviceUid,macAddr,isBackup:0,imgList}]`) |
| `https://cloud-api.linkplay.com/prod/notifydevice/{configparams, uploadlog}`, `/prod/deviceState/wifi-fault` | push settings and logs to the device via the cloud, telemetry |
| `https://cloud-api.linkplay.com/prod/radio/{list, station/list}`, `https://api.linkplay.com/apiv1/tool/location` | WiiM Radio catalogue |
| `https://cloud-api-us.linkplay.com/v1/rest/` (`api/queryDeviceStateV2`) | device online-state query |
| `https://iotapi.wiimhome.com/device/shadow/list` | IoT device shadow |
| `https://ota-app.linkplay.com/` | OTA app-side |
| `https://api.linkplay.com/apiv1/muzo/forceupdate?...` | app force-update check |
| `https://api.linkplay.com/datav2|datav3/...`, `https://cloudtest.linkplay.com/config/appConfig` | statistics and feedback |
| `https://dataact.wiimhome.com`, `https://dataactapi.wiimhome.com/dataAct/<x>` | data-access/privacy (POST `{"uuid":…}`) |
| `https://autoeq.wiimhome.com` / `autoeq.linkplay.com` / `autoeqtest.linkplay.com` | headphone/AutoEQ database |
| `https://a000.linkplay.com/alexa.php`, `:3000` | Alexa / TuneIn / Tidal token helpers |
| `https://app-resource.linkplay.com/music/July-MySoul_%sbits_%skHz.flac` | output test-tone file |
| AWS ES `search-linkplay-…us-west-1.es.amazonaws.com` (signed `Authorization`) | remote device info |
| `freshdesk.linkplay.com`, `faq.wiimhome.com`, `www.wiimhome.com` | support |
| Third-party catalogue APIs (Tidal, Qobuz, TuneIn/radiotime, iHeart, SoundCloud, Amazon Music, Deezer, Calm Radio, Radio Paradise, nugs, Plex, Presto) | browsing. The app sends Bearer tokens or API keys to each service's own API, then hands track/list ids to the device in PlayQueue XML. |

Most linkplay cloud calls carry no special auth beyond `appId`/`deviceUid` in the body. Per-service auth is Bearer, Basic, or `x-api-key` (Amazon).

### 4.6 What a local-only controller needs vs. can skip

**Needs (all local):**
- Discovery: SSDP/UPnP (see `02-discovery-upnp.md`).
- `httpapi.asp`: `getStatusEx`, `getPlayerStatus`, `setPlayerCmd:*`, `switchmode`, `MCUKeyShortClick:n`, the capability commands in 3.4, and `SetStreamServicesCapability`.
- UPnP:
  - AVTransport: `GetInfoEx`, transport actions.
  - RenderingControl: volume, mute, `StreamServicesCapability`.
  - PlayQueue: `CreateQueue`, `PlayQueueWithIndex`, `GetKeyMapping`, `SetKeyMapping`, `BackUpQueue`, `SetQueueLoopMode`, `BrowseQueue`.
- The HTTPS client-certificate path for devices that report `security` other than http.
- Optional asset copies: `device_config_info.json` (names/images) and `device_settings_config.json` (feature whitelist for older firmware).

**Can skip:**
- All linkplay/wiimhome cloud endpoints: OTA checks, telemetry, data-act, IoT shadow, remote state, WiiM Radio catalogue, AutoEQ database, Alexa helpers.
- Streaming-service catalogue browsing. You can still trigger playback of service content that is already in presets (`MCUKeyShortClick`) or of connect-protocol sessions, and you can send plain HTTP URLs through `CreateQueue`. OTA can be triggered locally through httpapi if desired.


---

## 5. Initial setup / OOBE (BLE provisioning + SoftAP "Direct EZ")

### 5.1 BLE provisioning (LinkPlay "BLE 2.0" protocol, used by WiiM)

### Advertising / discovery
- Scanning has no service-UUID filter by default. The app parses each scan record itself.
- It reads the AD type `0xFF` (manufacturer data). The payload, read **big-endian**, is laid out as follows:

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | `tag`: `0x4C50` ("LP") = WiiM/LinkPlay BLE 2.0; `0x4C51` = Vibelink |
| 2 | 2 | `platform` (must be > 0 for BLE 2.0) |
| 4 | 1 | `version` |
| 5 | 1 | `color` |
| 6 | 2 | `pid` (product id) |
| 8 | 6 | MAC address |
| 14 | 1 | `oobe` flags |

- **`oobe` flag bits**
  - bit0: the device is already configured on Wi-Fi.
  - bit4 and bit3: together they decide whether the "new device found" popup appears. The popup shows when bit4 is clear or bit3 is set.
- Other advertisement types the app recognises:
  - A manufacturer payload of 2 bytes `0x4C50` with service UUID-16 `0x1218` and a name containing "WiiM" is a WiiM BLE **remote**.
  - A manufacturer string containing `"linkp"` is the legacy **BLE 1.0** device.
  - Hex `0f6da118…` is also a BLE 1.0 device.
  - Service UUID-16 `0xA0FE` (that is, 0xFEA0) marks a **Google Cast BLE setup** device.
- If a device's UUID list contains `0000A501-0000-1000-8000-00805F9B34FB` and its name is exactly `"LP"`, the app ignores it.

### GATT
- **Service**: the first one present among:
  - `01000100-4C69-6E6B-506C-617947415454` ("LinkPlayGATT")
  - `0000180a-0000-1000-8000-00805f9b34fb`
  - `83debca8-554f-4a43-ab65-afc8b1b02516`
- **Characteristic**: the first one present among:
  - `02000100-4C69-6E6B-506C-617943484152` ("LinkPlayCHAR")
  - `00002a37-…`
  - `83debca9-554f-4a43-ab65-afc8b1b02516`
  - `CC16F6D4-1645-FA82-E340-65743AEC0747`
- One characteristic does both jobs. The app writes to it and enables notify on it through CCCD `00002902-…`.
- **MTU**: the app requests 512. The payload chunk size is `MTU-3`, capped at 512. If MTU negotiation fails, it falls back to 20-byte chunks.
  - Long frames are split into plain consecutive writes with no per-chunk header.

### Framing (all fields little-endian)
- **Request**:

  ```
  [tag u16 = 0x4C50 → bytes 50 4C][cmd u16][len u16][payload: UTF-8 string, usually JSON]
  ```

- **Notification / response**:
  - Form A (reply to a command): `50 4C | 00 80 | ?? ?? | cmd u16 | result u16 | len u16 | data…`. The first data bytes start at offset 12. The flag `0x8000` marks a reply. Bytes 4-5 are ignored.
  - Form B (unsolicited): `50 4C | cmd u16 | len u16 | data…`.
  - A packet that does not start with `50 4C` is a continuation. It is appended until `len` bytes have arrived.
- The command timeout is 15 s.

### Command IDs (decimal / hex)
| ID | Direction | Payload | Notes |
|---|---|---|---|
| 4097 / 0x1001 | app→dev | `{"version":"2.2","platform":"android"}` | Get device info. The reply is the device-info JSON (see below). Sent right after notify is enabled. |
| 4098 / 0x1002 | app→dev | Full connect JSON (see below) | Connect to AP, single-shot form. |
| 4099 / 0x1003 | app→dev | `{"code":"<CC>","ccode":"<CC>"}` | Set the Wi-Fi country code. CC is the phone-locale country if it is in the app's whitelist, otherwise `"XZ"`. |
| 4101 / 0x1005 | dev→app | — | Wi-Fi connect started (`LP_CONNECT_AP_START`). |
| 4102 / 0x1006 | dev→app | `{"code":N, "IP"/"ip":..., "UUID"/"uuid":..., "MAC","RSSI","SNR","NOISE","FREQ","DataRate","BSSID","SSID","Auth","pingIp","pingGateway",...}` | Connect result. `code` 0 means success; other codes are listed below. |
| 4103 / 0x1007 | app→dev | `""` | Get the AP list (legacy). |
| 4116 / 0x1014 | app→dev | `{"wlanGetNewApListEx":{"ssid":"","activeScan":false}}` | Get the AP list when `bleVersion >= 1.0.6`. The reply is `{"res":..,"aplist":[{ssid,displayed_ssid,bssid,rssi,channel,auth,encry,extch,signal,signal_db,wpa_auth,wpa_cipher,identity}]}`. SSIDs come **hex-encoded**; the app decodes them for display. |
| 4114 / 0x1012 | app→dev | `{"ssid":"<ssid>","sessionId":"<unix s>"}` | Two-step connect, step 1 (`bleVersion >= 1.0.2`). |
| 4115 / 0x1013 | app→dev | `{"sessionId":..,"code":"<CC>" (bleVersion ≥1.0.6), "pass":HEX,"password":HEX,"auth":..,"encry":..,"identity":HEX,"appIP":"<phone ip>","os":"android"}` | Two-step connect, step 2, sent after 4114 is acknowledged. |
| 4108 / 0x100C | app→dev | `{"oobe": "1"}` | Mark OOBE done. Sent after success. |
| 4110 / 0x100E | app→dev | `{"bypass":"<httpapi command>"}` | **Tunnels an httpapi command over BLE.** Examples: `setBleActiveStatus:1`, `getNewAudioOutputHardwareMode`, `GNOTIFY=KeyEvent:<outputType>`, `setWlanConnectSsid:<hexSSID>`, `checkBackupSettingState`, `recoverUserSetting`, `StartRebootTime:1`. A setup-tracking JSON object can also be sent as `{"bypass":{...}}`. |

- **Single-shot 4098 JSON** (used when `bleVersion < 1.0.2`):

  ```json
  {"ssid":"..","pass":"..","password":"..","auth":"WPA2PSK","encry":"AES","identity":"HEX","appIP":"a.b.c.d","os":"android"}
  ```

- **Password and identity encoding**: when `protocolVersion > 1.0.2`, `pass`, `password` and `identity` are the **uppercase hex of the UTF-8 bytes**. They are not encrypted.
- **SSID**: the app sends the `ssid` string exactly as the device returned it in `aplist`, which is normally hex. For a manually entered "other network" it sends the user's text.
- `auth` and `encry` come from the AP list. If the password is empty, `auth` must be `OPEN` or `WPA3OWE`.

**Connect-result codes:**

| Code | Meaning |
|---|---|
| 0 | OK |
| 1 | No SSID |
| 2 | Wi-Fi timeout |
| 3 | DHCP timeout |
| 4 | Wrong password |
| 5 | Unsupported security |
| 6 | Parameter error |
| 8 | Router connected, but the app did not find the device |
| 9 | User closed Bluetooth |
| 10 | Device found but not online |
| 11 | Retry |
| 12 | Code-12 retry |

**Device-info reply to 4097:**
- `commonInfo` contains:
  - Identity: `name`, `uuid`, `BT_MAC`, `sn`, `userName`
  - Versions: `security_version`, `protocolVersion`, `bleVersion`, `firmware`, `audioOutputModeVer`, `audioInputModeVer`, `deviceSettingVer`
  - Radio state: `hotpotState`, `wifiMode`, `btMode`, `bleActiveStatus`, `bt_name`, `wifi_5G`
  - Power: `battery`, `charge_status`
  - `first_oobe`
- `devSetting` contains: `autoPowerOff`, `autoPowerOffTime`, `autoStandby`, `autoStandbyTime`, `ain`, `ainStatus`, `devStatus`.
- `hwInfo` contains `pcbRevision`.
- Older firmware returns a bare object without the `commonInfo` wrapper. The app then wraps it itself and hex-encodes AP-list SSIDs itself.
- Version gates:
  - `protocolVersion < 1.0.4` switches to "send next chunk only after the last write succeeded".
  - `> 1.0.2` turns on hex passwords.
  - `bleVersion >= 1.0.2` uses the two-step 4114/4115 connect; `>= 1.0.6` uses 4116 plus the country `code`.

### Typical BLE OOBE sequence
1. Scan and match the `0x4C50` manufacturer data.
2. Connect, request MTU 512, then enable notify.
3. Send 4097 (device info). If `bleVersion >= 1.0.6`, immediately send 4116 to get the AP list and pick the phone's current SSID.
4. Send 4099 (country) where applicable.
5. Send 4114 (ssid, sessionId), then 4115 (credentials). Older devices get 4098 instead.
6. Receive 4101 (start), then 4102 (`code`, `ip`, `uuid`).
7. The app announces the new device's ip and uuid internally and sends 4108 `{"oobe":"1"}`.
8. It then finds the device over the LAN by UUID. If no 4102 IP arrives, it polls its device list by `uuid` every 5 s for up to 40 s.

### BLE 1.0 legacy (text protocol)
- Old form: `LP0.1:NET:SSID:<hex ssid>:PASS:<b64>`.
- Newer form, sent as two writes:
  - `LP0.1:NNETS:SSID:<hex ssid>:SESSIONID:<s>`
  - `LP0.1:NNETP:PASS:<b64>:SESSIONID:<s>`
- **Encryption**: `<b64>` = Base64(AES-128-CBC/NoPadding(password zero-padded to 16 bytes, key `"~linkplayble@#=="`, IV = 16 zero bytes)).
- The reply is a text notification `…IP:<ip>:UUID:<uuid>`, or `LP0.1:NETRES:ERR:CODE:<n>` on failure.

### Google Cast BLE setup (service `0000fea0-…`)
This is used only when the device advertises 0xFEA0 (Cast-enabled firmware). The steps are:

| Step | Characteristic | Operation |
|---|---|---|
| `kSetupAttrCountry` | `0128fe40-002f-11e5-87d0-0002a5d5c51b` | Write the country code (ASCII). |
| `kSetupAttrScanWifi` | `0228fe40-…` | Write `0x01`. |
| `kSetupAttrGetWifiList` / `kSetupAttrScanResultsGz` | `156c6160-fab2-11e4-9fbb-0002a5d5c51b` / `157c6160-…` | Paged reads that return gzip JSON. |
| `kSetupAttrGetPublicKey` / read | `1a6c6160-…` / `1a7c6160-…` | Returns gzip JSON `{"device_info":{"public_key":"<PEM>"}}`. |
| `kSetupAttrConnectNetwork` | `0328fe40-…` | Write `{"ssid","wpa_auth","wpa_cipher","enc_passwd":RSA/PKCS1(password, device key),"wpa_id":0,"scan_ssid":0}`. |
| `kSetupAttrState` | `0c7c6160-…` | Poll the state byte. |
| `kSetupAttrIpAddress` | `0d7c6160-…` | Read 4 raw bytes (the IP). |
| `kSetupAttrSaveWifi` | `147c6160-…` | Write `{"keep_hotspot_until_connected":true}`. |

- State byte values:
  - 61, 63–67, or 51: read the IP next.
  - 32, 21, 10, 11, or 33: map to code 4 (wrong password).
  - 41 or 52: map to code 3 (DHCP timeout).

### 5.2 SoftAP setup ("Direct EZ 4")
- **Joining the speaker AP**: the user joins the speaker's AP manually in Android Wi-Fi settings. The SSID looks like `<Name>_XXXX`.
  - The device is the gateway at **`10.10.10.254`**.
  - "Phone is on the device AP" is detected when the phone's gateway IP equals the IP of a discovered device.
- **Commands**: plain httpapi on the AP address, `http(s)://10.10.10.254/httpapi.asp?command=…`.
  - `getStatusEx` returns device info.
  - `wlanGetApListEx` returns the AP scan. The reply is `{"aplist":[{ssid(hex),bssid,channel,auth,encry,extch,rssi,signal_db}]}`. The app repairs truncated JSON by cutting at the last `},` and appending `]}`.
- **Connect**:

  ```
  wlanConnectApEx:ssid=<hexSSID>:ch=<channel>:auth=<AUTH|OPEN>:encry=<ENC|NONE>:pwd=<hex(password)>:chext=<1 or "">
  ```

  - On "security 3" firmware (`security_capabilities.aes_ver` set, and not the case `security=="https 2.0" && security_version=="3.0"`), the app uses `wlanConnectApExAES:` with the same fields. In that variant `pwd` = UPPERHEX(ascii(Base64(AES-128-CBC/NoPadding(hex(password) zero-padded, key = first 16 chars of device `uuid`, IV = 0)))).
- **Afterwards**: the device leaves its AP and joins the router. The phone must return to the router.
  - The app waits 6 s, then polls the discovered device list by `uuid` every ~1.5 s for up to 90 s. It checks getStatusEx `netstat == 2` and that `essid` equals the target SSID.
  - `getDeviceNameChangeable` is called on success.
- An older `setNetwork:<i>:<str>` command also exists.

### 5.3 Finding the device on the LAN
- mDNS service `_linkplay._tcp`.
- SSDP `M-SEARCH` with `ST: urn:schemas-upnp-org:device:MediaRenderer:1`.
- Devices are matched by `uuid` (the UUID from BLE 4102 or `getStatusEx`).
- HTTP base URL: `http://IP/httpapi.asp?command=` or `https://IP/httpapi.asp?command=` (port 443). Devices with `gc4aVer` (Google Cast 4 Audio) use `https://IP:4443/…`.

### 5.4 What a local controller needs for OOBE
- **BLE**:
  - Connect to service `01000100-4C69-6E6B-506C-617947415454`, characteristic `02000100-4C69-6E6B-506C-617943484152`, and enable notify.
  - Send 4097, then 4116 (AP list), then 4099, then 4114 and 4115 (hex-encoded password).
  - Wait for 4102, then send 4108 `{"oobe":"1"}`.
- **No cloud is needed for provisioning.**
- **SoftAP**: join the AP, then call `wlanGetApListEx` and `wlanConnectApEx:` on 10.10.10.254, then rediscover the device on the router via mDNS or SSDP.
- **Out of scope** (noted only): BLE OTA for the WiiM remote, and the Vibelink `0x4C51` BLE control notifications (`NotifyDevStatus` / `NotifyAInData`, etc.).
