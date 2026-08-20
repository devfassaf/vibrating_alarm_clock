# VibeAlarm — architecture

How the app is put together and why, for whoever maintains it next. The product side is in
the [README](../README.md); the rules that must not be broken are in
[CLAUDE.md](../CLAUDE.md).

Everything here follows from one requirement: **the alarm must ring, exactly as
configured, with nobody touching the phone — including after a reboot in the middle of the
night, before the PIN has been entered.** That is what pushes the state into the database,
the scheduling into `setAlarmClock`, and every alarm-path read into device-protected
storage.

## Layers

```
ui/        Compose screens and view models. Wiring only — no scheduling decisions.
domain/    Pure Kotlin, no android.*. Every decision worth testing lives here.
data/      Room in device-protected storage, DataStore settings, codecs, the log.
alarm/     The Android edge: AlarmManager, receivers, the foreground service, engines.
update/    GitHub release source, APK download and install.
```

`AppGraph` is a hand-written service locator (no Hilt: one fewer thing to be
direct-boot-aware). It exposes **only** the device-protected `Context`, which is what keeps
credential-encrypted storage out of the alarm path by construction rather than by
discipline.

### domain/ — the parts with no Android in them

| File | Decides |
| --- | --- |
| `NextOccurrenceCalculator` | when an alarm next fires, across one-time / weekly (with per-day times) / date lists, DST included |
| `AlarmSessionReducer` | the ring→snooze→end state machine, as a pure function |
| `AlarmSession` | its states, events and effects |
| `WaveformMapper` | pattern segments → vibrator waveform, including intensity emulation |
| `PatternSegment`, `RecorderQuantizer` | pattern shape and tap-recording cleanup |
| `ScheduleSummarizer` | how a schedule is described ("Sunday, Saturday", "Every day") |
| `TriggerDescriptor` | how the next ring is named (today / tomorrow / weekday / date) |
| `AutoSilence`, `SnoozeRepeats`, `SnoozeInterval` | the bounds on every free-choice number |
| `AlertWindow` | how long one ring lasts when sound and vibration disagree |
| `AlarmStreamVolume` | reaching the per-alarm level without turning other apps down |
| `VolumeRamp` | the ringtone's climb from quiet |
| `AlertSelection` | sound and vibration as two switches, with "neither" unrepresentable |
| `AlarmDuplicate` | naming a copy: the "(copy)" marker, added once, in the app's language |
| `update/` | version comparison and whether now is a good moment to offer an update |

## The alarm chain

One row in `instances` is one occurrence's chain, and it survives process death and
reboots. The reducer is the only thing that decides what happens next:

```
        armed trigger
             │
             ▼
   ┌──── SCHEDULED ────┐
   │         │ Fire    │ Resume (trigger long past)
   │         ▼         ▼
   │      FIRING    DONE(MISSED) ── ReportMissed
   │      │  │  │
   │      │  │  └─ UserDismiss ─▶ DONE(USER_DISMISSED)
   │      │  └──── UserSnooze ──┐
   │      │  PlaybackComplete   │
   │      ▼                     ▼
   │   budget left?  ──yes──▶ SNOOZED ──(armed)──▶ Fire ──▶ FIRING
   │      │ no                                              (repeats)
   │      ▼
   └▶ DONE(AUTO_DISMISSED) ── ReportUnattended + ScheduleNextOccurrence
```

`PlaybackComplete` is what makes the whole thing hands-free. The ring has **two
independent lengths** (`AlertWindow`): the ringtone plays for `autoSilenceSeconds`, the
pattern plays once — there is no "vibration finished" callback, so its length is computed
from the segments — and the window is whichever ends last, with the shorter one stopped on
its own timer. **The snooze interval is measured from the end of the ring, not its start**:
a 5-minute ringtone with a 1-minute snooze is a 6-minute cycle.

Effects are applied in list order by `SessionRuntime`, which is the only place where the
pure reducer meets Android. Two orderings are load-bearing: `Persist` before `ArmExact`, and
`CancelNotifications` before anything that posts one.

### Who runs what

