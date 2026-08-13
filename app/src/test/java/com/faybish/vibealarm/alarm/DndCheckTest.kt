package com.faybish.vibealarm.alarm

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.faybish.vibealarm.data.AlarmRepository
import com.faybish.vibealarm.data.AppDb
import com.faybish.vibealarm.data.ReliabilityLogger
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
