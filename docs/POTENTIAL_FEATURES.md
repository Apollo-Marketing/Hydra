# Potential Features Roadmap

A working catalogue of features we could build given what we now know about the protocol, the bottle's capabilities, and the existing codebase. Tiered by effort/value so the next session can pick where to invest.

**Codebase context:** `app/` is an Android Kotlin/Compose app with a working BLE protocol layer, Room-backed sip persistence, paginated sync, volume calibration, and a 2-tab navigation. See `docs/PROJECT_HANDOFF.md` for the full session history.

**Effort scale:** S = small (~30–100 lines, no new screens), M = medium (1–2 sessions, new UI), L = large (multiple sessions, schema changes, infrastructure).
**Value scale:** Low / Med / High based on user-facing impact.

---

## Already shipped (foundation)

These are in code today — listed so we don't duplicate.

- BLE scan-by-name + connect (handles RPA rotation)
- Nordic UART transport with hand-rolled protobuf wire format
- Auto SetCapTime on connect
- 13 typed `Get*` commands as tappable buttons + 1 `Set` (time)
- Settings response parsers (HydroReminder, DND, LowBattery, TofSettings, UvConfig, UiState) with formatted display + UTC→local time conversion
- Generic protobuf field-walker fallback for unknown responses
- Room database (`sip_log` table, PK on timestamp)
- Multi-page paginated sync with quiescence detection (auto on Ready, manual button)
- Volume calculation from hardcoded polynomial (matches official app to <1 mL)
- Per-day sip log with prev/next date arrows
- "Bottle: X mL" + "Intake: Y mL · N sips" header
- Clear sip log button with confirmation
- Health Connect write-only sync (opt-in; backfills history on first enable; deterministic clientRecordIds for upsert/delete; rationale-intent privacy dialog)

---

## Tier 1 — Quick wins (S, mostly High value)

Small, additive, mostly use commands we've already validated.

### 1. Fetch `RequestGetCapStateThresholdSettings` for exact intake math (S, High)
Our `intakeOnDateMl` uses a "any positive delta = drink" heuristic. The official app applies real `drinkThresholdInMl` / `fillThresholdInMl` / `minVolumeLimitInMl` values from this command. Fetching once on Ready and applying the proper drink/fill/skip classification would make our daily total **exactly** match the official Larq app instead of being approximate.
**Depends on:** new typed parser for `CapStateThresholdSettings`.

### 2. Auto-derive bottle size from `RequestGetCapTofSettings` (S, Med)
Currently `BottleConfig.BOTTLE_SIZE_ML = 1000` is hardcoded. We already parse `TofSettings.bottleSizeInMilliliter`. Persist that value once observed (DataStore or singleton) and use it for volume math. Generalizes the app to any PureVis 2 size.

### 3. "Wake bottle" button — `SetCapPowerSavingMode` (S, Med)
Sends a single byte to wake the bottle out of `UI_STATE_ALL_OFF`. Fixes the "no fresh sip readings until I shake the bottle" frustration. Add as a button on the Bottle tab next to the existing commands.
**Depends on:** payload format for `SetCapPowerSavingMode` — needs a quick decompiled-code check.

### 4. "Clean now" button — `SetCapUvActivate` (S, High)
Triggers a UV-C sterilization cycle on demand. Cool to have, single command, confirmation dialog. Equivalent to the "Clean now" button in the official app.

### 5. Bottle status badge on the Bottle tab (S, Low)
Show last-known bottle state (`UI_STATE_ALL_OFF`, `HYDRATION_REMINDER`, `UV_NORMAL`, etc.) as a pill on the Bottle tab. We already get UiState from the existing button — surface it persistently.

### 6. Show firmware/serial in app's status (S, Low)
We already read these (Manufacturer, Model, Serial, Firmware Rev) via the standard BLE Device Info Service. Surface them in a small "About this bottle" section on the Bottle tab so they're not buried in the activity feed.

---

