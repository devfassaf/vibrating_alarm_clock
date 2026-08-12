package com.faybish.vibealarm.domain

/** One finger press captured by the recorder pad, relative to recording start. */
data class RecordedPress(
    val startMs: Long,
    val endMs: Long,
    /** 1..255, already mapped from the finger's vertical position by the UI. */
    val amplitude: Int,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Cleans raw recorder-pad presses into an editable pattern:
 * merges micro-gaps (accidental finger bounces), floors segment durations to
 * something the vibrator can express, trims leading/trailing silence.
 */
object RecorderQuantizer {

    const val MIN_SEGMENT_MS = 50L
    const val MERGE_GAP_MS = 30L

    fun quantize(
        presses: List<RecordedPress>,
        minSegmentMs: Long = MIN_SEGMENT_MS,
        mergeGapMs: Long = MERGE_GAP_MS,
    ): List<PatternSegment> {
        val sorted = presses
            .filter { it.durationMs > 0 }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return emptyList()

        // Merge presses separated by a micro-gap; amplitude = duration-weighted mean.
        val merged = ArrayList<RecordedPress>()
        for (press in sorted) {
            val last = merged.lastOrNull()
            if (last != null && press.startMs - last.endMs < mergeGapMs) {
                val total = last.durationMs + press.durationMs
                val amplitude =
                    ((last.amplitude * last.durationMs + press.amplitude * press.durationMs) / total)
                        .toInt()
                        .coerceIn(1, PatternSegment.MAX_AMPLITUDE)
                merged[merged.size - 1] = RecordedPress(last.startMs, press.endMs, amplitude)
            } else {
                merged += press
            }
        }

        val segments = ArrayList<PatternSegment>(merged.size * 2)
        merged.forEachIndexed { index, press ->
            if (index > 0) {
                val gap = press.startMs - merged[index - 1].endMs
                if (gap > 0) segments += PatternSegment.pause(gap.coerceAtLeast(minSegmentMs))
            }
            segments += PatternSegment.vibrate(
                durationMs = press.durationMs.coerceAtLeast(minSegmentMs),
                amplitude = press.amplitude.coerceIn(1, PatternSegment.MAX_AMPLITUDE),
            )
        }
        return segments
    }
}
