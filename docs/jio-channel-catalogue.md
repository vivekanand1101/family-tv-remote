# JioTV+ channel catalogue

The app keeps 20 family favourites prominent and also bundles the complete
1,102-entry channel snapshot used by its collapsed local search. Both are sourced
from the public channel-search data used by Jio's own JioHome website.

- Guide API: `https://www.jio.com/jcms-api/master-lookups?filters%5Blabel%5D%5B%24eq%5D=AIRFIBER_CHANNELS`
- Guide label: `AIRFIBER_CHANNELS`
- Guide `updatedAt` during verification: `2026-07-29T07:11:04.328Z`
- Total guide entries during verification: 1,102
- Development check: `./scripts/verify-jio-channel-guide.py`

The app does **not** call this API at runtime. The generated catalogue is
committed as `mobile/src/jioChannels.generated.ts`, so searching and tuning have
no cloud dependency. Refresh it explicitly with:

```bash
./scripts/update-jio-channel-catalogue.py
```

The snapshot stores stable keys, friendly names, channel numbers, category,
language, quality, vendor, premium status, and a content ID when Jio's image URL
contains one. In the current guide, 920 entries have an extractable content ID;
182 remain searchable by name and channel number. Channel `1063` is duplicated
by Jio for `Sadhna TV` and `Vedant Ras`, so the generated key also includes the
name/content ID rather than assuming channel numbers are unique. The curated
development check compares both channel number and content ID.

Local search ranks exact names and exact numbers first, then prefixes and token
matches. It recognizes plain channel numbers and content IDs as well as explicit
`ch:<number>` and `id:<content-id>` forms. Results are rendered in pages of 40 but
all matching is performed locally over the complete snapshot.

| Group | App name | Channel | Content ID | Playback validation |
|---|---|---:|---:|---|
| Sports | Sony Sports Ten 1 HD | 470 | 300256 | Confirmed |
| Sports | Sony Sports Ten 2 HD | 473 | 300273 | Confirmed |
| Sports | Sony Sports Ten 5 HD | 483 | 300258 | Confirmed |
| Sports | Star Sports 1 HD | 454 | 301981 | Confirmed |
| Sports | Star Sports 2 HD | 456 | 301983 | Confirmed |
| Sports | Star Sports Select 1 HD | 463 | 302113 | Confirmed |
| Hindi News | Aaj Tak | 509 | 301036 | Confirmed |
| Hindi News | ABP News | 504 | 300001 | Confirmed |
| Hindi News | News18 India | 519 | 300209 | Confirmed |
| Hindi News | India TV | 514 | 300007 | Confirmed |
| Hindi News | Zee News | 511 | 301343 | Confirmed |
| Hindi News | NDTV India | 506 | 301049 | Confirmed |
| Hindi News | Republic Bharat | 1152 | 301371 | Confirmed |
| Hindi News | News18 Bihar Jharkhand | 1129 | 300284 | Pending |
| Entertainment | &TV HD | 137 | 300377 | Pending |
| Entertainment | Zee TV HD | 141 | 300378 | Confirmed |
| Entertainment | Zee Cinema HD | 319 | 300330 | Pending |
| Entertainment | &Pictures HD | 331 | 300328 | Pending |
| Entertainment | Sony Entertainment Television HD | 128 | 300266 | Pending |
| Entertainment | Star Plus HD | 115 | 301998 | Pending |

## Channel artwork

The generator downloads the current `image` artwork returned by Jio's public guide for all 1,102 channels into `mobile/public/channel-logos/all/`. The current snapshot contains every guide logo and adds about 31.9 MiB of uncompressed artwork. Images ship inside the app and are never downloaded at runtime. Initials remain available as a fallback if a bundled image cannot be loaded. The original curated artwork remains under `mobile/public/channel-logos/` for the larger favourite cards.

## Numeric tuning behavior

The companion accepts validated positive channel numbers from `1` through
`9999`, covering the current guide range of `115` through `9928`. This replaces
the old curated-only allowlist without exposing arbitrary keycodes or commands.

The current JioHome number-pad implementation sends Android digit keycodes only:

- `0` through `9` map to Android keycodes `7` through `16`.
- It does not send Enter or D-pad center after the digits.
- JioTV+ performs the tune after its numeric-entry timeout.

The companion reads the JioSTB screen state from its local beacon before tuning.
Every channel shortcut resets the JioSTB through Home and then Live TV before
sending the digits. When the box is already awake it begins with the harmless
Android wake key. When the beacon confirms that HDMI output is asleep, it sends
the physical-remote power key and waits for the screen-on beacon first. The
global Home key escapes support chats, keyboards, settings, apps, and stale
dialogs that can consume a direct Live TV command. After a two-second Home settle
and an eight-second Live TV cold-start window, the companion sends digits at
400 ms intervals and waits for the JioTV+ auto-tune timeout. These conservative
timings prevent the first digit from being consumed while Live TV is still
loading. A successful HTTP response still means
only that the command sequence was transmitted; actual playback remains subject
to end-to-end visual validation.
