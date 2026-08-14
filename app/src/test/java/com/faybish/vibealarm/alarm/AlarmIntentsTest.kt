package com.faybish.vibealarm.alarm

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * PendingIntent identity, which is the quietest way to break an alarm clock.
 *
 * `Intent.filterEquals` ignores extras, so two intents that differ only in the alarm id would
 * share one slot: arming alarm B would replace alarm A's trigger, and dismissing one would
 * dismiss the other. The data URI and the request code are what keep them apart, and nothing
 * about that is visible at a call site — hence this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmIntentsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
    }

    /** Every purpose, for one alarm: all five have to be distinct intents. */
    private fun allPurposes(alarmId: Long, instanceId: Long) = mapOf(
        "fire" to AlarmIntents.firePendingIntent(context, alarmId, instanceId),
        "snooze" to AlarmIntents.snoozePendingIntent(context, alarmId, instanceId),
        "dismiss" to AlarmIntents.dismissPendingIntent(context, alarmId, instanceId),
        "ring screen" to AlarmIntents.ringingActivityIntent(context, alarmId, instanceId),
        "open app" to AlarmIntents.appPendingIntent(context, alarmId),
    )

    private fun requestCodeOf(intent: android.app.PendingIntent): Int =
        shadowOf(intent).requestCode

    @Test
    fun `one alarm's five purposes never share a slot`() {
        val purposes = allPurposes(alarmId = 3, instanceId = 11)

        val codes = purposes.mapValues { requestCodeOf(it.value) }
        assertWithMessage("request codes: $codes")
            .that(codes.values.toSet())
            .hasSize(purposes.size)
    }

    @Test
    fun `different alarms never share a slot for the same purpose`() {
        val ids = listOf(1L, 2L, 3L, 9L, 17L, 100L)

        listOf<(Long) -> android.app.PendingIntent>(
            { AlarmIntents.firePendingIntent(context, it, 0) },
            { AlarmIntents.snoozePendingIntent(context, it, 0) },
            { AlarmIntents.dismissPendingIntent(context, it, 0) },
            { AlarmIntents.ringingActivityIntent(context, it, 0) },
            { AlarmIntents.appPendingIntent(context, it) },
        ).forEachIndexed { purpose, build ->
            val codes = ids.map { requestCodeOf(build(it)) }
            assertWithMessage("purpose #$purpose codes: $codes")
                .that(codes.toSet())
                .hasSize(ids.size)
        }
    }

    /**
     * The stride is what leaves room for the offsets. Alarm 1's slots must not run into
     * alarm 2's — with five purposes and a stride of ten there are five spare, but a sixth
     * purpose one day must not silently overflow.
     */
    @Test
    fun `an alarm's slots stay inside its own stride`() {
        val first = allPurposes(alarmId = 1, instanceId = 0).values.map { requestCodeOf(it) }
        val second = allPurposes(alarmId = 2, instanceId = 0).values.map { requestCodeOf(it) }

        assertThat(first.max()).isLessThan(second.min())
    }

    /** Cancelling has to find the very intent that was armed, or the alarm stays armed. */
    @Test
    fun `the cancel intent matches the armed trigger`() {
        val armed = AlarmIntents.firePendingIntent(context, alarmId = 5, instanceId = 42)

        val found = AlarmIntents.cancelFirePendingIntent(context, alarmId = 5)

        assertThat(found).isNotNull()
        assertThat(requestCodeOf(found!!)).isEqualTo(requestCodeOf(armed))
    }

    /** FLAG_NO_CREATE: asking about an alarm that was never armed must not create one. */
    @Test
    fun `the cancel intent is null when nothing was armed`() {
        assertThat(AlarmIntents.cancelFirePendingIntent(context, alarmId = 777)).isNull()
    }

    @Test
    fun `the service intent carries the ids the service needs`() {
        val intent = AlarmIntents.startRingingService(context, alarmId = 8, instanceId = 4)

        assertThat(intent.action).isEqualTo(AlarmIntents.ACTION_START_RINGING)
        assertThat(intent.getLongExtra(AlarmIntents.EXTRA_ALARM_ID, 0)).isEqualTo(8)
        assertThat(intent.getLongExtra(AlarmIntents.EXTRA_INSTANCE_ID, 0)).isEqualTo(4)
    }
}
