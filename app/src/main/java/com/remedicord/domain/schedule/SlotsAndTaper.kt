package com.remedicord.domain.schedule

import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** PLAN.md §4.7: doses due in the same minute share one alarm + notification. */
object SlotGrouper {
    fun slotId(remindAt: Instant): Long = remindAt.epochSecond.floorDiv(60)
    fun slotStart(slotId: Long): Instant = Instant.ofEpochSecond(slotId * 60)

    data class SlotItem(val doseId: Long, val type: MedicationType)

    /**
     * Eye-drop sequencing: if a slot has ≥2 eye drops, only the first is announced now; the rest wait.
     * Returns (announce now, deferred eye drops in order).
     */
    fun splitForEyeDropGap(items: List<SlotItem>): Pair<List<SlotItem>, List<SlotItem>> {
        val eye = items.filter { it.type == MedicationType.EYE_DROP }
        if (eye.size < 2) return items to emptyList()
        val deferred = eye.drop(1)
        return items.filterNot { it in deferred } to deferred
    }

    /** The next eye drop is due [gapMinutes] after the previous one was actually taken. */
    fun nextEyeDropAt(previousTakenAt: Instant, gapMinutes: Int): Instant =
        previousTakenAt.plus(Duration.ofMinutes(gapMinutes.toLong()))
}

/** PLAN.md §4.2: a taper = consecutive regimens created up front. */
object TaperBuilder {
    data class Step(val rule: ScheduleRule, val amount: BigDecimal, val unit: DoseUnit, val days: Int)
    data class Planned(val effectiveFrom: Instant, val effectiveTo: Instant, val rule: ScheduleRule, val amount: BigDecimal, val unit: DoseUnit)

    fun build(steps: List<Step>, startDate: LocalDate, zone: ZoneId): List<Planned> {
        require(steps.isNotEmpty()) { "A taper needs at least one step" }
        require(steps.all { it.days in 1..365 }) { "Each step lasts 1–365 days" }
        var day = startDate
        return steps.map { s ->
            val end = day.plusDays(s.days.toLong())
            Planned(
                DoseGenerator.resolve(day.atStartOfDay(), zone),
                DoseGenerator.resolve(end.atStartOfDay(), zone),
                s.rule, s.amount, s.unit,
            ).also { day = end }
        }
    }
}
