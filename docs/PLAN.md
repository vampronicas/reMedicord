# reMedicord — Project Plan

Personal Android app for tracking medicines, eye drops, ear drops, injections, bandaging and more.
Offline only. History is encrypted on the device. The only thing that leaves the app is a backup ZIP that you export yourself.

---

## 1. Requirements (agreed)

| # | Requirement |
|---|---|
| R1 | Track these types: **Oral pill/tablet, Liquid, Eye drop, Ear drop, Injection, Inhaler, Nasal spray, Cream/ointment, Patch, Bandaging/wound dressing** |
| R2 | Several medicines, each with its own schedule. Many alarms can be active at once |
| R3 | Schedule types: fixed times, chosen weekdays, every N hours, every N days/weeks, cycle (X days on / Y off), tapering, as-needed (PRN) |
| R4 | Reminder notification with **Taken / Snooze / Skip**. Repeats if you don't respond, then the dose is marked **Missed** |
| R5 | **Skip** is configurable: this dose only (default), next N doses, N days, or until a date. A reason is always recorded |
| R6 | **Stop medicine** with a reason (course completed, symptoms resolved, doctor instructed, side effect / reaction, other) |
| R7 | **Must-complete course** flag (e.g. antibiotics): normal stopping and multi-dose skipping are **blocked** |
| R8 | **Dose change**: new amount and/or schedule from now or from a date. The old dose stays in history |
| R9 | Type-specific details: eye/ear side (L/R/both), drop count, injection-site rotation, bandage body location and dressing type |
| R10 | Eye-drop gap: when two eye drops are due together, the app spaces them by a configurable gap (default 5 min) |
| R11 | Medicines due at the same time come as one grouped notification ("Take all" or one by one) |
| R12 | History: calendar, day details, adherence %, notes per dose, editing past entries with an audit trail |
| R13 | Late logging: "I took it at 08:40" |
| R14 | Encrypted local database. **No INTERNET permission.** No sharing with other apps. No cloud backup |
| R15 | **Backup/restore**: full settings + history exported as a `.zip` to a folder you pick (e.g. Downloads), and restored from it. Not password-protected |
| R16 | English only. No screenshot blocking. No stock or expiry tracking. Minimum Android 12 |
| R17 | Reminders survive restart, time change, time-zone change, app update and **Doze (mandatory)** |
| R18 | **App lock with a 4-digit PIN only.** No fingerprint, face, pattern or password |
| R19 | Must-complete course length is configurable as **days or number of doses**. **Missed or skipped doses push the end date back** in both modes |
| R20 | **Lock screen is generic** until the phone is unlocked. Taken/Skip need an unlocked phone |
| R21 | Doses due while the phone was off (or since a restored backup) are **not assumed**. The app asks whether each was taken, missed or skipped |
| R22 | Time-zone behaviour is set **per medicine**: follow local time (default) or keep home-time intervals |
| R23 | No in-app reset/erase. Code is published to a **public GitHub repo** at the end, with no secrets in it |
| R24 | *(v1.1, 2026-11-09)* Past records can be **amended for 5 days** (today + 4 previous days); older records are read-only |
| R25 | A dose with **no answer for 5 days** is closed as **Missed – "No answer within 5 days"** (counts in adherence) |
| R26 | Reminder action **"Got it, I'll take it later"**: reminders stop, the dose shows orange ("needs your answer") and the app **asks on the next open** whether it was taken |
| R27 | **"Don't remind me again today"** per medicine (e.g. supplements while travelling): no more reminders until midnight; the day's doses are filled in later. Nothing is marked skipped, so it's allowed for must-complete courses too. **"Skip this dose"** remains (reason required) |
| R28 | Notification buttons: **Taken · Later · Skip…** ("Skip…" opens the reminder screen, which offers every choice, including Snooze 5/10/15) |
| R29 | **Flexible schedule:** N doses a day at any of chosen times (Morning / Afternoon / Evening / Night, times configurable, optional minimum gap). Reminds at each time until N are taken; extra times become "not needed"; at day end only the shortfall counts as missed |
| R30 | **Why the medicine is needed** is required: category (Supplement / vitamin, Treatment, Periodic e.g. deworming, Long-term, Preventive, Other) + short reason. DB schema v2, with a tested migration |
| R31 | Calendar day tap shows a **per-medicine summary**. History has **multi-select** medicine and status filters. Today has a **date picker** and **soft status colours** (taken green, skipped grey, missed red, needs answer orange, due blue, not needed faint) |

> **Enhancement backlog:** requested but not yet built items are recorded in [ENHANCEMENTS.md](ENHANCEMENTS.md).

---

## 2. Technology stack

Rule: mainstream, Google-recommended, large active community, **latest stable** versions only (no alpha, beta or RC).
Exact versions are pinned in `gradle/libs.versions.toml` in Phase 0 after checking the official release notes, and none is newer than 2 weeks.

