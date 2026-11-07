package com.remedicord.domain.schedule

import com.remedicord.domain.model.PlannedDose
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure function: which doses are due in a time window (PLAN.md §4.3, §4.9).
 *
 * - Wall-clock rules (fixed times, every N days, cyclic) are evaluated in [zone] (local or home zone).
 * - Daylight saving: a time that doesn't exist (spring forward) moves to the first valid minute;
 *   a time that occurs twice (fall back) is produced once (earlier offset).
 * - Each dose belongs to the regimen in force at that instant (dose changes / tapering).
 */
object DoseGenerator {

    /** Doses with [fromInclusive] <= at < [toExclusive], sorted by time. */
    fun generate(regimens: List<Regimen>, zone: ZoneId, fromInclusive: Instant, toExclusive: Instant): List<PlannedDose> {
        if (!fromInclusive.isBefore(toExclusive)) return emptyList()
        return regimens.flatMap { reg ->
            val from = maxOf(fromInclusive, reg.effectiveFrom)
            val to = reg.effectiveTo?.let { minOf(toExclusive, it) } ?: toExclusive
            if (!from.isBefore(to)) emptyList()
            else instantsFor(reg, zone, from, to).map { PlannedDose(reg.id, it, zone) }
        }.distinctBy { it.at }.sortedBy { it.at }
    }

    private fun instantsFor(reg: Regimen, zone: ZoneId, from: Instant, to: Instant): List<Instant> =
        when (val rule = reg.rule) {
            is ScheduleRule.AsNeeded -> emptyList()
            is ScheduleRule.EveryNHours -> everyNHours(rule, reg.effectiveFrom, zone, from, to)
            is ScheduleRule.FixedTimes -> daily(zone, from, to) { date ->
                if (rule.daysOfWeek.isEmpty() || date.dayOfWeek in rule.daysOfWeek) rule.times else emptyList()
            }
            is ScheduleRule.EveryNDays -> daily(zone, from, to) { date ->
                val d = ChronoUnit.DAYS.between(rule.startDate, date)
                if (d >= 0 && d % rule.n == 0L) rule.times else emptyList()
            }
            is ScheduleRule.Cyclic -> daily(zone, from, to) { date ->
                val d = ChronoUnit.DAYS.between(rule.startDate, date)
                if (d >= 0 && d % (rule.daysOn + rule.daysOff) < rule.daysOn) rule.times else emptyList()
            }
        }

    private inline fun daily(zone: ZoneId, from: Instant, to: Instant, timesOn: (LocalDate) -> List<LocalTime>): List<Instant> {
        val out = ArrayList<Instant>()
        var date = from.atZone(zone).toLocalDate().minusDays(1)
        val last = to.atZone(zone).toLocalDate().plusDays(1)
        while (!date.isAfter(last)) {
            for (t in timesOn(date).distinct()) {
                val at = resolve(LocalDateTime.of(date, t.withSecond(0).withNano(0)), zone)
                if (!at.isBefore(from) && at.isBefore(to)) out += at
            }
            date = date.plusDays(1)
        }
        return out
    }

    /** Fixed elapsed intervals anchored at [firstTime] on the regimen's start day. */
    private fun everyNHours(rule: ScheduleRule.EveryNHours, regimenStart: Instant, zone: ZoneId, from: Instant, to: Instant): List<Instant> {
        val startDate = regimenStart.atZone(zone).toLocalDate()
        var anchor = resolve(LocalDateTime.of(startDate, rule.firstTime), zone)
        val step = Duration.ofHours(rule.intervalHours.toLong())
        if (anchor.isBefore(regimenStart)) {
            // First occurrence on/after the regimen start.
            val k = (Duration.between(anchor, regimenStart).toMillis() + step.toMillis() - 1) / step.toMillis()
            anchor = anchor.plus(step.multipliedBy(k))
        }
        val out = ArrayList<Instant>()
        val startK = maxOf(0L, Duration.between(anchor, from).toMillis().floorDiv(step.toMillis()))
        var at = anchor.plus(step.multipliedBy(startK))
        while (at.isBefore(to)) {
            if (!at.isBefore(from) && !inQuietHours(at.atZone(zone).toLocalTime(), rule)) out += at
            at = at.plus(step)
        }
        return out
    }

    private fun inQuietHours(t: LocalTime, rule: ScheduleRule.EveryNHours): Boolean {
        val s = rule.quietStart ?: return false
        val e = rule.quietEnd ?: return false
        return if (s.isBefore(e)) !t.isBefore(s) && t.isBefore(e) else !t.isBefore(s) || t.isBefore(e)
    }

    /** Local date-time → instant, with the DST rules above. */
    fun resolve(local: LocalDateTime, zone: ZoneId): Instant {
        val rules = zone.rules
        if (rules.getValidOffsets(local).isEmpty()) {
            return rules.getTransition(local).instant // gap: first valid minute after the jump
        }
        return ZonedDateTime.ofLocal(local, zone, null).toInstant() // overlap: earlier offset
    }
}
