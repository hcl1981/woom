# WiiM Home 3.6.11: discovery, UPnP/DLNA, GENA, local sockets

Based on analysis of the official WiiM Home 3.6.11 app. Nothing here has been tested against a real device yet.

The official app uses a heavily patched Cling 1.x UPnP stack.

Key design takeaways:
- The app does **not** fetch `description.xml` / SCPDs for renderers. It builds the device from a hard-coded template; real name/MAC/firmware come from the `Status` JSON of `GetControlDeviceInfo`.
- The app's periodic renderer M-SEARCH is a no-op in 3.6.11. It finds speakers via mDNS, passive NOTIFY, an IP cache and onboarding broadcasts. Our app should still M-SEARCH `MediaRenderer:1` — the firmware answers it.

## 0. Quick reference

| What | Value |
|---|---|
| Device UPnP HTTP port | **59152** (`LOCATION: http://<ip>:59152/description.xml`). Rewritten to **49152** when AP BSSID starts with `00:22:6c` / `00:25:92` |
| Device identity | UDN/USN `uuid:FF…`; MediaRenderers not starting with `UUID:FF` (case-insensitive) are dropped |
| mDNS service types | `_linkplay._tcp` (main), `_spotify-connect._tcp` (Android 11+). Diagnostic/3rd-party: `_airplay._tcp`, `_googlecast._tcp`, `_alexa._tcp`, `_tidalconnect._tcp` |
| mDNS TXT keys | `uuid`, `security`, `bootid`, `MAC` |
| Validation | RenderingControl `GetControlDeviceInfo`: response must contain `MultiType`; `Status` = getStatusEx JSON whose `upnp_uuid` must match the UDN |
| Liveness probe | TCP connect :443, fallback :59152, 5 s |
| Control URLs | `/upnp/control/rendertransport1`, `/rendercontrol1`, `/renderconnmgr1`, `/PlayQueue1`, `/LightManager1`, `/QPlay1` |
| Event URLs | `/upnp/event/rendertransport1`, `/rendercontrol1`, `/PlayQueue1` |
| App event-callback server | phone port 22809; SUBSCRIBE `TIMEOUT: Second-1800` |
| Raw TCP | `uart_pass_port` from status JSON, normally **8819**: "MCU" passthrough, 20-byte header `18 96 18 20 …` |
| UDP | `"linkplay"` to UDP **8822** after GetControlDeviceInfo failures |
| Phone media server | HTTP 11234, announced via PlayQueue `SetMediaServerInfo` |

## 1. Discovery

All discovery paths end as an SSDP-style search response processed by the app's SSDP response handler. mDNS results, IP-cache hits and onboarding results are converted into **fake SSDP response datagrams** and injected.

### 1.1 SSDP
- Multicast `239.255.255.250:1900`, IPv4 only. M-SEARCH: HOST, `MAN: "ssdp:discover"`, MX (default 3), ST.
- ST constants: `ssdp:all`, LinkPlay-specific `ssdp:wiimudevice`, `ssdp:easylink`; `urn:schemas-upnp-org:device:MediaServer:1`, `MediaRenderer:1`, `InternetGatewayDevice:1`, `urn:schemas-wifialliance-org:device:WFADevice:1`, `service:ContentDirectory:1`, `service:AVTransport:1`.
- Searches actually sent: MediaServer/ContentDirectory (DLNA servers, 6× every 10 s); IGD/WFADevice (router); the renderer search sends nothing.
- Incoming filter reads header `DEVICE-SOURCE` (`SSDP`/`mDNS`/`IP-Cache`/`_spotify-connect._tcp.`).
- Response headers used: `LOCATION`, `ST`, `USN`, `SERVER` → security mode (`"https 3.0"`, `"https 2.0"`, `"https"`, else `http`), `Bootid.upnp.org`, `ServiceName: ##hexStr##<hex name>`, `Easylink`.

### 1.2 Descriptors
- Real descriptors downloaded only for MediaServers. Renderers use a hard-coded device template + embedded SCPDs.
- Services (all on port 59152):