| Concern | Choice | Why |
|---|---|---|
| Language | **Kotlin** | Google's official language for Android; largest Android community |
| UI | **Jetpack Compose + Material 3** | Google's recommended UI toolkit for new apps |
| Architecture | **MVVM**: ViewModel + StateFlow, repositories, pure domain logic | Google's official app-architecture guide |
| Dependency injection | **Hilt** | Google's official DI for Android |
| Database | **Room** on **SQLCipher for Android** (`net.zetetic:sqlcipher-android`) | Room is the standard; SQLCipher is the long-established encrypted SQLite (AES-256) |
| Key protection | **Android Keystore** (AES-256-GCM, hardware-backed where available) wraps the random DB passphrase | The key never exists in plain form on disk |
| Navigation | Navigation Compose | Standard |
| Exact reminders | `AlarmManager.setAlarmClock()` | Treated like a Clock-app alarm: exempt from Doze, fires on time |
| Background upkeep | A daily **inexact AlarmManager alarm** (no WorkManager) | WorkManager adds a background service and an externally reachable diagnostics receiver. Dropping it keeps "nothing exported except the launcher" and one fewer library |
| Supply-chain protection | **Gradle dependency verification** (`gradle/verification-metadata.xml`, SHA-256 of every library), Gradle wrapper pinned by SHA-256, only Google Maven + Maven Central allowed (no Gradle Plugin Portal) | The build fails if any downloaded file is ever swapped or tampered with |
| Backup format | kotlinx.serialization (JSON) + `java.util.zip` | Official Kotlin library + built-in JDK; no extra dependencies |
| File picker | Storage Access Framework (`CreateDocument` / `OpenDocument`) | System file picker, not a third-party app. No storage permission needed |
| Build | Gradle (Kotlin DSL) + Android Gradle Plugin + version catalog | Standard |
| JDK | **JetBrains Runtime 25** bundled with Android Studio (already installed), used through the Gradle toolchain. Phase 0 confirms AGP/Gradle support | No separate JDK install |
| Tests | JUnit, kotlinx-coroutines-test, Robolectric (logic + plain-SQLite DAO tests), AndroidX instrumented tests (SQLCipher), Compose UI test, Gradle Managed Devices (headless emulator), Kover (coverage) | Standard |
| Min SDK / Target SDK | **31 (Android 12)** / latest stable API level | Your requirement |
| PIN hashing | PBKDF2-HMAC-SHA256 (built into Android), random salt, high iteration count | Standard, no extra library |

### Toolchain to install (Phase 0)
1. **Android Studio** (stable): includes the SDK Manager, the emulator, and a bundled JDK. ✅ Installed (JBR 25). Git ✅ installed.
2. Through the SDK Manager: Android SDK Platform (target API), Build-Tools, Platform-Tools (`adb`).
3. Gradle: downloaded automatically by the project's Gradle wrapper. No separate install.

---

## 3. Security and privacy design

| Control | How |
|---|---|
| No network | `INTERNET` / `ACCESS_NETWORK_STATE` are not declared, and are force-removed with `tools:node="remove"` in case a library adds them. **A build check fails the build** if the final merged manifest contains either |
| Data at rest | All data (including settings) lives in one SQLCipher database in app-private storage |
| DB key | A random 32-byte passphrase, encrypted with a Keystore AES-GCM key, stored in app-private storage |
| No cloud/device transfer | `android:allowBackup="false"`, plus `dataExtractionRules` / `fullBackupContent` excluding everything |
| No contact with other apps | **All components are `exported=false` except the launcher activity.** System broadcasts (restart, time change) still reach non-exported receivers because they come from the OS. No ContentProvider, no share intents, no deep links |
| Notification privacy | **Generic until the phone is unlocked.** While locked, the notification and full-screen alarm show only *"Medication reminder – unlock to see details"*, with **Snooze** and **Unlock to view**. **Taken / Skip require the phone to be unlocked** (`setAuthenticationRequired(true)`), so no dose is marked without you seeing which medicine it is. After unlocking, names and all buttons appear |
| Restart before first unlock | Only **upcoming reminder times** (timestamps, no names or medical data) are kept in device-protected storage, so alarms are set again right after a restart, even before the first unlock. They show the generic reminder |
| Release build | R8 shrinking/obfuscation, debug logging stripped, signed with your own keystore |
| Backup ZIP | ⚠️ **Not encrypted (your choice).** Anyone with access to the file can read your medical history. Keep it somewhere safe |
| App lock | 4-digit PIN, see 3.1 |

### 3.1 PIN lock
- **Setup:** on first launch you choose a 4-digit PIN and enter it twice. It's required, with no other lock types.
- **In-app keypad:** the PIN is entered on the app's own number pad, never the phone's keyboard, so no third-party keyboard can see it.
- **Storage:** only a salted PBKDF2 hash is kept, inside the encrypted database. The PIN itself is never stored.
- **When it asks:** every time the app is opened, and when you return after more than **1 minute** in the background (configurable: immediately / 1 / 5 / 15 min).
- **Wrong attempts:** after 5 wrong PINs → 30 s wait, then 1 min, 5 min, 15 min, 1 h (it keeps escalating). The countdown survives an app restart and **can't be skipped by changing the phone's clock**: it counts using device uptime plus saved state, not wall-clock time.
- **Change PIN:** in Settings. The current PIN is required.
- **Notification buttons don't need the app PIN:** Taken / Snooze / Skip work without the app PIN, so a reminder is never blocked. Taken / Skip do need the **phone** to be unlocked (see §3). Opening the app itself still needs the PIN.
- **No in-app reset or erase:** the app has no button that erases data.
- **Forgot PIN:** there is no recovery (no email, no account, by design), and the app can't be opened. The only way back in is Android's own *Settings → Apps → reMedicord → Clear storage* (or uninstall), which **erases all data**, then restoring your latest backup ZIP. Onboarding says this once when you set the PIN. This is why regular backups matter.
- **Backups never contain the PIN.** Restoring a backup keeps the PIN currently set on the phone.
- The PIN only controls access to the app's screens. The database encryption key is protected separately by the Android Keystore, so reminders can work while the app is locked.

