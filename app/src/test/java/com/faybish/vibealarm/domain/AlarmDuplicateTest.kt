package com.faybish.vibealarm.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Naming a copy so the two rows can be told apart at 6am. */
class AlarmDuplicateTest {

    @Test
    fun `a labelled alarm gets the copy marker`() {
        assertThat(duplicateLabel("השכמת שבת", "(עותק)")).isEqualTo("השכמת שבת (עותק)")
    }

    /** "(copy)" alone says nothing; the time and the open card are what identify it. */
    @Test
    fun `an unlabelled alarm stays unlabelled`() {
        assertThat(duplicateLabel("", "(עותק)")).isEmpty()
        assertThat(duplicateLabel("   ", "(עותק)")).isEmpty()
    }

    /** Duplicating a copy must not build "(copy) (copy) (copy)". */
    @Test
    fun `the marker is never added twice`() {
        val once = duplicateLabel("שבת", "(עותק)")

        assertThat(duplicateLabel(once, "(עותק)")).isEqualTo(once)
    }

    @Test
    fun `trailing space does not produce a double space`() {
        assertThat(duplicateLabel("שבת ", "(עותק)")).isEqualTo("שבת (עותק)")
    }

    @Test
    fun `the marker is whatever the language calls it`() {
        assertThat(duplicateLabel("Wake up", "(copy)")).isEqualTo("Wake up (copy)")
    }
}
