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

# --- 1b. Slim the bundled Java runtime ---
# jpackage ships a large, uncompressed runtime (~75 MB). Rebuild it with jlink: only the modules Woom
# needs, debug symbols stripped, compressed. Same module set the hand-tuned Linux build uses, so it is
# known to run this app. jdeps finds java.base/java.desktop/java.logging statically; the extras below
# are pulled in at runtime (TLS for HTTPS, preferences, instrumentation, naming) and jdeps cannot see them.
$modules = 'java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.instrument,java.logging,java.security.sasl,java.naming,jdk.crypto.ec,jdk.unsupported'
$slim = Join-Path (Get-Location) 'build\slim-runtime'
if (Test-Path $slim) { Remove-Item -Recurse -Force $slim }
Write-Host "`n== jlink: slim runtime =="
& "$env:JAVA_HOME\bin\jlink.exe" --module-path "$env:JAVA_HOME\jmods" `
  --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress zip-6 --output $slim
if ($LASTEXITCODE -ne 0) { throw "jlink failed ($LASTEXITCODE)" }
$rt = Join-Path $appDir 'runtime'
Remove-Item -Recurse -Force $rt
Move-Item $slim $rt
$rtMB = [math]::Round(((Get-ChildItem $rt -Recurse -File | Measure-Object Length -Sum).Sum)/1MB,1)
Write-Host "slim runtime: $rtMB MB"

# --- 2. Build the installer ---
# Output name carries a timestamp so a previously built installer that is still open or being scanned
# cannot lock the output file (Inno aborts with "output file in use" otherwise).
$distDir = Join-Path (Get-Location) 'build\dist'
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
$base = "WoomSetup-" + (Get-Date -Format 'yyyyMMdd-HHmm')
Write-Host "`n== Inno Setup: $base.exe =="
& $iscc "/DAppDir=$appDir" "/O$distDir" "/F$base" (Join-Path (Get-Location) 'windows\woom.iss')
if ($LASTEXITCODE -ne 0) { throw "Inno Setup failed ($LASTEXITCODE)" }

$setup = Join-Path $distDir "$base.exe"
Write-Host "`nDONE -> $setup"
if (Test-Path $setup) { Get-Item $setup | Select-Object FullName, @{n='MB';e={[math]::Round($_.Length/1MB,1)}} | Format-List }