### 3.2 Signing key (what it is and who sees it)
- Android only installs an APK that is **digitally signed**. The signature proves every update comes from the same author.
- The key is a file (`reMedicord-release.jks`) plus a password, created once on **this PC** in Phase 0. It **never goes onto the phone**. Phone users never see it. It's only used when building an APK.
- When you install an update, Android checks it's signed with the **same key** as the installed app. If not, the update is refused, and the only way forward is uninstalling, which **erases the app's data**.
- **How it could be lost:** PC failure or reinstall, accidentally deleting the folder, forgetting the password, or not backing it up when moving to a new PC.
- **What we'll do:**
  1. Store it at `reMedicord/keystore/reMedicord-release.jks`. Because this folder is in OneDrive, it also gets a cloud copy, which protects against PC loss. The key is useless without its password.
  2. The password lives in `keystore/keystore.properties`. **Also save it in a password manager.**
  3. Both files are **git-ignored**, so they are **never uploaded to GitHub**.
  4. Recommended: put one extra copy of the `.jks` file and password on a USB stick.

### 3.3 Public GitHub repository
- The code will be published in a **public** GitHub repository at the end (Phase 7). Security doesn't depend on hiding the code: the app has no server, no API keys and no secrets, and the signing key never enters the repo.
- `.gitignore` (created in Phase 0) excludes `keystore/`, `keystore.properties`, `local.properties`, all build output, and any `*.zip` backup files.
- A **pre-push secret check** (a git hook script) refuses to push if keystore files, passwords/properties or backup ZIPs are about to be committed.
- A licence is chosen in Phase 7.

---

## 4. Domain logic

### 4.1 Medication lifecycle
```
            ┌──────── pause ───────┐
            ▼                      │
 [ACTIVE] ──────────────────► [PAUSED] ── resume ──► [ACTIVE]
    │
    ├── stop (reason) ─────────► [STOPPED]      (blocked if must-complete, see 4.4)
    └── course end reached ────► [COMPLETED]    (automatic)
```
Stopped or completed medicines stay in history. They can be **restarted**, which creates a new regimen.

### 4.2 Regimens (dose changes and tapering)
A medicine has a list of **regimens**, each valid from `effectiveFrom` until the next one starts.
- **Dose change** = close the current regimen at time T and open a new one at T, with a reason (e.g. "Doctor increased dose").
- **Tapering** = several regimens created up front, e.g. 4×/day for 7 days → 3× for 7 → 2× for 7 → 1× for 7 → done.
- Past doses always point to the regimen they belonged to, so history shows the dose that applied at that time.

### 4.3 Schedule rules (a sealed type, stored as JSON in the regimen)
| Rule | Settings | Example |
|---|---|---|
| `FixedTimes` | times[], weekdays (empty = every day) | 08:00, 20:00 daily |
| `EveryNHours` | interval, first time, optional quiet hours | every 6h from 06:00, nothing 23:00–06:00 |
| `EveryNDays` | n, times[], start date | weekly injection, Sundays 09:00 |
| `Cyclic` | daysOn, daysOff, times[] | 21 on / 7 off |
| `AsNeeded` | min gap, max per day | painkiller: ≥4h apart, ≤4/day |

### 4.4 Must-complete course
Settings: `mustComplete = true`, course length chosen by you as **days** or **total doses**.
- Both modes are turned into a **required number of taken doses**: in days mode, days × doses per day from the regimen; in doses mode, the number you enter.
- **Each missed or skipped dose adds one dose at the end of the schedule**, so the projected end date moves back in both modes. The detail screen shows "14 / 21 taken · ends Tue 18 Nov (was Mon 17 Nov, +2 missed)".
- **The extension is calculated, never stored:** remaining = required taken doses − doses actually TAKEN. If a missed dose is later logged as taken ("I took it at 08:40"), its extension disappears automatically. UNCONFIRMED doses (§4.6) don't move the end date until you answer the review prompt.
- If the dose changes mid-course in days mode, the remaining days are recalculated with the new doses per day. Doses already taken still count.
- **Stop** is blocked for reasons "Symptoms resolved" and "Other".
- **Allowed exits**, which need a confirmation and a written note: "Doctor instructed" and "Side effect / allergic reaction". A hard block with no exit would be unsafe, because an allergic reaction to an antibiotic **must** stop it.
- **Pause** is blocked.
- **Skip**: only "this dose only", with a warning. Multi-dose skips (N doses, N days, until date) are blocked.
- Automatically **COMPLETED** when the course end condition is met.

### 4.5 Skip rules
| Mode | Effect |
|---|---|
| This dose only (default) | That one dose → SKIPPED |
| Next N doses | The next N planned doses → SKIPPED |
| N days / until date | All doses in the range → SKIPPED |

The reason is required (presets: *Doctor advised, Felt unwell, Not available, Procedure / fasting, Other + free text*).
Active skip rules appear on the medicine detail screen and can be cancelled before they end.

