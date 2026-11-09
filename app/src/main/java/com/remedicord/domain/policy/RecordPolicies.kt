package com.remedicord.domain.policy

import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.PlannedDose
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Correcting past records, and the 5-day auto-close (user requirement, 2026-11-09). */
object AmendPolicy {
    const val WINDOW_DAYS = 5L
    const val AUTO_CLOSE_REASON = "No answer within 5 days"

    /** First calendar day that can still be changed: today and the previous 4 days. */
    fun firstEditableDay(today: LocalDate): LocalDate = today.minusDays(WINDOW_DAYS - 1)

    fun canAmend(day: LocalDate, today: LocalDate): Boolean = !day.isBefore(firstEditableDay(today)) && !day.isAfter(today)

    /** An unanswered dose is closed as MISSED once it is older than 5 days. */
    fun shouldAutoClose(status: DoseStatus, dueAt: Instant, now: Instant): Boolean =
        status == DoseStatus.UNCONFIRMED && dueAt.plus(Duration.ofDays(WINDOW_DAYS)).isBefore(now)
}

/**
 * Flexible schedules ("2 a day, any of morning / afternoon / evening"). Pure rules:
 * - once the day's doses are taken, the remaining times that day are NOT_NEEDED;
 * - an unanswered time only counts as MISSED if the remaining times can no longer reach the daily number;
 * - when the day is over, only the shortfall stays (missed / needs answer); extra times become NOT_NEEDED.
 */
object FlexiblePolicy {
    data class Slot(val id: Long, val at: Instant, val status: DoseStatus)

    private val OPEN = setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)

    /** New statuses (id → status) for one medicine's slots on one local day. */
    fun reconcileDay(dosesPerDay: Int, slots: List<Slot>, now: Instant, dayOver: Boolean): Map<Long, DoseStatus> {
        val sorted = slots.sortedBy { it.at }
        val taken = sorted.count { it.status == DoseStatus.TAKEN }
        val changes = HashMap<Long, DoseStatus>()
        if (taken >= dosesPerDay) {
            sorted.filter { it.status in OPEN || it.status == DoseStatus.UNCONFIRMED || it.status == DoseStatus.MISSED }
                .forEach { changes[it.id] = DoseStatus.NOT_NEEDED }
            return changes
        }
        if (!dayOver) {
            // Fewer than needed taken (e.g. a "Taken" was undone): times still ahead are needed again.
            sorted.filter { it.status == DoseStatus.NOT_NEEDED && it.at.isAfter(now) }.forEach { changes[it.id] = DoseStatus.PLANNED }
        }
        if (dayOver) {
            // Keep only as many "missing" slots as the shortfall; the earliest unresolved ones represent it.
            var need = dosesPerDay - taken - sorted.count { it.status == DoseStatus.SKIPPED }
            sorted.filter { it.status in OPEN || it.status == DoseStatus.UNCONFIRMED || it.status == DoseStatus.MISSED }.forEach { s ->
                if (need > 0) {
                    if (s.status in OPEN) changes[s.id] = DoseStatus.MISSED
                    need--
                } else changes[s.id] = DoseStatus.NOT_NEEDED
            }
        }
        return changes
    }

    /** During the day: may a time that got no answer be closed as NOT_NEEDED instead of MISSED? */
    fun canStillReach(dosesPerDay: Int, slots: List<Slot>, closingId: Long, now: Instant): Boolean {
        val taken = slots.count { it.status == DoseStatus.TAKEN }
        val later = slots.count { it.id != closingId && it.at.isAfter(now) && it.status in OPEN }
        return taken + later >= dosesPerDay
    }

    /** Earliest reminder for a time, respecting the minimum gap after the last dose taken. */
    fun remindAt(slotAt: Instant, lastTakenAt: Instant?, minGapMinutes: Int): Instant =
        if (lastTakenAt == null || minGapMinutes == 0) slotAt
        else maxOf(slotAt, lastTakenAt.plus(Duration.ofMinutes(minGapMinutes.toLong())))

    /**
     * Doses that actually count: for flexible regimens only the first [ScheduleRule.FlexibleDaily.dosesPerDay]
     * times of each local day (used for course length and projected end date).
     */
    fun countable(doses: List<PlannedDose>, regimens: List<Regimen>, zone: ZoneId): List<PlannedDose> {
        val flex = regimens.associate { it.id to (it.rule as? ScheduleRule.FlexibleDaily)?.dosesPerDay }
        return doses.groupBy { it.regimenId to it.at.atZone(zone).toLocalDate() }
            .flatMap { (key, list) -> flex[key.first]?.let { n -> list.sortedBy { it.at }.take(n) } ?: list }
            .sortedBy { it.at }
    }
}
