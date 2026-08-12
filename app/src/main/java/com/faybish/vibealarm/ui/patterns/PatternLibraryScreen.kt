package com.faybish.vibealarm.ui.patterns

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.SegmentsCodec
import com.faybish.vibealarm.data.VibrationPatternEntity
import com.faybish.vibealarm.ui.format.formatDurationMs
import kotlinx.coroutines.launch

/**
 * Pattern library. Doubles as a picker: when [onPicked] is provided, tapping a
 * row assigns that pattern to the alarm the user came from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternLibraryScreen(
    viewModel: PatternViewModel,
    onBack: () -> Unit,
    onEdit: (Long?) -> Unit,
    onPicked: ((Long) -> Unit)? = null,
) {
    val patterns by viewModel.patterns.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val inUseMessage = stringResource(R.string.pattern_in_use)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_patterns)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(null) }) {
                Icon(Icons.Filled.Add, stringResource(R.string.action_new_pattern))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(patterns, key = { it.id }) { pattern ->
                PatternRow(
                    pattern = pattern,
                    onTest = { viewModel.testStored(pattern) },
                    onEdit = { onEdit(pattern.id) },
                    onDelete = {
                        viewModel.deletePattern(pattern) {
                            scope.launch { snackbar.showSnackbar(inUseMessage) }
                        }
                    },
                    onPick = onPicked?.let { pick -> { pick(pattern.id) } },
                )
            }
        }
    }
}

@Composable
private fun PatternRow(
    pattern: VibrationPatternEntity,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPick: (() -> Unit)?,
) {
    val segments = remember(pattern.segmentsJson) { SegmentsCodec.decode(pattern.segmentsJson) }
    val totalMs = remember(segments) { segments.sumOf { it.durationMs } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onPick != null) Modifier.clickable(onClick = onPick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = patternDisplayName(pattern),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.pattern_summary,
                            segments.size,
                            segments.size,
                            formatDurationMs(totalMs),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onTest) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_test))
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = if (pattern.isPreset) Icons.Filled.ContentCopy else Icons.Filled.Edit,
                        contentDescription = stringResource(
                            if (pattern.isPreset) R.string.action_duplicate else R.string.action_edit,
                        ),
                    )
                }
                if (!pattern.isPreset) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                    }
                }
            }
            PatternTimeline(segments = segments, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Built-in patterns are named by string resource so they localize. */
@Composable
private fun patternDisplayName(pattern: VibrationPatternEntity): String {
    if (!pattern.isPreset) return pattern.name
    val resId = when (pattern.name) {
        "gentle" -> R.string.pattern_preset_gentle
        "heartbeat" -> R.string.pattern_preset_heartbeat
        "sos" -> R.string.pattern_preset_sos
        "waves" -> R.string.pattern_preset_waves
        "escalating" -> R.string.pattern_preset_escalating
        else -> return pattern.name
    }
    return stringResource(resId)
}
