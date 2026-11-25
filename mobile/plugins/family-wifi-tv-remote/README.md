# Family Wi-Fi TV Remote plugin

Project-local Capacitor plugin for the Family TV Remote mobile app.

It implements only the phone-side Android TV Remote v2 features used by this project:

- local-network TV discovery by friendly name
- certificate-based PIN pairing
- Remote v2 key commands and state updates
- volume and power state
- Android TV keyboard text entry
- Sony BRAVIA HDMI 1, YouTube, and Hotstar app links

The TV companion and JioSTB transports are intentionally outside this plugin. They are implemented by this repository's `tv-companion` app and `mobile/src/remote.ts`.

## Local dependency

`mobile/package.json` references this directory directly:

```json
"capacitor-codeplay-wifi-tv-remote": "file:plugins/family-wifi-tv-remote"
```

No package download is required to build this plugin.

## App-link commands

The project-owned commands send Android TV Remote v2 app links for YouTube, Hotstar, and Sony HDMI 1. The HDMI command uses:

```text
content://android.media.tv/passthrough/com.mediatek.tis%2F.HdmiInputService%2FHW2
```

## Provenance

Originally based on the MIT-licensed `capacitor-codeplay-wifi-tv-remote` package. This vendored copy is maintained locally for the Family TV Remote project and carries project-specific changes.