## Tier 2 — Hydration tracker proper (M, mostly High value)

Cross the line from "playground" to "useful daily app." Each adds a real screen.

### 7. Daily goal + progress ring (M, High)
User sets a daily goal (e.g. 2000 mL). Sips tab shows a progress indicator: "412 / 2000 mL (21%)". Goal stored in DataStore.

### 8. Weekly bar chart (M, High)
Last 7 days as bars showing daily intake. Use Vico charts (Compose-native) or roll a simple Canvas drawing.
**Depends on:** at least 7 days of accumulated sip history (time, not code).

### 9. Time-of-day pattern view (M, Med)
Heatmap of when you drink across the day. Most useful after a few weeks of data.

### 10. Refill detection + display (M, Med)
We already see positive deltas → those are fills. Show fills as separate rows in the sip log (different icon/color), and total fills/day separately from intake. "Refilled +480 mL at 2:14 PM."

### 11. Settings editor screens (M, Med)
Three small screens, one each for HydroReminder, DND, UvConfig — let user **change** these without leaving our app. Each needs a proper UI (time pickers, day-of-week toggle, interval slider) and uses the matching `Set*` command.
**Depends on:** typed builders for each settings request, plus understanding of the `CapColor` sub-message (currently undecoded).

### 12. Manual sip entry / edit / delete (M, Med)
Sometimes the bottle records weird events (false detections during walking, missed sips). Let user add a manual sip ("I drank 250 mL at noon, my bottle wasn't on me") or correct/delete an existing entry. Pure DB operation.

### 13. Today widget for Android home screen (M, Med)
Glanceable "Today: 412 / 2000 mL · Bottle: 220 mL" widget. Glance API.

### 14. Local notifications (M, Med)
Two cases:
- Push from our app: "It's 11am and you've only drunk 120 mL today" (separate from bottle's built-in reminders, which we'd want to disable on the bottle to avoid duplication)
- Bottle disconnected unexpectedly while in range — let user know

### 15. Background BLE sync (M, Med)
Foreground service that keeps the BLE connection alive when the app isn't open. Sips appear in real time without you opening the app. Significant Android plumbing (foreground service, notification channel, battery exemption request).

---

## Tier 3 — Power-user / diagnostic features (S–M, mostly Low–Med value)

Use the unused commands we discovered. Niche but interesting.

### 16. Live sensor inspector screen (M, Low)
Polls the seven currently-unused `GetCap*State` commands every ~1 sec, displays live values: accelerometer XYZ, ambient light, hall effect (cap on/off), TOF current reading, sip sensor state. Makes the bottle's internals visible. Cool for nerds, no real user value.

### 17. State machine timeline (M, Low)
Periodic `GetCapStateLog` fetches → render the bottle's state-machine transitions over time. Useful for debugging "why isn't this sensor firing?"

