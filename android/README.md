# Woom – einfache Android-App für WiiM-Geräte

Fertige App: `../Woom.apk` (Android 9 oder neuer). Bauen: `./gradlew assembleRelease`, Tests: `./gradlew testDebugUnitTest`.

## Bedienung

- **Zahnrad oben rechts:** Geräte hinzufügen (werden im WLAN automatisch gefunden, sonst per IP), entfernen, sortieren, Kürzel (2 Zeichen) ändern, Eingänge ein-/ausblenden.
- **Sender-Balken:** bis zu 5 Geräte-Buttons. Gedrückt halten und schieben sortiert die Senderliste.
  - Tippen auf ein Gerät → spielt den Sender.
  - Läuft der Sender schon auf einem anderen Gerät → das getippte Gerät spielt **synchron** mit (Multiroom-Gruppe).
  - Tippen auf ein Gerät, das den Sender gerade spielt (gefüllter Button) → nur dieses Gerät verstummt.
    Ist es der Gruppen-Leiter, übernimmt ein Mitspieler den Sender (kurze Pause ~1 s), die übrigen hängen sich an ihn.
    Leiter/Mitspieler wird dafür direkt beim Gerät abgefragt, nicht aus dem letzten Abfrage-Stand.
- **Regler-Symbol im Balken:** Name, Stream-URL, Bild-URL, welche Geräte-Buttons erscheinen, Sender als **Favorit (Preset)** auf ein oder mehrere Geräte übertragen, Reihenfolge, löschen.
- **Sender hinzufügen:** Button unter den Sendern. Oben Suche im freien Senderverzeichnis radio-browser.info
  (füllt Name, Stream-URL und Logo aus); fehlende Logos werden einmal pro Start per Stream-URL nachgeschlagen.
- **Lautstärke & Eingänge** (antippen zum Auf-/Zuklappen, beim Start zu): pro Gerät Stummschalten (`setPlayerCmd:mute:0|1`), Lautstärkeregler, Eingänge zum Umschalten
  und ein Einstellungs-Symbol (Name, Kürzel, angezeigte Eingänge – nur in Woom, nicht auf dem Gerät).
- Zu lange Namen laufen als Lauftext durch.

## Sprachen

Systemsprache automatisch (ab Android 13 auch pro App wählbar): Englisch (Standard), Deutsch, Französisch, Spanisch,
Italienisch, Niederländisch, Polnisch, Portugiesisch, Japanisch, Chinesisch (vereinfacht). Texte in `res/values*/strings.xml`.

## Technik

Kotlin + Jetpack Compose. Gespeichert wird lokal in `woom.json` im App-Speicher.
Protokoll, Datenmodell und Steuerlogik (`WoomViewModel`) liegen in `../shared/src` und werden auch von der PC-Version (`../desktop`) benutzt.
Protokoll: siehe `../docs/`. Abspielen per PlayQueue `CreateQueue` (mit Name/Logo), Rückfall `setPlayerCmd:play:<url>`;
Gruppe `multiroom:JoinGroup` / `LeaveGroup` / `Ungroup`; Eingänge `setPlayerCmd:switchmode:<name>`;
Presets per `GetKeyMapping`/`SetKeyMapping` (nur der gewählte Platz wird ersetzt). Status-Abfrage alle 3 s, solange die App sichtbar ist.

## Teststand am Gerät (Stand 2026-10-29)

Verifiziert: Gerätesuche, Status, `setPlayerCmd:play`/`stop`, Presets lesen und **schreiben**
(`LiveDeviceTest`, schreibt auf einen leeren Platz und stellt die Liste danach wieder her),
Eingangsliste per `getAudioInputCapbility` (Ultra: inkl. `udisk`; Sound: `auxIn`; Mini kennt den Befehl nicht → Rückfall auf `plm_support`).

Ebenfalls verifiziert (Ultra, Lautstärke 10): Abspielen per `CreateQueue` (Gerät zeigt Name + Logo,
`vendor: CustomRadio`, startet nach ~1 s), Eingang umschalten (`switchmode:line-in` → `mode 40`, wird in der App markiert), Stop.

Gruppieren verifiziert (Ultra + Sound): `JoinGroup` → Follower `group=1`, `master_uuid` = Leiter, `getSlaveList` listet ihn;
`LeaveGroup` und `Ungroup` → `group=0`. Der Follower meldet in `getPlayerStatus` immer `status:"stop", mode:99` – auch während er
mitspielt; die App leitet seinen Zustand deshalb vom Leiter ab.
Laufender Sender wird über AVTransport `TrackURI` erkannt (bleibt die Stream-URL, während der Titel durch Sendungsinfos ersetzt wird;
im Stopp steht dort nur `SONGLIST-NETWORK`).

Live-Tests: `WOOM_LIVE_IP=<ip> WOOM_LIVE_SLOT=<leerer Platz> ./gradlew testDebugUnitTest --tests '*LiveDeviceTest*'`
(hörbarer Teil zusätzlich mit `WOOM_LIVE_AUDIBLE=1 WOOM_LIVE_VOLUME=10`).
