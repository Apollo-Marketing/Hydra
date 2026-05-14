# Hydra (unofficial Larq companion)

A reverse-engineered Android app for the Larq PureVis 2 smart water bottle. Built because the official app is "absolutely garbage" (user's words). Connects via BLE, decodes the bottle's protobuf-over-NUS protocol, and surfaces hydration data on a glassmorphic UI.

## Stack

- **Language:** Kotlin 2.2.10
- **UI:** Jetpack Compose (BOM 2026.02.01), Material 3
- **Persistence:** Room 2.7.0 + KSP
- **Build:** Gradle 9.4.1, AGP 9.2.0, JDK 21+ (Android Studio's bundled JBR)
- **Target:** Android API 36 (16); min SDK 31 (12) — for the post-Bluetooth-permissions-rework BLE APIs
- **Hardware:** Larq PureVis 2 (1000 mL). Bottles advertise as `LARQ_<id>` and use Resolvable Private Addresses — always scan by name prefix, never hardcode MAC.

## Commands

Gradle and adb aren't on PATH — use the bundled JDK and Android SDK paths:

- **Build:** `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`
- **Install on phone:** `JAVA_HOME=...JDK ./gradlew installDebug` (phone must be plugged in with USB debugging)
- **Logs:** `~/Library/Android/sdk/platform-tools/adb logcat -d -s HydraBle:V HydraScan:V`
- **Pull APK from phone (for re-decompile):** `~/Library/Android/sdk/platform-tools/adb shell pm path com.larq.live` then `adb pull <path>`
- **Clear sip log buffer:** Settings tab → Remove bottle. (The bottle's own ring buffer keeps re-syncing, so wiping locally only is temporary.)
- **Cut a release:** Bump `versionCode`/`versionName` in `app/build.gradle.kts`, commit, `git tag vX.Y.Z`, `git push --tags`. Full checklist (keystore, secrets, CI workflow) in `docs/PROJECT_HANDOFF.md` §9.

## Architecture

```
app/src/main/java/com/hydra/app/
├── MainActivity.kt         # entry — applies HydraTheme, hosts MainScreen; routes the Health Connect rationale intent to the privacy dialog
├── ble/                    # BLE + protocol layer
│   ├── BottleConnection.kt # GATT, sync orchestration, notifications
│   ├── BottleScanner.kt    # scan-only (used by Settings pairing)
│   ├── BottleProtocol.kt   # protobuf wire format + frame envelope + builders + parsers
│   ├── BottleEvent.kt      # typed events for the (unused) activity feed
│   └── BottleMath.kt       # polynomial volume calc + intake aggregators
├── data/                   # Persistence
│   ├── HydraDatabase.kt    # @Database — sip_log + saved_bottles tables (v3 — adds sip_log.manualVolumeMl)
│   ├── SipEntity/Dao/Repository  # SipEntity.manualVolumeMl distinguishes BLE vs manually-logged sips
│   ├── SavedBottleEntity/Dao/Repository
│   └── AppPreferencesRepository.kt  # DataStore — daily goal, last-sync ms, theme mode, show-streak, auto-update flag, last-update-check ms, hc-enabled, hc-last-sync-sec
├── update/                 # GitHub Releases auto-updater
│   ├── UpdateChecker.kt    # GitHub API call + tag → versionCode parse
│   ├── UpdateInstaller.kt  # DownloadManager + FileProvider hand-off to system installer
│   ├── UpdateController.kt # state machine, 24h auto-check rate-limit, manual-vs-auto routing
│   └── UpdateState.kt      # sealed Idle/Checking/UpToDate/Available/Downloading/ReadyToInstall/PermissionRequired/Error
├── health/                 # Health Connect bridge — write-only sync of sip data
│   ├── HealthConnectAvailability.kt   # sealed Available/NotInstalled/UpdateRequired/Unsupported
│   ├── HealthConnectGateway.kt        # SOLE point of contact with androidx.health.connect.client.*
│   ├── HealthConnectStatus.kt         # snapshot data class read by Settings UI
│   └── HealthConnectController.kt     # singleton: status StateFlow + onSipsChanged sync + watermark
└── ui/
    ├── MainScreen.kt       # tabbed root: Today / Bottle / History / Settings; hosts UpdateDialog;
    │                       # wires HealthConnectController to sipLog → onSipsChanged via combine()
    ├── components/         # GlassCard, GlassNavigationBar, GradientBackground, AuroraGlow,
    │                       # HydraIcon/Logo, HydrationRing, LiquidBottle, HourlyBarChart,
    │                       # WeeklyBarChart, HydrationHeatmap, ConnectionBadge, RelativeTime,
    │                       # ManualSipDialog (volume + time picker for hand-logged sips),
    │                       # UpdateDialog (Available/Downloading/ReadyToInstall/PermissionRequired/UpToDate/Error),
    │                       # HealthConnectPrivacyDialog (shared by Settings row + rationale intent)
    ├── screens/            # HydrationScreen, BottleScreen, HistoryScreen, SettingsScreen
    └── theme/              # HydraColors token bag (Dark/Light) + Material3 theme
```

**Untouched, working layers** (don't refactor without reason): `ble/` (especially `BottleConnection`, `BottleProtocol`, `BottleScanner`), `data/SipEntity/Dao/Repository`. `BottleMath` may grow new aggregators as the UI asks for them, but its polynomial layer (`volumeMl`, `POLY_*`) is settled. All four tabs now consume these layers.

**BLE notes:**
- `BottleConnection` callbacks fire on a binder thread — anything mutating shared state must post to its `Main.immediate` scope.
- All parsers are `runCatching`-wrapped — never throw from the BLE thread (would crash the app).
- `BottleConfig.BOTTLE_SIZE_ML = 1000` is hardcoded; should be derived from `RequestGetCapTofSettings` in a future session.

## Reference Docs

- `docs/PROJECT_HANDOFF.md` — full session history, protocol details, decompilation findings, captures location. Read first when picking up cold.
- `docs/POTENTIAL_FEATURES.md` — tiered roadmap of features we could build, with effort estimates.
- `~/.claude/plans/cozy-popping-muffin.md` — most recently approved plan.

## Available Skills

- `/spec` — explore, plan, execute (default entry point for new work)
- `/commit` — verified conventional commits (build green required)
- `/qa` — code quality review against `.claude/skills/qa/references/standards.md`
- `/wrapup` — end-to-end ship pipeline
- `/sync-docs` — audit docs against the codebase
- `/introspect` — adjust how Claude works on this project

## Rules

- **Never hardcode a BLE MAC.** Always scan by name (RPAs rotate every ~15 min on this bottle).
- **Never delete the `captures/` directory.** It contains the bugreport snoop log + decompiled APK that all protocol decoding depends on.
- **Build verifies via `./gradlew assembleDebug` with `JAVA_HOME` set.** No tests run by default — there are none yet.
- **BLE parsing must be crash-safe.** Wrap any new parser in `runCatching` and bound-check fixed32 reads. The notification thread is a binder thread; throws crash the process.
- **Database schema changes bump version + use destructive fallback.** Sip data is reconstructible from the bottle's own buffer; saved bottles re-pair.
- **Vercel/Next.js validator hooks fire on this Android project's `app/` directory.** They're false positives — ignore them.

## ⛔ FORBIDDEN BOTTLE COMMANDS — NEVER WIRE THESE UP

These protocol commands exist in the bottle's firmware but the official Larq app deliberately
never sends them. They are destructive, irreversible, or outright brick-the-device dangerous.
**Do not add a builder, button, or test path for any of them — even "just to see what happens"
or "behind a hidden debug gate." If a future user request calls for one of these, push back
and explain the risk before doing anything.**

| Command | Why forbidden |
|---|---|
| `RequestCapFactoryReset` | Wipes all bottle settings, pairing, calibration data, and history. Recovery requires re-pairing through the official app. No undo. |
| `RequestCapEnterDfuMode` | Puts the bottle into firmware-update mode. If the host app drops connection mid-update or sends a bad image, the bottle bricks. We have no firmware images and no DFU-flow implementation. |
| `RequestCapEnterLowBatteryMode` | Forces the bottle into emergency low-battery shutdown. No legitimate user-facing reason to ever send this. |
| `RequestCapStartCapCalibration` / `RequestCapStopCapCalibration` | Runs the factory sip-sensor calibration routine. The current calibration polynomial was set at the factory; re-running these likely overwrites it with garbage and breaks volume tracking permanently. |
| `RequestSetCapCalibrationSettings` | Modifies calibration parameters. Not as immediately destructive as the routine above, but can drift the polynomial and break volume math with no easy way to recover the original values. |

**`Bash(rm:*)` deny rule.** The user previously had a global `Bash(rm:*)` deny rule and removed it. Don't take that as license to delete files casually — confirm before any `rm` outside of obvious build/cache/scratch paths.

**Note for the `add-feature` skill and any future protocol scaffolding work:** the type-URL constants for these forbidden commands are intentionally absent from `BottleProtocol.Requests`. Don't add them. If audit tooling asks "why aren't these in the vocabulary," point at this rules block.
