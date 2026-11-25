# Family TV Remote

A direct local-network remote for the Sony BRAVIA/Android TV. The primary app is a single Capacitor/React codebase that builds for both iPhone and Android.

There is no AI, cloud service, or Mac dependency while using the installed phone apps. A Mac/Xcode is required only to build and sign the iPhone app.

## Mobile app

Source: [`mobile/`](mobile/)

Features:

- Android TV Remote v2 discovery and PIN pairing
- D-pad, home/back, power, volume, channel and playback controls
- TV search and keyboard text entry
- 20 built-in JioTV+ channel shortcuts across sports, Hindi news, and entertainment
- One-tap YouTube and Hotstar TV app shortcuts
- A local Android TV companion for authenticated JioSTB UDP control
- iOS 15+ and Android support

### Build

Generate a private token and configure the same value in both apps. These files are ignored by Git:

```bash
TOKEN="$(openssl rand -hex 32)"
printf 'VITE_FAMILY_REMOTE_TOKEN=%s\n' "$TOKEN" > mobile/.env.local
printf 'FAMILY_REMOTE_TOKEN=%s\n' "$TOKEN" > tv-companion/local.properties
```

Then build the mobile app:

```bash
cd mobile
npm install
npm run native:sync
```

Android debug APK:

```bash
npm run android:debug
```

Output: `mobile/android/app/build/outputs/apk/debug/app-debug.apk`

Open and sign the iPhone project:

```bash
npm run ios:open
```

Choose an Apple development team and your connected iPhone in Xcode, then press Run.

## Channel shortcuts

The phone app ships with 20 curated JioTV+ channels using catalogue content IDs and channel numbers verified against Jio's public channel-search guide. See [`docs/jio-channel-catalogue.md`](docs/jio-channel-catalogue.md) and run `./scripts/verify-jio-channel-guide.py` to recheck the static catalogue during development.

A shortcut asks the local [`tv-companion/`](tv-companion/) app to control the authenticated HDMI-connected JioSTB. Numeric tuning uses the same Android digit keycodes as JioHome and waits for JioTV+'s auto-tune timeout. Command transmission is implemented; each channel still requires playback validation before the shortcuts are considered complete.

Build and install the current companion from `tv-companion/app/build/outputs/apk/debug/app-debug.apk`, then open it once on the Sony TV. It starts automatically after future TV restarts. The companion rejects authenticated commands when no private token is configured. Generated APKs and repository-level `artifacts/` are intentionally excluded from source control.

## Jio STB protocol work

The recovered local UDP protocol and its current validation status are documented in [`docs/jio-udp-protocol.md`](docs/jio-udp-protocol.md). [`scripts/jio-udp-client.py`](scripts/jio-udp-client.py) is the minimal pairing/control probe used during development.

## Earlier CLI prototype

The root-level Node CLI was used to validate TV pairing and Remote v2 commands. The mobile app does not depend on it. Copy `config.example.json` to the ignored `config.json` before using the CLI.
