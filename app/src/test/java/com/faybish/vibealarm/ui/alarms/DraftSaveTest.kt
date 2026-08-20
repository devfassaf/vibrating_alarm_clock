package com.faybish.vibealarm.ui.alarms

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.AppGraph
import com.faybish.vibealarm.data.AlarmEntity
import com.faybish.vibealarm.data.ScheduleCodec
import com.google.common.truth.Truth.assertThat
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * What pressing Save means.
 *
 * The draft model exists so an edit does not reach the alarm by accident; the flip side is
 * that pressing Save has to be unambiguous — the alarm is on, the editor is done, and the
 * confirmation describes the alarm that now exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DraftSaveTest {

    private lateinit var application: Application
    private lateinit var viewModel: AlarmListViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        AppGraph.resetForTests(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel = AlarmListViewModel()
    }

    private fun createAlarm(enabled: Boolean, time: LocalTime = LocalTime.of(7, 0)): AlarmEntity =
        runBlocking {
            val id = AppGraph.repository.saveAlarm(
                AlarmEntity(
                    timeMinutesOfDay = ScheduleCodec.timeToMinutes(time),
                    enabled = enabled,
                ),
            )
            AppGraph.repository.getAlarm(id)!!
        }

    /** The view model launches on the main dispatcher; let those coroutines finish. */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun storedAlarm(id: Long): AlarmEntity = runBlocking {
        AppGraph.repository.getAlarm(id)!!
    }

    // --- duplicating ---

    /**
     * The copy arrives switched off on purpose. Two enabled alarms on the same minute
     * preempt each other: one silences the other, the silenced one is recorded PREEMPTED,
     * and the morning after reports a missed alarm that never failed. Off means the copy
     * cannot do that before the user has moved it.
     */
    @Test
    fun `a duplicate is created switched off`() {
        val original = createAlarm(enabled = true, time = LocalTime.of(7, 30))

        viewModel.duplicate(original.id, copySuffix = "(עותק)")
        awaitUntil("the copy exists") { allAlarms().size == 2 }

        val copy = allAlarms().single { it.id != original.id }
        assertThat(copy.enabled).isFalse()
        assertThat(storedAlarm(original.id).enabled).isTrue()
    }

    @Test
    fun `a duplicate carries every setting of the original`() {
        val original = runBlocking {
            val id = AppGraph.repository.saveAlarm(
                AlarmEntity(
                    label = "שבת",
                    timeMinutesOfDay = ScheduleCodec.timeToMinutes(LocalTime.of(6, 45)),
                    enabled = true,
                    volume = 0.42f,
                    intensityScale = 0.7f,
                    turnScreenOn = false,
                    autoSilenceSeconds = 45,
                    snoozeIntervalMinutes = 7,
                    snoozeRepeatCount = 4,
                    soundRampUp = true,
                ),
            )
            AppGraph.repository.getAlarm(id)!!
        }

        viewModel.duplicate(original.id, copySuffix = "(עותק)")
        awaitUntil("the copy exists") { allAlarms().size == 2 }

        val copy = allAlarms().single { it.id != original.id }
        assertThat(copy.timeMinutesOfDay).isEqualTo(original.timeMinutesOfDay)
        assertThat(copy.volume).isEqualTo(0.42f)
        assertThat(copy.intensityScale).isEqualTo(0.7f)
        assertThat(copy.turnScreenOn).isFalse()
        assertThat(copy.autoSilenceSeconds).isEqualTo(45)
        assertThat(copy.snoozeIntervalMinutes).isEqualTo(7)
        assertThat(copy.snoozeRepeatCount).isEqualTo(4)
        assertThat(copy.soundRampUp).isTrue()
        assertThat(copy.label).isEqualTo("שבת (עותק)")
    }

    /** It opens for editing, which is the reason to duplicate in the first place. */
    @Test
    fun `a duplicate opens as the draft`() {
        val original = createAlarm(enabled = true)

        viewModel.duplicate(original.id, copySuffix = "(עותק)")
        awaitUntil("the draft is the copy") {
            viewModel.draft.value?.let { it.id != original.id } == true
        }

        assertThat(viewModel.draft.value!!.enabled).isFalse()
        assertThat(viewModel.draftDirty.value).isFalse()
    }

    /** Off means unarmed: a copy that armed itself would ring beside the original. */
    @Test
    fun `a duplicate arms nothing`() {
        val original = createAlarm(enabled = true)
        val armedBefore = shadowOf(alarmManager()).scheduledAlarms.size

        viewModel.duplicate(original.id, copySuffix = "(עותק)")
        awaitUntil("the copy exists") { allAlarms().size == 2 }

        assertThat(shadowOf(alarmManager()).scheduledAlarms).hasSize(armedBefore)
    }

    @Test
    fun `duplicating an alarm that is already gone does nothing`() {
        viewModel.duplicate(alarmId = 404, copySuffix = "(עותק)")
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(allAlarms()).isEmpty()
    }

    private fun allAlarms(): List<AlarmEntity> = runBlocking {
        AppGraph.repository.getAllAlarms()
    }

    private fun alarmManager(): android.app.AlarmManager =
        application.getSystemService(android.app.AlarmManager::class.java)

    @Test
    fun `saving an edit writes it and switches the alarm on`() {
        val alarm = createAlarm(enabled = false)
        viewModel.beginEdit(alarm)
        viewModel.updateDraft(alarm.copy(label = "שבת", timeMinutesOfDay = 6 * 60 + 30))

        viewModel.commitDraft()

        awaitUntil("the edit to be stored") { storedAlarm(alarm.id).label == "שבת" }
        val saved = storedAlarm(alarm.id)
        assertThat(saved.timeMinutesOfDay).isEqualTo(6 * 60 + 30)
        assertThat(saved.enabled).isTrue()
    }

    /** The editor is done when the save lands; nothing should look pending afterwards. */
    @Test
    fun `saving closes the editor immediately`() {
        val alarm = createAlarm(enabled = true)
        viewModel.beginEdit(alarm)
        viewModel.updateDraft(alarm.copy(label = "x"))
        assertThat(viewModel.draft.value).isNotNull()

        viewModel.commitDraft()

        assertThat(viewModel.draft.value).isNull()
    }

    @Test
    fun `the confirmation describes the alarm that was saved`() {
        val alarm = createAlarm(enabled = false)
        viewModel.beginEdit(alarm)
        viewModel.updateDraft(alarm.copy(timeMinutesOfDay = 5 * 60))

        var announced: AlarmEntity? = null
        var trigger: java.time.Instant? = null
        viewModel.commitDraft { saved, next ->
            announced = saved
            trigger = next
        }

        awaitUntil("the confirmation") { announced != null }
        assertThat(announced!!.enabled).isTrue()
        assertThat(announced!!.timeMinutesOfDay).isEqualTo(5 * 60)
        // An enabled alarm always has a next occurrence, which is what the bubble reports.
        assertThat(trigger).isNotNull()
    }

    /** Discarding is still the way out that changes nothing. */
    @Test
    fun `discarding leaves the stored alarm alone, off included`() {
        val alarm = createAlarm(enabled = false)
        viewModel.beginEdit(alarm)
        viewModel.updateDraft(alarm.copy(label = "not saved", timeMinutesOfDay = 1))

        viewModel.resetDraft()
        viewModel.endEdit()
        shadowOf(Looper.getMainLooper()).idle()

        val stored = storedAlarm(alarm.id)
        assertThat(stored.label).isEmpty()
        assertThat(stored.timeMinutesOfDay).isEqualTo(7 * 60)
        assertThat(stored.enabled).isFalse()
    }

    @Test
    fun `committing with no draft open does nothing`() {
        viewModel.commitDraft()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(viewModel.draft.value).isNull()
    }
}
