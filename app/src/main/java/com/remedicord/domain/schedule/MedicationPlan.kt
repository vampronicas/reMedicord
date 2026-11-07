package com.remedicord.domain.schedule

import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.policy.SkipPolicy
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Pure planning for one medication (PLAN.md §5). The Android planner only persists the result. */
object MedicationPlan {
    data class Item(val regimenId: Long, val at: Instant, val skipReason: String?)

    /** Doses in [from, to) with skip rules applied. Skip rules may start before [from] (NEXT_N counts from rule start). */
    fun plan(regimens: List<Regimen>, zone: ZoneId, skipRules: List<Pair<SkipPolicy.Rule, String>>, from: Instant, to: Instant): List<Item> {
        val skipped = HashMap<Instant, String>()
        skipRules.forEach { (rule, reason) ->
            val start = minOf(rule.startAt, from)
            val all = DoseGenerator.generate(regimens, zone, start, to).map { it.at }
            SkipPolicy.skipped(rule, all, zone).forEach { skipped.putIfAbsent(it, reason) }
        }
        return DoseGenerator.generate(regimens, zone, from, to).map { Item(it.regimenId, it.at, skipped[it.at]) }
    }

    /** Max look-back when filling a gap (phone off / restore), so a very old backup can't create thousands of rows. */
    val MAX_BACKFILL: Duration = Duration.ofDays(31)

    /**
     * Doses that fell due while the app wasn't running: (max(lastPlannedUntil, now − 31 days), now − grace).
     * They become UNCONFIRMED and go to the review prompt (PLAN.md §4.6).
     */
    fun backfillWindow(lastPlannedUntil: Instant?, now: Instant, grace: Duration = Duration.ofMinutes(2)): Pair<Instant, Instant>? {
        val from = maxOf(lastPlannedUntil ?: return null, now.minus(MAX_BACKFILL))
        val to = now.minus(grace)
        return if (from.isBefore(to)) from to to else null
    }
}

/** Notification text (PLAN.md §3): the lock-screen version never contains medicine data. */
object ReminderText {
    const val PUBLIC_TITLE = "Medication reminder"
    const val PUBLIC_TEXT = "Unlock to see details"

    data class Line(
        val type: MedicationType,
        val name: String,
        val amount: BigDecimal?,
        val unit: DoseUnit?,
        val side: BodySide?,
        val site: String?,
    )

    fun line(l: Line): String = buildString {
        append(verb(l.type)).append(' ').append(l.name)
        if (l.amount != null && l.unit != null) append(" · ").append(l.amount.stripTrailingZeros().toPlainString()).append(' ').append(unitText(l.unit))
        l.side?.let { append(" · ").append(it.name.lowercase().replaceFirstChar(Char::uppercase)) }
        l.site?.let { append(" · ").append(it) }
    }

    fun title(lines: List<Line>): String = if (lines.size == 1) "Time for ${lines[0].name}" else "Time for ${lines.size} medicines"

    fun verb(t: MedicationType) = when (t) {
        MedicationType.PILL, MedicationType.LIQUID -> "Take"; MedicationType.EYE_DROP, MedicationType.EAR_DROP -> "Instill"
        MedicationType.INJECTION -> "Inject"; MedicationType.INHALER -> "Inhale"; MedicationType.NASAL_SPRAY -> "Spray"
        MedicationType.CREAM, MedicationType.PATCH -> "Apply"; MedicationType.BANDAGE -> "Change dressing"
    }

    private fun unitText(u: DoseUnit) = when (u) {
        DoseUnit.ML -> "mL"; DoseUnit.MG -> "mg"; else -> u.name.lowercase()
    }
}