| Service | type | control | event |
|---|---|---|---|
| AVTransport | `urn:schemas-upnp-org:service:AVTransport:1` | `/upnp/control/rendertransport1` | `/upnp/event/rendertransport1` |
| ConnectionManager | `urn:schemas-upnp-org:service:ConnectionManager:1` | `/upnp/control/renderconnmgr1` | `/upnp/event/renderconnmgr1` |
| RenderingControl | `urn:schemas-upnp-org:service:RenderingControl:1` | `/upnp/control/rendercontrol1` | `/upnp/event/rendercontrol1` |
| PlayQueue | `urn:schemas-wiimu-com:service:PlayQueue:1` | `/upnp/control/PlayQueue1` | `/upnp/event/PlayQueue1` |
| LightManager | `urn:schemas-wiimu-com:service:LightManager:1` | `/upnp/control/LightManager1` | `/upnp/event/LightManager1` |
| QPlay | `urn:schemas-tencent-com:service:QPlay:1` | `/upnp/control/QPlay1` | `/upnp/event/QPlay1` |

SCPD paths: `/upnp/rendertransportSCPD.xml`, `/upnp/renderconnmgrSCPD.xml`, `/upnp/rendercontrolSCPD.xml`, `/upnp/PlayQueueSCPD.xml`, `/upnp/LightMangerSCPD.xml`, `/upnp/QPlaySCPD.xml`.

### 1.3 Validation and device list
1. Device added: MediaRenderer with `uuid:FF` UDN → device entry `{IP, uuid}`.
2. After random 20–300 ms: `GetControlDeviceInfo(InstanceID=0)` → outputs `MultiType, Router, Ssid, SlaveMask, CurrentVolume, CurrentMute, CurrentChannel, SlaveList, Status`.
3. Success: `MultiType` present, `Status` (getStatusEx JSON) non-empty, `upnp_uuid` == UDN. Fields used: `DeviceName`, `GroupName`, `ssid`, `project`, `upnp_version`.
4. Failure: after 3 failures UDP 8822 poke; HTTP fallback `http(s)://<ip>[:4443]/httpapi.asp?command=getStatusEx`.
5. On success: subscribe AVTransport/RenderingControl/PlayQueue, `GetMediaInfo`, open 8819 socket if `uart_pass_port > 0`, store IP in cache.
6. Raw SOAP example:
```
POST http://<ip>:59152/upnp/control/rendercontrol1
SOAPACTION: "urn:schemas-upnp-org:service:RenderingControl:1#GetControlDeviceInfo"
Content-Type: text/xml

<?xml version="1.0" encoding="utf-8"?><s:Envelope s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/" xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetControlDeviceInfo xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID></u:GetControlDeviceInfo></s:Body></s:Envelope>
```

### 1.4 mDNS
- Android 11+: NsdManager browses `_linkplay._tcp` + `_spotify-connect._tcp`; older: bundled mDNS library, `_linkplay._tcp`.
- TXT: `uuid` (`uuid:FF…` form), `security`, `bootid`, `MAC`. IPv4 only (re-resolve up to 3× if only IPv6).
- On service-lost: TCP-check :443 (fallback 59152) before removing.
- Fake SSDP packet built from mDNS:
```
HTTP/1.1 200 OK
CACHE-CONTROL: max-age=1800
LOCATION: http://<ip>:<srvport>/description.xml
Bootid.upnp.org: <bootid>
ServiceName: ##hexStr##<hex>
SERVER: Linux/2.6.21 <security>
ST: urn:schemas-upnp-org:device:MediaRenderer:1
USN: <uuid>::urn:schemas-upnp-org:device:MediaRenderer:1
DEVICE-SOURCE: mDNS
```

### 1.5 IP cache
Stored IPs (current subnet only) are TCP-probed on :443/59152 and injected with `DEVICE-SOURCE: IP-Cache`, port 59152.

## 2. UPnP actions

General: User-Agent `WiiMHome/<ver> <x> Android/<rel>`; connect 10 s / socket 20 s; play/pause/seek/next/prev/SetVolume/SetChannel are fire-and-forget; extra argument `ControlSource="WiiMApp"`.

### 2.1 AVTransport
| Action | Arguments |
|---|---|
| Play | InstanceID=0, Speed="1", ControlSource |
| Pause / Next / Previous | InstanceID, ControlSource |
| Stop | InstanceID |
| Seek | InstanceID, Unit=`REL_TIME`, Target `hh:mm:ss`, ControlSource |
| GetInfoEx | InstanceID |
| GetMediaInfo, GetPositionInfo, GetTransportInfo | InstanceID |

