# reMedicord: Enhancement backlog

Recorded 2026-11-09 after v1.1.0. **All items E1–E8 were implemented in v1.2.0.** Each item lists the request, a proposed design, and the open questions to settle before building. Status: 📝 recorded · ❓ needs decision · ✅ done.

---

## E1. Time-of-day sections (History + Home) ✅ (v1.2.0)
**Request:** On History and Home (Today), group records into **Morning / Afternoon / Evening** sections that can be **collapsed and expanded**.

**Proposed design**
- Sections: **Morning, Afternoon, Evening, Night**, each header showing a count summary, e.g. *"Morning · 3/4 taken · 1 needs answer"*.
- Default boundaries: Morning 05:00–11:59, Afternoon 12:00–16:59, Evening 17:00–20:59, Night 21:00–04:59. Empty sections are hidden.
- Flexible doses (R29) go into the section of their own period name.
- On **Home/today**, the current section and any section with due or needs-answer doses start **expanded**. Past sections where everything is done start **collapsed**.
- On **History**, every section of the selected day starts expanded. Expand/collapse choices are remembered while the screen is open.
- A tiny coloured summary dot sits on each collapsed header, so problems stay visible when collapsed.

**Decision (2026-11-09):** proposed design accepted as is: four sections Morning / Afternoon / Evening / Night with the default boundaries above (fixed, not adjustable).

---

## E2. Calendar status filter (taken / missed / skipped): remove ✅ (v1.2.0)
**Question asked:** should the calendar's status filter be removed?

**Recommendation:** remove the multi-select status chips. They add clutter, and the per-day summary plus coloured dots already show status. Replace them with **one optional toggle: "Only show days with problems"** (missed or needs answer). It answers the common question, "which days did I miss?", with one tap.

**Decision (2026-11-09):** **remove completely.** No status chips and no "problems only" toggle. The coloured dots and the per-day summary remain.

---

## E3. Compact medicine filter ✅ (v1.2.0)
**Request:** with more than 5 medicines, the medicine filter chips take too much space and need scrolling.

**Proposed design**
- Replace the chip list with a single **"Medicines: All ▾"** button. It shows "Medicines: 3 selected" once you've picked some.
- Tapping it opens a **bottom sheet** with:
  - a search box
  - checkboxes, active medicines first, then finished ones
  - "Select all" and "Clear" at the top
  - "Apply" at the bottom
- It takes one line on screen regardless of how many medicines there are.

---

## E4. Remove a medicine (added by mistake) ✅ (v1.2.0)
**Request:** allow removing a medicine in case it was wrongly added.

**Analysis:** this is different from **Stop**. Stop keeps history, while removing **erases the medicine and all its dose records** permanently.

**Proposed design**
- "Delete medicine" sits at the bottom of the medicine detail screen, styled as a danger action and separate from Stop and Pause.
- Confirmation shows exactly what will be erased: *"Delete Amoxicillin and its 23 dose records? This can't be undone."* You confirm by typing the medicine's name.
- An **automatic snapshot** is taken first, like before a restore, so a mistaken delete can be undone from Backup & restore.
- Any reminders and notifications for it are cancelled. The audit trail keeps a "Medicine deleted" entry: name and date only.

**Decision (2026-11-09):**
- **Any medicine can be deleted**, with the typed-name confirmation and the automatic undo snapshot.
- **A must-complete course that has started** (at least one dose recorded) shows an **extra warning first**: *"This course must be completed and has already started (8 of 21 taken). Deleting removes the course and its history. Only do this if it was added by mistake."* Deletion only goes ahead after that warning is acknowledged, then the normal typed confirmation.

---

## E5. "Edit record" instead of "Change to missed / taken" ✅ (v1.2.0)
**Request:** replace the separate change buttons with one common **"Edit record"** (or **›**) button. It edits **the individual dose record**, not the medicine details, and must **not** open the medicine details page.

**Proposed design**
- Every dose card on Home and History gets one **›** / **Edit record** affordance; the whole card is tappable.
- It opens a **record sheet** (bottom sheet) for that single intake:
  - **Status:** Taken · Missed · Skipped (and Needs answer → answer it)
  - **Taken at** time, when Taken
  - **Skip reason**, when Skipped
  - **Site** (injection/patch) or **side** (eye/ear)
  - **Note**
  - **Change history** (audit count)
- Within the 5-day window the sheet is editable. Older records open the same sheet **read-only**.
- Tapping the **medicine name** on a card no longer jumps to medicine details from Home or History. Medicine details are reached only from the Medicines tab.
- The quick actions for due doses (Taken, Later, Skip) stay on today's cards because they're time-critical. Everything else moves into the sheet.

---

## E6. Medicine detail page: highlight what matters ✅ (v1.2.0)
**Request:** important details highlighted and/or bold.

**Proposed design:** a summary card at the top with **bold** values:
- **Current dose** and **schedule**
- **Next dose** (date and time)
- **Course progress**, e.g. "14 / 21 taken, ends Tue 18 Nov"
- **Why** (purpose)

Then:
- Badges for **Must complete**, **Paused until …** and **Reminders off today**.
- Status shown as a coloured badge (Active / Paused / Stopped / Completed).
- Secondary information (instructions, dose history, active skips) in clearly titled sections below, in regular weight.

