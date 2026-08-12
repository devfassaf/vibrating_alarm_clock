package com.faybish.vibealarm.data

import com.faybish.vibealarm.domain.PatternSegment
import com.faybish.vibealarm.domain.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/** JSON codec for a pattern's segment list (patterns.segmentsJson). */
object SegmentsCodec {
    private val serializer = ListSerializer(PatternSegment.serializer())

    fun encode(segments: List<PatternSegment>): String = json.encodeToString(serializer, segments)

    fun decode(encoded: String): List<PatternSegment> = try {
        json.decodeFromString(serializer, encoded)
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Maps between the alarms table's schedule columns and the domain [Schedule].
 * Weekly days are a bitmask (Monday = bit 0 ... Sunday = bit 6); per-day time
 * overrides are a JSON map of ISO day number to minutes-of-day; date lists are
 * JSON arrays of epoch days.
 */
object ScheduleCodec {
    private val overridesSerializer = MapSerializer(Int.serializer(), Int.serializer())
    private val datesSerializer = ListSerializer(Long.serializer())

    fun decode(entity: AlarmEntity): Schedule {
        val time = minutesToTime(entity.timeMinutesOfDay)
        return when (entity.scheduleType) {
            ScheduleType.WEEKLY -> Schedule.Weekly(
                days = daysFromBitmask(entity.daysBitmask),
                defaultTime = time,
                overrides = decodeOverrides(entity.perDayOverridesJson),
            )

            ScheduleType.DATES -> Schedule.Dates(
                dates = decodeDates(entity.datesJson),
                time = time,
            )

            else -> Schedule.OneTime(time)
        }
    }

    fun encode(schedule: Schedule, into: AlarmEntity): AlarmEntity = when (schedule) {
        is Schedule.OneTime -> into.copy(
            scheduleType = ScheduleType.ONE_TIME,
            timeMinutesOfDay = timeToMinutes(schedule.time),
            daysBitmask = 0,
            perDayOverridesJson = null,
            datesJson = null,
        )

        is Schedule.Weekly -> into.copy(
            scheduleType = ScheduleType.WEEKLY,
            timeMinutesOfDay = timeToMinutes(schedule.defaultTime),
            daysBitmask = daysToBitmask(schedule.days),
            perDayOverridesJson = schedule.overrides
                .takeIf { it.isNotEmpty() }
                ?.let { overrides ->
                    json.encodeToString(
                        overridesSerializer,
                        overrides.entries.associate { it.key.value to timeToMinutes(it.value) },
                    )
                },
            datesJson = null,
        )

        is Schedule.Dates -> into.copy(
            scheduleType = ScheduleType.DATES,
            timeMinutesOfDay = timeToMinutes(schedule.time),
            daysBitmask = 0,
            perDayOverridesJson = null,
            datesJson = json.encodeToString(datesSerializer, schedule.dates.map { it.toEpochDay() }),
        )
    }

    fun daysToBitmask(days: Set<DayOfWeek>): Int =
        days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    fun daysFromBitmask(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()

    fun timeToMinutes(time: LocalTime): Int = time.hour * 60 + time.minute

    fun minutesToTime(minutes: Int): LocalTime = LocalTime.of(minutes / 60 % 24, minutes % 60)

    private fun decodeOverrides(encoded: String?): Map<DayOfWeek, LocalTime> {
        if (encoded.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(overridesSerializer, encoded).entries.associate {
                DayOfWeek.of(it.key) to minutesToTime(it.value)
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun decodeDates(encoded: String?): List<LocalDate> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(datesSerializer, encoded).map(LocalDate::ofEpochDay)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
