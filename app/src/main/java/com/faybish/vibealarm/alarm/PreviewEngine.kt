package com.faybish.vibealarm.alarm

import android.content.Context
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.peakAmplitude
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Short, bounded previews of what an alarm will sound and feel like, for the volume and
 * intensity sliders.
 *
 * It deliberately drives the same [VibrationEngine] and [SoundEngine] the alarm itself
 * uses — including the alarm audio usage and the stream-volume handling — so what the
 * user judges here is what will actually wake them, not an approximation.
 */
class PreviewEngine(
    context: Context,
    private val scope: CoroutineScope,
    logger: ReliabilityLogger,
) {

    private val vibration = VibrationEngine(context)
    private val sound = SoundEngine(context, logger, scope)
    private var job: Job? = null

    val hasAmplitudeControl: Boolean get() = vibration.hasAmplitudeControl

    /**
     * A single burst at the strength the alarm would actually deliver.
     *
     * @param segments the alarm's pattern; the preview uses its loudest vibrate step, so
     *   a gentle pattern previews gently even at 100% intensity — the slider scales the
     *   pattern, it does not replace it.
     */
    fun previewVibration(
        segments: List<PatternSegment>,
        intensityScale: Float,
        forcePwmEmulation: Boolean = false,
    ) {
        if (!canPreview()) return
        val peak = segments.peakAmplitude
        restart {
            vibration.play(
                segments = listOf(PatternSegment.vibrate(BURST_MILLIS, peak)),
                intensityScale = intensityScale,
                repeat = false,
                forcePwmEmulation = forcePwmEmulation,
            )
            delay(BURST_MILLIS + TAIL_MILLIS)
            vibration.stop()
        }
    }

    /** Plays the chosen ringtone at the chosen volume, then stops itself. */
    fun previewSound(ringtoneUri: String?, volume: Float) {
        if (!canPreview()) return
        restart {
            sound.play(ringtoneUri, volume)
            delay(SOUND_MILLIS)
            // Also restores the alarm stream volume this engine borrowed.
            sound.stop()
        }
    }

    /** Cancels any preview in flight. Must be called when the screen goes away. */
    fun stop() {
        job?.cancel()
        job = null
        vibration.stop()
        sound.stop()
    }

    private fun restart(block: suspend () -> Unit) {
        job?.cancel()
        vibration.stop()
        sound.stop()
        job = scope.launch {
            try {
                block()
            } finally {
                // A cancelled preview must not leave the vibrator running or the user's
                // alarm volume changed.
                vibration.stop()
                sound.stop()
            }
        }
    }

    /**
     * Never preview over a real alarm: the preview would fight the alarm for the single
     * vibrator and would restore the alarm stream volume out from under it.
     */
    private fun canPreview(): Boolean = AlarmRingingService.playingAlarmId == null

    private companion object {
        const val BURST_MILLIS = 700L
        const val TAIL_MILLIS = 120L
        const val SOUND_MILLIS = 2_500L
    }
}