GetInfoEx outputs: CurrentTransportState, CurrentTransportStatus, CurrentSpeed, Track, TrackDuration, TrackMetaData, TrackURI, RelTime, AbsTime, RelCount, AbsCount, LoopMode, PlayType, CurrentVolume, CurrentMute, CurrentChannel, SlaveFlag, SlaveList, PlayMedium, TrackSource, InternetAccess, VerUpdateFlag, VerUpdateStatus, BatteryFlag, BatteryMains, BatteryPercent, AlarmFlag, TimeStamp, SubNum, SpotifyActive.
`SetAVTransportURI` is not used by the app.

### 2.2 RenderingControl
| Action | Arguments |
|---|---|
| GetControlDeviceInfo | InstanceID |
| SetVolume | InstanceID, Channel=**`Single`**, DesiredVolume, ControlSource (throttled 150 ms) |
| SetMute | Channel=`Master`, DesiredMute, ControlSource |
| SetChannel | Channel=`Master`, DesiredChannel (0/1/2) |
| StreamServicesCapability | InstanceID, AppVersion |
| AirplayAutoSyncDelay / AutoSyncDelaySub | Name (+SyncVersion="1") |
| SetAlarmQueue / GetAlarmQueue / DeleteAlarmQueue | AlarmContext / AlarmName |

### 2.3 PlayQueue (`urn:schemas-wiimu-com:service:PlayQueue:1`)
| Action | Arguments |
|---|---|
| CreateQueue / ReplaceQueue / AppendTracksInQueue | QueueContext |
| AppendTracksInQueueEx | QueueContext, Action ("create"), Direction (1 head / 0 tail), StartIndex, Play |
| PlayQueueWithIndex | QueueName, Index |
| BrowseQueue | QueueName (e.g. `CurrentQueue`) |
| BrowseQueueEx | `CurrentQueue`, TrackIndex, TrackNums |
| DeleteQueue | QueueName |
| BackUpQueue | QueueContext |
| GetQueueIndex | QueueName → CurrentIndex, PreloadingIndex, CurrentPage, TrackNums |
| SetQueueLoopMode | LoopMode |
| SetQueuePolicy | QueueName |
| RemoveTracksInQueue | QueueName, Action, RangStart, RangEnd |
| MoveTracksInQueue | QueueName, IndexList, ToIndex |
| SetKeyMapping / GetKeyMapping | QueueContext (presets) |
| SetSpotifyPreset | KeyIndex |
| UserLogin / UserLogout / GetUserInfo | streaming-service accounts on device |
| SetMediaServerInfo | `{"name":"Android_<BRAND>_<MODEL>_RemoteLocal","uuid":..,"ip":<phone>,"media_port":<port>}` |

Play flow: `CreateQueue` with PlayList containing `<StartPlayIndex>` (service ≥1.2 auto-plays) → older devices: `PlayQueueWithIndex` → further chunks via `AppendTracksInQueueEx(Action="create", Direction, StartIndex=0, Play=0)`.

PlayList XML:
```
<?xml version="1.0" ?><PlayList><ListName>..</ListName><ListInfo>
  <SourceName/><SearchUrl/><TrackNumber/>[<TotalNumber/>][<UserId/>]
  <PicUrl/><StreamType/><StreamId/><ShowName/>
  [<ContentType/>[<Radio>1</Radio>]][<PlayMedium/>][<OriginSource/>]
  <Quality/>|<requestQuality/>
  [<EnableReplayGain/>[<ReplayGainType/>]]
  <StartPlayIndex/>[<RealIndex/>]   (or <Group_name>WiimuCustomList</Group_name>[<AlarmPlayIndex/>])
</ListInfo><Tracks>
  <Track1><Source/><Id/><URL/><Metadata>{escaped DIDL}</Metadata></Track1>…
</Tracks></PlayList>
```
Per-track DIDL-Lite: namespaces `song`=`www.linkplay.com/song/`, `custom`=`www.linkplay.com/custom/`; `upnp:class object.item.audioItem.musicTrack`; `song:id`, `song:singerid`, `song:albumid`, `res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;" duration=..`, `dc:title`, `upnp:artist`, `upnp:album`, `upnp:albumArtURI`.

