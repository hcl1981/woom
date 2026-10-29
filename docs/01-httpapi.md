# WiiM Home 3.6.11 — LinkPlay/WiiM HTTP API catalog

Based on analysis of the official WiiM Home 3.6.11 app. Untested against a real device.

**Key points**
1. Playback in the app does **not** use httpapi: play/pause/next/seek, status, volume, mute, loop, queue, presets list and alarms go via UPnP (see `02-discovery-upnp.md`). The app only uses `setPlayerCmd:switchmode:<input>` and `MCUKeyShortClick:<n>`. Classic commands (`getPlayerStatus`, `setPlayerCmd:pause` …) are listed in §15 from public docs — unverified here.
2. HTTPS uses mutual TLS with a client certificate (§0.3). Test first whether a device answers `curl -k https://IP/httpapi.asp?command=getStatusEx` without a cert.
3. Base URL: `http(s)://IP/httpapi.asp?command=<cmd>`, port **4443** instead of 443 when getStatusEx has `gc4aVer`. Almost all GET; the app does not URL-encode — encode JSON params yourself. Large JSON → POST with raw body `command=<cmd>`.

## 0. Transport

### 0.1 URLs
- `http://IP/httpapi.asp?command=` when `security=="http"`, else `https://IP/…`, `:4443` when getStatusEx has `gc4aVer`.
- `/upload.asp?` for uploads.
- A few paths always use plain HTTP: Bluetooth helpers, firmware backup/OTA upload and SoftAP setup.

### 0.2 Sending
- GET raw. Reply conventions: `OK` / `Failed`, `1`/`0`, or JSON. `unknown command` / `no parameters` = error.
- POST to `/httpapi.asp`, raw body `command=<cmd>`, no Content-Type. Used for `createRoutine:`, `updateRoutine:`, `multiroom:savenamegrouplist:`, `set_ui_wallpaper_list:`, `setStreamServiceConfig:`.
- BLE bypass during setup: opcode 4110, payload `{"bypass":"<cmd>"}`.

### 0.3 TLS
- `security == "https"` → app asset `certificate_old` as client certificate.
- `"https 2.0"` / `"https 3.0"` → app asset `certificate_new`.
- Assets are PKCS#12 with every byte XOR `0x02`. The keystore passwords are in the app's native library `libwiimundk.so` (not extracted). The assets `client.pkcs12` and `alice.p12` are unused.
- Server cert **not** validated; hostname check always true. TLS via OkHttp, timeouts 15 s connect / 30 s read.
- "Security 3" firmware (`security_capabilities.aes_ver` set) switches some commands to AES variants.

### 0.4 Parameter encodings
| Encoding | Used for |
|---|---|
| lowercase hex of UTF-8 | `wlanConnectApEx` `pwd=` |
| UPPERCASE hex | `setHexDeviceName`, `ConnectMasterAp ssid=`, `PrivShell` |
| hex → UTF-8 decode (if all-hex) | `essid`, AP-list `ssid` |
| URL-encoding | `setDeviceName`, `multiroom:SlaveDeviceName`, `subwooferPair`, `subwooferForget`, `setcurrentnamegroup`, `NameGroupPingTest` |
| AES-128-CBC | `setNetworkExAES`, `wlanConnectApExAES`, `setAccessPIN`, `psk` decrypt on https 2.0 |
| raw JSON after `:` | everything else taking JSON |

AES: key = `uuid[0:16]`, IV = 0, zero padding (1–16 bytes), plaintext = hex of secret, ciphertext → Base64 (no newlines) → UPPERCASE hex.

## 1. Device info / status
- `getStatusEx` (fields below). `getStatus:ip:<slaveIP>` (via master).
- `getDebugInfo`, `getDeviceNameChangeable`, `getAudioDspVersion`, `getFeatureCapbility`, `getAudioInputCapbility`, `getAudioOutputCapbility`, `GetAcousticCapability`, `getDigitalInputAudioTypeSupport` (hex `0x…`).
- Rename: `setDeviceName:<urlenc>`; `setHexDeviceName:<UPPER hex>` when name contains `#`.
- `setLanguage:<l>`, `setInitialConfiguration:%d`, `appUploadEvent:<json>`.
- Time: `timeSync:<yyyyMMddHHmmss>`, `setTimezone:<float>[:0]`, `setTimezoneEx:<offset>:<dst 0/1>:<TZ id>`.
- `getWeatherInfo`, `setWeatherLocation:<x>:`, `restartUPnP`, `getsyslog[:ip:<slave>]` then `/data/sys.log`.
- `PrivShell:<UPPER hex cmd>` runs a shell command on the device.

