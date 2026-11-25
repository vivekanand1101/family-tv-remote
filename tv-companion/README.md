# Family TV Companion

A minimal modern Android TV app that provides reliable Sony wake, audio-state preservation, local JioSTB control, and safe installed-app discovery for Family TV Remote. Before app-initiated standby, the phone asks the companion to remember the current audio state; wake restores that exact mute and volume state. For channel shortcuts, the companion reads the Jio beacon's screen state and uses the physical power key only when HDMI output is confirmed asleep. Every validated one-to-four-digit channel request then sends the global Home key followed by Live TV before the digits, making favourites and full-catalogue results independent of whether either physical remote left the box in a support chat, keyboard, Wi-Fi/settings, another app, or a stale dialog.

## Security and scope

- Listens only on the local network on TCP port `6470`.
- Requires a private token supplied at build time and shared only with the family phone app.
- Discovers JioSTB beacons on UDP `5775` and sends the validated local protocol through UDP `4445`.
- Persists the Jio pairing identity privately in app storage.
- Accepts only positive one-to-four-digit channel numbers; it exposes no arbitrary Jio command endpoint.
- Lists MAIN/LEANBACK launcher activities dynamically, excluding Sony/MediaTek internals and the companion itself.
- Launches only an app identifier present in the current discovered launcher list.
- No Bluetooth permissions or pairing dialogs.
- Uses a short-lived wake lock and transparent activity only when Wake is requested.
- Requires the Android `SYSTEM_ALERT_WINDOW` app-op so Sony's background-activity restrictions do not block that transparent wake activity; the companion never draws an overlay.
- No cloud connection, analytics, account, microphone, or AI.

## Build

Configure the same random token used in `mobile/.env.local`. The local properties file is ignored by Git:

```bash
printf 'FAMILY_REMOTE_TOKEN=%s\n' "$(openssl rand -hex 32)" > local.properties
JAVA_HOME=/path/to/jdk-21-or-newer ./gradlew assembleDebug
```

If the token is absent, the companion builds but rejects all authenticated commands.

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install it once on the Sony Android TV, grant its wake app-op, and open **Family TV Companion**:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.vivekanand.familytvcompanion SYSTEM_ALERT_WINDOW allow
```

Its foreground service starts from Sony's earliest available direct-boot broadcast (`LOCKED_BOOT_COMPLETED`), with normal boot and package-update fallbacks. On the tested BRAVIA it is available about 39 seconds after a cold boot instead of waiting roughly three minutes for the normal boot broadcast. Pairing and audio preferences are copied into device-protected storage so Jio control is ready before Android finishes normal user unlock. The installed package is also exempted from TV idle restrictions. The app targets the current Android SDK and does not require the legacy JioHome APK.