Preset KeyList XML:
```
<?xml version="1.0"?><KeyList> <ListName>KeyMappingQueue</ListName>[<ActionFlag>1</ActionFlag>]<MaxNumber>33</MaxNumber> <Key0></Key0>
  <KeyN><Name/><PicUrl/><Source/><ContentType/><RoutineId>(id|Empty)</RoutineId><Shuffle>0</Shuffle><Loop>0</Loop>[<Url/><Metadata/>]</KeyN>
</KeyList>
```

### 2.4 Other services
- ContentDirectory (NAS/DLNA servers): Browse, Search, GetSearchCapabilities.
- Metadata `song:` extension tags: id, quality, actualQuality, bitrate, rate_hz, format_s, coding_f, stream_ch, atmos, dolby_*, controls, skiplimit, isLive, rating, originSource, …

## 3. GENA eventing
- Subscribe AVTransport, RenderingControl, PlayQueue; timeout 1800 s; retry 1 s ×4.
- `LastChange` parsing must tolerate malformed `<CurrentTrackMetaData val=…>` (the official app repairs it before parsing).
- AVTransport keys: `TransportState` (PLAYING, PAUSED_PLAYBACK, STOPPED, NO_MEDIA_PRESENT, TRANSITIONING), `CurrentTrackMetaData` (escaped DIDL), `CurrentTrackDuration`, `CurrentTrack`, `TrackSource`, `PlaybackStorageMedium`, `A_ARG_TYPE_SeekTarget`.
- RenderingControl regex:
```
<(Mute|Channel|Equaluzer|Volume|PresetNameList|Slave|network|SlaveMask|TimeStamp|DeviceName|GroupName|NextInvalid|BtPairState|BtPairDevAddr|PDALoginStatus|NetWorkEnterPairing|AirplayDelayNotify|FixVolume)\s+(channel="Master"\s+|action="(.*)"\s+|\s)?val="(.*)"/>|<(TimeStamp)\s+?val="(.*)"/>
```
  `Slave action=rm/add`; `action=volumechange/channelchange` carry slave volume. Fallback: `commonevent` JSON (`audio_input_signal_changed`, `audio_interface_changed`, `HDMI_ARC_changed`, `ntp`, `sound_effect_changed`, `nettest`), `blehidstatus`, `HarmanBarState`.
- PlayQueue regex (firmware typos are real): `<(CurretPlayListName|CurrentPlayListName|LoopMpde|LoopMode|CurrentIndex)\s+val="(.*)"/>`

## 4. Other local socket protocols

### TCP MCU passthrough (`uart_pass_port`, usually 8819)
| Offset | Content |
|---|---|
| 0 | magic `18 96 18 20` |
| 4 | payload length uint32 LE |
| 8 | checksum, 0 |
| 12 | 8 zero bytes |
| 20 | ASCII payload |

On connect: `MCU+USB+GET&MCU+MMC+GET&` (+`MCU+PAS+GET&`). Commands: `MCU+PAS+EQSet:bass:%02d&`, `MCU+PAS+EQSet:treble:%02d&`, `MCU+PAS+EQGet&`, `MCU+PAS+HDMIGet&`, `MCU+PAS+HDMISet:%03d&`. Notifications: `AXX+USB+…`, `AXX+MMC+…`, `MCU+PAS+…`, `AXX+VIS+…`.

### Other
- UDP 8822 `"linkplay"` poke. TCP 443 / 4443 / 59152 reachability. No WebSockets.

## 5. Minimal flow for our controller
1. **Discover:** browse `_linkplay._tcp` (IPv4) + M-SEARCH `MediaRenderer:1` MX 3 + listen to NOTIFY; keep `uuid:FF…`.
2. **Validate:** `GetControlDeviceInfo` on :59152 (retry 49152) or `getStatusEx` via HTTPS; check `upnp_uuid`.
3. **Control:** AVTransport GetInfoEx/Play/Pause/Seek; RenderingControl SetVolume `Channel=Single`; PlayQueue CreateQueue.
4. **Events:** SUBSCRIBE the three event URLs, renew before 1800 s, parse `LastChange`.
5. **Optional:** 8819 MCU socket.
