package com.faybish.vibealarm

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.faybish.vibealarm.alarm.AlarmIntents
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.InstanceState
import com.faybish.vibealarm.domain.SessionEvent
import com.faybish.vibealarm.ui.ringing.RingingScreen
import com.faybish.vibealarm.ui.theme.VibeAlarmTheme
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The full-screen alarm UI, shown over the lock screen while an alarm rings.
 *
 * Only reached for alarms configured to turn the screen on. A "screen stays dark"
 * alarm never launches this activity — the foreground service and the vibrator
 * are the whole alarm in that mode.
 */
class AlarmActivity : ComponentActivity() {

    private var alarmId: Long = 0
    private var instanceId: Long = 0
    private var alarm by mutableStateOf<AlarmEntity?>(null)
    private var volumeKeysSnooze = true
    private var watchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        bind(intent)

        setContent {
            VibeAlarmTheme {
                RingingScreen(
                    alarm = alarm,
                    onSnooze = { dispatch(SessionEvent.UserSnooze(Instant.now())) },
                    onDismiss = { dispatch(SessionEvent.UserDismiss(Instant.now())) },
                )
            }
        }

    }

    /**
     * This activity is singleInstance, so a second alarm's full-screen intent can be
     * delivered here instead of creating a new instance. Without rebinding, the screen
     * would keep showing the previous alarm while its buttons acted on stale ids.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        alarmId = intent.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0)
        instanceId = intent.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)

        watchJob?.cancel()
        watchJob = lifecycleScope.launch {
            val loaded = AppGraph.repository.getAlarm(alarmId)
            alarm = loaded
            // The alarm may defer the volume-key behavior to the global setting.
            volumeKeysSnooze = loaded?.volumeKeysSnooze
                ?: AppGraph.settings.volumeKeysSnooze.first()

            // A vibration-only pattern ends on its own and the session auto-snoozes with
            // nobody touching the phone. When that happens this screen has to go away
            // too, otherwise it sits there showing an alarm that already stopped.
            AppGraph.repository.observeActiveInstance(alarmId).collect { instance ->
                if (instance == null || instance.state != InstanceState.FIRING) finish()
            }
        }
    }

    /**
     * Volume keys act as snooze so the alarm can be silenced without finding a
     * button on a dark screen. This works only while this activity has focus:
     * with the screen off, an app cannot observe hardware volume keys at all.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVolumeKey && volumeKeysSnooze) {
            dispatch(SessionEvent.UserSnooze(Instant.now()))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dispatch(event: SessionEvent) {
        val runtime = AppGraph.sessionRuntime
        // Deliberately on the app scope, not lifecycleScope: finish() must not
        // cancel the transition that stops the vibrator and arms the snooze.
        AppGraph.appScope.launch {
            runtime.handle(alarmId, event, runtime.ServiceControlSink(), instanceId)
        }
        finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
