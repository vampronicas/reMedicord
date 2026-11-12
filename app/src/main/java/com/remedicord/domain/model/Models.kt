package com.remedicord.domain.model

import java.time.Instant
import java.time.ZoneId

/** A regimen as the schedule sees it (decoded rule, Instants). The dose amount stays on the database row. */
data class Regimen(
    val id: Long,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val rule: ScheduleRule,
)

/** One generated dose occurrence. */
data class PlannedDose(val regimenId: Long, val at: Instant)

/** Where in time a medication's schedule lives (PLAN.md §4.9). */
data class ZoneSettings(val mode: TimeZoneMode, val homeZone: ZoneId?) {
    fun effectiveZone(current: ZoneId): ZoneId = if (mode == TimeZoneMode.HOME && homeZone != null) homeZone else current
}

/** Result of a policy check shown to the user. */
sealed interface PolicyResult {
    data object Allowed : PolicyResult
    /** Allowed only after explicit confirmation and a written note. */
    data class NeedsConfirmation(val message: String) : PolicyResult
    data class Blocked(val message: String) : PolicyResult
}