### 4.6 Dose states
```
           alarm fires
PLANNED ──────────────► DUE ──Taken──► TAKEN
                         │ ──Skip───► SKIPPED
                         │ ──Snooze─► SNOOZED ──(snooze ends)──► DUE
                         │
                         └─ no reply: repeat every R min, up to M times
                            (default R = 10 min, M = 3) ──► MISSED
MISSED ──"I took it at hh:mm"──► TAKEN (late, logged in the audit trail)

PLANNED ──(due while the app wasn't running: phone off, or after a restore)──► UNCONFIRMED
UNCONFIRMED ──review prompt──► TAKEN (optional actual time) | MISSED | SKIPPED (reason)
```
- **UNCONFIRMED (review instead of assuming):** normally the phone is always on. If it was off, or data was restored from a backup, doses that fell due in the gap are **not** marked missed automatically.
  - **The prompt:** on the next app open (after the PIN), screen **S14** says *"While reMedicord wasn't running, these doses were due:"*. Doses are grouped by medicine and course, and you mark each one, or all at once, as **Taken** (optional actual time), **Missed** or **Skipped** (reason).
  - **Until answered:** they stay in a **"Needs review"** card at the top of Today. They show as "pending review", don't count against adherence, and don't move course end dates.
  - **After a restore:** the same prompt covers doses due between the backup's export time and now.
  - Normal reminders that time out **while the phone is on** still become MISSED as described above.
- The snooze length is chosen on the notification: **5 / 10 / 15 min** (the options are configurable).
- Snoozing has a limit (default 3). After that, only Taken or Skip are offered.
- A dose is also marked MISSED when the **next dose of the same medicine becomes due**. This prevents double reminders.
- As-needed (PRN) medicines have no alarms. You log them with "Take now", and the app enforces the minimum gap and daily maximum with a warning.

### 4.7 Grouping and the eye-drop gap
- Doses due within the same minute form a **slot**, which gets one alarm and one grouped notification.
- If a slot contains ≥2 eye drops, the notification shows only the first. Tapping **Taken** starts a timer for the gap (default 5 min), then the next eye drop is announced. Other medicine types in the slot are not delayed.
- The gap timer starts from the **actual Taken time** of the previous drop (snoozing the first drop shifts the rest). Each dose has its own reminder time (`remindAt`), separate from when it was originally due (`scheduledAt`). Snooze uses the same field.

### 4.8 Site rotation (injections and patches)
You enable the sites you use (e.g. abdomen L/R, thigh L/R, arm L/R, upper back, chest). The suggested next site is the **least recently used** one, and you can change it before confirming. Each dose records its site. This applies to **injections and patches**.

### 4.9 Time and time zones
- **Per-medicine time-zone mode:**
  - **Follow local time** (default): "08:00 wherever I am". The gap between doses changes on travel days.
  - **Keep home-time intervals**: for strict-interval medicines (e.g. some hormones or insulin). Doses stay tied to the home time zone set on the medicine, so the gap between doses never shrinks or stretches. Reminders still display in local time.
- History stores the **exact UTC instant + time zone**.
- Daylight saving: a time that doesn't exist (spring forward) moves to the next valid minute. A time that occurs twice (fall back) fires once.
- When the time zone or clock changes, all upcoming doses are recalculated.

---

## 5. Reminder engine (how alarms actually work)

```
Any change (add/edit med, dose change, skip, stop, restart, time change, daily job)
   └─► ReminderPlanner.replan()
         0. Gap check: doses due between the last planned time and now that never
            fired (phone off / restore) → UNCONFIRMED → review prompt (§4.6)
         1. DoseGenerator (pure function) → planned doses for [now, now + 48h]
         2. Upsert PLANNED dose_events (never touches doses that already have an action)
         3. Delete future PLANNED events that are no longer valid
         4. Group by slot → one setAlarmClock() per slot (requestCode = slot id)
         5. Cancel alarms for slots that disappeared
         6. Write the upcoming slot times (timestamps only) to device-protected storage

Alarm fires → ReminderReceiver
   └─ load the slot's doses → mark DUE → post grouped notification
      (locked phone: generic text, Snooze + Unlock to view; full-screen alarm if enabled)
   └─ schedule a repeat alarm (+R min)

Notification button → DoseActionReceiver (not exported)
   └─ Taken / Snooze(n) / Skip → update DB → cancel repeat → replan
```

| Component | Role |
|---|---|
| `SystemEventReceiver` | `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`*, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` → replan |
| `MaintenanceAlarm` (daily, inexact alarm) | Mark overdue doses MISSED, complete finished courses, extend the 48h window |
| `ReminderHealthChecker` | Checks notifications, exact alarms, full-screen permission, battery optimisation, channel muted. Feeds the Health screen |

\* The encrypted DB can't be opened before the first unlock after a restart. On `LOCKED_BOOT_COMPLETED`, alarms are set again from the timestamp-only list in device-protected storage and show the generic reminder. After the first unlock, a full replan runs.

### Permissions (complete list)
`POST_NOTIFICATIONS`, `USE_EXACT_ALARM` (API 33+), `SCHEDULE_EXACT_ALARM` (maxSdk 32), `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. **Nothing else.**

---

## 6. Data model (Room / SQLCipher)

