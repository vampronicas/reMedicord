package com.remedicord.domain.policy

import com.remedicord.domain.model.DoseStatus
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** PLAN.md S9: calendar markers and adherence windows. */
object HistoryCalc {
    enum class Marker { NONE, ALL_TAKEN, HAS_MISSED, NEEDS_REVIEW, SKIPPED_ONLY, PARTIAL }

    /** Worst-first: missed > needs review > partial > skipped-only > all taken. Upcoming doses don't count. */
    fun marker(statuses: List<DoseStatus>): Marker {
        val done = statuses.filter { it == DoseStatus.TAKEN || it == DoseStatus.MISSED || it == DoseStatus.SKIPPED || it == DoseStatus.UNCONFIRMED }
        return when {
            done.isEmpty() -> Marker.NONE
            DoseStatus.MISSED in done -> Marker.HAS_MISSED
            DoseStatus.UNCONFIRMED in done -> Marker.NEEDS_REVIEW
            done.all { it == DoseStatus.TAKEN } -> Marker.ALL_TAKEN
            done.all { it == DoseStatus.SKIPPED } -> Marker.SKIPPED_ONLY
            else -> Marker.PARTIAL
        }
    }

    /** Calendar cells for a month, Monday first; nulls pad the first week. */
    fun monthGrid(month: YearMonth): List<LocalDate?> {
        val pad = month.atDay(1).dayOfWeek.value - 1
        return List(pad) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    /** Local calendar date a dose belongs to (due time, or taken time for as-needed doses). */
    fun dayOf(scheduledAt: Long?, takenAt: Long?, zone: ZoneId): LocalDate? =
        (scheduledAt ?: takenAt)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /** Adherence over the last [days] days (including today) from (date, status) pairs. */
    fun adherence(entries: List<Pair<LocalDate, DoseStatus>>, today: LocalDate, days: Int): Int? {
        val from = today.minusDays((days - 1).toLong())
        return Adherence.percent(entries.filter { !it.first.isBefore(from) && !it.first.isAfter(today) }.map { it.second })
    }
}