- `AlarmReceiver` — the armed trigger and the notification action buttons. For a trigger it
  takes a wake lock and hands off to the service **without** running the state machine.
- `AlarmRingingService` — a `systemExempted` foreground service. Performs `Fire`, drives the
  engines, holds the wake lock for the window, then dispatches `PlaybackComplete` and stops.
  It does not survive a snooze: the chain lives in the database and in AlarmManager.
- `BootReceiver` — `LOCKED_BOOT_COMPLETED` **and** `BOOT_COMPLETED`; both may arrive,
  `armAll` is idempotent.
- `SystemEventReceiver` — time/timezone changes, our own package being replaced, and the
  exact-alarm permission changing (which cancels armed alarms outright).
- `MainActivity` — calls `syncSchedule()` on open. Deliberately *not* on process start: that
  would race the very trigger the process was woken for.

## Output

`VibrationEngine` builds a waveform via `WaveformMapper` and plays it with
`VibrationAttributes.USAGE_ALARM`. Devices without amplitude control get intensity emulated
as short pulses (PWM); amplitude 255 is the hardware ceiling, and the system's own vibration
strength scales everything below it.

`SoundEngine` plays on `STREAM_ALARM` with `USAGE_ALARM`. Per-alarm volume needs the stream
to be at least as loud as the level asked for, so the stream is **raised when too quiet and
never lowered** (`AlarmStreamVolume`) — it is shared with every other alarm clock on the
phone — and the remaining attenuation is the player's own volume, computed from the platform's
real dB values for the two indices (`getStreamVolumeDb`, API 28+) because indices are
dB-spaced while `setVolume` is linear. Either way the alarm comes out at the level the user
chose, whatever the phone's own volume happens to be. Sources are a fallback
chain — the chosen ringtone, the system default, then a bundled asset — because before first
unlock the user's own files are unreadable. The optional ramp steps the player's volume too,
never the stream.

Two alarm apps can therefore ring together, sound included. The vibrator cannot be shared:
one device, and each new request interrupts the previous one, so whichever app vibrates last
wins. That is a hardware limit, not something either app can arbitrate.

**Silent and vibrate-only modes cannot silence either engine**, and that is deliberate: the
ringer mode governs the ring and notification streams, not the alarm. The only interruption
setting that still can is Do Not Disturb on total silence, which the Reliability screen
reports.

### Notifications

Three channels, none of which carries a sound or a vibration of its own:

| Channel | For | Importance |
| --- | --- | --- |
| `alarm_alerting` | a ringing alarm that should light the screen (full-screen intent) | HIGH |
| `alarm_silent` | a ringing alarm in screen-stays-dark mode | LOW |
| `alarm_status` | snoozed, "missed alarm at 07:30", and never-rang notices | DEFAULT |

The two morning-after notices (`400_000+id` unattended, `300_000+id` never-rang) are the
only ones that can outlive the night, so they are the ones that put a red dot on the
launcher icon. Each is mirrored by a banner in the alarm list built from the same
`ui/format/NoticeText`, and the pair is retired together: `MissedNotice` rows come from
`instances` (`endedReason` in AUTO_DISMISSED/MISSED/PREEMPTED, `noticeAckAt IS NULL`),
"got it" writes `noticeAckAt` **and** calls `cancelNotices`, and the alarm ringing again
does both from `ShowFiringNotification`. `acknowledgeNoticesFor` is scoped to `state = 3`
because it runs from inside a ring.

Notification ids are per-alarm (`base + alarmId`) so one alarm's notice can never take
another's slot: firing `100000+`, snoozed `200000+`, missed `300000+`, unattended `400000+`.

## The UI

`AlarmListScreen` mirrors Google Clock's alarm tab: cards that expand in place. An open card
edits a **draft** held in `AlarmListViewModel`; nothing reaches the database until Save, and
every way out of the card (collapse, opening another, the back gesture) goes through the
unsaved-changes question. The switch is the exception — it acts immediately, because it
means one thing at the moment it is tapped. Delete asks first, from both routes (the card's
button and the long-press menu), naming the alarm: deletion cannot be undone, and a long
press on a list is the easiest gesture in the app to aim wrongly. A long press on a row
offers duplicate/delete (`AlarmActionsDialog`); the copy is created switched off so it
cannot preempt its original, and opens as the draft.

