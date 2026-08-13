package com.faybish.vibealarm.data

/**
 * The fields an open alarm card owns while it is being edited.
 *
 * The card holds a draft until the user saves, and in that window the stored row can
 * move on its own: a one-time alarm turns itself off after it rings, the pattern picker
 * writes the chosen pattern straight to the row, and the switch on the collapsed card
 * still applies immediately. Saving the draft as a whole entity would quietly undo all
 * three — the alarm that already rang would come back enabled — so a save takes the
 * freshest row and lays only the edited fields on top of it.
 *
 * [editedFields] and [withEditsFrom] must list the same fields; AlarmEditsTest fails if
 * one of them forgets a field the other has.
 */
fun AlarmEntity.withEditsFrom(draft: AlarmEntity): AlarmEntity = copy(
    label = draft.label,
    scheduleType = draft.scheduleType,
    timeMinutesOfDay = draft.timeMinutesOfDay,
    daysBitmask = draft.daysBitmask,
    perDayOverridesJson = draft.perDayOverridesJson,
    datesJson = draft.datesJson,
    mode = draft.mode,
    ringtoneUri = draft.ringtoneUri,
    volume = draft.volume,
    vibrateWithSound = draft.vibrateWithSound,
    intensityScale = draft.intensityScale,
    turnScreenOn = draft.turnScreenOn,
    autoSilenceSeconds = draft.autoSilenceSeconds,
    soundRampUp = draft.soundRampUp,
    snoozeIntervalMinutes = draft.snoozeIntervalMinutes,
    snoozeRepeatCount = draft.snoozeRepeatCount,
    backgroundType = draft.backgroundType,
    backgroundColorArgb = draft.backgroundColorArgb,
    backgroundImagePath = draft.backgroundImagePath,
    volumeKeysSnooze = draft.volumeKeysSnooze,
)

/** Everything a save would carry over — and nothing else, so that timestamps and the
 *  fields other screens own cannot register as an unsaved change. */
fun AlarmEntity.editedFields(): List<Any?> = listOf(
    label,
    scheduleType,
    timeMinutesOfDay,
    daysBitmask,
    perDayOverridesJson,
    datesJson,
    mode,
    ringtoneUri,
    volume,
    vibrateWithSound,
    intensityScale,
    turnScreenOn,
    autoSilenceSeconds,
    soundRampUp,
    snoozeIntervalMinutes,
    snoozeRepeatCount,
    backgroundType,
    backgroundColorArgb,
    backgroundImagePath,
    volumeKeysSnooze,
)

/** True when saving [draft] over this row would change nothing the user can see. */
fun AlarmEntity.hasSameEditsAs(draft: AlarmEntity): Boolean =
    editedFields() == draft.editedFields()
