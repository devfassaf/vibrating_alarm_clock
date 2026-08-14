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
import com.faybish.vibealarm.domain.AlarmStreamVolume
import com.faybish.vibealarm.domain.VolumeRamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays an alarm ringtone on the alarm stream.
 *
 * **Silent and vibrate-only modes do not silence this.** Everything goes out with
 * [AudioAttributes.USAGE_ALARM], which the platform routes to `STREAM_ALARM` — the one
 * stream the ringer mode does not touch, because "silent" means "do not ring for other
 * people", not "do not wake me up". The only setting that can still mute it is Do Not
 * Disturb configured for total silence, which the Reliability screen reports.
 *
 * Two more things here are less obvious than they look:
 *  - Per-alarm volume needs the alarm *stream* to be at least as loud as the level asked
 *    for, because [MediaPlayer.setVolume] is relative to it. The stream is shared with
 *    every other alarm clock on the phone, so it is raised when too quiet and never
 *    lowered; the rest of the distance is covered by the player's own volume.
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
    /** @return false when no source could be played at all — the one case worth logging. */
    fun play(ringtoneUri: String?, volume: Float, rampMillis: Long = 0L): Boolean {
        stop()
        val sources = buildList {
            ringtoneUri?.takeIf { it.isNotBlank() }?.let { add(it.toUri()) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
        }

        for ((index, uri) in sources.withIndex()) {
            if (start(uri, volume, rampMillis)) {
                if (index > 0) logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "system default")
                return true
            }
        }
        if (startBundledFallback(volume, rampMillis)) {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "bundled asset")
            return true
        }
        logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "no playable source")
        return false
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
     * The stream is only ever **raised** — see [AlarmStreamVolume]. Lowering it would turn
     * down every other alarm clock on the phone for as long as ours plays, which is exactly
     * what "this app stopped my built-in alarm from working" looks like.
     *
     * @return the volume the player itself should use, relative to the stream it ends up on.
     */
    private fun applyStreamVolume(volume: Float): Float {
        if (savedStreamVolume != null) return 1f
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val plan = AlarmStreamVolume.plan(volume, current, max)

        val raiseTo = plan.raiseStreamTo ?: return plan.playerVolume
        return try {
            savedStreamVolume = current
            // Some OEM volume panels can leave the alarm stream muted, and a muted stream
            // ignores the level we set. Unmuting is best-effort: it is refused under a
            // total-silence policy, where the fallback below is all that is left.
            if (audioManager.isStreamMute(AudioManager.STREAM_ALARM)) {
                runCatching {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_ALARM,
                        AudioManager.ADJUST_UNMUTE,
                        0,
                    )
                }
            }
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, raiseTo, 0)
            plan.playerVolume
        } catch (e: SecurityException) {
            savedStreamVolume = null
            volume.coerceIn(0f, 1f)
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

    internal companion object {
        /** Fine enough that the climb is not heard as steps, coarse enough to be free. */
        private const val RAMP_STEP_MS = 200L

        /**
         * What keeps the alarm audible in silent and vibrate-only modes: alarm usage routes
         * playback to the alarm stream, which the ringer mode does not govern. Exposed so a
         * test can assert it, because dropping it would be silent in every sense.
         */
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
