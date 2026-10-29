# Woom für PC (Linux und Windows)

Dieselbe Fernbedienung wie die Android-App, als kleines Fenster am Tray-Icon (Infobereich der Taskleiste).

## Bedienung

- **Klick auf das Tray-Icon** öffnet das Fenster neben dem Icon, ein weiterer Klick oder ein Klick daneben schließt es.
  Esc geht eine Ebene zurück bzw. schließt das Fenster. Das Fenster erscheint nicht als eigenes Programm in der Taskleiste.
- **Rechtsklick auf das Icon:** „Woom öffnen“ und „Beenden“.
- **Computer** (erste Leiste): spielt den Ton des PCs über Bluetooth auf einem WiiM – geringe Verzögerung, Video-Player
  gleichen sie automatisch aus (Filme bleiben lippensynchron). Klick auf ein Gerät: der WiiM schaltet auf seinen
  Bluetooth-Eingang und verbindet sich mit dem PC, das System leitet den Ton um (Linux ca. 2,5–5 s).
  Nochmal klicken trennt. Wechselt der WiiM auf eine andere Quelle (Sender, Fernbedienung, WiiM-App), trennt Woom die
  Verbindung, damit der PC wieder über die eigenen Lautsprecher spielt.
  Erstes Mal: unter Linux koppelt Woom selbst (bluetoothctl); unter Windows öffnet Woom die Bluetooth-Einstellungen,
  dort einmal den WiiM hinzufügen – danach verbindet sich der WiiM selbst mit dem PC.
- Sender, Geräte-Knöpfe, Lautstärke, Stummschalten, Eingänge, Presets und Sendersuche wie in der App.
  Sender lassen sich mit der Maus an eine andere Stelle ziehen.
- **Einstellungen:** Geräte suchen/hinzufügen und „Woom bei der Anmeldung starten“ (startet dann nur im Tray).
- Ein zweiter Start von Woom öffnet einfach das Fenster des bereits laufenden Woom.

Die Senderliste ist unabhängig von der Android-App (kein Abgleich).
Gespeichert wird in `~/.config/woom/woom.json` (Linux) bzw. `%APPDATA%\Woom\woom.json` (Windows).

## Installation

- **Linux (Debian/Ubuntu/Mint):** `sudo apt install ./Woom-linux.deb` – danach im Menü unter „Multimedia“ → Woom.
  Das Tray-Icon nutzt StatusNotifier (Cinnamon, KDE, XFCE, MATE; GNOME braucht die AppIndicator-Erweiterung).
- **Windows 10/11:** `Woom-windows.zip` entpacken (z. B. nach `%LOCALAPPDATA%\Woom`) und `Woom.exe` starten.
  Java ist enthalten, keine Installation nötig. Windows SmartScreen warnt beim ersten Start, weil das Programm nicht signiert ist.

## Geräte finden

Statt mDNS probiert Woom auf dem PC den HTTPS-Port aller Adressen im eigenen /24-Netz und fragt offene Adressen nach
`getStatusEx`. Das braucht keine eingehenden Pakete, eine Firewall auf dem PC stört also nicht.
Geräte in anderen Netzen lassen sich per IP-Adresse hinzufügen.

## Bauen

- Starten zum Ausprobieren: `./gradlew run`
- Linux-Paket: `./gradlew packageReleaseDeb` → `build/compose/binaries/main-release/deb/`
- Windows-Paket (auf Linux): `WIN_JDK=<entpacktes Windows-JDK 21, gleiche Version wie das lokale> ./build-windows.sh`
  → `build/windows/Woom-windows.zip`. Braucht mingw-w64 (für `Woom.exe`) und zip.

Technik: Kotlin + Compose Multiplatform 1.12, Tray über ComposeNativeTray, JNA für die Taskleisten-Ausnahme unter X11.
Protokoll und Steuerlogik kommen aus `../shared/src` (gemeinsam mit der Android-App).

## Teststand (2026-10-29)

- Linux (Cinnamon/X11): Tray-Icon, Fenster am Icon, kein Taskleisten-Eintrag, Sender abspielen, Logos – vom Nutzer bzw. lokal geprüft.
- Computer-Ton (Linux, Ultra): Koppeln, Verbinden, Trennen, automatisches Trennen beim Quellenwechsel geprüft
  (Entwicklerversion). Hörtest/Lippensynchronität durch den Nutzer steht aus.
- Windows: nur unter Wine geprüft (Start, Oberfläche, Gerätesuche findet alle drei Geräte, Autostart-Eintrag in der Registry).
  Tray-Icon, Fensterposition und Computer-Ton (Bluetooth) auf echtem Windows noch ungetestet.
