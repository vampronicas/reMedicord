package com.remedicord.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** How often a regimen is due (PLAN.md §4.3). Stored as JSON in regimen.scheduleRuleJson. */
@Serializable
sealed interface ScheduleRule {
    /** Times of day, optionally only on some weekdays (empty = every day). */
    @Serializable @SerialName("fixed")
    data class FixedTimes(
        val times: List<@Serializable(LocalTimeSerializer::class) LocalTime>,
        val daysOfWeek: Set<DayOfWeek> = emptySet(),
    ) : ScheduleRule {
        init {
            require(times.isNotEmpty()) { "At least one time is required" }
        }
    }

    /** Every [intervalHours] from [firstTime] on the regimen's start day, skipping quiet hours. */
    @Serializable @SerialName("every_n_hours")
    data class EveryNHours(
        val intervalHours: Int,
        @Serializable(LocalTimeSerializer::class) val firstTime: LocalTime,
        @Serializable(LocalTimeSerializer::class) val quietStart: LocalTime? = null,
        @Serializable(LocalTimeSerializer::class) val quietEnd: LocalTime? = null,
    ) : ScheduleRule {
        init {
            require(intervalHours in 1..72) { "Interval must be 1–72 hours" }
            require((quietStart == null) == (quietEnd == null)) { "Quiet hours need a start and an end" }
        }
    }

    /** Every [n] days from [startDate] (n = 7 → weekly). */
    @Serializable @SerialName("every_n_days")
    data class EveryNDays(
        val n: Int,
        val times: List<@Serializable(LocalTimeSerializer::class) LocalTime>,
        @Serializable(LocalDateSerializer::class) val startDate: LocalDate,
    ) : ScheduleRule {
        init {
            require(n in 1..366) { "n must be 1–366" }
            require(times.isNotEmpty()) { "At least one time is required" }
        }
    }

    /** [daysOn] days on, then [daysOff] days off, repeating from [startDate]. */
    @Serializable @SerialName("cyclic")
    data class Cyclic(
        val daysOn: Int,
        val daysOff: Int,
        val times: List<@Serializable(LocalTimeSerializer::class) LocalTime>,
        @Serializable(LocalDateSerializer::class) val startDate: LocalDate,
    ) : ScheduleRule {
        init {
            require(daysOn in 1..365 && daysOff in 1..365) { "Days on/off must be 1–365" }
            require(times.isNotEmpty()) { "At least one time is required" }
        }
    }

    /**
     * [dosesPerDay] doses a day at any of the chosen [periods] (e.g. 2 of morning / afternoon / evening).
     * A reminder is given at each period until enough doses are taken that day; the rest become NOT_NEEDED.
     */
    @Serializable @SerialName("flexible_daily")
    data class FlexibleDaily(
        val dosesPerDay: Int,
        val periods: List<Period>,
        /** Optional minimum gap between two doses (0 = none). */
        val minGapMinutes: Int = 0,
    ) : ScheduleRule {
        init {
            require(periods.isNotEmpty() && periods.size <= 6) { "Choose 1–6 times of day" }
            require(periods.map { it.time }.toSet().size == periods.size) { "Times of day must differ" }
            require(dosesPerDay in 1..periods.size) { "Doses per day must be 1–${periods.size}" }
            require(minGapMinutes in 0..(12 * 60)) { "Minimum gap must be 0–12 hours" }
        }
    }

    @Serializable
    data class Period(val name: String, @Serializable(LocalTimeSerializer::class) val time: LocalTime) {
        init {
            require(name.isNotBlank() && name.length <= 20 && name.none { it.isISOControl() }) { "Invalid period name" }
        }
    }

    /** As needed (PRN): no reminders, only limits. */
    @Serializable @SerialName("as_needed")
    data class AsNeeded(val minGapMinutes: Int, val maxPerDay: Int) : ScheduleRule {
        init {
            require(minGapMinutes in 0..(7 * 24 * 60)) { "Invalid minimum gap" }
            require(maxPerDay in 1..48) { "Invalid daily maximum" }
        }
    }

    companion object {
        private val json = Json { classDiscriminator = "kind" }
        fun toJson(rule: ScheduleRule): String = json.encodeToString(serializer(), rule)
        fun fromJson(text: String): ScheduleRule = json.decodeFromString(serializer(), text)
    }
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("com.remedicord.LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.withSecond(0).withNano(0).toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("com.remedicord.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
