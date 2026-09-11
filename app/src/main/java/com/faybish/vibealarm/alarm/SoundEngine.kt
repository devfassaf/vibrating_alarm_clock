package com.faybish.vibealarm.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
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
 *  - The chosen percentage is delivered by [MediaPlayer.setVolume], the one gain that is
 *    ours alone, measured against the loudest alarm the phone will play. The shared stream
 *    is only given enough headroom for that — raised when too quiet, never lowered, handed
 *    back in [stop]. [AlarmStreamVolume] is where that is worked out, and why the phone's
 *    own alarm volume cannot change how loud a configured alarm comes out.
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
     * The device's own volume curve, read once. It describes the hardware, not the moment,
     * and reading it costs one call per step of the stream.
     */
    private val curve: AlarmStreamVolume.Curve? by lazy { readCurve() }

    /**
     * @param rampMillis when > 0, opens at [VolumeRamp.START_FRACTION] of [volume] and
     *   climbs to it over this long. The ramp is the only part of playback that keeps
     *   running after this call returns.
     */
    /** @return false when no source could be played at all — the one case worth logging. */
    fun play(ringtoneUri: String?, volume: Float, rampMillis: Long = 0L): Boolean {
        stop()
        // Once for the whole call: the stream is one shared thing, and moving it again per
        // source would leave the level of a source that failed remembered as the level to
        // hand back — the alarm after it would then play at full volume.
        val playerVolume = applyStreamVolume(volume)
        val sources = buildList {
            ringtoneUri?.takeIf { it.isNotBlank() }?.let { add(it.toUri()) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
        }

        for ((index, uri) in sources.withIndex()) {
            if (start(uri, playerVolume, rampMillis)) {
                if (index > 0) logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "system default")
                return true
            }
        }
        if (startBundledFallback(playerVolume, rampMillis)) {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "bundled asset")
            return true
        }
        logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "no playable source")
        // Nothing is going to play, so the borrowed stream goes back now rather than at the
        // end of a window that produced no sound.
        restoreStreamVolume()
        return false
    }

    private fun start(uri: Uri, playerVolume: Float, rampMillis: Long): Boolean = try {
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

    /**
     * The attributes are handed to `create` rather than set on the player it returns. That
     * player is already prepared, and its track is already routed; audio attributes are a
     * before-prepare decision, so setting them afterwards is not guaranteed to move this
     * last resort off the media stream — where the silent mode it exists to survive would
     * mute it.
     */
    private fun startBundledFallback(playerVolume: Float, rampMillis: Long): Boolean = try {
        player = MediaPlayer.create(
            context,
            R.raw.fallback_alarm,
            ALARM_ATTRIBUTES,
            // A device that will not hand out a session id gets the "allocate one" value
            // rather than the error it answered with.
            audioManager.generateAudioSessionId()
                .takeIf { it > 0 } ?: AudioManager.AUDIO_SESSION_ID_GENERATE,
        )?.apply {
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
     * Gives the player the headroom it needs, remembering the stream's own level so [stop]
     * can put it back.
     *
     * The stream is only ever **raised** — see [AlarmStreamVolume]. Lowering it would turn
     * down every other alarm clock on the phone for as long as ours plays, which is exactly
     * what "this app stopped my built-in alarm from working" looks like.
     *
     * @return the volume the player itself should use, relative to the stream it ends up on.
     */
    private fun applyStreamVolume(volume: Float): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        // Some OEM volume panels can leave the alarm stream muted, and a muted stream makes
        // no sound at the level it reports. Read as the silence it is, so the plan raises
        // out of it instead of attenuating from a level nothing is playing at.
        val muted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_ALARM) }
            .getOrDefault(false)
        val current = if (muted) 0 else audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val plan = AlarmStreamVolume.plan(volume, current, max, curve)

        val raiseTo = plan.raiseStreamTo ?: return plan.playerVolume
        return try {
            savedStreamVolume = current
            // Best-effort: unmuting is refused under a total-silence policy, which is the
            // one state no app can play through and the reliability screen reports.
            if (muted) {
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
            // The stream stayed where it was, so the player covers what it can and the
            // phone's own level becomes the ceiling — the most an app is allowed to do.
            AlarmStreamVolume.plan(volume, current, max, curve, mayRaise = false).playerVolume
        }
    }

    /**
     * The device's loudness per stream step, or null when it will not say.
     *
     * `getStreamVolumeDb` arrived in API 28, and phones below that — or ones that answer
     * with the same figure for every step, which is not an answer — leave the indices
     * meaningless. [AlarmStreamVolume] then stops using them as a volume control.
     */
    private fun readCurve(): AlarmStreamVolume.Curve? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        return AlarmStreamVolume.Curve.of(max) { index ->
            runCatching {
                audioManager.getStreamVolumeDb(
                    AudioManager.STREAM_ALARM,
                    index,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                )
            }.getOrNull()
        }
    }

    fun stop() {
        rampJob?.cancel()
        rampJob = null
        releasePlayer()
        restoreStreamVolume()
    }

    /** Hands the stream back at the level the phone had it. Doing nothing is the norm:
     *  most rings never need to move it at all. */
    private fun restoreStreamVolume() {
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
