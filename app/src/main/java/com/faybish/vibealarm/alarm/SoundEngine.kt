package com.faybish.vibealarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.net.toUri
import com.faybish.vibealarm.R
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.domain.VolumeRamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays an alarm ringtone on the alarm stream.
 *
 * Two things here are less obvious than they look:
 *  - Per-alarm volume has to move the alarm *stream* volume (saved and restored),
 *    because [MediaPlayer.setVolume] is relative to it — with the stream at zero
 *    the alarm would be silent no matter what we set.
 *  - The source list is a fallback chain. After a reboot, before the user unlocks,
 *    anything in credential-encrypted storage is unreadable, so we degrade to the
 *    system default and finally to a bundled asset rather than failing silently.
 */
class SoundEngine(
    private val context: Context,
    private val logger: ReliabilityLogger,
    /** Drives the ramp-up; the caller's scope so it dies with the caller. */
    private val scope: CoroutineScope,
) {

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedStreamVolume: Int? = null
    private var rampJob: Job? = null

    /**
     * @param rampMillis when > 0, opens at [VolumeRamp.START_FRACTION] of [volume] and
     *   climbs to it over this long. The ramp is the only part of playback that keeps
     *   running after this call returns.
     */
    fun play(ringtoneUri: String?, volume: Float, rampMillis: Long = 0L) {
        stop()
        val sources = buildList {
            ringtoneUri?.takeIf { it.isNotBlank() }?.let { add(it.toUri()) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
        }

        for ((index, uri) in sources.withIndex()) {
            if (start(uri, volume, rampMillis)) {
                if (index > 0) logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "system default")
                return
            }
        }
        if (startBundledFallback(volume, rampMillis)) {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "bundled asset")
        } else {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "no playable source")
        }
    }

    private fun start(uri: Uri, volume: Float, rampMillis: Long): Boolean = try {
        val playerVolume = applyStreamVolume(volume)
        player = MediaPlayer().apply {
            setAudioAttributes(ALARM_ATTRIBUTES)
            setDataSource(context, uri)
            isLooping = true
            setVolume(playerVolume, playerVolume)
            prepare()
            start()
        }
        startRamp(playerVolume, rampMillis)
        true
    } catch (e: Exception) {
        releasePlayer()
        false
    }

    private fun startBundledFallback(volume: Float, rampMillis: Long): Boolean = try {
        val playerVolume = applyStreamVolume(volume)
        player = MediaPlayer.create(context, R.raw.fallback_alarm)?.apply {
            setAudioAttributes(ALARM_ATTRIBUTES)
            isLooping = true
            setVolume(playerVolume, playerVolume)
            start()
        }
        startRamp(playerVolume, rampMillis)
        player != null
    } catch (e: Exception) {
        releasePlayer()
        false
    }

    /**
     * Steps the player's own volume, never the stream: the stream is already where the
     * user wants it, and moving it in steps would leave the phone quiet if the ramp were
     * killed mid-climb.
     */
    private fun startRamp(targetVolume: Float, rampMillis: Long) {
        rampJob?.cancel()
        if (rampMillis <= 0L) return
        val ramping = player ?: return
        rampJob = scope.launch {
            var elapsed = 0L
            while (elapsed <= rampMillis) {
                // Another alarm, a snooze, or a stop replaced the player: leave it alone.
                if (player !== ramping) return@launch
                val level = targetVolume * VolumeRamp.fractionAt(elapsed, rampMillis)
                runCatching { ramping.setVolume(level, level) }
                delay(RAMP_STEP_MS)
                elapsed += RAMP_STEP_MS
            }
            if (player === ramping) runCatching { ramping.setVolume(targetVolume, targetVolume) }
        }
    }

    /**
     * Moves the alarm stream to the per-alarm level, remembering the previous one so
     * [stop] can put it back.
     *
     * @return the volume the player itself should use: 1.0 when the stream was set
     *   for us, or the requested fraction when it could not be (total-silence DND
     *   makes this throw on some builds), since player volume is relative to the
     *   stream and is the only lever left in that case.
     */
    private fun applyStreamVolume(volume: Float): Float {
        val requested = volume.coerceIn(0f, 1f)
        if (savedStreamVolume != null) return 1f
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (requested * max).toInt().coerceIn(1, max)
        return try {
            savedStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            1f
        } catch (e: SecurityException) {
            savedStreamVolume = null
            requested
        }
    }

    fun stop() {
        rampJob?.cancel()
        rampJob = null
        releasePlayer()
        savedStreamVolume?.let { previous ->
            savedStreamVolume = null
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
        }
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        // release() must happen even if the stop() path throws, or the player leaks.
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }

    private companion object {
        /** Fine enough that the climb is not heard as steps, coarse enough to be free. */
        const val RAMP_STEP_MS = 200L

        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
