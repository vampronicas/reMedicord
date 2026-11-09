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

    /** History filter chips (multi-select). Empty selection = everything. */
    enum class StatusFilter(val statuses: Set<DoseStatus>) {
        TAKEN(setOf(DoseStatus.TAKEN)),
        MISSED(setOf(DoseStatus.MISSED)),
        NEEDS_ANSWER(setOf(DoseStatus.UNCONFIRMED)),
        SKIPPED(setOf(DoseStatus.SKIPPED)),
        UPCOMING(setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)),
    }

    fun matches(status: DoseStatus, filters: Set<StatusFilter>): Boolean =
        filters.isEmpty() || filters.any { status in it.statuses }

    data class MedSummary(val medicationId: Long, val taken: Int, val missed: Int, val needsAnswer: Int, val skipped: Int, val upcoming: Int) {
        val total get() = taken + missed + needsAnswer + skipped + upcoming
        val complete get() = total > 0 && taken + skipped == total
    }

    /** Day summary per medicine (calendar day tap). NOT_NEEDED flexible times are left out. */
    fun summarize(entries: List<Pair<Long, DoseStatus>>): List<MedSummary> =
        entries.filter { it.second != DoseStatus.NOT_NEEDED }.groupBy({ it.first }, { it.second }).map { (id, s) ->
            MedSummary(id, s.count { it == DoseStatus.TAKEN }, s.count { it == DoseStatus.MISSED }, s.count { it == DoseStatus.UNCONFIRMED },
                s.count { it == DoseStatus.SKIPPED }, s.count { it == DoseStatus.PLANNED || it == DoseStatus.DUE || it == DoseStatus.SNOOZED })
        }.sortedBy { it.medicationId }

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
