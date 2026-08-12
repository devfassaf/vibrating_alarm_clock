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
) {

    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedStreamVolume: Int? = null

    fun play(ringtoneUri: String?, volume: Float) {
        stop()
        val sources = buildList {
            ringtoneUri?.takeIf { it.isNotBlank() }?.let { add(it.toUri()) }
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { add(it) }
        }

        for ((index, uri) in sources.withIndex()) {
            if (start(uri, volume)) {
                if (index > 0) logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "system default")
                return
            }
        }
        if (startBundledFallback(volume)) {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "bundled asset")
        } else {
            logger.log(ReliabilityLogger.FALLBACK_SOUND_USED, "no playable source")
        }
    }

    private fun start(uri: Uri, volume: Float): Boolean = try {
        applyStreamVolume(volume)
        player = MediaPlayer().apply {
            setAudioAttributes(ALARM_ATTRIBUTES)
            setDataSource(context, uri)
            isLooping = true
            prepare()
            start()
        }
        true
    } catch (e: Exception) {
        releasePlayer()
        false
    }

    private fun startBundledFallback(volume: Float): Boolean = try {
        applyStreamVolume(volume)
        player = MediaPlayer.create(context, R.raw.fallback_alarm)?.apply {
            setAudioAttributes(ALARM_ATTRIBUTES)
            isLooping = true
            start()
        }
        player != null
    } catch (e: Exception) {
        releasePlayer()
        false
    }

    /**
     * Sets the alarm stream to the per-alarm level, remembering the previous one.
     * Total-silence DND can make this throw on some builds; then we fall back to
     * scaling within whatever the stream is already at.
     */
    private fun applyStreamVolume(volume: Float) {
        if (savedStreamVolume != null) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val target = (volume.coerceIn(0f, 1f) * max).toInt().coerceIn(1, max)
        try {
            savedStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            savedStreamVolume = null
            player?.setVolume(volume, volume)
        }
    }

    fun stop() {
        releasePlayer()
        savedStreamVolume?.let { previous ->
            savedStreamVolume = null
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0) }
        }
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    private companion object {
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
