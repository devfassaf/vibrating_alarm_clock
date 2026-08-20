# Working on VibeAlarm

An Android alarm clock whose reason to exist is one scenario: **waking up on Shabbat
morning without waking the household and without touching the phone at all.** Read
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the code map; this file is the set of
rules that must survive every future change.

If a change would break one of the invariants below, stop and say so instead of working
around it. They are not style preferences — each one is a morning that would be missed.

## The product rule that outranks the others

**Zero interaction, and no safety nets.** The vibration pattern plays **once**, stops
itself, auto-snoozes, rings again, and after the last repeat the chain ends silently. The
user has said explicitly, more than once: never add "keeps ringing until you switch it
off" behaviour, never add a confirmation the user must tap to make an alarm work, never
require touching the phone. On Shabbat touching it is forbidden, so a feature that needs a
tap is a feature that does not exist.

The one thing added *after* the fact is allowed to be informational: the
"it rang and was never dismissed" notice (silent, no vibration, nothing to press).

## Invariants

1. **Direct Boot.** Every component on the alarm path is `directBootAware` and listens for
   `LOCKED_BOOT_COMPLETED`. All state lives in **device-protected storage**: `AppGraph`
   exposes only `deviceProtectedContext`, and `data/` and `alarm/` must never receive any
   other `Context`. One credential-encrypted read on this path silently breaks every alarm
   after a night-time reboot. DataStore needs an explicit `File(context.filesDir, ...)` —
   the `dataStore` delegate resolves the application context and lands in CE storage.
2. **`setAlarmClock` only.** It is the sole scheduling API that is fully exempt from Doze
   and App Standby. One PendingIntent per alarm (`AlarmIntents` request-code offsets + a
   per-alarm data URI), so a failure in one alarm cannot silence the others.
3. **The service performs `Fire`, never the receiver.** `AlarmReceiver` hands the trigger
   to `AlarmRingingService` and does not touch the state machine: only the service owns the
   vibrator, and a `Fire` run in the receiver marks the instance FIRING, turning the
   service's own transition into a no-op — a silent alarm.
4. **The reducer is pure and its effect order is load-bearing.** `AlarmSessionReducer`
   returns effects that `SessionRuntime` applies in order: `Persist` before `ArmExact` (a
   crash between them is recoverable by Resume), and anything that posts a notification
   comes **after** `CancelNotifications`.
5. **One mutex.** `AlarmScheduler.mutex` is shared with `SessionRuntime`; public entry
   points take it, `*Locked` variants assume it. A Kotlin `Mutex` is not reentrant — never
   call a locking function from inside the lock.
6. **Disabling an alarm drops its live instance** (`AlarmRepository.setAlarmEnabled`).
   `resumeAll` walks instances, not the enabled flag, so a leftover instance would re-arm
   an alarm the user switched off.
7. **Alarm output ignores the ringer mode.** Sound goes out with
   `AudioAttributes.USAGE_ALARM` on `STREAM_ALARM`; vibration with
   `VibrationAttributes.USAGE_ALARM`. Silent and vibrate-only modes must never silence an
   alarm the user asked to hear. Notification channels carry **no** sound and **no**
   vibration of their own — output is driven explicitly by the engines. `SilentModeTest`
   fails if any of this is dropped.
8. **The alarm card edits a draft.** `withEditsFrom` and `editedFields` must list the same
   fields; `AlarmEditsTest` fails if one forgets a field the other has. Fields the editor
   does *not* own (`enabled`, `patternId`, timestamps) come from the freshest row, so a save
   cannot revive an alarm that finished while the card was open.
9. **Every schema change needs a hand-written migration.** `app/schemas/` is committed;
   bump `AppDb.version`, add a `Migration`, and extend `AppDbMigrationTest`. Never
   `fallbackToDestructiveMigration`: those rows are the alarms someone is relying on
   tomorrow morning.

10. **Saving a card enables the alarm** and closes the editor. An edit that left the alarm
    off would be a silent morning wearing the appearance of a saved alarm.
