package com.faybish.vibealarm.ui.alarms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.R
import java.time.LocalTime

/**
 * The app's home screen, modelled on the alarm tab of Google's Clock: a list of
 * cards that expand in place for editing, and a "+" button that opens a time picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmListViewModel,
    onOpenPatterns: () -> Unit,
    onOpenReliability: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickPatternFor: (Long) -> Unit,
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val patternNames by viewModel.patternNames.collectAsStateWithLifecycle()

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }

    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.title_alarms)) },
                actions = {
                    IconButton(onClick = onOpenPatterns) {
                        Icon(Icons.Filled.Vibration, stringResource(R.string.title_patterns))
                    }
                    IconButton(onClick = onOpenReliability) {
                        Icon(Icons.Filled.HealthAndSafety, stringResource(R.string.title_reliability))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.title_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTimePicker = true }) {
                Icon(Icons.Filled.Add, stringResource(R.string.action_add_alarm))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ReliabilityBanner(onOpenReliability = onOpenReliability) }

            if (alarms.isEmpty()) {
                item { EmptyState() }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        schedule = viewModel.scheduleOf(alarm),
                        nextTrigger = viewModel.nextTrigger(alarm),
                        patternName = alarm.patternId?.let { patternNames[it] },
                        expanded = expandedId == alarm.id,
                        onExpandToggle = {
                            expandedId = if (expandedId == alarm.id) null else alarm.id
                        },
                        onEnabledChange = { viewModel.setEnabled(alarm.id, it) },
                        onAlarmChange = viewModel::save,
                        onScheduleChange = { viewModel.updateSchedule(alarm, it) },
                        onPickPattern = { onPickPatternFor(alarm.id) },
                        onDelete = {
                            expandedId = null
                            viewModel.delete(alarm.id)
                        },
                    )
                }
            }
        }
    }

    if (showTimePicker) {
        val now = LocalTime.now()
        TimePickerDialog(
            initialHour = now.hour,
            initialMinute = now.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                viewModel.addAlarm(LocalTime.of(hour, minute)) { id -> expandedId = id }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 64.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.empty_alarms_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.empty_alarms_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
