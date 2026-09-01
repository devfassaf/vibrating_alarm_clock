package com.faybish.vibealarm.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The countdown flips when the wall-clock minute turns, not at an arbitrary offset — a
 * drifting 60-second timer shows "in 18 minutes" up to a full minute after it stopped
 * being true, which is the frozen text this screen-wide clock exists to fix.
 */
class MinuteTickerTest {

    @Test
    fun `mid-minute waits exactly to the boundary`() {
        // 12:00:15.000 → 45s to the minute, plus the slack.
        assertThat(MinuteTicker.millisUntilNextMinute(15_000L))
            .isEqualTo(45_000L + MinuteTicker.SLACK_MS)
    }

    @Test
    fun `on the boundary waits a whole minute`() {
        assertThat(MinuteTicker.millisUntilNextMinute(120_000L))
            .isEqualTo(60_000L + MinuteTicker.SLACK_MS)
    }

    /** A timer that fired a hair early must not sleep again into the same minute. */
    @Test
    fun `just before the boundary the slack carries it across`() {
        val wait = MinuteTicker.millisUntilNextMinute(59_990L)

        assertThat(wait).isEqualTo(10L + MinuteTicker.SLACK_MS)
        assertThat(59_990L + wait).isGreaterThan(60_000L)
    }
}
