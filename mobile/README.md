# Family TV Remote

Cross-platform iOS and Android remote for the Sony/Android TV. The app connects directly over the local Wi-Fi network using Android TV Remote v2. There is no AI, cloud service, or Mac dependency at runtime.

## Features

- TV discovery, friendly-name selection, and PIN pairing
- One familiar Power button for both standby and turn-on, plus prominent mute and press-and-hold volume controls
- Always-visible D-pad and Home/Back controls that automatically control the active Sony app or Jio box
- Collapsed playback, search, HDMI, and text controls
- 20 prominent JioTV+ favourites with locally bundled artwork
- A collapsed, searchable offline catalogue of all 1,102 Jio channels by name, number, or content ID, with locally bundled artwork
- One-tap YouTube and Hotstar shortcuts plus a searchable launcher for genuine installed Sony TV apps
- Browser preview mode for UI development

## Development

Create an ignored `.env.local` with the same private token used by the TV companion:

```bash
cp .env.example .env.local
# Replace the example value with: openssl rand -hex 32
npm install
npm run dev
npm run build
```

Companion-backed controls fail closed when `VITE_FAMILY_REMOTE_TOKEN` is missing.

## Native projects

```bash
npm run build
npx cap add ios
npx cap add android
npx cap sync
```

The iOS app needs these `Info.plist` entries:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Find and control your TV on your local Wi-Fi network.</string>
<key>NSBonjourServices</key>
<array>
  <string>_androidtvremote2._tcp</string>
</array>
```

Each phone discovers and pairs with the TV independently. Discovered TV names are cached for 24 hours and shown without IP addresses. The app connects only after the user taps a TV; pairing credentials are reused automatically.

## Connectivity and recovery

Internet access is not required for remote-control commands. A phone connected to the same home Wi-Fi as the TV continues to control it even when that Wi-Fi has no internet. JioTV+, YouTube, Hotstar, and other streaming playback still need their own internet connection and may show an error on the TV. If Wi-Fi and mobile data are both active, local TV traffic still uses Wi-Fi.

When only mobile data is available, the app stops before discovery or a long-running workflow and shows **Connect to home Wi-Fi**. Android offers an **Open Wi-Fi** button that opens the system Wi-Fi chooser. iOS explains the public, App Store-safe path through **Settings → Wi-Fi**. Neither platform lets an app silently switch to an arbitrary network previously saved by the user.

If Wi-Fi is present but the device cannot be reached, the app distinguishes between the TV, TV companion, and Jio box. Operations show a visible spinner and current step, finish on a bounded timeout, and replace the loader with a specific recovery card and **Retry** action. After a TV cold boot, the app probes the companion every two seconds for up to 90 seconds and enables Jio shortcuts automatically as soon as it starts. A channel tap also waits up to 60 seconds for the companion instead of failing during startup. No IP address is shown to the user.

## JioTV+ channel shortcuts

The app keeps 20 curated shortcuts prominent, sourced from JioTV+'s current channel catalogue:

- 6 Sony/JioHotstar sports channels
- 8 Hindi news channels
- 6 general entertainment channels

A collapsed **All channels & TV apps** section below the remote bundles a generated snapshot of all 1,102 guide channels and all 1,102 available channel logos. Search is entirely local and accepts channel names, partial names, channel numbers, `ch:<number>`, content IDs, and `id:<content-id>`. Results render progressively and use exactly the same reliable tuning workflow as favourites. Run `../scripts/update-jio-channel-catalogue.py` during development to refresh the committed snapshot; the installed app never contacts Jio's guide API.

The TV-app tab asks the authenticated local companion for activities that are genuinely launchable from the Sony TV, including each app's locally extracted icon. It filters Sony/MediaTek system internals and the companion itself and displays friendly names only. Package identifiers remain internal and launching is restricted to the currently discovered list.

A channel shortcut wakes the Sony TV, selects HDMI 1, and asks the local Family TV Companion to wake the JioSTB when its beacon reports that HDMI output is asleep. Every channel request then sends the global Jio Home key, waits two seconds, opens Live TV, waits eight seconds for cold-start loading, and sends digits at human-like 400 ms spacing. This avoids losing the first digit while JioTV+ is still loading and can escape support chats, keyboards, Wi-Fi/settings, another app, or a stale dialog. JioTV+ auto-tunes after its numeric-entry timeout; no Enter or D-pad center key is sent. The mobile request allows 45 seconds for sleeping-box recovery and the longer reliable sequence. Before app-initiated standby, the companion remembers mute and volume so the next Power press can restore them exactly. Input, channel, and app shortcuts also preserve the current audio state.

The dedicated Mute button is a true toggle: muting remembers the current volume, sets volume 0, shows Sony's native on-screen audio indicator, and displays a persistent **Muted** state in the phone app; pressing it again unmutes and restores the previous volume. The YouTube and Hotstar shortcuts use phone-side Android TV Remote v2 app links and automatically route navigation to Sony TV; HDMI and channel shortcuts route navigation back to the Jio box. Install and open the companion APK once on the TV; it starts automatically after future TV restarts.
