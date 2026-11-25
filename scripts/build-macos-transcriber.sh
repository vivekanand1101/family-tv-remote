#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
app="bin/Audio TV Remote Transcriber.app"
executable="$app/Contents/MacOS/macos-transcribe"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS"
cp native/Info.plist "$app/Contents/Info.plist"
swiftc native/macos-transcribe.swift \
  -o "$executable" \
  -framework Speech
codesign --force --deep --sign - --identifier dev.vivekanand.audio-tv-remote.transcribe "$app" >/dev/null
printf 'Built %s\n' "$PWD/$app"