---

## E7. Pause: until a date (auto-resume) or until I resume ✅ (v1.2.0)
**Request:** pausing must ask for a **timeline**, with **past days not selectable**. When the pause ends, ask whether to **extend** or **resume**.

**Proposed design**
- Pause asks **how long**, with two options:
  1. **Until a date:** a date picker where **past dates are disabled**, plus quick choices (3 days, 1 week, 2 weeks). **Reminders resume automatically** when the date is reached (start of the day after the chosen end date). A notification confirms: *"Vitamin D resumed: reminders are back on."*
  2. **Until I resume:** no end date. Paused until you tap Resume.
- Optional pause reason.
- While paused, there are no reminders and no planned doses. The medicine shows **"Paused until 20 Nov"** or **"Paused until you resume"**, and Resume is always available early.
- Schema change: `pausedUntil` (null = until I resume) + `pauseReason` on medication (DB v3, with a tested migration). Auto-resume is done by the planner and daily upkeep, and also caught on restart or time change.
- Must-complete courses still can't be paused (existing rule).

**Decision (2026-11-09):**
- Two options: **"Until a date"** and **"Until I resume"**.
- A dated pause **resumes automatically** at the end, so there's no extend-or-resume question. Only a confirmation notification is shown.
- The pause can start **today**; only past days are blocked.
- *(Updated from the original request, which asked to extend or resume at the end.)*

---

---

## E8. Modern app-style UI refresh ✅ (v1.2.0)

**Decision (2026-11-09):**
- **Swipe gestures** on dose cards: yes. Swipe right = Taken, swipe left = Later, always with an Undo snackbar.
- **Colour per medicine:** yes, chosen from 8 soft colours (stored in the existing `colorTag` field).
- **Icons:** a small hand-picked set of vector icons; no `material-icons-extended`.
- **App icon:** no redesign (keep the teal capsule).
- Visual reference: the interactive preview reviewed by the user (Home, record sheet, History, medicine detail, pause, delete).

**Request:** improve the UI in a modern app style.

**Proposed design** (Google's Material 3 guidelines, using what the app already has: Jetpack Compose, Material 3 and dynamic colours from the wallpaper)

*Layout & navigation*
- **Large collapsing top bar** on each main screen (title shrinks as you scroll), edge-to-edge, consistent 16 dp margins on an 8 dp spacing grid.
- **Bottom sheets instead of pop-up dialogs** for choices (record editing E5, skip reason, pause E7, filters E3). They're easier to reach with one hand.
- **Segmented buttons** instead of chip rows where there are only 2–4 options (e.g. Follow local time / Keep home time, Days / Doses).

*Home (Today)*
- **Header card with a progress ring:** "3 of 5 done today", plus the next dose ("Next: Timolol at 20:00").
- **Dose cards** with rounded corners, a **medicine-type icon** (pill, drop, syringe, inhaler, cream, patch, bandage), a **per-medicine colour tag** (the existing `colorTag` field) and the soft status colours (R31). The status is also written as a small label, for accessibility.
- **Undo snackbar** after Taken / Skip / Later ("Marked as taken · UNDO") instead of confirmation pop-ups, for fast one-tap actions that are still recoverable.
- Small **haptic tick** when marking Taken.
- **Animated** expand/collapse for the time-of-day sections (E1), plus gentle list animations when a card changes status.

*Medicines & detail*
- List rows with type icon, colour tag, name in bold, purpose and schedule underneath, and a status badge.
- Detail page in E6 style: a hero summary card with bold key facts, then sections.

*Polish*
- Friendly **empty states** (icon + one line + action button), e.g. "No medicines yet · Add your first".
- **Touch targets ≥ 48 dp**, full support for large system font sizes, and TalkBack labels on every icon.
- Light/dark mode and the wallpaper-based colours already work; colours would be reviewed for contrast in both.

**Original questions (all decided above)**
- ✅ **Swipe gestures** on dose cards (swipe right = Taken, swipe left = Later / Skip)? They're fast, but easy to trigger by accident; the undo snackbar would cover that. Recommendation: yes, with undo.
- ✅ **Per-medicine colour:** let the user pick a colour for each medicine (shown as a tag and on reminders)? Recommendation: yes, from 8 soft colours.
- ✅ **Icons:** use Google's official Material icons set (`material-icons-extended`: large, but unused icons are stripped from the APK), or hand-pick ~15 icons as small vector files (no new library)? Recommendation: hand-picked vectors, which keeps dependencies minimal.
- ✅ **App icon / brand colour:** keep the current teal capsule, or refresh it too?

---

## Implementation notes (for when these are scheduled)
- E7 changes the database (v3), so it needs a migration test like v1 → v2.
- E5 and E1 change both main screens together, so they're best built in the same round.
- E8 overlaps E1, E3, E5, E6 and E7 (sheets, sections, detail card). Recommended order: build E8's shared components first (sheet, card, icons, colours), then E1 / E5 / E6 / E3 on top, then E4 and E7.
- Every item gets unit and/or emulator tests, and must pass `verifyAll` before release.
