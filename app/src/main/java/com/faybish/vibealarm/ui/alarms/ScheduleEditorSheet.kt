package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.Schedule
import com.faybish.vibealarm.domain.ScheduleSummarizer
import com.faybish.vibealarm.ui.components.LabeledRow
import com.faybish.vibealarm.ui.format.dayLabel
import com.faybish.vibealarm.ui.format.formatDate
import com.faybish.vibealarm.ui.format.formatTime
import com.faybish.vibealarm.ui.format.weekStart
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private enum class ScheduleKind { ONCE, WEEKLY, DATES }

/**
 * Editor for the three schedule shapes.
 *
 * The weekly mode supports a different time per day inside one alarm (Monday at
 * 07:00, Tuesday at 08:00): each selected day shows its effective time and can
 * override the default.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorSheet(
    schedule: Schedule,
    onDismiss: () -> Unit,
    onConfirm: (Schedule) -> Unit,
) {
    var kind by remember {
        mutableStateOf(
            when (schedule) {
                is Schedule.OneTime -> ScheduleKind.ONCE
                is Schedule.Weekly -> ScheduleKind.WEEKLY
                is Schedule.Dates -> ScheduleKind.DATES
            },
        )
    }

    val initialTime = when (schedule) {
        is Schedule.OneTime -> schedule.time
        is Schedule.Weekly -> schedule.defaultTime
        is Schedule.Dates -> schedule.time
    }

    var defaultTime by remember { mutableStateOf(initialTime) }
    var days by remember {
        mutableStateOf((schedule as? Schedule.Weekly)?.days ?: emptySet())
    }
    var overrides by remember {
        mutableStateOf((schedule as? Schedule.Weekly)?.overrides ?: emptyMap())
    }
    var dates by remember {
        mutableStateOf((schedule as? Schedule.Dates)?.dates ?: emptyList())
    }

    var editingDefaultTime by remember { mutableStateOf(false) }
    var editingDayTime by remember { mutableStateOf<DayOfWeek?>(null) }
    var addingDate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val result = when (kind) {
                        ScheduleKind.ONCE -> Schedule.OneTime(defaultTime)
                        ScheduleKind.WEEKLY -> Schedule.Weekly(
                            days = days,
                            defaultTime = defaultTime,
                            // Overrides for deselected days would silently reappear
                            // if the day is re-enabled later, so drop them.
                            overrides = overrides.filterKeys { it in days },
                        )

                        ScheduleKind.DATES -> Schedule.Dates(
                            dates = dates.distinct().sorted(),
                            time = defaultTime,
                        )
                    }
                    onConfirm(result)
                },
                enabled = kind != ScheduleKind.WEEKLY || days.isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        title = { Text(stringResource(R.string.field_schedule)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ScheduleKind.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = kind == entry,
                            onClick = { kind = entry },
                            shape = SegmentedButtonDefaults.itemShape(index, ScheduleKind.entries.size),
                            label = { Text(kindLabel(entry)) },
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                LabeledRow(
                    title = stringResource(
                        if (kind == ScheduleKind.WEEKLY) R.string.field_default_time else R.string.field_time,
                    ),
                    value = formatTime(defaultTime),
                    onClick = { editingDefaultTime = true },
                )

                when (kind) {
                    ScheduleKind.ONCE -> Text(
                        text = stringResource(R.string.schedule_once_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ScheduleKind.WEEKLY -> {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        DayChips(
                            selected = days,
                            onToggle = { day ->
                                days = if (day in days) days - day else days + day
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.per_day_times_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.per_day_times_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ScheduleSummarizer.orderedDays(days, weekStart()).forEach { day ->
                            val effective = overrides[day] ?: defaultTime
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LabeledRow(
                                    title = dayLabel(day, short = false),
                                    value = formatTime(effective),
                                    onClick = { editingDayTime = day },
                                    modifier = Modifier.weight(1f),
                                )
                                if (overrides.containsKey(day)) {
                                    IconButton(onClick = { overrides = overrides - day }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(
                                                R.string.action_reset_to_default,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ScheduleKind.DATES -> {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        dates.sorted().forEach { date ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = formatDate(date),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { dates = dates - date }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.action_delete),
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { addingDate = true },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(
                                text = stringResource(R.string.action_add_date),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    )

    if (editingDefaultTime) {
        TimePickerDialog(
            initialHour = defaultTime.hour,
            initialMinute = defaultTime.minute,
            onDismiss = { editingDefaultTime = false },
            onConfirm = { hour, minute ->
                editingDefaultTime = false
                defaultTime = LocalTime.of(hour, minute)
            },
        )
    }

    editingDayTime?.let { day ->
        val current = overrides[day] ?: defaultTime
        TimePickerDialog(
            initialHour = current.hour,
            initialMinute = current.minute,
            onDismiss = { editingDayTime = null },
            onConfirm = { hour, minute ->
                editingDayTime = null
                overrides = overrides + (day to LocalTime.of(hour, minute))
            },
        )
    }

    if (addingDate) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { addingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            dates = dates + Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                        }
                        addingDate = false
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { addingDate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun DayChips(selected: Set<DayOfWeek>, onToggle: (DayOfWeek) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScheduleSummarizer.weekOrder(weekStart()).forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = { onToggle(day) },
                label = { Text(dayLabel(day).take(2)) },
            )
        }
    }
}

@Composable
private fun kindLabel(kind: ScheduleKind): String = stringResource(
    when (kind) {
        ScheduleKind.ONCE -> R.string.schedule_kind_once
        ScheduleKind.WEEKLY -> R.string.schedule_kind_weekly
        ScheduleKind.DATES -> R.string.schedule_kind_dates
    },
)
