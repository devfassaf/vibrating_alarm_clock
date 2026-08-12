package com.faybish.vibealarm.ui.patterns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.SegmentType
import com.faybish.vibealarm.ui.format.formatDurationMs
import kotlin.math.roundToInt

/**
 * The visual pattern builder: an ordered list of vibrate/pause segments with a
 * duration and an intensity each, a live timeline, and a Test button that plays
 * the pattern through the same engine the alarm uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternBuilderScreen(
    viewModel: PatternViewModel,
    onBack: () -> Unit,
    onOpenRecorder: () -> Unit,
) {
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_pattern_builder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.saveDraft { onBack() } },
                        enabled = draft.segments.isNotEmpty(),
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = viewModel::setDraftName,
                    label = { Text(stringResource(R.string.field_pattern_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                PatternTimeline(segments = draft.segments)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.pattern_summary,
                            draft.segments.size,
                            draft.segments.size,
                            formatDurationMs(draft.totalMs),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { viewModel.test(draft.segments) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.action_test),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    IconButton(onClick = viewModel::stopTest) {
                        Icon(Icons.Filled.Stop, stringResource(R.string.action_stop))
                    }
                }
                if (!viewModel.hasAmplitudeControl) {
                    Text(
                        text = stringResource(R.string.pattern_no_amplitude_control),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(draft.segments) { index, segment ->
                SegmentCard(
                    index = index,
                    segment = segment,
                    isFirst = index == 0,
                    isLast = index == draft.segments.lastIndex,
                    onChange = { viewModel.updateSegment(index, it) },
                    onMove = { delta -> viewModel.moveSegment(index, delta) },
                    onRemove = { viewModel.removeSegment(index) },
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.addSegment(PatternSegment.vibrate(400, 200)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.action_add_vibration),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.addSegment(PatternSegment.pause(600)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.action_add_pause),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenRecorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.action_record_pattern),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (draft.copiedFromPreset != null) {
                    Text(
                        text = stringResource(R.string.pattern_copied_from_preset),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: PatternSegment,
    isFirst: Boolean,
    isLast: Boolean,
    onChange: (PatternSegment) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (segment.type == SegmentType.VIBRATE) {
                            R.string.segment_vibrate
                        } else {
                            R.string.segment_pause
                        },
                        index + 1,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = !isFirst) {
                    Icon(Icons.Filled.ArrowUpward, stringResource(R.string.action_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = !isLast) {
                    Icon(Icons.Filled.ArrowDownward, stringResource(R.string.action_move_down))
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.field_duration),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${segment.durationMs} ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = segment.durationMs.toFloat(),
                onValueChange = { onChange(segment.copy(durationMs = it.roundToInt().toLong())) },
                valueRange = MIN_DURATION_MS..MAX_DURATION_MS,
            )

            if (segment.type == SegmentType.VIBRATE) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.field_intensity),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(segment.amplitude * 100 / PatternSegment.MAX_AMPLITUDE)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = segment.amplitude.toFloat(),
                    onValueChange = { onChange(segment.copy(amplitude = it.roundToInt())) },
                    valueRange = 1f..PatternSegment.MAX_AMPLITUDE.toFloat(),
                )
            }
        }
    }
}

private const val MIN_DURATION_MS = 50f
private const val MAX_DURATION_MS = 5_000f
