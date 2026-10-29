#!/bin/sh
# Builds the portable Windows version (build/windows/Woom-windows.zip) on Linux.
# Needs: a Windows JDK 21 of exactly the same version as the local one (WIN_JDK=<unpacked folder>),
# mingw-w64 for Woom.exe, zip.
set -eu
cd "$(dirname "$0")"
: "${WIN_JDK:?set WIN_JDK to an unpacked Windows x64 JDK 21}"
JDK_HOME="${JDK_HOME:-$HOME/.jdks/jdk-21.0.12.1+1}"
OUT=build/windows/Woom
rm -rf build/windows
mkdir -p "$OUT/app"

# Jars with the Windows graphics libraries, shrunk like the Linux release.
./gradlew -q -Pwindows proguardReleaseJars
cp build/compose/tmp/main-release/proguard/*.jar "$OUT/app/"

# Java runtime with only the modules Woom needs.
"$JDK_HOME/bin/jlink" --module-path "$WIN_JDK/jmods" \
  --add-modules java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.instrument,java.logging,java.security.sasl,java.naming,jdk.crypto.ec,jdk.unsupported \
  --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$OUT/runtime"

# Launcher with icon.
x86_64-w64-mingw32-windres windows/woom.rc -O coff -o build/windows/woom.res
x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o "$OUT/Woom.exe" windows/launcher.c build/windows/woom.res

(cd build/windows && zip -qr Woom-windows.zip Woom)
ls -la build/windows/Woom-windows.zip
