package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.PatternSegment.Companion.pause
import com.faybish.vibealarm.domain.PatternSegment.Companion.vibrate

/**
 * Built-in patterns, seeded with reserved ids so app updates can refresh them
 * without ever colliding with user-created patterns (autoGenerate continues
 * from the largest id). Exact feel is tuned on-device.
 */
object PresetPatterns {

    const val GENTLE_ID = 1L
    const val HEARTBEAT_ID = 2L
    const val SOS_ID = 3L
    const val WAVES_ID = 4L
    const val ESCALATING_ID = 5L

    /** Fallback used when an alarm's pattern can't be loaded — never fail silently. */
    val DEFAULT_SEGMENTS: List<PatternSegment> = buildList {
        repeat(6) {
            add(vibrate(500, 200))
            add(pause(700))
        }
    }

    val all: List<VibrationPatternEntity> = listOf(
        entity(GENTLE_ID, "gentle") {
            repeat(8) {
                add(vibrate(400, 110))
                add(pause(900))
            }
        },
        entity(HEARTBEAT_ID, "heartbeat") {
            repeat(6) {
                add(vibrate(120, 200))
                add(pause(120))
                add(vibrate(180, 230))
                add(pause(800))
            }
        },
        entity(SOS_ID, "sos") {
            repeat(3) { add(vibrate(150, 255)); add(pause(150)) }
            add(pause(200))
            repeat(3) { add(vibrate(450, 255)); add(pause(150)) }
            add(pause(200))
            repeat(3) { add(vibrate(150, 255)); add(pause(150)) }
        },
        entity(WAVES_ID, "waves") {
            repeat(2) {
                add(vibrate(600, 80)); add(pause(200))
                add(vibrate(600, 140)); add(pause(200))
                add(vibrate(600, 200)); add(pause(200))
                add(vibrate(600, 255)); add(pause(600))
            }
        },
        entity(ESCALATING_ID, "escalating") {
            repeat(2) { add(vibrate(300, 90)); add(pause(700)) }
            repeat(2) { add(vibrate(500, 150)); add(pause(600)) }
            repeat(2) { add(vibrate(800, 210)); add(pause(500)) }
            repeat(2) { add(vibrate(1200, 255)); add(pause(400)) }
        },
    )

    /**
     * Preset display names are resolved from string resources by this key
     * ("pattern_preset_<name>") so they localize; the DB name is the fallback.
     */
    fun resourceKey(entity: VibrationPatternEntity): String? =
        if (entity.isPreset) "pattern_preset_${entity.name}" else null

    private fun entity(
        id: Long,
        name: String,
        build: MutableList<PatternSegment>.() -> Unit,
    ) = VibrationPatternEntity(
        id = id,
        name = name,
        isPreset = true,
        segmentsJson = SegmentsCodec.encode(buildList(build)),
    )
}
