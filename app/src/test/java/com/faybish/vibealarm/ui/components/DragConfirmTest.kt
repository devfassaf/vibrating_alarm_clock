package com.faybish.vibealarm.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The threshold that separates "the user meant it" from "the phone was picked up".
 *
 * Pinned as a number rather than left as a feel, because the whole reason the ringing
 * screen drags instead of taps is that an accidental dismiss costs a morning.
 */
class DragConfirmTest {

    private val track = 1000f
    private val handle = 200f

    @Test
    fun `a short nudge is not a confirmation`() {
        val progress = DragConfirm.progress(offsetPx = 80f, trackPx = track, handlePx = handle)
        assertThat(progress).isEqualTo(0.1f)
        assertThat(DragConfirm.shouldTrigger(progress)).isFalse()
    }

    @Test
    fun `halfway is still not a confirmation`() {
        val progress = DragConfirm.progress(offsetPx = 400f, trackPx = track, handlePx = handle)
        assertThat(DragConfirm.shouldTrigger(progress)).isFalse()
    }

    @Test
    fun `most of the way across confirms`() {
        val progress = DragConfirm.progress(offsetPx = 480f, trackPx = track, handlePx = handle)
        assertThat(progress).isEqualTo(0.6f)
        assertThat(DragConfirm.shouldTrigger(progress)).isTrue()
    }

    @Test
    fun `the end of the track confirms`() {
        assertThat(DragConfirm.shouldTrigger(DragConfirm.progress(800f, track, handle))).isTrue()
    }

    @Test
    fun `dragging past the end does not wrap around`() {
        assertThat(DragConfirm.progress(5_000f, track, handle)).isEqualTo(1f)
        assertThat(DragConfirm.progress(-5_000f, track, handle)).isEqualTo(0f)
    }

    /** Before layout the sizes are zero; nothing may fire from a divide by zero. */
    @Test
    fun `an unmeasured track never confirms`() {
        assertThat(DragConfirm.travelPx(0f, 0f)).isEqualTo(0f)
        assertThat(DragConfirm.progress(0f, 0f, 0f)).isEqualTo(0f)
        assertThat(DragConfirm.shouldTrigger(DragConfirm.progress(999f, 0f, 0f))).isFalse()
    }

    /** A handle wider than its track would otherwise produce negative travel. */
    @Test
    fun `travel is never negative`() {
        assertThat(DragConfirm.travelPx(trackPx = 100f, handlePx = 300f)).isEqualTo(0f)
    }
}
