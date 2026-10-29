# WiiM-Kommunikation – Überblick

Grundlage: Analyse der offiziellen App WiiM Home 3.6.11, geprüft an WiiM Ultra, Mini und Sound (siehe „Live-Test“ unten).

| Datei | Inhalt |
|---|---|
| `01-httpapi.md` | Katalog aller `httpapi.asp?command=…`-Befehle, TLS, Parameter-Kodierungen, getStatusEx-Felder |
| `02-discovery-upnp.md` | Geräteerkennung (mDNS/SSDP), UPnP-Dienste und -Aktionen, PlayQueue-XML, GENA-Events, TCP 8819 |
| `03-features-multiroom-eq-setup.md` | Multiroom, EQ/PEQ, Raumkorrektur, Capability-Bits, Presets, Cloud-Endpunkte, Ersteinrichtung (BLE/SoftAP) |

## Die drei Kanäle zum Gerät

1. **UPnP/DLNA auf Port 59152** (selten 49152): das eigentliche Steuerprotokoll der App.
   - AVTransport: Play/Pause/Next/Seek, `GetInfoEx` (Status + Metadaten in einem Aufruf)
   - RenderingControl: `SetVolume` (Channel=`Single`), Mute, `GetControlDeviceInfo` (liefert getStatusEx-JSON)
   - PlayQueue (`urn:schemas-wiimu-com:service:PlayQueue:1`): Warteschlange, Presets (`GetKeyMapping`/`SetKeyMapping`)
   - GENA-Events (`LastChange`) für Live-Updates statt Polling
2. **HTTP-API `httpapi.asp?command=…`** (HTTPS 443, bzw. 4443 bei Google-Cast-Firmware): Einstellungen, Eingänge, EQ, Multiroom, Ausgänge, Bluetooth, Firmware.
3. **TCP 8819 „MCU“-Passthrough** (optional): Bass/Treble/HDMI bei einigen Geräten.

## Erkennung

mDNS `_linkplay._tcp` (TXT: `uuid`, `security`, `bootid`) + SSDP M-SEARCH `urn:schemas-upnp-org:device:MediaRenderer:1`. Nur UDNs mit `uuid:FF…` sind LinkPlay-Geräte. Validierung über `GetControlDeviceInfo` oder `getStatusEx`.

## Offene Punkte / Risiken

