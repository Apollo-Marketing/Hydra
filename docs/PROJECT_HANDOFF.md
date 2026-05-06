# Larq PureVis 2 Reverse-Engineering — Project Handoff

**Last updated:** 2026-05-03 (session ending ~23:30 MDT)

This document is a complete, self-contained handoff. A new Claude (or a new human) reading this with zero prior context should be able to resume work without redoing the discovery process. **Read this whole document before doing anything destructive.**

---

## 1. Who, what, why

### The user (&lt;user&gt;)
- **Email:** &lt;redacted&gt;
- **Not a developer.** Speaks in product/user terms. Has never built an Android app or worked with Bluetooth before this session. Avoid jargon; if you must use a technical term, explain it in plain English on first use.
- **Has the official Larq Android app installed** on the Pixel (`com.larq.live`).
- **Also uses the Larq app on iPhone**, which is where the screenshot of "Product details" came from.

### The hardware
- **Phone:** Google Pixel 10 Pro (codename `&lt;codename&gt;`), running Android 16 (API 36).
- **Bottle:** LARQ Bottle PureVis 2.
  - Software version: `1.047`
  - Hardware version: `101`
  - Larq's "UUID" (their per-device identifier shown in app settings): `&lt;bottle-uuid&gt;`
  - **BLE local name: `LARQ_&lt;bottle-id&gt;`** ← scan by this name, NOT MAC
  - The bottle uses **Resolvable Private Addresses (RPAs)** — its MAC rotates roughly every 15 minutes for privacy. We've seen multiple addresses across a single session. **Never hardcode a MAC.** Always scan by name and connect to whatever address is current.
- **Other BLE noise on this phone** (will appear in scans/snoop logs):
  - Garmin watch / Connect app — uses its own NUS-style protocol, syncs aggressively in background. Not the bottle.
  - "LE-Ts Beats" — Beats audio device.
  - Another LARQ bottle (`LARQ_<other-id>`) — belongs to someone else in the room. **Not the user's bottle.**

### The goal
The official Larq app is "absolutely garbage" (user's words). The user wants to build a better Android app that reads sip events from the bottle and presents intake data more usefully. **Scope so far is just decoding the protocol and getting data flowing**; UI/dashboard work comes later.

---

## 2. Environment & tools

### Tool paths (these are NOT on `$PATH` by default)
- **adb:** `~/Library/Android/sdk/platform-tools/adb`
- **tshark:** `/opt/homebrew/bin/tshark` (Wireshark 4.6.5, installed via `brew install wireshark`)
- **jadx:** `/opt/homebrew/bin/jadx` (1.5.5, installed via `brew install jadx` — pulled in OpenJDK 25 as a dependency)
- **Android Studio** is installed (the user uses it daily during this project).

### Claude Code settings
- The user's global settings file is `~/.claude/settings.json`. We modified it once:
  - **Removed `"Bash(rm:*)"` from the deny list** (line 19) so I could delete unused project files. The user explicitly approved this. The `permissions.deny` array is now empty `[]`. If you want to be safer in the future, scope `rm` denials to specific paths (e.g. `/`, `~`) instead of blanket-denying.
- Hooks: there's a PostToolUse formatter hook (prettier/biome on Edit/Write — irrelevant here) and a `protect-files.sh` PreToolUse hook (didn't fire for us).
- The Vercel plugin is enabled and **constantly false-fires** on this Android project (matches `app/**` to Next.js patterns, `sandbox` to Vercel Sandbox, etc.). Ignore all those skill-injection messages — none are relevant. The "knowledge update" reminders about Vercel CLI 53.1.0 are also irrelevant.