### 18. Fault log viewer (S, Low)
Parse `GetCapFaultLog` (we currently see "0B inner" because the user's bottle has no faults — but if it ever does, show them in a friendly table).

### 19. UV cycle history (S, Med)
Parse `GetCapActivationLog` for UV-C cycle events. Show "Last cleaned: 4 hours ago" + a list of recent cycles.

### 20. Bottle clock check (S, Low)
`GetCapTimeSettings` returns the bottle's stored clock. Useful to verify our SetCapTime worked — show "Bottle clock: in sync (±2 sec)" or "Bottle clock drifted by 47 sec."

### 21. Decoded CapColor display (S, Low)
The `HydroReminderSettings` has a `color` sub-message we never decoded. Almost certainly RGB. Decode it, show "Reminder pulse color: 🔵" with a color swatch.

### 22. Decoded CapUvParameters display (S, Low)
The `UvConfig` has 3 `CapUvParameters` (maintenance / standard / adventure) we currently dump as "(NB inner)". Decode each — likely contains UV duty cycle and intensity per mode.

---

## Tier 4 — Big scope (L, mostly Med value)

Each of these is a multi-session investment.

### 23. Cloud sync (L, Med)
Push sip events to a backend (Firebase / Supabase / your own) so history follows you across devices. Requires backend setup, auth, sync logic, conflict resolution.

### 24. Multi-bottle support (L, Med)
Track multiple bottles by name. Pick from a list on the Bottle tab. Sip log filtered per bottle. DB schema needs a `bottle_id` column.

### 25. Multi-user (L, Low)
Household tracking — different family members share bottles. Probably overkill for personal use.

### 26. iOS app (L, High)
You use the Larq app on iPhone. We could rewrite as Kotlin Multiplatform (KMP) with shared BLE/protocol code + native UI per platform, or just write Swift/SwiftUI with the protocol code re-implemented. Big lift.

### 27. Wear OS companion (L, Low)
Tiny watch face / complication showing today's intake. Cool, niche.

### 28. CSV / JSON export (S, Low)
Download sip history as CSV/JSON for analysis in Excel/Python. Could also import the same format (manual entry from another tracker).

### 29. Backup / restore of local DB (S, Low)
Export the entire Room DB to a JSON file in Downloads, restore from one. Useful before destructive operations.

### 30. Onboarding flow (M, Med)
First-time-launch wizard: pair bottle, set daily goal, configure reminders, etc. Replaces the bare "Find & Connect" button as the entry point.

### 31. Friends leaderboard / social (L, Low)
Compare daily intake with friends. Adds backend, auth, friends list, privacy controls. Probably nope.

---

## Reverse-engineering gaps still worth filling (S–M, supports other features)

Not features themselves — but things we still don't know that block other features.

### G1. `CapColor` schema (S)
Inside HydroReminderSettings. Almost certainly RGB. Two minutes with grep.

### G2. `CapUvParameters` schema (S)
Inside UvConfig. Probably duty cycle + intensity. Quick grep.

### G3. `SetCapPowerSavingMode` payload format (S)
Single-field `bool` or enum? Decompile check before wiring the Wake button.

### G4. `SetCapUvActivate` payload format (S)
Same — single byte? Enum of UV cycle modes?

### G5. `RequestGetCapStateThresholdSettings` schema (S)
Returns the drink/fill/min thresholds we need for exact intake math.

### G6. `RequestSetCapHydroReminderSettings` round-trip verification (S)
We'd want to verify our written settings exactly match what the bottle stored — possibly by re-reading after a write.

---

## Out of scope (intentionally not pursuing)

These were considered and rejected.

- **`CapFactoryReset` button.** Too easy to tap accidentally, no recovery. The official app doesn't expose it either.
- **`CapEnterDfuMode` (firmware update mode).** Could brick the bottle. Larq's official app handles firmware updates through a dedicated flow we'd have to fully replicate.
- **`CapEnterLowBatteryMode`.** No useful purpose for end users.
- **`CapStartCapCalibration` / `StopCapCalibration`.** The polynomial is set at the factory. Re-running calibration here would only break things.
- **OTA firmware updates.** Big scope, high blast radius if we get it wrong.
- **Cloud auth replacement (sign in with Larq account).** We bypass the cloud entirely; staying that way is simpler.

---

## Recommended next session

If we resume cold, my picks in order are:

1. **Fill the protocol gaps (G1–G5)** — under an hour, unblocks several Tier 1 features.
2. **Ship Tier 1 #1 (`StateThresholdSettings` for exact intake)** — single small change, makes our most-visible number trustworthy.
3. **Tier 1 #4 (Clean now)** — satisfying, instant, demos that we can drive the bottle.
4. **Tier 1 #3 (Wake bottle)** — fixes a real frustration ("no fresh sips until I move the bottle").
5. **Tier 2 #7 (Daily goal + progress)** — first step from "playground" to "real tracker."

After that, the choice depends on whether you want to keep polishing personal-use features or pivot toward something like background sync, widget, or iOS support.
