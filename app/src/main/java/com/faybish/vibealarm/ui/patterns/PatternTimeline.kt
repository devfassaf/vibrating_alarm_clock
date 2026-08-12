package com.faybish.vibealarm.ui.patterns

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.SegmentType
import com.faybish.vibealarm.domain.totalDurationMs

/**
 * Visual shape of a pattern: time runs along the width, bar height shows
 * intensity, gaps are pauses. Lets the user see the rhythm they built.
 */
@Composable
fun PatternTimeline(
    segments: List<PatternSegment>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val total = segments.totalDurationMs
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            drawRect(color = trackColor, size = size)
            if (total <= 0) return@Canvas

            var x = 0f
            segments.forEach { segment ->
                val width = (segment.durationMs.toFloat() / total) * size.width
                if (segment.type == SegmentType.VIBRATE) {
                    val fraction = segment.amplitude.coerceIn(1, PatternSegment.MAX_AMPLITUDE) /
                        PatternSegment.MAX_AMPLITUDE.toFloat()
                    val barHeight = size.height * (0.15f + 0.85f * fraction)
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(width.coerceAtLeast(1f), barHeight),
                    )
                }
                x += width
            }
        }
    }
}
