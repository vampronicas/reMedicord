# reMedicord

A private, offline Android app for tracking medicines: pills, liquids, eye and ear drops, injections, inhalers, nasal sprays, creams, patches and wound dressings.

> **Not a medical device.** reMedicord helps you remember and record. It does not give medical advice. Always follow your doctor's or pharmacist's instructions.

## Privacy by design
- **No internet permission at all.** The app physically cannot send data anywhere. A build check fails if a network permission is ever added.
- **Encrypted on the phone.** Data is stored in a SQLCipher (AES-256) database. Its key is protected by the Android Keystore.
- **No cloud backup or device transfer.** Android's automatic backup is switched off.
- **Nothing exposed to other apps.** Only the home-screen launcher is exported. There are no services, content providers, deep links or share targets. All internal intents are explicit and immutable.
- **4-digit PIN lock** with escalating wait times after wrong attempts. The PIN is stored only as a salted PBKDF2 hash.
- **Private lock screen.** Reminders say only "Medication reminder" until the phone is unlocked.
- **Your backups, your files.** You can export a backup ZIP to a folder you choose. Note: the backup file is **not encrypted**.

## Features
- Schedules: times of day, chosen weekdays, every N hours (with quiet hours), every N days/weeks, on/off cycles, tapering, and as-needed (with minimum gap and daily maximum).
- Exact reminders that work in Doze, after a restart (even before the first unlock), and across time-zone and daylight-saving changes. Each medicine can follow local time or keep home-time intervals.
- Taken / Later / Skip… from the notification; the full-screen reminder also offers Snooze 5/10/15 and "Don't remind me again today". No answer means the reminder repeats, then the dose is marked missed.
- Doses that fell due while the phone was off are **not assumed**: you're asked whether you took them.
- Must-complete courses (by days or number of doses). Missed or skipped doses push the end date back. Stopping early is only allowed on a doctor's instruction or because of a reaction.
- Dose changes with history, skips (one dose, N doses, N days, until a date) with reasons, eye-drop spacing, and injection/patch site rotation.
- Home: progress ring and next dose; Morning / Afternoon / Evening / Night sections that collapse; type icons and a colour per medicine; swipe right = Taken, swipe left = Later, with Undo.
- "Later" and "Don't remind me again today"; doses waiting for an answer are asked about on the next open and closed as missed after 5 days.
- Flexible schedules (e.g. 2 a day, any of morning / afternoon / evening).
- One "Edit record" sheet per dose; past records can be corrected for 5 days.
- Pause until a date (resumes automatically) or until you resume. Delete a medicine added by mistake (undo snapshot kept).
- Calendar history with a per-medicine day summary, a compact medicine filter, and adherence %.
- "All taken" / "Skip all" for a whole morning (or any section), with Undo. Postpone the next 1–3 reminders by a few hours.
- Gentle reminder sounds made for the app (chime, soft beep, bell), or phone default or silent.

## Install (sideload)
See [docs/INSTALL.md](docs/INSTALL.md). Requires Android 12 or newer.

## Build from source
Requirements: Android Studio (stable) with the Android SDK; the bundled JDK is used.

```bash
./gradlew verifyFast        # unit tests, coverage gate, lint, manifest security gate
./gradlew verifyAll         # + instrumented tests on a headless managed emulator + release build
./gradlew assembleRelease   # needs your own signing key in keystore/keystore.properties (git-ignored)
```

Release signing reads `keystore/keystore.properties`:
```
storeFile=keystore/your-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

### Supply-chain protections
- Only Google Maven and Maven Central are allowed as repositories.
- Every downloaded library is pinned by SHA-256 (`gradle/verification-metadata.xml`), and the Gradle wrapper is pinned by checksum.
- Dependencies are kept minimal: AndroidX/Jetpack, Kotlin, Hilt and SQLCipher.

## Project layout
`docs/PLAN.md` is the full design: requirements, security model, data model, reminder engine and test plan.

| Package | Contents |
|---|---|
| `domain/` | Schedules, policies and planning. Pure Kotlin, ≥ 90% test coverage enforced |
| `data/` | Encrypted Room database, repositories, backup |
| `reminders/` | Alarms, notifications, receivers |
| `ui/` | Jetpack Compose screens |

## Licence
[MIT](LICENSE)
