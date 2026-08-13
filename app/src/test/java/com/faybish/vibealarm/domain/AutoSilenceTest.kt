package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoSilenceTest {

    @Test
    fun `the presets are the ones the chips offer`() {
        assertThat(AutoSilence.PRESET_SECONDS).containsExactly(60, 120, 300, 600).inOrder()
        AutoSilence.PRESET_SECONDS.forEach {
            assertThat(AutoSilence.isCustom(it)).isFalse()
        }
    }

    @Test
    fun `anything that is not a preset selects the custom chip`() {
        listOf(5, 45, 90, 1800).forEach {
            assertThat(AutoSilence.isCustom(it)).isTrue()
        }
    }

    /** A typo must not become an alarm that rings for a fifth of a second. */
    @Test
    fun `only usable numbers of seconds are accepted`() {
        assertThat(AutoSilence.parse("45")).isEqualTo(45)
        assertThat(AutoSilence.parse(" 90 ")).isEqualTo(90)
        assertThat(AutoSilence.parse("5")).isEqualTo(5)
        assertThat(AutoSilence.parse("1800")).isEqualTo(1800)

        assertThat(AutoSilence.parse("4")).isNull()
        assertThat(AutoSilence.parse("1801")).isNull()
        assertThat(AutoSilence.parse("0")).isNull()
        assertThat(AutoSilence.parse("-30")).isNull()
        assertThat(AutoSilence.parse("")).isNull()
        assertThat(AutoSilence.parse("abc")).isNull()
        assertThat(AutoSilence.parse("4.5")).isNull()
    }

    @Test
    fun `clamping keeps a stored value inside the range`() {
        assertThat(AutoSilence.clamp(0)).isEqualTo(AutoSilence.MIN_SECONDS)
        assertThat(AutoSilence.clamp(99_999)).isEqualTo(AutoSilence.MAX_SECONDS)
        assertThat(AutoSilence.clamp(45)).isEqualTo(45)
    }
}