### getStatusEx fields
The app regex-extracts `DeviceName`/`GroupName` before JSON parsing (firmware doesn't escape quotes). Bitmasks are hex strings `"0x…"`.

| Fields | Meaning |
|---|---|
| `uuid`, `MAC`, `ETH_MAC`, `BT_MAC`, `upnp_uuid` | identity |
| `upnp_version` | ≥1005 → new loop-mode values |
| `DeviceName`, `GroupName`, `ssid`, `hideSSID` | names; `ssid` = own hotspot |
| `hardware`, `project`, `firmware`, `build`, `Release`, `mcu_ver`, `wmrm_version`, `ota_api_ver`, `audioOutputModeVer`, `audioInputModeVer`, … | versions / feature gates |
| `EQVersion` | default 2.0; ≥3.0.99 new PEQ |
| `gc4aVer` | Google Cast fw → HTTPS on 4443 |
| `eth0`, `eth2`, `apcli0` | IPs; Ethernet if `apcli0` empty/0.0.0.0 |
| `essid` | upstream Wi-Fi, hex UTF-8 |
| `RSSI`, `BSSID`, `WifiChannel`, `wlanSnr`, `wlanNoise`, `wlanFreq`, `wlanDataRate` | Wi-Fi link |
| `internet` | 1/2 online, 0/-1 offline |
| `security`, `security_version`, `security_capabilities` | TLS level |
| `preset_key` | preset count (0 → app uses 6) |
| `plm_support` | input bitmask (§4), valid if `capability` bit 19 |
| `capability`, `cap1` | bitmasks (bit 19, bit 3 volume limit; cap1 bit 17 BT transmitter) |
| `group`, `master_uuid`, `wmrm_capability` | multiroom |
| `max_volume`, `volume_control`, `prompt_status`, `DeviceSN` | misc |
| `uart_pass_port`, `communication_port` | MCU TCP port (8819) |

## 2. Playback via httpapi
- `setPlayerCmd:switchmode:<name>` (§4 names).
- `MCUKeyShortClick:<n>` plays preset n (1-based).
- `getEndTrackEnable` / `setEndTrackEnable:<0/1>`.
- Per-input volume: `get/setPlayModeVolumeEnable`, `get/setPlayModeVolumeValue`, `getPlayModeGainConfig` / `setPlayModeGainConfig: <json>` (space after colon).

## 3. Volume, channel, loop
- `setMaxVolume:<n>`, `setVolumeControl:<0/1>` (fixed volume), `getSoftMute`/`setSoftMute:<v>`.
- Steps: `get/set_button_volume_step`, `get/set_remote_volume_step`, `get/set_cec_volume_step`.
- `multiroom:SlaveVolume:<ip>:<v>`.
- `getChannelBalance` / `setChannelBalance:<-1.0..1.0>`; `getChannelMode` / `setChannelMode:<0 stereo,1 L,2 R,3 mono,4 swapped>`.
- Normal volume uses UPnP RenderingControl `SetVolume` (`Channel=Single`, `ControlSource=WiiMApp`).

Loop mode (UPnP `LoopMode`, `upnp_version` ≥ 1005):

| Value | Shuffle | Repeat |
|---|---|---|
| 0 | off | all |
| 1 | off | one |
| 2 | on | all |
| 3 | on | off |
| 4 | off | off |
| 5 | on | one |

## 4. Inputs
`plm_support` bit → switchmode name:

| Bit | name | | Bit | name |
|---|---|---|---|---|
| – | `wifi` | | 11 | `cd` |
| 1 | `line-in` | | 13 | `TFcard` |
| 2 | `bluetooth` | | 15 | `PCUSB` |
| 3 | `udisk` / `external_usb` | | 16 | `phono` |
| 4 | `optical` | | 17 | `DAB` |
| 5 | `RCA` | | 18 | `optical2` |
| 6 | `co-axial` | | 19 | `co-axial2` |
| 7 | `FM` | | 22 | `ARC` |
| 8 | `line-in2` | | 21 | (bit-perfect UI flag, not an input) |
| 9 | `XLR` | | | |
| 10 | `HDMI` | | | |

Commands: `getInputModeSupportList`; `getAudioInputEnable` / `setAudioInputEnable:{"ver":"1.0","audioInput":[..]}`; `getModeRename` / `setModeRename:{"<type>":"<name>"}`; `getTotalAudioInsertState`, `getAudioInputStreamState`, `getHdmiArcState`; auto-sense `get/setAutoSenseEnable`, `getAutoSenseStatus`, `setInputmodeAutoSense:[{"setAutosenseMode":"<name>","autosenseEnable":0/1}]` (+ legacy `get/setSpdifAutoSenseEnable`, `…AuxAutoSense…`, `…HDMIAutoSense…`); `get/setSpdifInNoiseRemove`; `PHONO_MODE_SWITCH_GET/SET:<v>`; `get/setAudioInputPassThroughEnable`, `get/setAudioInputPassThroughSrc`; `get/setCecPowerCtrl`; `get/setNonHDMIModeSwitchSpkCtrl`.

## 5. Presets & routines
- Play: `MCUKeyShortClick:<n>`. List/save: UPnP `GetKeyMapping` / `SetKeyMapping`.
- Routines: `getRoutineCapability`; `getAllRoutines[:preset|input]` → `{"version","routines":[{id,name,index,createDate,updateDate,type,bindInput,enabled,steps:[{type,payload}]}]}`; `createRoutine:<json>`, `updateRoutine:<json>` (POST). Step types: volume, loopMode, audioInput, audioOutput, EQ, aliveTime, subwoofer, screenController, nameGroup.

## 6. EQ / PEQ / room correction
### Legacy graphic EQ
`EQGetList`, `EQLoad:<name>`, `EQSave:<name>`, `EQOn`, `EQOff`, `EQGetBand` → `{"EQStat":"On|Off","Name","EQBand":[{index,value}]}`, `EQSetBand:{"EQBand":[{"index":i,"value":v}]}` (0–100, 50 flat). Oldest: `EQGet`, `EQSet:Bass:<n>`, `EQSet:Treble:<n>`, `EQEnable`/`EQDisable`.

### EQv2 (LV2 plugins)
pluginURI: `http://moddevices.com/plugins/caps/Eq10HP` (10-band), `…/Eq4p` (old PEQ), `…/EqNp` (new PEQ, `EQVersion` ≥ 3.0.99).
Band params `<a_..j_><freq|q|gain|mode>`; defaults 30, 62.5, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz, Q 0.25. mode: -1 off, 0 low shelf, 1 peak, 2 high shelf, 3 LP, 5 HP.
Reply: `{channelMode:"Stereo"|"L/R", EQBand|EQBandL|EQBandR:[{param_name,value}], EQStat, Name, pluginURI, source_name, status}`. `EQLevel` 1 = per source, 2 = output.

- Read: `EQGetLV2Band:<uri>`, `EQGetLV2SourceBand:{"EQLevel":2,"source_name","pluginURI"}`, `EQGetLV2SourceBandEx:{"source_name","pluginURI"}`
- Write: `EQSetLV2Band:{"pluginURI","EQBand":[..]}`, `EQSetLV2SourceBand:{"EQLevel":2,"source_name":"","pluginURI","channelMode","EQBand"}`
- Presets: `EQv2GetList:<uri>` → `{preset[],custom[]}`, `EQv2GetNewList:{"pluginURI"}`, `EQv2Load:<x>`, `EQv2SourceLoad:{"EQLevel":2,"pluginURI","Name"}`, `EQSourceSave:{…,"Name","UpdateAt"}`, `EQv2Rename:{…,"Name","newName","UpdateAt"}`, `EQv2Delete:{"EQLevel":2,"pluginURI","Name"}`
- On/off/type: `EQChangeFX:<uri>`, `EQChangeSourceFX:{"EQLevel","source_name","pluginURI"}`, `EQSourceOff:{same}`
- Channels: `EQSetChannelMode:<json>`, `EQGetChannels:{"profile","channels":[5,6]}`, `EQSetChannels:<json>`
- Migration: `EQMoveMode:`, `EQMoveRCModes:{"EQLevel":1,"newLevel":2,"pluginURI":"…/EqNp"}`, `EQGetModeParam:`

### Other DSP
`EQGetHeadroom` / `EQSetHeadroom:{"Mode":"Auto"|"Off"}`; `EQGetDynamicBass` / `EQSetDynamicBass:{"on":1,"level":n}`; `EQGetSoundMode:{}` / `EQSetSoundMode:<m>`; `EQGetVoiceLevel:{}` / `EQSetVoiceLevel:{"Level":".."}`; `get_soundeffect:dap` / `set_soundeffect:dap:<v>`; `get_virtualx_enable` / `set_virtualx_enable:<0/1>`.

### Room correction / sub
`getRoomCorrection` / `setRoomCorrection:{"delay":"x.xxx"}`, `RoomCorrSet:`, `RoomCorrSetLR:`, `RoomCorrSetMode:{"Mode"}`; `EQGetRCSubCal` / `EQSetRCSubCal:{"delayMs","levelDb","Version","Time"}`; `getSubLPF` / `setSubLPF:<v>`; `getMRMSubLPF:<v>` / `setMRMSubLPF:<v>`; `getMainProtect`, `setMainProtect:cross:<hz>`, `setMainProtect:status:<0/1>`; `getMainSubExtraDelay`. (Measurement/filter design is native code on the phone — see 03.)

## 7. Audio output & Bluetooth
- Set output: `setAudioOutputHardwareMode:<type>` (most), `GNOTIFY=KeyEvent:<type>` (WiiM Mini), `setSoundCardOutputMode:<json>` (some). Get: `getNewAudioOutputHardwareMode` → `{hardware,source,audiocast,airplay}`.

| Output | hardware | source |
|---|---|---|
| Line out | 0 | 1 |
| Speaker | 0 | 2 |
| HDMI | 0 | 3 |
| ARC | 0 | 4 |
| Optical | 1 | 0 |
| AUX | 2 | 0 |
| Coax | 3 | 0 |
| Headphone | 4 | 0 |
| BT out | 3 | 1 |
| AirPlay | -5 | 0 |
| DLNA | -6 | 0 |
| USB DAC | -7 | 0 |

- `getActiveSoundCardOutputMode`, `getSoundCardModeSupportList`, `get/setSpdifOutSwitchDelayMs`.
- DAC filter: `getDigitalFilterType`, `getDigitalFilterTypeSupportList`, `setDigitalFilterType:%d`; new `getOutputDigitalFilterType:<mode>`, `getOutputDigitalFilterTypeSupportList`, `setOutputDigitalFilterType:<json>`.
- Output level: `getOutputVoltage`, `getAuxVoltageSupportList`, `setOutputVoltage:%d`; new `getAudioOutputVrms:<mode>`, `getAudioOutputVrmsSupportList:{"ver":"1.1"}`, `setAudioOutputVrms:{"mode","type"}`.
- MQA: `get/setMQAReceiverCap`, `get/setTidalMQAOnOff`. Fade: `GetFadeFeature` / `SetFadeFeature: <0/1>` (space!). `get/setDigitalAudioMultiChnlOutput`. Prompts: `getLowPriorityPromptDisable` / `disableLowPriorityPrompt:<v>`. 12 V trigger: `get/setTriggeroutStatus`, `triggerOnByRebootGet/Set`. `get_bt_latency` / `set_bt_latency:%d`.
- Max sample rate per port: `get<Port>MaxCap` / `set<Port>MaxCap:<p>`, ports SpdifOut, CoaxOut, LineIn (AuxIn old), UacOut, AuxIn, AuxOut, LineOut, SpeakerOut, HeadphoneOut. With mode ver ≥1.3: GET `{"ver":"v<ver>"}`, SET `{sampleRate,bitDepth,cfgType,multiList[],ver}`; legacy SET `<rate>:<depth>:<cfgType>`.
- Bluetooth: `startbtdiscovery:<sec>`, `stopbtdiscovery`, `getbtdiscoveryresult` → `{"scan_status","list":[{name,ad,ct,role}]}` (4 = done), `clearbtdiscoveryresult`, `getbthistory`, `delbthistory:<mac>`, `connectbta2dpsource:<mac>`, `connectbta2dpsynk:<mac>`, `btdisconnectall`, `disconnectbta2dpsynk:<mac>`, `getbtpairstatus` (0 broken,1 unpaired,2 in progress,3 OK,4 failed), `startbtserver:<n>`, `stopbtserver:<n>`, `getbtstatus`, `startgetbtPairDevStat`, `getbtPairDevStat`, `btavkenterpair`, `btrecovery`. BLE remote: `startblescan:<n>`, `getblediscoveryresult`, `blehidpair:<mac>`, `blehidremoveall`, `getblehidstatus`, `setBleActiveStatus:<n>`.

## 8. LEDs, display, buttons
`LED_SWITCH_GET`, `LED_SWITCH_SET:<1/0>`, `LED_SWITCH_STATUS_GET`, `VOL_LED_SWITCH_GET/SET`, `get/setLightOperationBrightConfig:{auto_sense_enable,default_bright,wakeup_by_touch,support_wakeup_by_touch,disable}`, `get_ui_config` / `set_ui_config:<json>`, `get/setVolumeAdjustUvmeterFlag`, `get_ui_wallpaper_list`, `set_ui_wallpaper_list:` (POST), `Button_Enable_GET/SET`, `Reload_Button_GET/UPDATE`.

## 9. Streaming & casting
- `streamServicesCapability` → `{encrypt,version,streamServices:[{id,active,configurable,removable}]}`; `SetStreamServicesCapability:<Active|Deactive>:<id>` (AVS_MRM, AudioCast, TidalConnect, SpotifyConnect, Squeezelite, Soundtrack).
- `getStreamServiceConfig:<src>`, `setStreamServiceConfig:{"source","payload":{autoplay,hires,loudnessNormalization,quality,smartPlay}}` (POST).
- Squeezelite: `Squeezelite:getState`, `:discover`, `:connectServer:<host:port>`, `:autoConnectEnable:<0/1>`, `:setDefaultServer`, `:updateServiceName`.
- AudioCast (AirPlay out): `audio_cast:get_speaker_list`, `scan_speaker`, `speaker_enable:<id>`, `speaker_disable:<id>`, `speaker_set_password:<id>:<pwd>`, `speaker_set_volume:<id>:<v>`, `speaker_stereo_group_enable/disable:<gid>`, transcode profile/buffer get/set.
- Google Cast: `Cast:EnableCast`, `Cast:Disable`, `Cast:EnableUsageReport`, `Cast:DisableUsageReport`, `Cast:Register`, `Cast:Deregister`, `Cast:GetRegisterStatus`.
- Amazon/TuneIn/Alexa: `GetAmazonConfig`, `GetAmazonHD`, `EnableAmazonHD`, `EnableAmazonAtmos`, `refreshTuneinToken`, `getAlexaProfile`, `alexaLogOut`, `setAlexaCountry`, `alexaSetLanguage`, …
- USB library: `mediaserver:udiskumount`, `mediaserver:scan:<id>`, `mediaserver:rebuild:<id>`.

## 10. Multiroom
- Join (to slave): `multiroom:JoinGroup:IP=<masterIP>:uuid=<masterUUID>`; legacy `ConnectMasterAp:…:JoinGroupMaster:eth<ip>:wifi<ip>:uuid<uuid>`.
- Leave (to slave): `multiroom:LeaveGroup`. To master: `multiroom:Ungroup`, `multiroom:SlaveKickout:<ip>`.
- `multiroom:getSlaveList` → `{"slaves","slave_list":[{name,uuid,ip,mask,volume,mute,channel,version,ssid,battery,project,group_channel}]}`.
- `multiroom:SlaveVolume:<ip>:<v>`, `multiroom:SlaveChannel:<ip>:<0/1/2>`, `multiroom:SlaveDeviceName:<ip>:<urlenc>`.
- Sync: `multiroom:ConfigGet/ConfigSet:leadtime[:v]`, `…realtime_cache_limit`, `multiroom:get/set_source_cache_time`, `Get/SetSyncPlayExtraDelay`.
- Saved groups: `multiroom:getnamegrouplist`, `multiroom:savenamegrouplist:<json>` (POST), `multiroom:setcurrentnamegroup:<urlenc {"group_id","slavelist"}>` (-1 ungroups), ping test commands.
- WiiM Sub: `multiroom:subwooferGetPairInfo`, `multiroom:subwooferPair:<urlenc json>`, `multiroom:subwooferForget:<urlenc {"uuid"}>`.

## 11. Timers, power, maintenance
`getShutdown` (sec remaining) / `setShutdown:<sec>` (-1 cancels); alarms via UPnP only; `getPowerModeTime` / `setPowerModeTime:{"idleInterval":"<v>"}`; `StartRebootTime:1`; `restoreToDefault`; `checkBackupSettingState`, `removeBackupSetting`, `recoverUserSetting`.

## 12. Network & Wi-Fi
`wlanGetApListEx` → `{"aplist":[{ssid(hex),bssid,rssi,channel,auth,encry,extch,signal_db}]}`, `wlanGetApFullList`; `wlanConnectApEx:ssid=<hex>:ch=<ch>:auth=<a|OPEN>:encry=<e|NONE>:pwd=<hex>:chext=<1|>`, `wlanConnectApExAES:`; `wlanSwitchConnectedAp:ssid=%s:bssid=%s`; `getNetwork`, `setNetwork:<1|0>:<pwd>` / `setNetworkExAES:`, `setHideSSID`; `setAccessPIN:{"PIN":"<aes>"}`; `getStaticIpInfo`, `setWlanStaticIp:ipAddr=<ip>/<prefix>:gateWay=<gw>:dnsServer=<dns>`, `setEthStaticIp:…`; `get/setSmartDNSEnable`, `get/setNetworkPreferDNS`, `get/setIPV6Enable`, `get/setWlanBandConfig`, `get/setWlanRoamConfig`, `setWlanConnectDisable`, `wpsclientmode`; net test `netPerformanceTest*`.

## 13. Firmware update
`getMvRemoteUpdateStartCheck` → `getMVRemoteCheckResult` (`ota_state`: no_check/checking/check_done_with_success/failed; `ota_result`: unknown/has_new_version/no_new_version) → `getMvRemoteUpdateStart` → poll `getMvRomDownloadStatus` / `getMvRomDownloadV2Status` (`status`: 0 idle, 1 downloading, 2 dl failed, 3 writing flash, 5 flash failed, 6 done/rebooting, 8 not updating, 9 retry failed). `getMvRemoteUpdateDeviceOtaInfo`; `get/setMVRemoteAutoOTAEnable`; `get/setMvRemoteSilenceUpdateTime:00:01_23:59`; `GetUpdateServer` / `SetUpdateServer:<url>`. Local upload (plain HTTP): `GET /goform/getMvKillProcess`, `POST /cgi-bin/upload.cgi` (multipart `file`), `getMvRomBurnPrecent`, `NotifyUpgradeType:firmware`.

## 14. Other endpoints
| Endpoint | Purpose |
|---|---|
| `POST /upload.asp?filename=<n>&crc32=<dec>` (JPEG body) | wallpaper; read back at `/wallpaper/<n>` |
| `/data/sys.log` | device log |
| `POST :8008/setup/save_wifi` | Google Cast setup |
| `:8200/rootDesc.xml`, `/json_status`, `/ctl/manage_playlist` | built-in DLNA server (USB library) |
| `:<port>/zc?action=getInfo` | Spotify Connect |
| `:59152/…` | UPnP |
| TCP 8819, UDP 8822 | see 02 |

## 15. Classic LinkPlay commands not used by the app (public docs, unverified)
- `setPlayerCmd:pause|resume|onepause|stop|prev|next`, `setPlayerCmd:seek:<sec>`, `setPlayerCmd:vol:<n>`, `setPlayerCmd:mute:<0/1>`, `setPlayerCmd:loopmode:<n>`, `setPlayerCmd:play:<url>`.
- `getPlayerStatus` → `{type,ch,mode,loop,eq,status,curpos(ms),totlen(ms),Title/Artist/Album (hex UTF-8),plicount,plicurr,vol,mute}`.
- `mode`: 0 none, 1 AirPlay, 2 DLNA, 10–19 network, 31 Spotify, 32 TIDAL, 40 line-in, 41 BT, 43 optical, 45 coax, 47 line-in 2, 49 HDMI, 51 USB, 99 multiroom slave.
- `getMetaInfo` → `{"metaData":{album,title,artist,albumArtURI,sampleRate,bitDepth}}`.