Saving switches the alarm on, closes the card, returns the list to the top, and answers
with a snackbar naming the day, the time and the time left (`TriggerDescriptor` +
`ui/format`). Pressing save means wanting the alarm, so an edit cannot leave it off. Which
card is open is `rememberSaveable`, so choosing a pattern — which navigates away — returns
to the same open card with the draft intact.

A chain waiting on a snooze appears as a `SnoozedBanner` above everything else, with the
time it will ring, how many snoozes are left (`snoozedRings`, pure) and a button that ends
the chain there and then — the same `UserDismiss` the ring itself would have taken, so the
armed snooze is replaced by the alarm's next real occurrence.

Free-choice numbers (ring duration in seconds, snooze count, snooze interval) share one
`NumberInputDialog` whose Save stays disabled until the value is one the app will honour;
the bounds live in `AutoSilence`, `SnoozeRepeats` and `SnoozeInterval`.

`TimePickerDialog` offers both Material input modes — clock face and keypad — behind one
icon, and remembers which one was last used in `SettingsStore.timeInputByKeyboard`.

`AlarmActivity` is the full-screen ringing UI, shown over the lock screen, and only for
alarms configured to turn the screen on. Its two actions must be **dragged**, not tapped
(`DragToConfirm`, 60% of the track): a phone picked up half asleep produces taps nobody
meant. Volume keys snooze through two mechanisms, because neither covers the job alone:
`onKeyDown` here while the screen has focus, and `alarm/VolumeKeySnooze` — a media session
held for the alerting window, which receives the keys with the screen off, paired with a
watch on the alarm stream's level for the case where a ringtone is playing and the platform
routes the keys to the stream instead. The one measured dead spot: screen dark *and* a
ringtone playing, where the press reaches neither the app nor the stream. The keys always
snooze, never dismiss, and a press spends one of the alarm's configured snoozes.

`docs/index.html` is the landing page, served by GitHub Pages from `main` + `/docs`, and it
is what the app's "home page" button opens (`UpdateAssets.siteUrl()`). That Pages setting is
a precondition of the URL: while it was off the address 404'd, and the button pointed at
`projectUrl()` instead — a home button that 404s is worse than a plain repository page.

## Data

```
alarms          the alarm and everything about how it alerts
patterns        vibration patterns, presets and the user's own
instances       one live chain per alarm; survives reboot (FK → alarms, cascade)
reliability_log the app's own account of what happened, pruned after 30 days
```

Schema version 2. Schemas are exported to `app/schemas/` and every column change needs a
hand-written migration plus a case in `AppDbMigrationTest`.

Settings that are not per-alarm (volume-keys default, the PWM debug switch, updater state)
live in a DataStore file created from the device-protected context by explicit path.

## Reliability screen

Every platform or vendor requirement that can silence an alarm, each with a button that
opens the exact settings page that fixes it: exact alarms, notifications, full-screen intent
(Android 14+), battery optimisation, amplitude control, system vibration strength, Do Not
Disturb, and the OEM background limits. Plus a **reboot test** that arms an alarm four
minutes out so the user can restart the phone, not unlock it, and watch it fire.

The one that matters most has no API: Samsung's "put unused apps to sleep" force-stops the
app, and a stopped app **receives no boot broadcast at all** — verified. Until the app is
opened again, nothing is re-armed. That is why it is presented as a condition, not a tip.

## Tests

411 JVM tests, `./gradlew testDebugUnitTest`, no device needed. Unit tests for everything in
`domain/`; Robolectric tests for the wiring that a unit test cannot see — the real pipeline
against AlarmManager and Room, the Room migration from a hand-built version-1 file, the
notification wording in both languages, and that silent mode does not silence the engines.

What only a phone can answer: how the vibration feels, how the ramp sounds, Samsung's own
background behaviour, and which pages the fix buttons land on in One UI.
