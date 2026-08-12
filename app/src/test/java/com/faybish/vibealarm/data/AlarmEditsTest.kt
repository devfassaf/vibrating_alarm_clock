package com.faybish.vibealarm.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The draft model rests on one promise: saving an open card changes exactly the fields the
 * user edited, and nothing else. Both directions are pinned here, because either one
 * failing silently loses a morning — a dropped edit, or a resurrected alarm.
 */
class AlarmEditsTest {

    private val stored = AlarmEntity(
        id = 4,
        label = "wake",
        timeMinutesOfDay = 7 * 60 + 30,
        enabled = true,
        patternId = 9,
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    /**
     * Every field the editor owns, with a change to it. The size assertion below ties this
     * list to [editedFields]: adding a field to one and not the other fails the test rather
     * than shipping a setting that silently cannot be saved.
     */
    private val edits: List<Pair<String, (AlarmEntity) -> AlarmEntity>> = listOf(
        "label" to { it.copy(label = "other") },
        "scheduleType" to { it.copy(scheduleType = ScheduleType.WEEKLY) },
        "timeMinutesOfDay" to { it.copy(timeMinutesOfDay = 5 * 60) },
        "daysBitmask" to { it.copy(daysBitmask = 0b101) },
        "perDayOverridesJson" to { it.copy(perDayOverridesJson = """{"1":300}""") },
        "datesJson" to { it.copy(datesJson = "[20000]") },
        "mode" to { it.copy(mode = RingMode.SOUND) },
        "ringtoneUri" to { it.copy(ringtoneUri = "content://ringtone/7") },
        "volume" to { it.copy(volume = 0.25f) },
        "vibrateWithSound" to { it.copy(vibrateWithSound = false) },
        "intensityScale" to { it.copy(intensityScale = 0.4f) },
        "turnScreenOn" to { it.copy(turnScreenOn = false) },
        "autoSilenceSeconds" to { it.copy(autoSilenceSeconds = 300) },
        "snoozeIntervalMinutes" to { it.copy(snoozeIntervalMinutes = 1) },
        "snoozeRepeatCount" to { it.copy(snoozeRepeatCount = -1) },
        "backgroundType" to { it.copy(backgroundType = BackgroundType.IMAGE) },
        "backgroundColorArgb" to { it.copy(backgroundColorArgb = 0xFF00FF00.toInt()) },
        "backgroundImagePath" to { it.copy(backgroundImagePath = "/data/user_de/0/bg.png") },
        "volumeKeysSnooze" to { it.copy(volumeKeysSnooze = true) },
    )

    @Test
    fun `the edited fields and the fields a save carries are the same list`() {
        assertThat(edits).hasSize(stored.editedFields().size)
    }

    @Test
    fun `every edited field is both noticed and carried over`() {
        edits.forEach { (name, edit) ->
            val draft = edit(stored)
            assertWithMessage("$name should register as an unsaved change")
                .that(stored.hasSameEditsAs(draft))
                .isFalse()
            assertWithMessage("$name should survive the save")
                .that(stored.withEditsFrom(draft))
                .isEqualTo(draft)
        }
    }

    @Test
    fun `an untouched draft is not an unsaved change`() {
        assertThat(stored.hasSameEditsAs(stored.copy())).isTrue()
        assertThat(stored.withEditsFrom(stored)).isEqualTo(stored)
    }

    /** The switch on the collapsed card writes straight through, and must stay written. */
    @Test
    fun `the switch is not part of the draft`() {
        val draftFromBefore = stored.copy(label = "edited")
        val switchedOff = stored.copy(enabled = false)

        assertThat(switchedOff.withEditsFrom(draftFromBefore).enabled).isFalse()
        assertThat(switchedOff.hasSameEditsAs(stored.copy(enabled = true))).isTrue()
    }

    /**
     * The exact failure this design exists to prevent: a one-time alarm rings while its
     * card is open, turns itself off — and saving the card brings it back to life for
     * another morning.
     */
    @Test
    fun `saving a draft cannot revive an alarm that finished while it was open`() {
        val openedWhileEnabled = stored.copy(label = "edited during the night")
        val afterItRang = stored.copy(enabled = false)

        val saved = afterItRang.withEditsFrom(openedWhileEnabled)

        assertThat(saved.enabled).isFalse()
        assertThat(saved.label).isEqualTo("edited during the night")
    }

    /** The pattern picker is its own screen and commits on its own. */
    @Test
    fun `a pattern chosen while the card was open is not overwritten`() {
        val openedBefore = stored.copy(intensityScale = 0.6f)
        val patternPicked = stored.copy(patternId = 42)

        val saved = patternPicked.withEditsFrom(openedBefore)

        assertThat(saved.patternId).isEqualTo(42)
        assertThat(saved.intensityScale).isEqualTo(0.6f)
    }

    @Test
    fun `identity and timestamps come from the stored row, never from the draft`() {
        val draft = stored.copy(id = 99, createdAt = 5, updatedAt = 6, label = "x")

        val saved = stored.withEditsFrom(draft)

        assertThat(saved.id).isEqualTo(4)
        assertThat(saved.createdAt).isEqualTo(1_000)
        assertThat(saved.updatedAt).isEqualTo(2_000)
        assertThat(saved.label).isEqualTo("x")
    }

    /** A timestamp bump alone must not light up "unsaved changes". */
    @Test
    fun `a row saved elsewhere is not an unsaved change`() {
        assertThat(stored.hasSameEditsAs(stored.copy(updatedAt = 9_999))).isTrue()
    }
}
