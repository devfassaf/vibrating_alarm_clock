package com.faybish.vibealarm.alarm

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.ReliabilityLogger
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The one interruption setting that really can silence an alarm.
 *
 * Silent and vibrate-only modes cannot (see [SilentModeTest]); Do Not Disturb set to total
 * silence can, and it is invisible unless something says so. The Reliability screen is the
 * only place in the app that can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DndCheckTest {

    private lateinit var application: Application
    private lateinit var db: AppDb
    private lateinit var checks: ReliabilityChecks

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(application, AppDb::class.java)
            .allowMainThreadQueries()
            .build()
        val logger = ReliabilityLogger(db.logDao(), CoroutineScope(Dispatchers.Unconfined))
        val repository = AlarmRepository(db)
        val scheduler = AlarmScheduler(application, repository, logger) { ZoneId.of("Asia/Jerusalem") }
        checks = ReliabilityChecks(application, scheduler)
    }

    @After
    fun tearDown() = db.close()

    private fun dnd(): CheckResult =
        checks.runAll().single { it.id == CheckId.DND_TOTAL_SILENCE }

    /** The shadow's setter is protected; the real API is public and does the same thing. */
    private fun setFilter(filter: Int) {
        application.getSystemService(NotificationManager::class.java)
            .setInterruptionFilter(filter)
    }

    @Test
    fun `total silence is reported as something to fix`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        assertThat(dnd().status).isEqualTo(CheckStatus.ACTION_NEEDED)
        assertThat(dnd().fixable).isTrue()
    }

    /** Ordinary Do Not Disturb lets alarms through, and must not cry wolf. */
    @Test
    fun `priority and alarms-only filters are fine`() {
        listOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
        ).forEach { filter ->
            setFilter(filter)
            assertThat(dnd().status).isEqualTo(CheckStatus.OK)
        }
    }

    @Test
    fun `the check is part of the list the screen shows`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertThat(checks.runAll().map { it.id }).contains(CheckId.DND_TOTAL_SILENCE)
    }

    // --- the rest of the screen ---

    /** Every requirement gets exactly one row: a duplicate would render twice, a missing one
     *  would hide a reason an alarm can fail. */
    @Test
    fun `every check appears exactly once`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        val ids = checks.runAll().map { it.id }

        assertThat(ids).containsNoDuplicates()
        assertThat(ids).containsExactlyElementsIn(CheckId.entries)
    }

    /** The permission the whole app rests on. */
    @Test
    fun `exact alarms are reported as broken when the permission is missing`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(statusOf(CheckId.EXACT_ALARMS)).isEqualTo(CheckStatus.ACTION_NEEDED)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(statusOf(CheckId.EXACT_ALARMS)).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun `notifications turned off are reported as broken`() {
        shadowOf(application.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(false)
        assertThat(statusOf(CheckId.NOTIFICATIONS)).isEqualTo(CheckStatus.ACTION_NEEDED)

        shadowOf(application.getSystemService(NotificationManager::class.java))
            .setNotificationsEnabled(true)
        assertThat(statusOf(CheckId.NOTIFICATIONS)).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun `battery optimisation is reported until the app is exempt`() {
        val power = shadowOf(application.getSystemService(android.os.PowerManager::class.java))
        power.setIgnoringBatteryOptimizations(application.packageName, false)
        assertThat(statusOf(CheckId.BATTERY_OPTIMIZATION)).isEqualTo(CheckStatus.ACTION_NEEDED)

        power.setIgnoringBatteryOptimizations(application.packageName, true)
        assertThat(statusOf(CheckId.BATTERY_OPTIMIZATION)).isEqualTo(CheckStatus.OK)
    }

    /** The two that can never be "fixed", only explained. */
    @Test
    fun `the informational rows never demand action`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertThat(statusOf(CheckId.AMPLITUDE_CONTROL))
            .isAnyOf(CheckStatus.OK, CheckStatus.INFO)
        assertThat(statusOf(CheckId.SYSTEM_VIBRATION_STRENGTH)).isEqualTo(CheckStatus.MANUAL)
        assertThat(statusOf(CheckId.OEM_BACKGROUND_LIMITS)).isEqualTo(CheckStatus.MANUAL)
    }

    /**
     * A row that claims to be fixable but opens nothing is a button that does not work — the
     * one thing a reliability screen must not have.
     */
    @Test
    fun `every fixable row actually opens something`() {
        setFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        checks.runAll().filter { it.fixable }.forEach { result ->
            assertWithMessage("${result.id} should open a settings page")
                .that(checks.openFix(result.id))
                .isTrue()
        }
    }

    @Test
    fun `a row that is not fixable says so rather than pretending`() {
        assertThat(checks.openFix(CheckId.AMPLITUDE_CONTROL)).isFalse()
    }

    private fun statusOf(id: CheckId): CheckStatus = checks.runAll().single { it.id == id }.status
}