11. **User-facing URLs must resolve today.** `UpdateAssets.siteUrl()` is what the app's
    home button opens: the GitHub Pages landing page from `main` + `/docs`, which is
    enabled. Pages being on is a precondition of that URL — if it is ever switched off,
    point the button at `projectUrl()` (the repository, which always renders the README)
    rather than shipping a button that 404s.

12. **Sound length and vibration length are separate.** The ring duration governs the
    ringtone only; the pattern governs the vibration and plays **once**, never looping to
    fill a long ringtone. The alerting window is the longer of the two (`AlertWindow`), so
    neither can cut the other short.
13. **The alarm stream may be raised, never lowered, and the chosen loudness is honoured
    either way.** The stream is shared with every other alarm clock on the phone
    (`AlarmStreamVolume`): lowering it to reach a quiet per-alarm volume would play the
    built-in clock's alarm at our level too. Too quiet, and it is raised to the level the
    user chose; louder than needed, and the player is attenuated instead — by the real dB
    difference between the two indices (`getStreamVolumeDb`), because volume indices are
    dB-spaced while `MediaPlayer.setVolume` is linear. Neither the ringer mode nor the
    phone's own volume may change how loud a configured alarm comes out.

14. **A notice the user cannot clear from inside the app is a red dot with no explanation.**
    The morning-after notices (`showUnattended`, `showMissed`) put a badge on the launcher
    icon, and Samsung shows the notification itself as a two-second pill — so the app has to
    say the same thing in a place that waits. The banner and the notification are two faces
    of one `instances` row (`endedReason` + `noticeAckAt`): they are built from the same
    `NoticeText`, and whatever retires one retires the other. Opening the app clears
    nothing; only "הבנתי" does, plus the alarm ringing again.
    **`acknowledgeNoticesFor` must never touch a live chain** — it runs from inside a ring,
    and without the `state = 3` filter the ringing chain marks its own row read before it
    has anything to report, so the notice it goes on to create is invisible from birth.

15. **A full-screen intent is only weighed when the notification is *added*.** It must ride
    on `buildStarting` — the post that `startForeground` makes — and not only on the
    `buildFiring` update that follows a moment later. Measured on a dozing phone: with the
    intent on the update alone, SystemUI granted the first ring of a chain and never even
    evaluated the second, so ring two arrived with nothing on screen, no snooze, no dismiss,
    and no activity to read the volume keys. Neither launch route may carry
    `FLAG_ACTIVITY_CLEAR_TASK`: one ring can start the screen twice (the platform, and the
    service which cannot know whether the platform did), and CLEAR_TASK made the second start
    tear down what the first had just shown. `singleInstance` turns the duplicate into
    `onNewIntent`. The service's own `startActivity` is a second chance, gated on
    `turnScreenOn` — a dark-screen alarm must never light anything up — and it needs
    `SYSTEM_ALERT_WINDOW` to survive background-activity-launch checks, which is why that is
    an **informational** row in the reliability screen rather than a demand.
16. **Volume keys snooze through two mechanisms, because neither covers the job alone.**
    `AlarmActivity.onKeyDown` works only while that activity has focus; `VolumeKeySnooze`'s
    media session receives the keys with the screen off, but only while nothing is playing on
    the alarm stream — with a ringtone active the platform routes them to that stream instead,
    which is what the stream watch is for. Measured: with the screen dark *and* a ringtone
    playing, the key never reaches the app or the stream at all, so that one combination
    cannot be caught from an app. The keys always **snooze**, never dismiss.

17. **A duplicated alarm is created switched off.** Two enabled alarms on the same minute
    run into `preemptOthers`: one silences the other, the silenced one is recorded
    `PREEMPTED`, and the morning after reports a missed alarm that never failed. The copy's
    card opens as the draft instead — moving it is the reason to duplicate — and saving is
    what enables it (invariant 10). Long-press actions go through the same unsaved-changes
    guard as opening another card, because duplicating takes over the draft.

## Conventions

- **Strings**: `values/` (English) and `values-iw/` (Hebrew) must stay at exact parity, with
  no unused entries. Hebrew needs `one`/`two`/`other` plural forms, and the maqaf belongs
  before a numeral ("ו-23 שעות") but not before a word ("ושעה") — see
  `time_until_two_parts`. Never build a Hebrew sentence by concatenating fragments in code.
