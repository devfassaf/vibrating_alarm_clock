package com.faybish.vibealarm.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.domain.SessionEvent
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs one alerting window: plays the pattern (or ringtone) exactly once, then
 * hands control back to [SessionRuntime], which decides whether to auto-snooze
 * or finish the chain. The service does not stay alive across a snooze — the
 * chain lives in the database and in AlarmManager, not in this process.
 *
 * This service is the only place that performs the `Fire` transition, because it
 * is the only place that owns the vibrator: the receiver hands the trigger over
 * without touching the state machine.
 */
class AlarmRingingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var vibration: VibrationEngine
    private lateinit var sound: SoundEngine
    private var wakeLock: PowerManager.WakeLock? = null

    /** Waits out the alerting window; cleared before it dispatches completion. */
    private var timerJob: Job? = null
    private var currentStartId: Int = 0

    private val runtime: SessionRuntime get() = AppGraph.sessionRuntime
    private val notifications: AlarmNotifications get() = AppGraph.notifications
    private val logger: ReliabilityLogger get() = AppGraph.reliabilityLogger

    /** Drives the engines directly, unlike the receiver-side sink. */
    private val sink = object : SessionRuntime.OutputSink {
        override fun startOutputs(alarm: AlarmEntity, instanceId: Long) =
            startPlayback(alarm, instanceId)

        override fun stopOutputs(alarmId: Long) {
            // Only stop if this really is the alarm we are playing: a transition for
            // some other alarm must never silence a live one.
            if (alarmId == playingAlarmId) stopOutputsOnly()
        }

        override fun showFiring(alarm: AlarmEntity, instanceId: Long) =
            notifications.postFiring(alarm, instanceId)
    }

    override fun onCreate() {
        super.onCreate()
        vibration = VibrationEngine(this)
        sound = SoundEngine(this, logger)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0) ?: 0

        if (intent == null || alarmId == 0L) {
            stopEverything()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (intent.action == AlarmIntents.ACTION_STOP_RINGING) {
            if (alarmId == playingAlarmId) {
                stopEverything()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        // A different alarm arriving replaces this one: there is a single vibrator.
        if (playingAlarmId != null && playingAlarmId != alarmId) stopOutputsOnly()

        // The platform allows only a few seconds between startForegroundService and
        // startForeground, so post before touching the database.
        val turnScreenOn = intent.getBooleanExtra(EXTRA_TURN_SCREEN_ON, true)
        if (!startForegroundCompat(alarmId, turnScreenOn)) return START_NOT_STICKY

        playingAlarmId = alarmId
        currentStartId = startId
        acquireWakeLock(PROVISIONAL_WAKE_LOCK_MS)

        val instanceId = intent.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)
        scope.launch {
            runtime.preemptOthers(exceptAlarmId = alarmId, sink = runtime.ServiceControlSink())
            runtime.handle(alarmId, SessionEvent.Fire(Instant.now()), sink, instanceId)
        }
        return START_NOT_STICKY
    }

    /**
     * @return false when the platform refused the foreground start. The alerting
     *   notification is posted directly in that case, so the alarm still surfaces —
     *   a refusal must never turn into a silent morning.
     */
    private fun startForegroundCompat(alarmId: Long, turnScreenOn: Boolean): Boolean {
        val notification = notifications.buildStarting(turnScreenOn)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    AlarmNotifications.firingId(alarmId),
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(AlarmNotifications.firingId(alarmId), notification)
            }
            true
        } catch (e: Exception) {
            logger.log(
                ReliabilityLogger.FGS_DENIED,
                "alarm=$alarmId startForeground ${e.javaClass.simpleName}: ${e.message}",
            )
            scope.launch {
                AppGraph.repository.getAlarm(alarmId)?.let { notifications.postFiring(it, 0) }
            }
            stopSelf()
            false
        }
    }

    /**
     * Starts output and schedules the single [SessionEvent.PlaybackComplete] that
     * ends this alerting window. There is no public "vibration finished" callback,
     * so the window length is computed from the pattern itself.
     */
    private fun startPlayback(alarm: AlarmEntity, instanceId: Long) {
        timerJob?.cancel()
        timerJob = scope.launch {
            val segments = AppGraph.repository.segmentsForAlarm(alarm)
            val vibrateOnly = alarm.mode == RingMode.VIBRATE_ONLY

            val patternMs = if (vibrateOnly || alarm.vibrateWithSound) {
                vibration.play(
                    segments = segments,
                    intensityScale = alarm.intensityScale,
                    // Alongside a ringtone the pattern loops; on its own it plays once.
                    repeat = !vibrateOnly,
                    forcePwmEmulation = AppGraph.settings.forcePwmEmulation,
                )
            } else {
                0L
            }

            if (!vibrateOnly) sound.play(alarm.ringtoneUri, alarm.volume)

            // A vibration-only alarm lasts exactly one pass of the pattern. That is
            // the entire point: it stops by itself without anyone touching the phone.
            // The floor guards against a device with no vibrator reporting 0 and
            // turning the chain into an instant snooze loop.
            val windowMs = if (vibrateOnly) {
                patternMs.coerceAtLeast(MIN_WINDOW_MS)
            } else {
                alarm.autoSilenceSeconds.coerceAtLeast(1) * 1000L
            }
            acquireWakeLock(windowMs + WAKE_LOCK_MARGIN_MS)

            delay(windowMs + PLAYBACK_TAIL_MS)

            // Detach before dispatching: the completion transition emits StopOutputs,
            // which must not cancel the coroutine that is applying the effects.
            timerJob = null
            scope.launch {
                // Deliberately keeps the wake lock past stopping the outputs: the
                // transition still has to write the instance and arm the next snooze,
                // and with the screen off this is the only thing keeping the CPU up.
                acquireWakeLock(TRANSITION_WAKE_LOCK_MS)
                runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)
                stopSelf(currentStartId)
            }
        }
    }

    /** Silences output without giving up the wake lock. */
    private fun stopOutputsOnly() {
        timerJob?.cancel()
        timerJob = null
        vibration.stop()
        sound.stop()
    }

    private fun stopEverything() {
        stopOutputsOnly()
        releaseWakeLock()
        playingAlarmId = null
    }

    private fun acquireWakeLock(durationMs: Long) {
        releaseWakeLock()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { acquire(durationMs) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopEverything()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_TURN_SCREEN_ON = "turnScreenOn"

        /**
         * The alarm currently alerting, or null. Read by [AlarmServiceStarter] so a
         * transition belonging to one alarm cannot stop another's ringing service.
         * Service and receivers always share a process, so a static is the honest
         * representation of "what this process is currently playing".
         */
        @Volatile
        var playingAlarmId: Long? = null
            private set

        private const val WAKE_LOCK_TAG = "VibeAlarm:ringing"
        private const val PROVISIONAL_WAKE_LOCK_MS = 3 * 60 * 1000L
        private const val WAKE_LOCK_MARGIN_MS = 10_000L
        private const val TRANSITION_WAKE_LOCK_MS = 30_000L
        private const val PLAYBACK_TAIL_MS = 250L
        private const val MIN_WINDOW_MS = 1_000L
    }
}

