# Builds Woom for Windows on Windows itself: a jpackage app image via Gradle/Compose,
# then WoomSetup.exe via Inno Setup. No mingw, no Linux, no admin rights needed.
#
#   powershell -ExecutionPolicy Bypass -File desktop\build-windows.ps1
#
# Output: desktop\build\installer\WoomSetup.exe
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '.')   # the desktop\ folder

function Find-First($paths) { foreach ($p in $paths) { if (Test-Path $p) { return (Resolve-Path $p).Path } } return $null }

# --- JDK 21 (the build pins the toolchain to 21; the machine default may be another version) ---
$jdk = Find-First @(
  "$env:ProgramFiles\Eclipse Adoptium\jdk-21*",
  "${env:ProgramFiles(x86)}\Eclipse Adoptium\jdk-21*",
  "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-21*"
)
if (-not $jdk) { throw "JDK 21 not found. Install it, e.g.: winget install EclipseAdoptium.Temurin.21.JDK" }
Write-Host "JDK 21: $jdk"
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

# --- Inno Setup compiler ---
$iscc = Find-First @(
  "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
  "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
  "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
)
if (-not $iscc) { throw "Inno Setup (ISCC.exe) not found. Install it, e.g.: winget install JRSoftware.InnoSetup" }
Write-Host "Inno Setup: $iscc"

# --- 1. Build the app image (Woom.exe + bundled runtime) ---
Write-Host "`n== Gradle: createReleaseDistributable =="
& .\gradlew.bat --no-daemon -q createReleaseDistributable
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" }

$appDir = Join-Path (Get-Location) 'build\compose\binaries\main-release\app\Woom'
if (-not (Test-Path (Join-Path $appDir 'Woom.exe'))) { throw "app image not found at $appDir" }
Write-Host "App image: $appDir"

# --- 2. Build the installer ---
Write-Host "`n== Inno Setup: WoomSetup.exe =="
& $iscc "/DAppDir=$appDir" (Join-Path (Get-Location) 'windows\woom.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed ($LASTEXITCODE)" }

$setup = Join-Path (Get-Location) 'build\installer\WoomSetup.exe'
Write-Host "`nDONE -> $setup"
if (Test-Path $setup) { Get-Item $setup | Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}} | Format-List }
