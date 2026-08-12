package com.faybish.vibealarm.ui.reliability

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.alarm.CheckId
import com.faybish.vibealarm.alarm.CheckResult
import com.faybish.vibealarm.alarm.CheckStatus
import com.faybish.vibealarm.alarm.ReliabilityChecks
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ReliabilityLogEntity
import com.faybish.vibealarm.data.ReliabilityLogger
import com.faybish.vibealarm.data.RingMode
import com.faybish.vibealarm.data.ScheduleCodec
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReliabilityViewModel(context: Context) : ViewModel() {

    private val checks = ReliabilityChecks(context, AppGraph.scheduler)
    private val repository = AppGraph.repository

    private val _results = MutableStateFlow(checks.runAll())
    val results: StateFlow<List<CheckResult>> = _results.asStateFlow()

    val log: StateFlow<List<ReliabilityLogEntity>> = AppGraph.db.logDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val androidVersion: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    fun refresh() {
        _results.value = checks.runAll()
    }

    fun openFix(id: CheckId): Boolean = checks.openFix(id)

    fun needsAttention(): Boolean = _results.value.any { it.status == CheckStatus.ACTION_NEEDED }

    /**
     * Creates a throwaway alarm a few minutes out that goes through the exact same
     * pipeline as a real one. The user reboots without unlocking; if the phone
     * vibrates on the lock screen, direct boot works on this device.
     */
    fun scheduleRebootTest(minutesFromNow: Int = REBOOT_TEST_MINUTES, onScheduled: (Instant) -> Unit) {
        viewModelScope.launch {
            val fireAt = Instant.now().plusSeconds(minutesFromNow * 60L)
            val local = fireAt.atZone(ZoneId.systemDefault()).toLocalTime()
            val id = repository.saveAlarm(
                AlarmEntity(
                    label = REBOOT_TEST_LABEL,
                    timeMinutesOfDay = ScheduleCodec.timeToMinutes(
                        LocalTime.of(local.hour, local.minute),
                    ),
                    mode = RingMode.VIBRATE_ONLY,
                    snoozeRepeatCount = 0,
                    turnScreenOn = true,
                ),
            )
            repository.getAlarm(id)?.let { AppGraph.scheduler.onAlarmSaved(it) }
            AppGraph.reliabilityLogger.log(REBOOT_TEST_EVENT, "scheduled for $fireAt")
            onScheduled(fireAt)
        }
    }

    /** Whether a test alarm actually fired after the last reboot test was scheduled. */
    suspend fun rebootTestVerdict(): RebootTestVerdict {
        val logDao = AppGraph.db.logDao()
        val scheduled = logDao.latest(REBOOT_TEST_EVENT) ?: return RebootTestVerdict.NOT_RUN
        val boot = logDao.latest(ReliabilityLogger.BOOT_RECEIVED)
        val fired = logDao.latest(ReliabilityLogger.FIRED)
        return when {
            fired != null && fired.timestamp > scheduled.timestamp &&
                boot != null && boot.timestamp > scheduled.timestamp -> RebootTestVerdict.PASSED

            boot != null && boot.timestamp > scheduled.timestamp -> RebootTestVerdict.BOOTED_NOT_FIRED
            else -> RebootTestVerdict.WAITING
        }
    }

    companion object {
        const val REBOOT_TEST_LABEL = "Reboot test"
        const val REBOOT_TEST_EVENT = "REBOOT_TEST_SCHEDULED"
        const val REBOOT_TEST_MINUTES = 4
    }
}

enum class RebootTestVerdict { NOT_RUN, WAITING, BOOTED_NOT_FIRED, PASSED }
