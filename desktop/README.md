# Woom for PC (Linux and Windows)

The same remote as the Android app, as a small window at the tray icon (notification area of the taskbar).

## Usage

- **Click the tray icon** to open the window next to the icon; another click, or a click elsewhere, closes it.
  Esc goes back one level or closes the window. The window does not appear as a separate program in the taskbar.
- **Right-click the icon:** "Open Woom" and "Quit".
- **Device audio** (first bar, can be switched off in the settings): plays the PC's sound on a WiiM over Bluetooth – low latency, video players
  compensate for it automatically (films stay in sync). Click a device: the WiiM switches to its
  Bluetooth input and connects to the PC, and the system reroutes the sound (Linux about 2.5–5 s).
  Click again to disconnect. If the WiiM switches to another source (station, remote, WiiM app), Woom
  disconnects so the PC plays through its own speakers again.
  First time: on Linux Woom pairs itself (bluetoothctl); on Windows Woom opens the Bluetooth settings,
  where you add the WiiM once – after that the WiiM connects to the PC by itself.
- Stations, device buttons, volume, mute, inputs, presets and station search as in the app.
  Stations can be dragged to another position with the mouse.
- **Settings:** find/add devices and "Start Woom at login", which is on after installing (then starts in the tray only).
- Starting Woom a second time simply opens the window of the already running Woom.

The station list is independent of the Android app (no sync).
Data is stored in `~/.config/woom/woom.json` (Linux) or `%APPDATA%\Woom\woom.json` (Windows).

## Installation

- **Linux (Debian/Ubuntu/Mint):** `sudo apt install ./Woom-linux.deb` – then find Woom in the menu under "Multimedia".
  The tray icon uses StatusNotifier (Cinnamon, KDE, XFCE, MATE; GNOME needs the AppIndicator extension).
- **Windows 10/11:** run `WoomSetup.exe` – installs to `%LOCALAPPDATA%\Woom` (no admin rights), with a Start-menu
  entry and an optional start at login. Java is included.
  Windows SmartScreen warns on first start ("More info" → "Run anyway") because the program is not signed.

## Finding devices

Instead of mDNS, on the PC Woom probes the HTTPS port of every address in its own /24 network and asks open
addresses for `getStatusEx`. This needs no incoming packets, so a firewall on the PC does not get in the way.
Devices in other networks can be added by IP address.

## Building

- Run to try it out: `./gradlew run`
- Linux package: `./gradlew packageReleaseDeb` → `build/compose/binaries/main-release/deb/`
- Windows package (on Linux): `WIN_JDK=<unpacked Windows JDK 21, same version as the local one> ./build-windows.sh`
  → `build/windows/Woom-windows.zip`. Needs mingw-w64 (for `Woom.exe`) and zip.
- Windows installer (on Windows): `powershell -ExecutionPolicy Bypass -File build-windows.ps1`
  → `build/dist/WoomSetup-<date>.exe`. Needs JDK 21 and Inno Setup (ISCC).

Tech: Kotlin + Compose Multiplatform 1.12, tray via ComposeNativeTray, JNA for the taskbar exception under X11.
Protocol and control logic come from `../shared/src` (shared with the Android app).

## Test status (2026-10-30)

- Linux (Cinnamon/X11): tray icon, window at the icon, no taskbar entry, playing stations, logos – checked by the user / locally.
- Device audio (Linux, Ultra): pairing, connecting, disconnecting and automatic disconnect on source change checked
  (developer build). Listening test / lip sync by the user still pending.
- Windows 10/11: start, device discovery, settings persistence, first-time Bluetooth pairing for device audio, and the
  autostart installer checked by the user. (Earlier versions had only been tested under Wine.)
