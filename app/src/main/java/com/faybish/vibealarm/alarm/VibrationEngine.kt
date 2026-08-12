package com.faybish.vibealarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.WaveformMapper

/**
 * Plays vibration patterns with alarm usage attributes, so ringer-silent and
 * Do Not Disturb (which exempts alarms) do not suppress them.
 *
 * The waveform itself is built by the pure [WaveformMapper]; this class only
 * deals with the platform API differences and the device's capabilities.
 */
class VibrationEngine(context: Context) {

    private val vibrator: Vibrator? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator

        else -> context.getSystemService(Vibrator::class.java)
    }

    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /**
     * Whether the hardware can vary vibration strength. When false, the mapper
     * emulates intensity by chopping segments into short pulses.
     */
    val hasAmplitudeControl: Boolean get() = vibrator?.hasAmplitudeControl() == true

    /**
     * Plays [segments] once (or looping when [repeat] is true).
     *
     * @param forcePwmEmulation debug switch: pretend the device has no amplitude
     *   control, to exercise the emulation path on hardware that does.
     * @return the pattern's total duration in ms, or 0 if nothing was played.
     */
    fun play(
        segments: List<PatternSegment>,
        intensityScale: Float,
        repeat: Boolean = false,
        forcePwmEmulation: Boolean = false,
    ): Long {
        val vibrator = vibrator ?: return 0
        if (!vibrator.hasVibrator()) return 0

        val waveform = WaveformMapper.toWaveform(
            segments = segments,
            intensityScale = intensityScale,
            hasAmplitudeControl = hasAmplitudeControl && !forcePwmEmulation,
        ) ?: return 0

        val effect = VibrationEffect.createWaveform(
            waveform.timings,
            waveform.amplitudes,
            if (repeat) 0 else -1,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
        }
        return waveform.totalMs
    }

    /** Continuous buzz used as live feedback while recording a pattern. */
    fun startPreview(amplitude: Int) {
        val vibrator = vibrator ?: return
        val effect = VibrationEffect.createOneShot(
            PREVIEW_DURATION_MS,
            amplitude.coerceIn(1, PatternSegment.MAX_AMPLITUDE),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ALARM_AUDIO_ATTRIBUTES)
        }
    }

    fun stop() = vibrator?.cancel()

    private companion object {
        const val PREVIEW_DURATION_MS = 10_000L

        val ALARM_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
