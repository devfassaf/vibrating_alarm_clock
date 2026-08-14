package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SnoozeChoicesTest {

    @Test
    fun `the repeat presets are the chips the card shows`() {
        assertThat(SnoozeRepeats.PRESETS)
            .containsExactly(0, 1, 3, 5, SnoozeRepeats.UNTIL_DISMISSED)
            .inOrder()
        SnoozeRepeats.PRESETS.forEach { assertThat(SnoozeRepeats.isCustom(it)).isFalse() }
    }

    @Test
    fun `a typed number of repeats selects the custom chip`() {
        listOf(2, 4, 7, 12, 99).forEach { assertThat(SnoozeRepeats.isCustom(it)).isTrue() }
    }

    @Test
    fun `only a sane number of repeats is accepted`() {
        assertThat(SnoozeRepeats.parse("7")).isEqualTo(7)
        assertThat(SnoozeRepeats.parse(" 12 ")).isEqualTo(12)
        assertThat(SnoozeRepeats.parse("99")).isEqualTo(99)

        assertThat(SnoozeRepeats.parse("0")).isNull()
        assertThat(SnoozeRepeats.parse("100")).isNull()
        assertThat(SnoozeRepeats.parse("-1")).isNull()
        assertThat(SnoozeRepeats.parse("")).isNull()
        assertThat(SnoozeRepeats.parse("many")).isNull()
    }

    @Test
    fun `the interval presets are the chips the card shows`() {
        assertThat(SnoozeInterval.PRESETS).containsExactly(1, 3, 5, 10).inOrder()
        SnoozeInterval.PRESETS.forEach { assertThat(SnoozeInterval.isCustom(it)).isFalse() }
    }

    @Test
    fun `a typed interval selects the custom chip`() {
        listOf(2, 7, 20, 120).forEach { assertThat(SnoozeInterval.isCustom(it)).isTrue() }
    }

    @Test
    fun `only a sane interval is accepted`() {
        assertThat(SnoozeInterval.parse("7")).isEqualTo(7)
        assertThat(SnoozeInterval.parse("120")).isEqualTo(120)

        assertThat(SnoozeInterval.parse("0")).isNull()
        assertThat(SnoozeInterval.parse("121")).isNull()
        assertThat(SnoozeInterval.parse("abc")).isNull()
    }

    @Test
    fun `clamping keeps a stored value inside the range`() {
        assertThat(SnoozeRepeats.clamp(0)).isEqualTo(SnoozeRepeats.MIN)
        assertThat(SnoozeRepeats.clamp(1_000)).isEqualTo(SnoozeRepeats.MAX)
        assertThat(SnoozeInterval.clamp(0)).isEqualTo(SnoozeInterval.MIN)
        assertThat(SnoozeInterval.clamp(1_000)).isEqualTo(SnoozeInterval.MAX)
    }

    /** "Until dismissed" and "none" are answers, not counts, and must stay distinguishable. */
    @Test
    fun `until dismissed and none are never treated as typed counts`() {
        assertThat(SnoozeRepeats.isCustom(SnoozeRepeats.UNTIL_DISMISSED)).isFalse()
        assertThat(SnoozeRepeats.isCustom(SnoozeRepeats.NONE)).isFalse()
        assertThat(SnoozeRepeats.parse(SnoozeRepeats.UNTIL_DISMISSED.toString())).isNull()
    }
}