- **TLS-Clientzertifikat:** Die App sendet bei HTTPS ein Clientzertifikat (Assets `certificate_old`/`certificate_new`, PKCS#12 XOR 0x02). Die Passwörter stecken in der nativen Bibliothek `libwiimundk.so`. Erster Test am Gerät: `curl -k "https://<IP>/httpapi.asp?command=getStatusEx"` — klappt das ohne Zertifikat, ist das Thema erledigt. Das Serverzertifikat prüft die App nicht.
- **Klassische Befehle** (`getPlayerStatus`, `setPlayerCmd:pause` …) nutzt die App nicht mehr; sie stammen aus öffentlicher LinkPlay-Doku und sind am Gerät zu prüfen. UPnP ist der sicherere Weg.
- **Raumkorrektur/Sub-Kalibrierung** rechnet nativ auf dem Handy (`libwiimundk.so`) – nicht sinnvoll nachbaubar; nur das Setzen fertiger Werte ist dokumentiert.
- **Streamingdienste** (Tidal, Qobuz, Spotify …) laufen über die Cloud-APIs der Anbieter bzw. Login auf dem Gerät (PlayQueue `UserLogin`) – für lokale Steuerung nicht nötig.

## Live-Test am 2026-10-20 (3 Geräte im WLAN 192.168.0.0/24)

| Gerät | IP | project | Firmware |
|---|---|---|---|
| WiiM Ultra | 192.168.0.4 | WiiM_Ultra | Linkplay.5.2.829661 |
| WiiM Mini (Arbeitszimmer) | 192.168.0.2 | Muzo_Mini | Linkplay.4.6.819436 |
| WiiM Sound | 192.168.0.7 | WiiM_Sound | Linkplay.5.2.829356 |

Ergebnisse (nur lesende Befehle):
- **mDNS** `_linkplay._tcp` findet alle drei (Port 59152, TXT `security=https 3.0`, `uuid=uuid:FF…`).
- **HTTPS ohne Clientzertifikat funktioniert** (`curl -k`). Das mTLS-Thema ist für diese Geräte erledigt. Plain HTTP (Port 80) und Port 4443 sind zu; kein Gerät hat `gc4aVer`.
- `getStatusEx`-Feld `security` ist `"https/2.0"` (mit Schrägstrich), `security_version` `"3.0"`.
- **Klassische Befehle funktionieren:** `getPlayerStatus` (Title/Artist/Album hex-UTF-8, `mode` 10 = Netzwerk/Radio, 40 = Line-in) und `getMetaInfo` (Klartext, inkl. sampleRate/bitDepth/albumArtURI).
- **UPnP auf :59152 funktioniert:** `description.xml` abrufbar; `GetInfoEx`, `GetControlDeviceInfo`, PlayQueue `GetKeyMapping` (Presets) antworten. Event-/Metadaten-DIDL nutzt Namespace `www.wiimu.com/song/`.
- **GENA SUBSCRIBE** wird angenommen (200, SID, `Second-300`), aber Events kamen nicht an – die ufw-Firewall dieses PCs blockiert eingehende Verbindungen. Für die App: Callback-Port in der Firewall freigeben.
- **Abspielen per URL funktioniert:** `setPlayerCmd:play:<URL, komplett percent-encoded>` → `OK`, Status danach `status:"play"`, `vendor:"CustomPushUrl"`. Ohne Metadaten zeigt das Gerät die URL als Titel – für schöne Anzeige stattdessen PlayQueue `CreateQueue` mit DIDL-Metadaten nutzen.
- **2026-10-29:** `getAudioInputCapbility` → `{"ver":"1.0","audioInput":[{"mode":"wifi"},{"mode":"line-in"},…]}` (Ultra: line-in, bluetooth, optical, phono, HDMI, udisk; Sound: auxIn, bluetooth; Mini: `unknown command`). `getAudioInputEnable` → gleiche Liste mit `"enable":1|0`. PlayQueue `SetKeyMapping` (Preset schreiben, read-modify-write der KeyList) am Ultra erfolgreich getestet und wiederhergestellt. IPs per DHCP wechseln (Sound .7 → .5).
- **2026-10-29 (Bluetooth-Eingang, Ultra):** `getStatusEx` liefert die BT-Adresse als `BT_MAC` (Mini) bzw. `BTMAC` (Ultra, Sound).
  `btavkenterpair` macht das Gerät koppelbar; `getbthistory` → `{"list":[{"name","ad","role":"Audio Source"}]}`;
  `connectbta2dpsource:<mac>` lässt das Gerät sich selbst mit einer bekannten Quelle (PC) verbinden (~4 s);
  `getbtstatus` → `{"a2dp_sink":{"pairing":0|1,"link_state":"connected|disconnected","name":"<Quelle>"}}`;
  `btdisconnectall` trennt. Wechselt das Gerät auf eine andere Quelle, bleibt die BT-Verbindung bestehen.
- `uart_pass_port` ist bei allen 0 → der TCP-8819-MCU-Kanal wird bei diesen Geräten nicht genutzt.

## Mindestumfang für eine eigene App

1. Erkennen (mDNS + SSDP), validieren (`GetControlDeviceInfo`).
2. Status über `GetInfoEx` + GENA-Abo auf die drei Event-URLs.
3. Steuerung: Play/Pause/Next/Prev/Seek (AVTransport), Lautstärke/Mute (RenderingControl).
4. Quelle wechseln: `setPlayerCmd:switchmode:<name>`; Presets: `MCUKeyShortClick:<n>`.
5. Multiroom: `multiroom:JoinGroup` / `LeaveGroup` / `Ungroup`, `getSlaveList`.
6. Danach: EQ (EQv2), Ausgänge, Einstellungen.
