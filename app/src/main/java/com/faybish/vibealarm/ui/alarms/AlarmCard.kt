package com.faybish.vibealarm.ui.alarms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import com.faybish.vibealarm.data.applying
import com.faybish.vibealarm.domain.AlertSelection
import com.faybish.vibealarm.domain.AutoSilence
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.faybish.vibealarm.ui.components.LabeledRow
import com.faybish.vibealarm.ui.components.OptionChips
import com.faybish.vibealarm.ui.components.PercentSlider
import com.faybish.vibealarm.ui.format.currentLocale
import com.faybish.vibealarm.ui.format.dayName
import com.faybish.vibealarm.ui.format.formatTime
import com.faybish.vibealarm.ui.format.scheduleSummaryText
import com.faybish.vibealarm.ui.format.timeUntilText
import com.faybish.vibealarm.ui.format.weekStart
import java.time.Instant
import java.time.LocalTime

/**
 * One alarm, collapsed to time + schedule + switch, expanding in place into the
 * full editor. Mirrors how Google Clock behaves so the app feels familiar.
 *
 * While it is open the card edits [draft] and the stored alarm is left alone: what time
 * you wake up tomorrow is not something to change by brushing a slider, so it takes a
 * save. The switch is the exception — it means one thing and it means it now.
 *
 * @param stored the alarm as saved; shown when the card is collapsed.
 * @param draft the edited copy, non-null while this card is the open one.
 * @param dirty whether the draft holds changes the alarm has not been given yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCard(
    stored: AlarmEntity,
    draft: AlarmEntity?,
    dirty: Boolean,
    schedule: Schedule,
    nextTrigger: Instant?,
    patternName: String?,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAlarmChange: (AlarmEntity) -> Unit,
    onScheduleChange: (Schedule) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onPickPattern: () -> Unit,
    onPreviewVibration: () -> Unit,
    onPreviewSound: () -> Unit,
    onDelete: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showScheduleEditor by remember { mutableStateOf(false) }

    val alarm = draft ?: stored

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpandToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TimeHeadline(alarm, schedule, expanded) { showTimePicker = true }
                    Text(
                        text = scheduleSummaryText(schedule),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (alarm.label.isNotBlank()) {
                        Text(
                            text = alarm.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // While there are unsaved edits, "rings in 6 hours" would describe
                    // neither the alarm on screen nor the one that is armed, so it says
                    // what is actually true instead.
                    if (dirty) {
                        Text(
                            text = stringResource(R.string.unsaved_changes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (alarm.enabled && nextTrigger != null) {
                        Text(
                            text = timeUntilText(nextTrigger),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Switch(checked = alarm.enabled, onCheckedChange = onEnabledChange)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    LabeledRow(
                        title = stringResource(R.string.field_schedule),
                        value = scheduleSummaryText(schedule),
                        onClick = { showScheduleEditor = true },
                    )

                    if (schedule is Schedule.Weekly) {
                        PerDayTimes(schedule)
                    }

                    OutlinedTextField(
                        value = alarm.label,
                        onValueChange = { onAlarmChange(alarm.copy(label = it)) },
                        label = { Text(stringResource(R.string.field_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )

                    // Two independent switches, and each one's settings appear only when
                    // it is on — so the screen shows exactly what this alarm will do.
                    val alert = AlertSelection.fromStorage(
                        soundMode = alarm.mode == RingMode.SOUND,
                        vibrateWithSound = alarm.vibrateWithSound,
                    )
                    AlertSelector(alert) { onAlarmChange(alarm.applying(it)) }

                    if (alert.vibration) {
                        LabeledRow(
                            title = stringResource(R.string.field_vibration_pattern),
                            value = patternName ?: stringResource(R.string.pattern_default),
                            leadingIcon = Icons.Filled.Vibration,
                            onClick = onPickPattern,
                        )
                        IntensitySlider(alarm, onAlarmChange, onPreviewVibration)
                    }

                    if (alert.sound) {
                        SoundSettings(alarm, onAlarmChange, onPreviewSound)
                    }

                    SnoozeSettings(alarm, onAlarmChange)

                    ScreenSettings(alarm, onAlarmChange)

                    if (dirty) {
                        Text(
                            text = stringResource(R.string.unsaved_changes_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // Both stay visible when there is nothing to save: buttons that
                        // come and go are harder to find than buttons that are greyed out.
                        TextButton(onClick = onDiscard, enabled = dirty) {
                            Text(stringResource(R.string.action_discard))
                        }
                        Button(
                            onClick = onSave,
                            enabled = dirty,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        val time = ScheduleCodec.minutesToTime(alarm.timeMinutesOfDay)
        TimePickerDialog(
            initialHour = time.hour,
            initialMinute = time.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                onAlarmChange(
                    alarm.copy(timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(hour, minute))),
                )
            },
        )
    }

    if (showScheduleEditor) {
        ScheduleEditorSheet(
            schedule = schedule,
            onDismiss = { showScheduleEditor = false },
            onConfirm = {
                showScheduleEditor = false
                onScheduleChange(it)
            },
        )
    }
}

/**
 * A weekly alarm with per-day overrides has more than one ring time, so the
 * headline shows them all rather than pretending there is a single time.
 */
