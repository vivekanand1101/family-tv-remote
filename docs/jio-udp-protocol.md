# Recovered Jio STB UDP protocol

This documents the local protocol implemented by the decrypted RTI **Jio Set Top Box** driver and the older official JioHome app. It was validated successfully against the local JMSD200A on 7 November 2026.

## Transport and identity

- STB control destination: UDP `4445`
- Controller control socket: UDP `4445`; pairing responses return to this source port
- Controller beacon socket: UDP `5775`
- Payloads: plain UTF-8/ASCII strings with no terminator
- Client ID: JioHome uses Android `net.hostname`, falling back to `Settings.Secure.ANDROID_ID`; the RTI driver uses its processor MAC without separators
- Persist after pairing: client ID, STB DNS name, address, and pair code

The controller must bind UDP `4445` before sending control or pairing packets. Binding only `5775` receives beacons but misses `PAIR` and `CONNECTED` replies.

## Pairing state machine

1. Wait for a beacon, which discovers the STB without exposing its address in the UI.
2. Controller sends:

   ```text
   CONNECT:<clientId>
   ```

3. Expected STB response:

   ```text
   PAIR:<dnsName>:<serverPairCode>
   ```

   The four-digit `serverPairCode` is displayed by the STB. The validated local session returned the same value that the controller then submitted.

4. User reads the four-digit code from the TV. Controller sends:

   ```text
   PAIRCODE:<clientId>:<fourDigitPin>
   ```

5. Expected success response:

   ```text
   CONNECTED:<dnsName>:<pairCode>
   ```

6. Persist `<dnsName>` and `<pairCode>` for subsequent commands.

Cancellation may arrive as `Cancelled:<dnsName>:` or `CANCELLED:...`. The RTI driver retries `CONNECT` two seconds later. It retries an unanswered initial connection after ten seconds.

## Beacons and status

The validated JMSD200A beacon was:

```text
beacon:RFLSBIJL6580419:0:false:0:1:SV=181048
```

After pairing, its paired flag changed from `0` to `1`:

```text
beacon:RFLSBIJL6580419:1:false:0:1:SV=181048
```

The final numeric field before `SV` reports the JioSTB screen/HDMI-output state on this model: `1` while output is awake and `0` while it is asleep. This was validated by toggling the box with Android keycode `26` and observing the TV's HDMI signal and subsequent beacon transition. Android `KEYCODE_WAKEUP` (`224`) did not restore HDMI output from the `0` state, so the companion uses the toggle-style physical power key only after a current beacon confirms that the box is asleep.

The driver expects beacons at least every 15 seconds once paired. It also searches arbitrary status datagrams for these tokens:

- `KEYBOARD:OPEN`
- `KEYBOARD:CLOSE` or `KEYBOARD:CLOSED`
- `SCREEN:OFF`
- `SCREEN:ON`

The exact status packet format was not implemented by the RTI author.

## Commands

Normal key:

```text
KEYCODE:<clientId>:<pairCode>:<androidKeycode>
```

Long press:

```text
KEYCODE:<clientId>:<pairCode>:<androidKeycode>:longpress
```

Text is sent one character per packet (the doubled separators are intentional):

```text
KEYINPUTTEXT::<clientId>::<pairCode>::<character>
```

The RTI driver follows text with Android Enter keycode `66`.

Unpair/cancel packet:

```text
CANCELLED
```

## Keycodes exposed by the driver

| Action | Android keycode |
| --- | ---: |
| Home | 3 |
| Back | 4 |
| D-pad up/down/left/right | 19 / 20 / 21 / 22 |
| D-pad center | 23 |
| Power toggle / sleep | 26 / 223 |
| Play-pause / stop | 85 / 86 |
| Next / previous | 87 / 88 |
| Rewind / fast-forward | 89 / 90 |
| Play / pause | 126 / 127 |
| Record | 130 |
| Info | 165 |
| Channel up/down | 166 / 167 |
| Live TV | 170 |
| Guide | 172 |
| Recordings | 173 |
| Red/green/yellow/blue | 183 / 184 / 185 / 186 |
| Digits 0-9 | 7-16 |

## Local probe

Use [`../scripts/jio-udp-client.py`](../scripts/jio-udp-client.py):

```bash
python3 scripts/jio-udp-client.py --host <discovered-stb-address> connect
python3 scripts/jio-udp-client.py --host <discovered-stb-address> pair 1234
python3 scripts/jio-udp-client.py --host <discovered-stb-address> key 3
```

The probe binds both required local ports: `4445` for pairing/control replies and `5775` for beacons. The final phone UI must continue to discover the STB and keep its address internal; the explicit host option is only for protocol development.

## Validated local session

The modern TV companion discovered the STB through its `5775` beacon, sent `CONNECT` from local UDP `4445`, received `PAIR`, submitted `PAIRCODE`, and received `CONNECTED`. A harmless Home command was then sent successfully:

```text
CONNECT:22fb53e82f8b6e25
PAIR:RFLSBIJL6580419:<pin>
PAIRCODE:22fb53e82f8b6e25:<pin>
CONNECTED:RFLSBIJL6580419:<pairCode>
KEYCODE:22fb53e82f8b6e25:<pairCode>:3
```

The persisted pair code is sensitive and should not be displayed by the production UI.
