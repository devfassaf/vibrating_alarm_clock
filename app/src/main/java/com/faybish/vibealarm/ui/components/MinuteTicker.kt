package com.faybish.vibealarm.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * "Now", refreshed on every minute boundary while the screen is actually visible.
 *
 * Every countdown on the list screen reads this one value, so they all flip together the
 * moment the minute turns — and a screen left open no longer claims "in 18 minutes" long
 * after that stopped being true, which is the frozen text this exists to fix. Tied to the
 * lifecycle: in the background the ticking stops, and coming back — including from recents,
 * where nothing else would recompose — re-enters the block and refreshes immediately.
 */
@Composable
fun rememberMinuteNow(): State<Instant> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = Instant.now()
                delay(MinuteTicker.millisUntilNextMinute(value.toEpochMilli()))
            }
        }
    }
}

object MinuteTicker {
    /**
     * Aligned to the wall clock rather than a fixed interval, so "in 18 minutes" becomes
     * "in 17 minutes" when the minute actually turns — a drifting 60s timer flips the text
     * at an arbitrary offset, up to a whole minute late. The slack keeps a timer that fires
     * a hair early from landing in the same minute and sleeping again for a full minute.
     */
    const val SLACK_MS = 100L

    fun millisUntilNextMinute(epochMilli: Long): Long =
        60_000L - (epochMilli % 60_000L) + SLACK_MS
}