**medication**
`id, name, type(enum), instructions, foodRelation(NONE/BEFORE/WITH/AFTER), colorTag, status(ACTIVE/PAUSED/STOPPED/COMPLETED), mustComplete, courseMode(NONE/DAYS/DOSES), courseLength, courseStart, stopReason, stopNote, stoppedAt, side(LEFT/RIGHT/BOTH, eye/ear), bodyLocation (bandage/cream), dressingType, enabledSites (injection/patch, JSON), timeZoneMode(LOCAL/HOME), homeZone, createdAt, updatedAt`
(Drop count is the regimen's `doseAmount` with unit DROP.)

**regimen**
`id, medicationId, effectiveFrom(instant), effectiveTo(instant?), doseAmount(decimal), doseUnit(TABLET/ML/DROP/UNIT/MG/PUFF/SPRAY/APPLICATION/PATCH/DRESSING), scheduleRule(JSON), changeReason, createdAt`

**dose_event**
`id, medicationId, regimenId, slotId, scheduledAt(UTC), scheduledZone, remindAt(UTC, snooze / eye-drop gap), status(PLANNED/DUE/SNOOZED/TAKEN/SKIPPED/MISSED/UNCONFIRMED), actionAt, takenAt(actual), amount, site, skipReason, note, snoozeCount, repeatCount, isLateLog, isPrn`
Unique on `(medicationId, scheduledAt)`.

**skip_rule**
`id, medicationId, mode(NEXT_N/DAYS/UNTIL), count, untilDate, reason, note, createdAt, cancelledAt`

**audit_log**
`id, entityType, entityId, action, beforeJson, afterJson, at, note`

**setting**
`key, value`: snooze options, repeat interval/count, eye-drop gap, full-screen on/off, 12/24h, missed rule.

Schema migrations are versioned and tested. The exported schema JSON is kept in `app/schemas/`. **Destructive migration fallback is banned**: an app update must never wipe data, and a test fails the build if the fallback is enabled.

---

## 7. Backup and restore

**Export** → system file picker → `reMedicord-backup-2026-11-07_2030.zip`

```
reMedicord-backup-....zip
├── manifest.json        app version, schema version, export time, record counts, SHA-256 per file
├── settings.json
├── medications.json
├── regimens.json
├── dose_events.json
├── skip_rules.json
├── audit_log.json
└── history.csv          human-readable history (opens in Excel)
```
`history.csv` is protected against **formula injection**: any text starting with `= + - @` (or tab/CR) is escaped, so Excel never runs it as a formula.

**Restore** → pick a ZIP → validate → preview ("12 medications, 3,410 doses, exported 2026-11-01") → confirm → restore.
- Checks: manifest present, schema version supported (older backups are migrated forward), file size/count limits, no path traversal (zip-slip), **only known file names accepted, unknown fields and enum values rejected**, every field type- and range-checked.
- **About SHA-256:** the checksums are stored in the same unprotected ZIP, so they detect **accidental corruption**, not deliberate tampering. Protection against malicious content comes from the strict validation above. Text from a backup is only ever displayed, never executed.
- After restoring, doses due between the backup's export time and now go to the **review prompt** (§4.6).
- An **automatic snapshot of the current data** is taken before restoring (kept in private storage, last 3).
- The PIN hash and lock state are **never exported**, and restore leaves the current PIN unchanged.
- Restore **replaces** all data in one transaction: either everything is restored or nothing changes. Alarms are then re-planned.
- Optional reminder: "Last backup was 30 days ago" (on by default, interval configurable).

---

## 8. Screens

| # | Screen | Contents |
|---|---|---|
| S0 | **PIN lock** | In-app 4-digit keypad, wrong-attempt countdown. No reset/erase option (forgot PIN → see §3.1) |
| S1 | **Onboarding** (first run) | Short intro → **set 4-digit PIN** (with the one-time "forgot PIN" warning) → grant notifications → exact alarms → full-screen → battery "Unrestricted". Each step explains in plain words **what it's for**, e.g. *"Some phones shut down apps in the background to save battery. If that happens, your medicine reminders won't ring. Set reMedicord to 'Unrestricted' so reminders always arrive on time."* |
| S2 | **Today** (home) | **"Needs review" card** (if any UNCONFIRMED doses), timeline of today's slots with status chips, quick Taken/Snooze/Skip, "Take now" for PRN, "Log a late dose" |
| S3 | **Medications** | Tabs: Active / Paused / Finished (stopped + completed). Search |
| S4 | **Add/Edit medication** (step by step) | ① Type → ② Details (fields change per type) → ③ Dose & schedule (rule builder, taper builder, time-zone mode) → ④ Course (must-complete, days/doses length) → ⑤ Review |
| S5 | **Medication detail** | Current regimen, upcoming doses, regimen history, active skip rules, adherence. Actions: Change dose, Skip…, Pause, Stop, Restart |
| S6 | **Change dose** | New amount/schedule, effective now/date, reason |
| S7 | **Skip** | Mode, count/days/date, reason (applies the course rules) |
| S8 | **Stop** | Reason, note (applies the course rules) |
| S9 | **History** | Month calendar (coloured day markers) → day detail → edit a dose (audit). Filter by medicine. Adherence for 7/30/90 days |
| S10 | **Full-screen alarm** | **Locked:** generic "Medication reminder", Snooze / Unlock to view. **Unlocked:** big medicine card(s), side/site details, Taken / Snooze (5/10/15) / Skip |
| S11 | **Backup & Restore** | Export, Restore, last backup date, snapshots |
| S12 | **Settings** | Change PIN, lock timeout, snooze options, repeat interval/count, eye-drop gap, full-screen toggle, notification sound (system channel settings), 12/24h |
| S13 | **Reminder health** | Checklist with ✅/❌ and a "Fix" button for each item |
| S14 | **Review missed period** | "While reMedicord wasn't running, these doses were due:". Grouped by medicine/course. Per dose or all at once: Taken (optional time) / Missed / Skipped (reason) |

Action wording follows the type: *Take* (pill/liquid), *Instill* (eye/ear), *Inject*, *Inhale*, *Spray*, *Apply* (cream/patch), *Change dressing* (bandage).

---

## 9. Project structure

```
reMedicord/
├── docs/PLAN.md
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradle/libs.versions.toml · gradle/wrapper/
├── keystore/ (git-ignored; your release signing key)
└── app/
    ├── build.gradle.kts · proguard-rules.pro
    ├── schemas/                         Room schema history
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── res/ (strings, themes, icons, xml/data_extraction_rules.xml)
        │   └── java/com/remedicord/
        │       ├── ReMedicordApp.kt         Hilt app, notification channels
        │       ├── MainActivity.kt
        │       ├── core/
        │       │   ├── security/  DbKeyManager.kt
        │       │   ├── time/      TimeProvider.kt, ZoneUtils.kt
        │       │   └── di/        DatabaseModule.kt, AppModule.kt
        │       ├── data/
        │       │   ├── db/        AppDatabase.kt, Converters.kt, entity/*, dao/*, migrations/*
        │       │   ├── repo/      MedicationRepo, RegimenRepo, DoseRepo, SkipRepo, SettingsRepo, AuditRepo
        │       │   └── backup/    BackupExporter.kt, BackupImporter.kt, BackupModels.kt, SnapshotManager.kt
        │       ├── domain/
        │       │   ├── model/     Medication, Regimen, ScheduleRule (sealed), DoseEvent, enums
        │       │   ├── schedule/  DoseGenerator.kt, SlotGrouper.kt, TaperBuilder.kt
        │       │   ├── policy/    CoursePolicy.kt, StopPolicy.kt, SkipPolicy.kt, PrnPolicy.kt
        │       │   └── usecase/   TakeDose, SnoozeDose, SkipDoses, ChangeDose, StopMedication,
        │       │                  LogLateDose, SuggestInjectionSite, ComputeAdherence
        │       ├── reminders/
        │       │   ├── ReminderPlanner.kt, AlarmScheduler.kt, NotificationFactory.kt
        │       │   ├── ReminderReceiver.kt, DoseActionReceiver.kt, SystemEventReceiver.kt
        │       │   ├── AlarmActivity.kt, MaintenanceAlarm.kt, ReminderHealthChecker.kt
        │       └── ui/
        │           ├── theme/ · navigation/ · components/
        │           ├── onboarding/ · today/ · medications/ (list, editor, detail, dialogs)
        │           ├── history/ · backup/ · settings/ · health/
        ├── test/          JVM unit tests (domain, policies, generator, backup round trip)
        └── androidTest/   Room/SQLCipher, receivers, UI flows
```

---

## 10. Build phases (each ends with an installable APK and a checklist)

Every phase also requires **`gradlew verifyAll` to pass** (all automated tests + security checks in sections 11 and 12, including emulator tests) before it counts as done. `gradlew verifyFast` runs after every change during the phase.

### Phase 0: Toolchain and skeleton ✅ (2026-11-07, except the emulator image, moved to Phase 1)
- Git ✅, Android Studio ✅, local git repo ✅. Remaining: Android SDK (Android Studio setup wizard), emulator image for the Gradle Managed Device.
- `.gitignore` and the pre-push secret check (§3.3).
- Create the Gradle project, version catalog, Hilt, Compose theme, empty navigation.
- **Build output is redirected outside OneDrive** (to `%LOCALAPPDATA%\reMedicord-build`). Otherwise OneDrive keeps syncing thousands of temporary build files and locks them, which breaks builds.
- Manifest hardening (no INTERNET + build check, backup disabled), signing key + git-ignored properties, R8.
- **Done when:** `gradlew assembleRelease` produces a signed APK that installs and opens, and the merged manifest has no INTERNET.

### Progress log (2026-11-07)
**v1.2.0 (2026-11-09):** E1–E8 from [ENHANCEMENTS.md](ENHANCEMENTS.md):
- time-of-day sections
- calendar status filter removed
- compact medicine filter
- delete a medicine (with must-complete warning and undo snapshot)
- one "Edit record" sheet per dose
- bold medicine summary card
- pause until a date (auto-resume) or until I resume (DB schema v3, tested migration)
- modern Material 3 UI: progress ring, type icons, 8 medicine colours, swipe with Undo

**v1.1.0 (2026-11-09):** the R24–R31 changes. `verifyAll` passes with **86 unit tests** (98% coverage) and **35 instrumented tests**. (The v1.1.0 commit message says "101 unit"; that count was wrong, and 86 is correct.)

**Phases 0–7 are built (v1.0.0), and `gradlew verifyAll` passes:**
- 74 unit tests and 31 instrumented tests on the managed emulator (Pixel 6, API 34 ATD).
- Coverage 97.7% (domain + backup validation).
- Lint (warnings as errors), the manifest security gate, and a signed release build.

The first emulator run found 1 app bug and 2 test bugs, all fixed:
- **App bug:** the planner deleted and re-inserted upcoming doses on every re-plan, which changed their ids, so a stale id from a screen or notification would silently do nothing. It now updates rows in place, and a test checks that ids stay stable.
- **Test bugs:** a new medicine's schedule starts "now", so the gap test needed a back-dated regimen; and the PIN UI test didn't expect the first-launch reminder setup screen.

Decisions made during the build:
- **Robolectric is not used.** Its Android runtime jars download outside Gradle's checksum verification. All Android-level tests (DB, receivers, UI) run as instrumented tests on the managed emulator instead.
- **Notification buttons:** Android allows 3, so the notification shows **Taken · Snooze (default minutes) · Skip**. The 5 / 10 / 15 snooze choices are on the full-screen alarm screen and the Today screen.
- **Receivers** use a Hilt `EntryPoint` lookup *after* validating input (no field injection), and never build DB-backed objects before the first unlock.
- **Removed library components** caught by the security gate: Compose's `ProfileInstallReceiver` (exported) and Room's `MultiInstanceInvalidationService`.
- **Bugs caught by lint:** literal bidi-override characters in source (Trojan-Source pattern), and the Android 12 notification-permission check (would have silently blocked all reminders on Android 12).

### Phase 1: Encrypted data layer ✅
- `DbKeyManager` (Keystore), Room + SQLCipher, all entities/DAOs, converters, schema export, audit logging in repos.
- **Done when:** DAO tests pass (Robolectric, plain SQLite), and the **instrumented** tests on the emulator prove the SQLCipher DB works, can't be opened without the key, and destructive migration is disabled.

### Phase 2: Domain logic (pure Kotlin, test-first) ✅
- `ScheduleRule` types, `DoseGenerator`, `SlotGrouper`, `TaperBuilder`, course/stop/skip/PRN policies, site rotation, adherence.
- **Done when:** unit tests cover every rule type, DST gap/overlap, time-zone travel in both time-zone modes, midnight crossing, taper transitions, skip ranges, must-complete blocks, course completion and calculated extension, and gap detection → UNCONFIRMED.

### Phase 3: PIN lock + medication management UI ✅
- S0 PIN lock (in-app keypad, hashing, clock-proof attempt countdown, background timeout), PIN setup.
- S3, S4 (all types incl. bandaging, taper builder), S5, S6, S7, S8.
- **Done when:** you can create one of each type, change a dose, skip with each mode, and stop/complete. Blocked actions on a must-complete course show a clear explanation.

### Phase 4: Reminder engine ✅
- Planner (incl. gap check), AlarmScheduler, receivers, device-protected timestamp list, grouped notifications (generic when locked, unlock required for Taken/Skip), full-screen alarm (S10), snooze/repeat/missed, eye-drop gap sequencing, daily worker, onboarding (S1), health screen (S13).
- **Done when (on a real phone):** alarms fire on time with the screen off, after a restart **before the first unlock**, after a time-zone change, and in forced Doze (`adb shell dumpsys deviceidle force-idle`). Every notification action updates history correctly. The lock screen never shows medicine names.

### Phase 5: Today and History ✅
- S2 (Today, "Needs review" card, PRN "Take now", late logging), S14 (review missed period), S9 (calendar, day detail, edit with audit, adherence).
- **Done when:** a week of mixed actions shows correctly in the calendar and stats, edits appear in the audit trail, and a simulated "phone off for 2 days" produces the review prompt, with each answer updating courses correctly.

### Phase 6: Backup and restore ✅
- S11, exporter/importer, validation, snapshots, CSV, backup reminder.
- **Done when:** export → uninstall → reinstall → restore brings back identical data (automated round-trip test + manual test), corrupted or malformed/malicious ZIPs are rejected with no data change, the CSV is formula-safe, and post-restore doses go to the review prompt.

### Phase 7: Polish and release ✅ (v1.0.0; GitHub publish pending your login)
- S12 settings, accessibility (large text, TalkBack labels), dark mode, empty states, app icon, final R8 release build, install guide.
- Choose a licence, run the pre-push secret check, publish to a **public** GitHub repository.
- **Done when:** the full manual test checklist passes, the signed release APK is delivered in `release/` (git-ignored), and the repo is on GitHub with no keystore, passwords or backups in it.

---

## 11. Automated testing

Every function gets an automated test. Tests are written **before or with** the code (test-first for domain logic). Two commands:

| Command | Runs | When |
|---|---|---|
| **`gradlew verifyFast`** | Unit + Robolectric tests, lint, manifest/security checks, coverage gate | After every change (no emulator needed, ~1–2 min) |
| **`gradlew verifyAll`** | `verifyFast` + instrumented and UI tests on a **Gradle Managed Device** (headless emulator started and stopped automatically) | Required to finish every phase |

| Layer | Tool | What it proves | Examples |
|---|---|---|---|
| **Unit (JVM, fast)** | JUnit + coroutines-test + fake clock | All business rules | Every schedule type; DST gap/overlap; time-zone travel in LOCAL and HOME modes; taper steps; skip modes; must-complete blocks; course end moves back on missed/skipped (days & doses mode) and moves forward again on late "taken"; gap detection → UNCONFIRMED and each review answer; PRN gap/max; eye-drop gap from actual taken time; site rotation (injection, patch); adherence % (UNCONFIRMED excluded); PIN hashing & clock-proof lockout escalation; CSV formula escaping |
| **Database logic** | Robolectric + Room (plain SQLite) | Queries and constraints are correct | CRUD per table; unique constraints; DAO queries |
| **Database encryption** | **Instrumented** (emulator) + Room + SQLCipher. SQLCipher's native library can't run under Robolectric | Data is encrypted and upgrades are safe | DB file unreadable without the key; wrong key fails; **every schema migration** v1 → latest; destructive migration fallback disabled |
| **Reminder engine** | Robolectric (`ShadowAlarmManager`, `ShadowNotificationManager`) | Right alarms at the right time, right notifications | Replan sets one alarm per slot; cancelled meds remove alarms; restart/time-zone broadcasts trigger replan; locked-boot path uses the timestamp list; notification public version has no medicine names; Taken/Skip actions require authentication; Taken/Snooze/Skip update the DB and cancel repeats; no reply → repeats → MISSED |
| **Backup** | JUnit + Robolectric | Export/restore is lossless and safe | Export → wipe → restore = identical data; PIN never exported; corrupted / malformed / unknown-entry / zip-slip / oversized / wrong-version ZIPs are rejected with no data change; post-restore doses → UNCONFIRMED |
| **UI / end-to-end** | Compose UI Test on emulator | Screens work as a user would use them | PIN setup & unlock & lockout; add one medicine of every type; change dose; skip; stop blocked on must-complete; Today actions; review-missed-period prompt; history edit; backup screen |
| **Security tests** | See 12.2 | Nothing outside the app can reach in | Exported-component scan, intent injection attempts, manifest permission check |
| **Static checks** | Android Lint (warnings = errors, security checks on), Kotlin compiler warnings as errors | No known risky code patterns | |
| **Coverage** | Kover (JVM tests only) | Business logic is thoroughly tested | Build fails below **90%** for `domain/` and backup validation. Instrumented-test coverage is reported, not gated |

Real-device checks that can't be fully automated (run with `adb` scripts where possible): forced Doze (`dumpsys deviceidle force-idle`), restart, battery saver, app swiped from recents, notifications denied.

---

## 12. Security verification

### 12.1 Design rules (no interaction with other apps, nothing exposed, nothing injectable)
| Threat | Protection |
|---|---|
| Network access / data leaving the device | No `INTERNET` permission at all (enforced by a build check) |
| Other apps calling into reMedicord | **Every component is `exported="false"`** except the launcher activity, which is required for the home-screen icon. It accepts only the plain launch action and **ignores all incoming data/extras**. System broadcasts (restart, time change) still arrive at non-exported receivers because they come from the OS itself |
| APIs exposed to the outside | **None**: no ContentProvider, no Service, no deep links, no app links, no share targets, no file provider, no WebView, no JavaScript |
| Tampering with notification buttons | All `PendingIntent`s are **explicit** (fixed target class) and **`FLAG_IMMUTABLE`**, so other apps can't redirect or modify them. Receivers also re-check that the action is known and the dose id exists in the DB, and ignore anything else |
| SQL injection | Only Room with parameterised queries. No raw SQL built from strings |
| Malicious backup file (injection via import) | Strict JSON schema validation, only known files accepted, unknown fields/enum values rejected, type/range checks on every field, zip-slip and zip-bomb protection, size and entry limits, all-or-nothing transaction. (SHA-256 only detects accidental corruption) |
| Malicious text in fields | Input length limits and character validation. Text is only ever displayed, never executed |
| Formula injection via exported CSV | Text starting with `= + - @` (or tab/CR) is escaped in `history.csv` |
| Third-party keyboards | PIN uses an in-app keypad (never the system keyboard). All text fields use **incognito mode** (`IME_FLAG_NO_PERSONALIZED_LEARNING`) so keyboards don't learn medicine names |
| Someone seeing reminders on the locked phone | Generic notification and full-screen alarm until unlock. Taken/Skip require unlock |
| Overlay / tapjacking on the PIN screen | `filterTouchesWhenObscured` on the PIN keypad and confirmation buttons |
| Guessing the PIN | Salted PBKDF2 hash, escalating lockout that survives restarts |
| Debug access | Release APK is `debuggable=false`, logs stripped by R8, no test hooks in release |
| Backup to cloud / other device | `allowBackup=false` + data-extraction rules exclude everything |
| Vulnerable libraries | Minimal dependencies (Google/JetBrains + SQLCipher only), latest stable versions |
| Secrets leaking into the public repo | `.gitignore` + pre-push secret check (§3.3) |

**Out of scope (can't be prevented by any app):** a rooted or compromised phone, someone who knows your PIN, or an accessibility app you granted full screen access to yourself.

### 12.2 Automated security tests (part of `verifyAll`)
1. **Merged-manifest test:** parses the final release manifest and fails if it contains INTERNET or any other non-allowed permission, any exported component other than the launcher, any `<provider>`, `<service>`, intent-filter `<data>` (deep link), or `debuggable=true`.
2. **Intent injection test:** sends crafted intents/extras to the launcher activity and every receiver and verifies nothing happens: no crash, no DB change, no alarm.
3. **PendingIntent audit test:** checks that every created PendingIntent is explicit and immutable.
4. **Backup fuzz tests:** malformed, oversized, path-traversal, unknown-entry and type-confused ZIP/JSON inputs are all rejected cleanly.
5. **CSV injection test:** names/notes starting with `= + - @` are escaped in the exported CSV.
6. **Lock-screen privacy test:** the notification's public version contains no medicine data, and Taken/Skip actions require authentication.
7. **Lint security rules** treated as errors (exported components, unsafe intents, crypto misuse, logging).

### 12.3 Release security check (on a real device, scripted with adb)
- `adb shell dumpsys package com.remedicord` → confirm **zero exported components besides the launcher**, and the permission list is exactly: `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (≤ API 32), `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- `adb shell am start` / `am broadcast` against every internal component from outside → must be refused ("not exported").
- Confirm the app has no network access listed in system settings.
- Optional deeper scan with the open-source **MobSF** static analyser (run locally on the PC) on the release APK.
