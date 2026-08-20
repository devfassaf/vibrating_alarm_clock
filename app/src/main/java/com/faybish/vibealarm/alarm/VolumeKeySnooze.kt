package com.faybish.vibealarm.alarm

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the volume keys snooze the alarm even when there is no screen to press.
 *
 * The keys used to be read by [com.faybish.vibealarm.AlarmActivity.onKeyDown], which only
 * works while that activity has focus — so they worked on the ring that managed to show the
 * screen and did nothing on the ones that did not, and nothing at all for an alarm whose
 * screen deliberately stays dark. An app cannot observe hardware volume keys directly with
 * the screen off, but the media stack routes them to the active remote-volume session, and
 * that arrives whether or not anything is on screen.
 *
 * Snooze, never dismiss: a phone under a blanket can press its own volume keys, and the worst
 * a stray press may do is delay this ring — it spends one of the alarm's configured snoozes,
 * never ends the chain outright the way a dismiss would.
 */
class VolumeKeySnooze(
    private val context: Context,
    private val onSnooze: () -> Unit,
) {

    private var session: MediaSession? = null
    private var volumeObserver: ContentObserver? = null

    /**
     * The alarm stream level this alarm itself established. Atomic because it is written on
     * the service's background scope when the ring starts and read on the main looper by the
     * observer; a stale read would compare against the wrong number and snooze the alarm
     * milliseconds after it started vibrating.
     */
    private val baseline = AtomicInteger(0)

    private val audio: AudioManager get() = context.getSystemService(AudioManager::class.java)

    /**
     * Held only for the alerting window: outside it the keys must change the volume again.
     *
     * Two mechanisms, because neither covers the whole job. The media session catches the
     * keys when nothing is playing on the alarm stream — a vibration-only alarm, the common
     * Shabbat case. But while a ringtone is playing, Android routes volume keys to the
     * active alarm stream instead of to any session, so the press lands as a volume change
     * and never reaches us. Watching the stream's own level catches exactly that press.
     */
    /**
     * @param watchStream whether to also watch the alarm stream's level. Only worth it when
     *   this ring actually plays a ringtone: that is the case where the platform hands the
     *   keys to the stream instead of to the session. A vibration-only alarm — the common
     *   Shabbat one — would otherwise register an observer on every system-settings change
     *   for a stream nothing is touching.
     */
    fun start(watchStream: Boolean = true) {
        if (watchStream) startVolumeWatch()
        if (session != null) return
        session = MediaSession(context, SESSION_TAG).apply {
            // A session with no callback is a session the framework can drop volume
            // adjustments for before they reach the provider. Measured working without it on
            // Android 15, but the phone this app exists for is a Galaxy running Samsung's own
            // media stack, and an empty callback costs nothing. The Handler is explicit
            // because this is constructed on a background dispatcher, where the one-argument
            // overload would try to use a Looper that does not exist.
            setCallback(object : MediaSession.Callback() {}, Handler(Looper.getMainLooper()))
            setPlaybackState(
                PlaybackState.Builder()
                    // Playing, because the media stack routes the keys to the session that
                    // is actually making noise; a paused session is not a candidate.
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_STOP)
                    .build(),
            )
            setPlaybackToRemote(volumeProvider())
            isActive = true
        }
    }

    fun stop() {
        // Unregistered first: stopping the alarm restores the stream to the level it had
        // before the alarm raised it, and that restore must not read as a user's press.
        volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
        session?.run {
            isActive = false
            release()
        }
        session = null
    }

    /**
     * A press on a phone that is already playing an alarm shows up as the alarm stream
     * changing level. The baseline is whatever the level is now — the alarm has already
     * raised it to the volume this alarm asked for — so any later change is the user's.
     */
    private fun startVolumeWatch() {
        if (volumeObserver != null) return
        baseline.set(alarmStreamVolume())
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val current = alarmStreamVolume()
                if (current == baseline.getAndSet(current)) return
                onSnooze()
            }
        }
        volumeObserver = observer
        register(observer)
    }

    /**
     * Runs [block] without the alarm stream's own level changes counting as a key press.
     *
     * The alarm raises the stream to the level this alarm asked for and puts it back when the
     * ringtone stops (`SoundEngine`), and that restore is indistinguishable from a user
     * pressing volume-down — it would snooze the alarm at the moment the ringtone ends,
     * cutting a longer vibration pattern short. Ordering the two calls correctly worked only
     * as long as every caller remembered to; this makes it a property of the mechanism.
     */
    fun withStreamChangeIgnored(block: () -> Unit) {
        val observer = volumeObserver
        if (observer == null) {
            block()
            return
        }
        context.contentResolver.unregisterContentObserver(observer)
        try {
            block()
        } finally {
            baseline.set(alarmStreamVolume())
            register(observer)
        }
    }

    private fun register(observer: ContentObserver) =
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)

    private fun alarmStreamVolume(): Int =
        runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(0)

    /**
     * A remote volume provider is what takes the keys away from the alarm stream: without
     * it the press only moves the slider, and the alarm keeps playing at a new volume.
     */
    internal fun volumeProvider(): VolumeProvider = object : VolumeProvider(
        VOLUME_CONTROL_ABSOLUTE,
        MAX_VOLUME,
        MAX_VOLUME / 2,
    ) {
        override fun onAdjustVolume(direction: Int) {
            // Direction 0 is the system asking about the current volume, not a key press.
            if (direction != 0) onSnooze()
        }

        override fun onSetVolumeTo(volume: Int) = onSnooze()
    }

    internal companion object {
        const val SESSION_TAG = "VibeAlarm:volumeKeys"

        /** Arbitrary: the value never reaches a speaker, only the key press matters. */
        const val MAX_VOLUME = 10

        /**
         * Whether this alarm wants its volume keys to snooze — the per-alarm choice, or the
         * global setting when the alarm has no opinion of its own.
         *
         * The default is a lambda so an alarm that has already decided never pays for the
         * settings read: this runs on the alarm path, once per ring.
         */
        suspend fun enabledFor(perAlarm: Boolean?, globalDefault: suspend () -> Boolean): Boolean =
            perAlarm ?: globalDefault()
    }
}
