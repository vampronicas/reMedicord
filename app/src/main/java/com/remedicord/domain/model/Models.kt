package com.remedicord.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** A regimen as the domain sees it (decoded rule, Instants). */
data class Regimen(
    val id: Long,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val rule: ScheduleRule,
    val amount: BigDecimal,
    val unit: DoseUnit,
) {
    fun isActiveAt(at: Instant) = !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo))
}

/** One generated dose occurrence. */
data class PlannedDose(val regimenId: Long, val at: Instant, val zone: ZoneId)

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