- **Day names** come from our own strings (`day_sunday`…`day_saturday`), never from
  `DayOfWeek.getDisplayName`: the platform's short Hebrew name for Sunday is "יום א׳", and
  truncating it gives the same two letters for six days.
- **No group shorthands for days** ("weekend", "weekdays"): which days those are depends on
  the country, and this app is about Saturday.
- **Pure logic goes in `domain/`** (no `android.*`) so it is unit-testable, and the UI keeps
  only wiring. When a behaviour is worth pinning, extract the decision into a pure function
  rather than testing it through Compose.
- **Comments explain why, not what.** The repo's existing comments are the standard: they
  name the failure the code prevents.
- Ktlint-ish formatting, trailing commas, 100-column-ish lines. `./gradlew lintDebug` must
  report zero errors (invisible bidi characters in source are lint **errors** — use Unicode
  escapes in regexes and tests).

## Before you commit

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

All of it green, plus:

- **strings parity + no unused strings** (see the check in the README's test section);
- **documentation updated in the same commit** — README for behaviour, this file and
  ARCHITECTURE.md for structure, `docs/index.html` for the landing page;
- **verified on the emulator** for anything a user can see. The JVM suite cannot tell you
  that a drag gesture reaches its handler, that a notification is readable at 6am, or that
  an RTL layout is not mirrored wrongly.

## Verifying on the emulator

The AVD is `vibealarm_test` (Android 15, FBE, lock PIN **1234**). It boots
**before-first-unlock**, where non-`directBootAware` activities report "does not exist" —
unlock before installing or launching:

```bash
adb shell input keyevent KEYCODE_WAKEUP && adb shell input swipe 540 1600 540 600 && adb shell input text 1234 && adb shell input keyevent KEYCODE_ENTER
```

Useful facts:

- The database is `/data/user_de/0/com.faybish.vibealarm/databases/alarms.db` (the
  `vibealarm.db` file next to it is empty). Read it in a debug build with
  `adb shell run-as com.faybish.vibealarm sqlite3 <path> '<sql>'` — the fastest way to prove
  a UI change did or did not write to storage.
- `adb install -r` over an existing install exercises the Room migration for real.
- `adb shell dumpsys notification --noredact | grep -A4 pkg=com.faybish.vibealarm` shows the
  live notification, its channel, and whether it carries sound or vibration.
- The reliability log is the app's own account of what happened:
  `select datetime(timestamp/1000,'unixepoch','localtime'),event,detail from reliability_log order by id desc limit 10;`
- A full-screen intent launches `AlarmActivity` only when the screen is off/locked. With the
  device unlocked and in use, Android shows a heads-up notification instead — that is
  platform behaviour, not a bug.
- The emulator does not vibrate and gives no meaningful audio, so *feel* and *loudness* are
  the two things only the real Galaxy can answer.

## Releasing

```bash
./release.sh patch "מה חדש: ..."
```

The script refuses to run on a dirty tree, syncs `main` fast-forward-only, bumps
`versionName` **and** `versionCode` together, runs the tests, builds and verifies the
signature, and uploads two assets — `vibealarm-v<tag>.apk` and a fixed-name
`vibealarm.apk`, because GitHub's `/releases/latest/download/<name>` redirect matches by
exact name and the landing page's download button depends on it. A version with four
components (`1.0.0.1`) is refused: the in-app updater compares three.

Keep the release keystore. Android refuses to replace a signature, so a new key breaks the
in-app update for everyone who already installed.

## How the user works with you

Their global instructions (`~/.claude/FEATURE_WORKFLOW.md`) apply here: for any new
feature, investigate the code first, present the understanding, ask the decision questions,
surface the blind spots — and do not implement until the requirement is settled. Then
implement at production grade: repo conventions, docs in the same commit, tests for every
new behaviour, the whole suite green, verified end to end, and a PR.

They write in Hebrew and expect PR descriptions and release notes in Hebrew; code,
comments, and commit messages are in English. They read the diff — explain the *why*.