/**
 * Starts and stops the ringing service from outside it, with the Android 12+
 * background-start restriction handled explicitly.
 *
 * Exact alarm delivery puts the app on the temporary power allowlist, which is
 * what makes a foreground-service start legal here. If an OEM blocks it anyway we
 * post the alerting notification directly so the alarm is still surfaced, and
 * record it — silent failure is the one outcome this app cannot have.
 */
object AlarmServiceStarter {

    fun start(
        context: Context,
        alarm: AlarmEntity,
        instanceId: Long,
        logger: ReliabilityLogger,
        notifications: AlarmNotifications,
    ) {
        val intent = AlarmIntents.startRingingService(context, alarm.id, instanceId)
            .putExtra(AlarmRingingService.EXTRA_TURN_SCREEN_ON, alarm.turnScreenOn)
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            logger.log(
                ReliabilityLogger.FGS_DENIED,
                "alarm=${alarm.id} ${e.javaClass.simpleName}: ${e.message}",
            )
            notifications.postFiring(alarm, instanceId)
        }
    }

    /** Stops the ringing service only if it is this alarm that is playing. */
    fun stop(context: Context, alarmId: Long) {
        if (AlarmRingingService.playingAlarmId != alarmId) return
        val intent = Intent(context, AlarmRingingService::class.java)
        runCatching { context.stopService(intent) }
    }
}
