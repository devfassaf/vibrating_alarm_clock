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
 */
class AlarmRingingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var vibration: VibrationEngine
    private lateinit var sound: SoundEngine
    private var wakeLock: PowerManager.WakeLock? = null

    /** Waits out the alerting window; cleared before it dispatches completion. */
    private var timerJob: Job? = null

    private val runtime: SessionRuntime get() = AppGraph.sessionRuntime
    private val notifications: AlarmNotifications get() = AppGraph.notifications
    private val logger: ReliabilityLogger get() = AppGraph.reliabilityLogger

    /** Drives the engines directly, unlike the receiver-side sink. */
    private val sink = object : SessionRuntime.OutputSink {
        override fun startOutputs(alarm: AlarmEntity, instanceId: Long) =
            startPlayback(alarm, instanceId)

        override fun stopOutputs() = stopPlayback()

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

        if (intent == null || intent.action == AlarmIntents.ACTION_STOP_RINGING || alarmId == 0L) {
            stopPlayback()
            stopSelf()
            return START_NOT_STICKY
        }

        // The platform allows only a few seconds between startForegroundService and
        // startForeground, so post before touching the database.
        startForegroundCompat(intent.getBooleanExtra(EXTRA_TURN_SCREEN_ON, true))
        acquireWakeLock(PROVISIONAL_WAKE_LOCK_MS)

        val instanceId = intent.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)
        scope.launch {
            runtime.preemptOthers(exceptAlarmId = alarmId, sink = sink)
            runtime.handle(alarmId, SessionEvent.Fire(Instant.now()), sink, instanceId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(turnScreenOn: Boolean) {
        val notification = notifications.buildStarting(turnScreenOn)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AlarmNotifications.FIRING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(AlarmNotifications.FIRING_NOTIFICATION_ID, notification)
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
            val windowMs = if (vibrateOnly) {
                patternMs
            } else {
                alarm.autoSilenceSeconds.coerceAtLeast(1) * 1000L
            }
            acquireWakeLock(windowMs + WAKE_LOCK_MARGIN_MS)

            delay(windowMs + PLAYBACK_TAIL_MS)

            // Detach before dispatching: the completion transition emits StopOutputs,
            // which must not cancel the coroutine that is applying the effects.
            timerJob = null
            scope.launch {
                runtime.handle(alarm.id, SessionEvent.PlaybackComplete(Instant.now()), sink, instanceId)
                stopSelf()
            }
        }
    }

    private fun stopPlayback() {
        timerJob?.cancel()
        timerJob = null
        vibration.stop()
        sound.stop()
        releaseWakeLock()
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
        stopPlayback()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_TURN_SCREEN_ON = "turnScreenOn"
        private const val WAKE_LOCK_TAG = "VibeAlarm:ringing"
        private const val PROVISIONAL_WAKE_LOCK_MS = 3 * 60 * 1000L
        private const val WAKE_LOCK_MARGIN_MS = 10_000L
        private const val PLAYBACK_TAIL_MS = 250L
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

    fun stop(context: Context) {
        val intent = Intent(context, AlarmRingingService::class.java)
            .setAction(AlarmIntents.ACTION_STOP_RINGING)
        runCatching { context.stopService(intent) }
    }
}