@Composable
private fun TimeHeadline(
    alarm: AlarmEntity,
    schedule: Schedule,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val locale = currentLocale()
    val text = ScheduleSummarizer.distinctTimes(schedule)
        .joinToString(" · ") { formatTime(context, it, locale) }
    Text(
        text = text,
        style = MaterialTheme.typography.displaySmall,
        textDecoration = if (expanded) TextDecoration.Underline else null,
        color = if (alarm.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.clickable(enabled = expanded, onClick = onClick),
    )
}

@Composable
private fun PerDayTimes(schedule: Schedule.Weekly) {
    val overridden = ScheduleSummarizer.overriddenDays(schedule, weekStart())
    if (overridden.isEmpty()) return
    val context = LocalContext.current
    val locale = currentLocale()
    Text(
        text = overridden.joinToString(" · ") { day ->
            val name = dayName(context, day)
            "$name ${formatTime(context, schedule.overrides.getValue(day), locale)}"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Sound and vibration as two independent choices, which is how people describe what they
 * want. Tapping the only one that is on does nothing — that is deliberate, and the line
 * underneath says why rather than leaving a switch that mysteriously refuses to move.
 */
@Composable
private fun AlertSelector(alert: AlertSelection, onChange: (AlertSelection) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.field_ring_mode),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = alert.sound,
                onClick = { onChange(alert.toggleSound()) },
                label = { Text(stringResource(R.string.alert_sound)) },
                leadingIcon = {
                    if (alert.sound) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                },
            )
            FilterChip(
                selected = alert.vibration,
                onClick = { onChange(alert.toggleVibration()) },
                label = { Text(stringResource(R.string.alert_vibration)) },
                leadingIcon = {
                    if (alert.vibration) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                },
            )
        }
        Text(
            text = stringResource(R.string.alert_at_least_one),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun IntensitySlider(
    alarm: AlarmEntity,
    onChange: (AlarmEntity) -> Unit,
    onPreview: () -> Unit,
) {
    Column {
        PercentSlider(
            title = stringResource(R.string.field_vibration_intensity),
            value = alarm.intensityScale,
            valueRange = 0.1f..1f,
            onValueChange = { onChange(alarm.copy(intensityScale = it)) },
            onPreview = onPreview,
        )
        // Without this line the slider looks like it competes with the pattern's own
        // per-step strengths, when in fact it scales all of them together.
        Text(
            text = stringResource(R.string.field_vibration_intensity_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoundSettings(
    alarm: AlarmEntity,
    onChange: (AlarmEntity) -> Unit,
    onPreview: () -> Unit,
) {
    Column {
        RingtonePickerRow(
            currentUri = alarm.ringtoneUri,
            onPicked = { onChange(alarm.copy(ringtoneUri = it)) },
        )
        Text(
            text = stringResource(R.string.field_ringtone_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PercentSlider(
            title = stringResource(R.string.field_volume),
            value = alarm.volume,
            valueRange = 0f..1f,
            onValueChange = { onChange(alarm.copy(volume = it)) },
            leadingIcon = Icons.Filled.MusicNote,
            onPreview = onPreview,
        )
        AutoSilenceChips(alarm, onChange)
        // Every ring of the chain climbs again, so this is about how each one starts, not
        // just the first.
        com.faybish.vibealarm.ui.components.SwitchRow(
            title = stringResource(R.string.field_sound_ramp_up),
            subtitle = stringResource(R.string.field_sound_ramp_up_hint),
            checked = alarm.soundRampUp,
            onCheckedChange = { onChange(alarm.copy(soundRampUp = it)) },
        )
    }
}

/**
 * The presets plus a chip for any number of seconds. "How long should it ring" is a
 * question about a specific bedroom, and whole minutes are a coarse unit for it.
 */
@Composable
private fun AutoSilenceChips(alarm: AlarmEntity, onChange: (AlarmEntity) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val isCustom = AutoSilence.isCustom(alarm.autoSilenceSeconds)

    OptionChips(
        title = stringResource(R.string.field_auto_silence),
        options = AutoSilence.PRESET_SECONDS.map { it to durationLabel(it) },
        selected = alarm.autoSilenceSeconds,
        onSelected = { onChange(alarm.copy(autoSilenceSeconds = it)) },
        customLabel = if (isCustom) {
            durationLabel(alarm.autoSilenceSeconds)
        } else {
            stringResource(R.string.field_auto_silence_custom)
        },
        customSelected = isCustom,
        onCustomClick = { editing = true },
    )

    if (editing) {
        SecondsInputDialog(
            initialSeconds = alarm.autoSilenceSeconds,
            onDismiss = { editing = false },
            onConfirm = { seconds ->
                editing = false
                onChange(alarm.copy(autoSilenceSeconds = AutoSilence.clamp(seconds)))
            },
        )
    }
}

/** Whole minutes read as minutes; anything else says seconds, because that is what it is. */
@Composable
private fun durationLabel(seconds: Int): String = if (seconds % 60 == 0) {
    stringResource(R.string.minutes_short, seconds / 60)
} else {
    stringResource(R.string.seconds_short, seconds)
}

@Composable
private fun SnoozeSettings(alarm: AlarmEntity, onChange: (AlarmEntity) -> Unit) {
    Column {
        // Repeats first: the interval below only exists because of the answer here, and a
        // setting that governs another belongs above it.
        OptionChips(
            title = stringResource(R.string.field_snooze_repeats),
            options = listOf(
                0 to stringResource(R.string.snooze_repeats_none),
                1 to "1",
                3 to "3",
                5 to "5",
                -1 to stringResource(R.string.snooze_repeats_until_dismissed),
            ),
            selected = alarm.snoozeRepeatCount,
            onSelected = { onChange(alarm.copy(snoozeRepeatCount = it)) },
            leadingIcon = Icons.Filled.Snooze,
        )
        // With no repeats there is no automatic snooze to space out. A snooze pressed by
        // hand still uses the stored interval — it just has nothing to configure here.
        if (alarm.snoozeRepeatCount != 0) {
            OptionChips(
                title = stringResource(R.string.field_snooze_interval),
                options = listOf(
                    1 to stringResource(R.string.minutes_short, 1),
                    3 to stringResource(R.string.minutes_short, 3),
                    5 to stringResource(R.string.minutes_short, 5),
                    10 to stringResource(R.string.minutes_short, 10),
                ),
                selected = alarm.snoozeIntervalMinutes,
                onSelected = { onChange(alarm.copy(snoozeIntervalMinutes = it)) },
            )
        }
        // "Until dismissed" plus a dark screen means nothing will ever stop it on its
        // own — the opposite of what a hands-free alarm is for. Say so plainly.
        if (alarm.snoozeRepeatCount == -1 && !alarm.turnScreenOn) {
            Text(
                text = stringResource(R.string.snooze_until_dismissed_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * "Turn screen on" is the setting that makes a hands-free alarm possible: with it
 * off, nothing lights up and the alarm is only the vibration.
 */
@Composable
private fun ScreenSettings(alarm: AlarmEntity, onChange: (AlarmEntity) -> Unit) {
    Column {
        com.faybish.vibealarm.ui.components.SwitchRow(
            title = stringResource(R.string.field_turn_screen_on),
            subtitle = stringResource(R.string.field_turn_screen_on_hint),
            checked = alarm.turnScreenOn,
            onCheckedChange = { onChange(alarm.copy(turnScreenOn = it)) },
        )
        Spacer(Modifier.width(4.dp))
    }
}
