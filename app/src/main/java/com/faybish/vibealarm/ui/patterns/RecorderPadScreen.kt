package com.faybish.vibealarm.ui.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.R
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.RecordedPress
import com.faybish.vibealarm.domain.RecorderQuantizer

/**
 * Tap-to-record pad: press and hold to vibrate, release to pause.
 *
 * Hold length becomes the segment length, and how high up the pad the finger is
 * becomes the intensity — top is strongest. The phone buzzes along as you record
 * so the rhythm can be felt rather than imagined; the result opens in the builder
 * for fine-tuning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderPadScreen(
    viewModel: PatternViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val presses = remember { mutableStateListOf<RecordedPress>() }
    var startedAt by remember { mutableStateOf(0L) }
    var preview by remember { mutableStateOf<List<PatternSegment>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_recorder)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.recorder_instructions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val padHeight = size.height.toFloat().coerceAtLeast(1f)
                            if (presses.isEmpty()) startedAt = System.currentTimeMillis()
                            val pressStart = System.currentTimeMillis() - startedAt

                            var amplitude = amplitudeFor(down.position.y, padHeight)
                            viewModel.previewAmplitude(amplitude)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val next = amplitudeFor(change.position.y, padHeight)
                                // Re-issue only on a meaningful move: every call restarts
                                // the vibrator, which would stutter otherwise.
                                if (kotlin.math.abs(next - amplitude) > AMPLITUDE_STEP) {
                                    amplitude = next
                                    viewModel.previewAmplitude(amplitude)
                                }
                            }

                            viewModel.stopTest()
                            presses += RecordedPress(
                                startMs = pressStart,
                                endMs = System.currentTimeMillis() - startedAt,
                                amplitude = amplitude,
                            )
                            preview = RecorderQuantizer.quantize(presses.toList())
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (presses.isEmpty()) R.string.recorder_pad_idle else R.string.recorder_pad_active,
                        presses.size,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PatternTimeline(segments = preview)
            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        presses.clear()
                        preview = emptyList()
                        viewModel.stopTest()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_clear)) }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { viewModel.test(preview) },
                    enabled = preview.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_test)) }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        viewModel.applyRecording(presses.toList())
                        onDone()
                    },
                    enabled = preview.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_use)) }
            }
        }
    }
}

/** Top of the pad is full strength, bottom is the gentlest usable buzz. */
private fun amplitudeFor(y: Float, padHeight: Float): Int {
    val fraction = 1f - (y / padHeight).coerceIn(0f, 1f)
    return (MIN_AMPLITUDE + fraction * (PatternSegment.MAX_AMPLITUDE - MIN_AMPLITUDE)).toInt()
        .coerceIn(MIN_AMPLITUDE, PatternSegment.MAX_AMPLITUDE)
}

private const val MIN_AMPLITUDE = 30
private const val AMPLITUDE_STEP = 20
