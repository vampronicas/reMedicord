package com.remedicord.domain.policy

import com.remedicord.domain.schedule.SlotGrouper
import java.time.Duration
import java.time.Instant

/**
 * v1.3: "Postpone the next N reminders (all medicines) by X hours". Only the reminder moves; the dose keeps its
 * original due time. A reminder is never pushed past the same medicine's next dose (it stops 1 minute before).
 */
object PostponePolicy {
    val COUNTS = 1..3
    val HOURS = 1..12

    data class Dose(val id: Long, val medicationId: Long, val scheduledAt: Instant, val remindAt: Instant)
    data class Move(val id: Long, val from: Instant, val to: Instant)

    fun plan(upcoming: List<Dose>, count: Int, hours: Int, now: Instant): List<Move> {
        require(count in COUNTS) { "Choose 1–3 reminders" }
        require(hours in HOURS) { "Choose 1–12 hours" }
        val future = upcoming.filter { it.remindAt.isAfter(now) }.sortedBy { it.remindAt }
        // "Reminders" = distinct reminder minutes (doses due together ring together).
        val slots = future.map { SlotGrouper.slotId(it.remindAt) }.distinct().take(count).toSet()
        val byMed = future.groupBy { it.medicationId }
        return future.filter { SlotGrouper.slotId(it.remindAt) in slots }.mapNotNull { d ->
            val wanted = d.remindAt.plus(Duration.ofHours(hours.toLong()))
            val nextDose = byMed.getValue(d.medicationId).map { it.scheduledAt }.filter { it.isAfter(d.scheduledAt) }.minOrNull()
            val to = nextDose?.let { minOf(wanted, it.minus(Duration.ofMinutes(1))) } ?: wanted
            if (to.isAfter(d.remindAt)) Move(d.id, d.remindAt, to) else null
        }
    }
}
