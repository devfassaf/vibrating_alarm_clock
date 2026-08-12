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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.faybish.vibealarm.ui.components.LabeledRow
import com.faybish.vibealarm.ui.components.OptionChips
import com.faybish.vibealarm.ui.components.PercentSlider
import com.faybish.vibealarm.ui.format.currentLocale
import com.faybish.vibealarm.ui.format.formatTime
import com.faybish.vibealarm.ui.format.scheduleSummaryText
import com.faybish.vibealarm.ui.format.timeUntilText
import com.faybish.vibealarm.ui.format.weekStart
import java.time.Instant
import java.time.LocalTime
import java.time.format.TextStyle

/**
 * One alarm, collapsed to time + schedule + switch, expanding in place into the
 * full editor. Mirrors how Google Clock behaves so the app feels familiar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCard(
    alarm: AlarmEntity,
    schedule: Schedule,
    nextTrigger: Instant?,
    patternName: String?,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAlarmChange: (AlarmEntity) -> Unit,
    onScheduleChange: (Schedule) -> Unit,
    onPickPattern: () -> Unit,
    onPreviewVibration: () -> Unit,
    onPreviewSound: () -> Unit,
    onDelete: () -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }
    var showScheduleEditor by remember { mutableStateOf(false) }

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
                    if (alarm.enabled && nextTrigger != null) {
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

                    RingModeSelector(alarm, onAlarmChange)

                    LabeledRow(
                        title = stringResource(R.string.field_vibration_pattern),
                        value = patternName ?: stringResource(R.string.pattern_default),
                        leadingIcon = Icons.Filled.Vibration,
                        onClick = onPickPattern,
                    )

                    IntensitySlider(alarm, onAlarmChange, onPreviewVibration)

                    if (alarm.mode == RingMode.SOUND) {
                        SoundSettings(alarm, onAlarmChange, onPreviewSound)
                    }

                    SnoozeSettings(alarm, onAlarmChange)

                    ScreenSettings(alarm, onAlarmChange)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
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
            val name = day.getDisplayName(TextStyle.SHORT, locale)
            "$name ${formatTime(context, schedule.overrides.getValue(day), locale)}"
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun RingModeSelector(alarm: AlarmEntity, onChange: (AlarmEntity) -> Unit) {
    OptionChips(
        title = stringResource(R.string.field_ring_mode),
        options = listOf(
            RingMode.VIBRATE_ONLY to stringResource(R.string.ring_mode_vibrate_only),
            RingMode.SOUND to stringResource(R.string.ring_mode_sound),
        ),
        selected = alarm.mode,
        onSelected = { onChange(alarm.copy(mode = it)) },
    )
}

@Composable
private fun IntensitySlider(
    alarm: AlarmEntity,
    onChange: (AlarmEntity) -> Unit,
    onPreview: () -> Unit,
) {
    PercentSlider(
        title = stringResource(R.string.field_vibration_intensity),
        value = alarm.intensityScale,
        valueRange = 0.1f..1f,
        onValueChange = { onChange(alarm.copy(intensityScale = it)) },
        onPreview = onPreview,
    )
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
        com.faybish.vibealarm.ui.components.SwitchRow(
            title = stringResource(R.string.field_vibrate_with_sound),
            checked = alarm.vibrateWithSound,
            onCheckedChange = { onChange(alarm.copy(vibrateWithSound = it)) },
        )
        OptionChips(
            title = stringResource(R.string.field_auto_silence),
            options = listOf(
                60 to stringResource(R.string.minutes_short, 1),
                120 to stringResource(R.string.minutes_short, 2),
                300 to stringResource(R.string.minutes_short, 5),
                600 to stringResource(R.string.minutes_short, 10),
            ),
            selected = alarm.autoSilenceSeconds,
            onSelected = { onChange(alarm.copy(autoSilenceSeconds = it)) },
        )
    }
}

@Composable
private fun SnoozeSettings(alarm: AlarmEntity, onChange: (AlarmEntity) -> Unit) {
    Column {
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
            leadingIcon = Icons.Filled.Snooze,
        )
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
        )
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