### Phone setup
- **USB debugging is on.** The Pixel shows up as `&lt;device-serial&gt;` in `adb devices`.
- **Bluetooth HCI snoop log is enabled** in Developer Options (this was turned on during session and may already be on if the user hasn't toggled it off).

---

## 3. Current state of the Android app

### Project location
`~/Documents/Projects/larq/`

### Key files (everything else is auto-generated boilerplate)
```
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/larq/MainActivity.kt
app/src/main/java/com/example/larq/ble/BottleConnection.kt   ← the BLE layer
app/src/main/java/com/example/larq/ui/BottleScreen.kt        ← the Compose UI
gradle/libs.versions.toml                                     ← dependency versions
app/build.gradle.kts                                          ← module build config
```

### Configuration
- **Package:** `com.hydra.app`
- **Min SDK:** 31 (Android 12) — chosen because BLE permissions changed at API 31; min 31 lets us use the modern permission model without legacy fallback code
- **Target SDK:** 36 (Android 16)
- **Kotlin:** 2.2.10
- **Jetpack Compose** (BOM 2026.02.01) for UI
- **No third-party BLE library** — we use the platform `BluetoothLeScanner` / `BluetoothGatt` APIs directly. Considered Nordic's Android-BLE-Library but didn't need it since our BLE code stays simple.

### What the app currently does
1. On launch, shows a "Status: Disconnected" screen.
2. User taps **"Find & Connect"** — app starts scanning by name (`LARQ_&lt;bottle-id&gt;`).
3. When the bottle is found, the app auto-connects to whatever current MAC the bottle is using.
4. After GATT service discovery, the app **subscribes to NUS TX notifications** (handle `0x0024` / UUID `6e400003-...`).
5. The app shows a **packet log** of all incoming notifications (hex + ASCII attempt + timestamp).
6. There's an **input field** to manually write hex bytes to the NUS RX characteristic, plus a "Probe" button (added by Gemini at some point, see `BottleConnection.probe()`) that sends 7 hardcoded blind-guess command bytes — **all confirmed useless**, the bottle responds to every blind write with the same 2-byte error `10 02`. Recommend removing.
7. There's a **device picker UI** when scanning for cases where multiple matching devices appear (the user has 2 LARQ bottles in the same room).

### What the app does NOT do yet
- Does not send the real Larq protocol commands (we now know what they are — see §6).
- Does not parse Google Protocol Buffer responses.
- Does not show sip events as typed/structured data.
- Does not persist anything to local storage.

### Active vs orphaned code
- `ble/BleScanner.kt` and `ui/ScanScreen.kt` were earlier scratch files. **Already deleted** (rm worked once we cleared the deny rule).
- All code currently in the tree is in active use.

---

## 4. The hardest-won knowledge in this project

### 4.1 The protocol layer

**Transport:** Bluetooth Low Energy → **Nordic UART Service (NUS)**.
- Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write characteristic ("RX" from bottle's perspective): `6e400002-b5a3-f393-e0a9-e50e24dcca9e` — this is what *we* write to to send commands. Properties: WRITE, WRITE_NR.
- Notify characteristic ("TX" from bottle's perspective): `6e400003-b5a3-f393-e0a9-e50e24dcca9e` — this is what we *subscribe to* to receive responses. Properties: NOTIFY.
- CCCD descriptor for enabling notifications: `00002902-0000-1000-8000-00805f9b34fb` (standard).
- **The official Larq app uses Write Request (ATT opcode `0x12`)** with response, NOT Write Command (`0x52`). Our code currently uses `0x52` via `WRITE_TYPE_NO_RESPONSE`. This may matter — try the with-response variant if commands fail.
- Handle numbers vary per connection. On the capture I analyzed, the bottle's NUS RX was handle `0x0022` and TX was `0x0024`. **Don't hardcode handles**; resolve them from the discovered service every time.

**Frame format on the wire:**
```
0x0d  <seq:4-byte little-endian uint32>  0x12  <protobuf-Any payload bytes...>
```
- The `0x0d` is a constant frame marker (header type? "data"?).
- The 4-byte LE sequence increments per command. The very first command in a session uses a non-zero seed (`0d cf 76 20 57` = seed 0x5720_76CF — purpose unknown, maybe a session nonce or persisted counter). Subsequent commands use `0d 01 00 00 00`, `0d 04 00 00 00`, `0d 05 00 00 00`, etc.
- After the header comes a literal `0x12` byte, which is a Google Protobuf wire-format tag for "field 2, length-delimited" — i.e., the payload starts as if it were the value of field 2 of an outer message. The payload itself is a serialized **`google.protobuf.Any`** message, which has the standard structure:
  ```
  field 1 (tag 0x0a): type_url string, e.g. "type.googleapis.com/RequestGetCapTofLog"
  field 2 (tag 0x12): the actual message bytes
  ```

**The vocabulary captured so far:**

Requests (app → bottle):
| Type URL                                               | Notes |
|--------------------------------------------------------|-------|
| `RequestSetCapTimeSettings`                            | First command of a session — sends current time as ms-since-epoch (varint). |
| `RequestSetCapHydroReminderSettings`                   | Configures hydration reminders. |
| `RequestGetCapUiState`                                 | Polled frequently (~every second). Returns trivial state enum. |
| `RequestGetCapDoNotDisturbSettings`                    | One-shot at startup. |
| `RequestGetCapLowBatterySettings`                      | One-shot at startup. |
| `RequestGetCapTofSettings`                             | TOF (Time-of-Flight) sensor settings. |
| `RequestGetCapHydroReminderSettings`                   | One-shot at startup. |
| `RequestGetCapUvConfig`                                | UV-C sterilization config. Polled frequently with UiState. |
| `RequestGetCapTofLog`                                  | **THE SIP HISTORY.** Single request returns multiple notifications, one per recent sip. |
| `RequestGetCapFaultLog`                                | Fault history. |
| `RequestGetCapActivationLog`                           | UV activation history. |
| `RequestGetChargingCapAdcLog`                          | Charging sensor log. |
| `RequestGetActivationCapAdcLog`                        | UV-activation sensor log. Polled repeatedly with incrementing offsets. |

Responses (bottle → app):
| Type URL                                  | Count in capture | Bytes | Notes |
|-------------------------------------------|------------------|-------|-------|
| `ResponseGetCapUiState`                   | 407              | 58    | Constant payload `1204080f1001` — i.e. `{f1: 15, f2: 1}`. UI state enum. |
| `ResponseGetCapUvConfig`                  | 405              | 76    | Polled in lockstep with UiState. |
| `ResponseGetActivationCapAdcLog`          | 27               | 101 / 140 | UV sensor data, paginated. |
| `ResponseGetCapTofLog`                    | **5**            | 78    | **Sip events.** Each entry contains one TofLog. |
| `ResponseGetCapHydroReminderSettings`     | 5                | 89    | |
| `ResponseGetCapTofSettings`               | 2                | 82    | TOF sensor calibration? Worth re-investigating. |
| `ResponseGetCapLowBatterySettings`        | 2                | 77    | |
| `ResponseGetCapDoNotDisturbSettings`      | 2                | 81    | |
| `ResponseGetCapActivationLog`             | 3                | 75/135 | |

**Common error response:** if the bottle doesn't recognize a write, it sends back two notifications:
1. An empty (0-byte) notification.
2. `10 02` — likely `{field 2, varint, value 2}` meaning "error code 2 / unknown command."

We confirmed this pattern by sending 7 different blind-guess commands; all returned `10 02`. So **never expect to brute-force-guess the protocol**.

### 4.2 The TofLog message structure (what a sip looks like)

Outer wrapper (the protobuf `Any.value`):
```
0a 17  <inner-bytes>
```
where `0a` = field 1 length-delimited, `17` = 23 bytes follow.

Inner TofLog message fields (mapped from decompiled `Fc.g0`):
| Proto # | Wire type | Java field | Meaning                         | Example |
|---------|-----------|------------|---------------------------------|---------|
| 1       | varint    | `f5627e`   | `timestamp` — Unix epoch SECONDS (use `Instant.ofEpochSecond`) | `08 9a cf e0 cf 06` = 1777870746 |
| 2       | varint    | `f5623a`   | `triggerType` — `CapEnumTofTriggerType` enum  | `10 04` = 4 |
| 3       | fixed32   | `f5624b`   | **`distanceInMillimeter`** — int. **THE POLYNOMIAL INPUT.** | `1d 46 00 00 00` = 70 |
| 4       | fixed32   | `f5625c`   | `kcps` — int (sensor noise/photon-count metric) | `25 2e 00 00 00` = 46 |
| 5       | fixed32   | `f5626d`   | `uvLedTempInOhm` — IEEE-754 float (UV LED temperature reading) | `2d 56 ca 24 46` = 10546.57 |

**Important nuance:** `distanceInMillimeter` increases as the bottle empties. The TOF sensor sits at the top of the bottle and measures down to the water surface, so a fuller bottle → shorter distance.

### 4.3 The 5 actual sip events captured (raw and decoded)

These are the only ground-truth sip records we have. The user took these 4 sips manually and saw the volumes in the official Larq app:
- 67 mL (timing not reported, before snoop window started)
- 100 mL at 22:59
- 79 mL at 23:00
- 35 mL at 23:00

The bottle's `ResponseGetCapTofLog` returned 5 entries (the 4 above + 1 the user didn't notice taking, possibly an auto-detected sip or the original 67mL):

| # | Frame | Timestamp           | distance | kcps | uvLedTemp  | User-reported mL |
|---|-------|---------------------|----------|------|------------|------------------|
| 1 | 490   | 22:57:23            | 58       | 1336 | 11197.95   | (probably 67mL)  |
| 2 | 828   | 22:59:06            | 70       | 46   | 10546.57   | 100              |
| 3 | 961   | 22:59:44            | 88       | 89   | 10916.56   | (unreported)     |
| 4 | 1076  | 23:00:19            | 103      | 122  | 11197.95   | 79               |
| 5 | 1097  | 23:00:26            | 110      | 302  | 11178.73   | 35               |

Raw bytes for entry 2 (the 100mL sip), as a worked example:
```
hex: 0da401000010011a450a28747970652e676f6f676c65617069732e636f6d2f526573706f6e7365476574436170546f664c6f6712190a17089acfe0cf0610041d46000000252e0000002d56ca2446
       ^^header        ^^   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^   ^^entry list ^^TofLog inner

  0d a4 01 00 00      = frame header, seq 0x000001a4 = 420
  10 01               = field 2, varint 1 (some response status)
  1a 45               = field 3, length 0x45 = 69 (the Any payload)
  0a 28               = field 1, length 0x28 = 40 (type URL)
  "type.googleapis.com/ResponseGetCapTofLog"
  12 19               = field 2, length 0x19 = 25 (the actual ResponseGetCapTofLog message)
  0a 17               = field 1, length 0x17 = 23 (one TofLog entry)
  08 9a cf e0 cf 06   = timestamp varint = 1777870746 = 22:59:06 May 3 2026 UTC-6
  10 04               = triggerType = 4
  1d 46 00 00 00      = distanceInMillimeter = 70
  25 2e 00 00 00      = kcps = 46
  2d 56 ca 24 46      = uvLedTempInOhm = 10546.57f
```

### 4.4 The volume calculation algorithm (what the official app does)

From decompiling `nc.C4568a.a()` (file `captures/larq-decompiled/sources/Nc/C4568a.java` after running with `--show-bad-code` — see §5):

```
Inputs:
  d  = distanceInMillimeter (from current TofLog entry)
  prevRaw       = previous raw distance reading
  prevSmoothed  = previous smoothed/clamped volume in mL
  prevAccum     = running cumulative intake in mL
  dArr          = 5-element double[] of polynomial coefficients (per-bottle calibration)
  drinkThresholdInMl     (config)
  fillThresholdInMl      (config)
  minVolumeLimitInMl     (config)

Algorithm:
  rawVolumeMl = a4*d^4 + a3*d^3 + a2*d^2 + a1*d + a0
              = dArr[0]*d^4 + dArr[1]*d^3 + dArr[2]*d^2 + dArr[3]*d + dArr[4]
  volumeMl    = max(rawVolumeMl, minVolumeLimitInMl)
  delta       = prevSmoothed - volumeMl

  if (volumeMl - prevRaw) > fillThresholdInMl:
      event = "fill"
      sipMl = max(0, prevSmoothed - prevRaw) if some flag else 0
  else if delta > drinkThresholdInMl:
      event = "drink"  (i.e. SIP)
      sipMl = delta
  else:
      event = "skip"   (noise / too small to count)
      sipMl = 0

  if sipMl > drinkThresholdInMl:
      record sipMl as an intake event in mL
```

Notes:
- The dArr has 5 elements. If it's null or wrong length, the fallback is `volumeMl = 0`, which will short-circuit the algorithm. So calibration is required.
- `triggerType` (TofLog field 2) is consulted to pick between two thresholds (`i10` vs `i11`). One of those is for "auto-triggered" sips, the other for "user-button" sips, probably.
- The algorithm is in a Kotlin coroutine so the decompile is gnarly. **`jadx` cannot fully recover the method body** — it dumps the smali but skips the actual logic. The reading above is from `--show-bad-code --comments-level debug` output which reconstructs partially. Treat it as the best available approximation.

### 4.5 What we DON'T have (the still-missing pieces)

1. **The 5 polynomial coefficients (`dArr`) for PureVis 2.** These are passed all the way down from `BottleVolumeSubscriptionKt$execute` → `o.b()` → eventually `C4568a.a()`. We could not find them as hardcoded constants in the APK. Strongly suspected to be **fetched from Larq's cloud API** when the bottle is paired (see `cloudBottleService` in `AlgoParams.toString()`). Confirming this would require a man-in-the-middle proxy capture of the official app's HTTPS traffic.

2. **The 3 thresholds** (`drinkThresholdInMl`, `fillThresholdInMl`, `minVolumeLimitInMl`). Same situation — from the cloud config.

3. **The handshake byte sequence we need to send on connect.** We know what messages the official app sends, but I never enumerated the exact handshake order or constructed the framed bytes. This is straightforward to do from the captured `tshark` data — see §5.2 for the captures already on disk.

4. **The exact protobuf schema** for any message. We've inferred the TofLog schema from the decompiled `Fc.g0` Java class. Other messages would need similar inference. The full schema is embedded in the APK as compiled protobuf descriptors but we haven't extracted them.

5. **What `triggerType` enum values mean.** We saw values 2, 3, 4 in TofLog entries. The enum is `CapBle.CapEnumTofTriggerType` in the protobuf-generated class — find the enum definition in the decompiled tree.

---

## 5. Captured data and decompiled APK on disk

All in `~/Documents/Projects/larq/captures/`:

```
larq-capture.zip          40 MB   Full adb bugreport from Pixel during snoop capture.
                                  Snoop log lives at FS/data/misc/bluetooth/logs/btsnoop_hci.log inside.
btsnoop_hci.log          783 KB   Extracted Bluetooth HCI snoop log. THIS IS THE KEY ARTIFACT.
                                  Capture window: 22:54:50 to 23:03:06 on 2026-05-03 (8 minutes).
                                  Includes 4 user-taken sips at 22:59, 23:00, 23:00 plus one earlier.
                                  12,261 total HCI frames; bottle's connection is handle 0x0043.
larq-base.apk             31 MB   The official Larq Android app, pulled from /data/app/...
                                  Package: com.larq.live.
larq-decompiled/                  jadx output of larq-base.apk (--no-res, no resources).
                                  Heavily obfuscated by R8 — class names like y2, v6, Pc.
                                  Kotlin metadata still references original FQNs as strings.
```

### 5.1 Useful `tshark` invocations (verified working against `btsnoop_hci.log`)

Quick orientation — total ATT activity:
```bash
tshark -r captures/btsnoop_hci.log -Y "btatt.opcode" \
  -T fields -e frame.number -e frame.time_relative -e btatt.opcode -e btatt.handle -e btatt.value \
  | head -50
```

Identify the bottle's connection by mapping handles to BD addresses:
```bash
tshark -r captures/btsnoop_hci.log \
  -Y "bthci_evt.le_meta_subevent == 0x01 || bthci_evt.le_meta_subevent == 0x0a" \
  -T fields -e frame.number -e frame.time -e bthci_evt.connection_handle -e bthci_evt.bd_addr
```
For our capture, the bottle's connection handle was `0x0043`. Always re-derive — handles are per-session.

Find LARQ devices by name in advertisement reports:
```bash
tshark -r captures/btsnoop_hci.log -Y 'btcommon.eir_ad.entry.device_name' \
  -T fields -e frame.number -e btcommon.eir_ad.entry.device_name | sort -u
```

Filter ATT writes & notifications to JUST the bottle's connection:
```bash
tshark -r captures/btsnoop_hci.log \
  -Y "bthci_acl.chandle == 0x0043 && (btatt.opcode == 0x12 || btatt.opcode == 0x1b)" \
  -T fields -e frame.number -e frame.time -e btatt.opcode -e btatt.handle -e btatt.value
```

Get raw NUS payloads (the field is `btgatt.nordic.uart_tx` for app→bottle writes, `btgatt.nordic.uart_rx` for bottle→app notifications):
```bash
# All writes from the app
tshark -r captures/btsnoop_hci.log \
  -Y "bthci_acl.chandle == 0x0043 && btatt.opcode == 0x12 && btatt.handle == 0x0022" \
  -T pdml | grep -oE 'btgatt.nordic.uart_tx" [^>]*value="[^"]*"' | sed 's/.*value="//;s/"//'

# All notifications from the bottle
tshark -r captures/btsnoop_hci.log \
  -Y "bthci_acl.chandle == 0x0043 && btatt.opcode == 0x1b && btatt.handle == 0x0024" \
  -T pdml | grep -oE 'btgatt.nordic.uart_rx" [^>]*value="[^"]*"' | sed 's/.*value="//;s/"//'
```
The `value=...` attribute is the raw hex bytes. Pipe to `xxd -r -p` for binary, or to a Python `bytes.fromhex` for parsing.

Find type URLs in payloads:
```bash
# In writes (Python because grep on binary bytes is fragile)
python3 -c "
import re
with open('rx.hex') as f:  # one hex line per packet
    for line in f:
        b = bytes.fromhex(line.strip())
        for m in re.finditer(r'type\.googleapis\.com/(\w+)', b.decode('latin-1', errors='replace')):
            print(m.group(1))
" | sort | uniq -c | sort -rn
```

### 5.2 Useful `adb` invocations
```bash
# Devices visible
~/Library/Android/sdk/platform-tools/adb devices

# Live logcat from our app
~/Library/Android/sdk/platform-tools/adb logcat -d -s HydraBle:V BluetoothGatt:V

# Clear logcat buffer before a fresh capture run
~/Library/Android/sdk/platform-tools/adb logcat -c

# Full bugreport (includes the BT snoop log) — slow, ~1-2 min, large file
~/Library/Android/sdk/platform-tools/adb bugreport <out.zip>

# Find official Larq app's APK paths
~/Library/Android/sdk/platform-tools/adb shell pm path com.larq.live

# Pull base.apk
~/Library/Android/sdk/platform-tools/adb pull '/data/app/.../com.larq.live-.../base.apk' larq-base.apk
```

### 5.3 Useful `jadx` invocations
```bash
# Decompile entire APK (no resources, faster)
jadx -d <out-dir> --no-res --threads-count 8 larq-base.apk

# Re-decompile a single class with bad-code rendering (when default fails)
# This is essential for the volume algorithm — default decompile produces "Method not decompiled"
jadx --show-bad-code --comments-level debug -d <out-dir> --single-class nc.a larq-base.apk
```

### 5.4 Where things live in the decompiled tree
The R8 obfuscation rewrote class names. **Use Kotlin metadata strings** (in `@Ad.f(c = "...")` annotations) to find the original Kotlin source file. Examples:

| Original Kotlin                                         | Obfuscated location |
|---------------------------------------------------------|---------------------|
| `BottleVolumeSubscription.kt` (top-level)               | `sources/N3/` (multiple lowercase files: a-p) |
| `BottleVolumeIntakeCalc.subscription`                   | `sources/N3/c.java`, `e.java`, `g.java`, `h.java` (coroutine inner classes) |
| `BottleVolumeSubscriptionKt.algo`                       | `sources/N3/k.java` |
| `BottleVolumeSubscriptionKt.execute`                    | `sources/N3/o.java` (the `o.a()` and `o.b()` methods) |
| `BottleVolumeSubscriptionKt.deviceSoftwareVersion`      | `sources/N3/l.java` |
| `BottleVolumeAlgo` (the actual sip-detect & convert)    | `sources/Nc/C4568a.java` (the `a(C4571d, int, int, double[])` method) |
| `AlgoParams` data class                                 | `sources/N3/a.java` |
| `TofLog` data class                                     | `sources/Fc/g0.java` |
| `LarqBottleElpServiceV3`                                | `sources/Yb/c.java` |

**Tip for the next session:** if you need to find any particular class, grep the decompiled tree for the original Kotlin FQN — Kotlin metadata preserves it in annotation strings even when the class itself is renamed:
```bash
grep -rln 'api.subscriptions.device.bottle' captures/larq-decompiled/sources/
```

**Filesystem case-sensitivity warning:** macOS APFS by default is case-insensitive but case-preserving. jadx output contains BOTH `C4471a.java` and `a.java` (different files!) in the same directory. `ls` shows uppercase by default; use `ls | grep -E '\b[a-p]\.java'` or read each explicitly.

---

## 6. Concrete next steps

These are ordered roughly by effort and recommended order. The user is **not a developer** — explain everything in plain English. Don't say "Tier 1/2/3."

### Step A — Implement the protocol layer (no volume yet)
This is the obvious win. We have everything needed except the volume formula. Build it, get sip events flowing, ship something working.

Concrete work:
1. **Switch our writes from `0x52` (no-response) to `0x12` (with-response)** in `BottleConnection.writeRx()`. The official app uses `0x12`. May matter, may not.
2. **Build a `BottleProtocol.kt`** module that:
   - Wraps a serialized `google.protobuf.Any` in the framed format `0d <seq> 12 <bytes>`.
   - Has functions for each known request type (start with `RequestSetCapTimeSettings`, `RequestGetCapTofLog`).
   - Parses incoming notifications: strip the 5-byte header, parse the protobuf `Any`, dispatch on type URL.
   - Decodes a `TofLog` entry into a typed `SipEvent(timestampSec: Long, distanceMm: Int, kcps: Int, uvTempOhm: Float, triggerType: Int)`.
3. **For protobuf serialization in Kotlin:** add the `com.google.protobuf:protobuf-kotlin-lite` dependency, write minimal `.proto` files for the messages we use (we know enough for `TofLog`, `RequestGetCapTofLog`, `ResponseGetCapTofLog`), generate Kotlin classes. OR hand-roll the wire format — it's small. The hand-rolled approach avoids adding 1MB+ of protobuf runtime.
4. **In `BottleConnection.onServicesDiscovered()`**, after subscribing, send the handshake sequence the official app sends (replay the writes from the snoop log; need to re-derive these). Then send `RequestGetCapTofLog` to get sip history.
5. **Update `BottleScreen.kt`** to show a typed event log instead of raw hex: "10:42:13 — Sip detected, distance 70mm." Strip the dead `probe()` button and `connection.probe()` call.

### Step B — Get the volume formula
Three options, in order of effort:

1. **Empirical calibration on the user's bottle.** Have the user take 8-12 sips of known volume (using a kitchen measuring cup) and compare with the official Larq app's reported mL. Record `(distanceMm, mL)` pairs. Fit a 4th-degree polynomial in Python with `numpy.polyfit`. Hardcode the result. Bottle-specific (each unit slightly varies in geometry) but accurate enough. **~15 minutes of user effort.**

2. **MITM the official Larq app's HTTPS traffic.** Install `mitmproxy` on the Mac, configure the Pixel to use it as an HTTPS proxy, install the mitmproxy CA cert on the Pixel, run the official Larq app, capture all API traffic. The polynomial coefficients are almost certainly downloaded from Larq's cloud as part of bottle pairing config. ~30 min setup, no guarantee. **Requires more aggressive intervention on the user's phone (CA cert install).**

3. **Root the Pixel and dump app memory.** Way out of scope.

### Step C — Persistence and dashboard
- Add **Room** (SQLite wrapper) for storing sip events locally.
- Aggregate by day. Show today/week totals.
- Add a chart (Vico for Compose, or hand-rolled).
- Background sync: keep BLE alive in the background so sips are captured even when the app isn't open. **This is the hard part on Android** — requires foreground service + notification, BLE scanning permissions, battery-optimization exceptions. Plan for it but don't tackle it day 1.

### What NOT to do (lessons learned)
- **Don't blind-probe the bottle's protocol.** The error response `10 02` tells us nothing useful, and the search space is effectively infinite. We confirmed this is a dead end.
- **Don't hardcode a MAC address.** RPAs rotate every ~15 minutes. Always scan by name.
- **Don't try to read 40MB bugreports into the LLM context.** Use `tshark`/`grep` to filter on disk first.
- **Don't ignore which BLE device you're looking at.** The user has multiple BLE-active devices (Garmin, Beats, another LARQ bottle) and traffic is interleaved in HCI snoop logs. Always identify the bottle's specific connection handle / BD address before drawing conclusions.
- **Don't skip the bugreport extraction step.** On Android 14+, `/data/misc/bluetooth/logs/btsnoop_hci.log` is not directly `adb pull`-able without root. The bugreport bundles it.

---

## 7. Open questions worth investigating next session

1. **What does `ResponseGetCapTofSettings` contain?** It was returned twice in our capture (82 bytes each). Could it include the polynomial calibration coefficients? Worth decoding.
2. **What's the seed used in the first frame** (`0d cf 76 20 57`)? Is it a fixed magic, a session nonce, or persisted state?
3. **What is `triggerType`?** Values 2, 3, 4 observed. Find `CapBle$CapEnumTofTriggerType` in decompiled tree.
4. **Does the bottle require us to send `RequestSetCapTimeSettings` first** before it'll respond to other queries? (It might explain why we got `10 02` errors — we never set the clock.)
5. **What's the MTU negotiation?** The official app may negotiate a larger MTU. Default is 23 (20-byte payload). If we need to send messages larger than 20 bytes, we need to call `gatt.requestMtu(247)` first.

---

## 8. Other context worth knowing

- The user invoked `/plan` once mid-session and an approved plan exists at `~/.claude/plans/cozy-popping-muffin.md`. That plan covered Phases 1-4 (capture → decode → implement → verify) for the protocol; Phases 1-2 are now done, Phase 3 is the next concrete work.
- The user is running in **auto mode** (continuous, autonomous execution). Don't re-enter plan mode unless they explicitly ask. Do prefer action over more planning.
- The user's tone is patient but pragmatic. They explicitly asked for plain language after this session got too jargon-heavy. Match that.
- The user is paying for our session via Claude Code. Don't burn time. Status updates should be tight.

---

## 9. Releases (sideloaded distribution to a small group)

Hydra is not on the Play Store. Updates are distributed as signed APKs attached to GitHub Releases. Slices 2-5 of the `auto-updates` plan add an in-app updater that downloads the APK and prompts to install when a newer release exists.

### 9.1 Release keystore — the single most important file in this project

Every release APK is signed by a single keystore. **If this file is lost, no further updates can install over the existing app on any device** — only uninstall+reinstall, which wipes the local sip log. Back it up immediately.

- **File:** `~/keystores/hydra-release.jks`
- **Alias:** `hydra`
- **Password:** stored alongside the `.jks` in 1Password / iCloud Keychain (used for both keystore and key — same value)
- **Validity:** ~27 years (10,000 days from 2026-05-05)
- **SHA-256 fingerprint:** `8A:62:56:F4:7C:4F:76:19:54:21:B0:E4:7A:7D:8D:68:17:F1:FA:38:8E:EB:21:BA:D2:E0:CF:16:7D:18:33:CD`

Backup locations (do at least two):
- 1Password / iCloud Keychain (drag the `.jks` into a Secure Note alongside the password)
- iCloud Drive (`~/Library/Mobile Documents/com~apple~CloudDocs/keystores/`)
- GitHub Secret `KEYSTORE_BASE64` (set up in §9.3 — once that exists, GitHub itself is a backup)

**Regenerating the keystore (only if you've lost it AND nobody has Hydra installed yet):**
```bash
mkdir -p ~/keystores && cd ~/keystores
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -genkeypair -v -keystore hydra-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias hydra \
  -storepass <pass> -keypass <pass> \
  -dname "CN=<name>, OU=Hydra, O=Personal, L=Unknown, ST=Unknown, C=US"
```

### 9.2 Local build configuration

`keystore.properties` at the repo root (gitignored) wires the keystore into Gradle:
```
storeFile=~/keystores/hydra-release.jks
storePassword=<keystore password — see 1Password>
keyAlias=hydra
keyPassword=<same as storePassword>
```

Any clone of the repo without this file can still `assembleDebug` normally; only `assembleRelease` requires the keystore.

To build a signed APK locally:
```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Verify a signed APK:
```bash
~/Library/Android/sdk/build-tools/<latest>/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

### 9.3 GitHub Actions release workflow

`.github/workflows/release.yml` triggers on any tag matching `v*.*.*` and produces a signed APK attached to a GitHub Release. The first time you push a tag, the workflow will fail unless these four repository secrets are configured (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i ~/keystores/hydra-release.jks \| pbcopy` then paste |
| `KEYSTORE_PASSWORD` | the keystore password (see 1Password) |
| `KEY_ALIAS` | `hydra` |
| `KEY_PASSWORD` | same as `KEYSTORE_PASSWORD` |

### 9.4 Cutting a release

1. Bump `versionCode` AND `versionName` in `app/build.gradle.kts`. **`versionCode` must follow the formula `major*10000 + minor*100 + patch`** so it matches what `UpdateChecker.parseVersionCode` derives from the git tag. Examples: `v1.0.0` → `10000`; `v1.0.1` → `10001`; `v1.1.0` → `10100`; `v2.0.0` → `20000`. `versionName` should be the same `X.Y.Z` as the tag (no leading `v`). If the two diverge, the in-app updater either misses the new release (formula gives a smaller number than installed) or pesters the user forever to "update" to the version they already have (formula is larger than installed).
2. Commit: `git commit -am "chore: bump version to X.Y.Z"`.
3. Tag: `git tag vX.Y.Z` (the leading `v` matters — the workflow filters on it; the suffix must match `^v\d+\.\d+\.\d+$` or the in-app updater will skip it).
4. Push: `git push && git push --tags`.
5. Watch the Actions tab — workflow takes ~3 min.
6. Verify the new release at https://github.com/Apollo-Marketing/Hydra/releases — `Hydra-X.Y.Z.apk` should be attached and signed.

The in-app updater (slices 2-5) will pick the new release up on next app launch on each user's phone.
