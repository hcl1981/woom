; Woom – Windows installer (Inno Setup).
; Packs the app image built by `./gradlew createReleaseDistributable`
; (desktop/build/compose/binaries/main-release/app/Woom) into WoomSetup.exe.
;
; Build it with:
;   iscc /DAppDir="<path to ...\app\Woom>" windows\woom.iss
; (build-windows.ps1 does this for you.)

#define MyName "Woom"
#define MyVersion "1.0.3"
#define MyPublisher "Woom"
#define MyExe "Woom.exe"

; Folder with the built app image (Woom.exe + runtime\ + app\). Override with /DAppDir=...
#ifndef AppDir
  #define AppDir "..\build\compose\binaries\main-release\app\Woom"
#endif

[Setup]
AppId={{8F3C7A51-2B4D-4E9A-9C1F-7A6B5D0E2C44}
AppName={#MyName}
AppVersion={#MyVersion}
AppPublisher={#MyPublisher}
; Per-user install to %LOCALAPPDATA%\Woom – no administrator rights needed. A fixed install
; location also means the virus scanner only checks the program once, so later starts are fast.
DefaultDirName={localappdata}\{#MyName}
DisableDirPage=yes
DefaultGroupName={#MyName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
UninstallDisplayIcon={app}\{#MyExe}
UninstallDisplayName={#MyName}
OutputDir=..\build\installer
OutputBaseFilename=WoomSetup
SetupIconFile=..\icons\woom.ico
WizardStyle=modern
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "autostart"; Description: "{cm:Autostart}"; GroupDescription: "{cm:StartupGroup}"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[CustomMessages]
de.Autostart=Woom automatisch mit Windows starten (nur im Infobereich)
de.StartupGroup=Beim Anmelden:
de.CreateDesktopIcon=Verknüpfung auf dem Desktop anlegen
de.LaunchAfter=Woom jetzt starten
en.Autostart=Start Woom automatically with Windows (tray only)
en.StartupGroup=At sign-in:
en.CreateDesktopIcon=Create a desktop shortcut
en.LaunchAfter=Launch Woom now

[Files]
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyName}"; Filename: "{app}\{#MyExe}"
Name: "{group}\{cm:UninstallProgram,{#MyName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyName}"; Filename: "{app}\{#MyExe}"; Tasks: desktopicon

[Registry]
; Start at login. Same value name and format the app itself uses, so the in-app
; "Start at login" switch stays in sync with this checkbox.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; \
  ValueName: "Woom"; ValueData: """{app}\{#MyExe}"" --hidden"; Flags: uninsdeletevalue; Tasks: autostart

[Run]
Filename: "{app}\{#MyExe}"; Description: "{cm:LaunchAfter}"; Flags: nowait postinstall skipifsilent
