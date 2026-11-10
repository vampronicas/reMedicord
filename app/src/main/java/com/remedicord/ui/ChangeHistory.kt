package com.remedicord.ui

import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.StopReason
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json

/**
 * v1.4: a readable change history for one medicine, built from the audit trail (nothing new is stored).
 * Covers dose / quantity / schedule changes, detail edits, pause / resume / stop / restart and replacement.
 */
object ChangeHistory {
    data class Entry(val at: Instant, val title: String, val detail: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    private fun med(s: String?) = s?.let { runCatching { json.decodeFromString(MedicationEntity.serializer(), it) }.getOrNull() }
    private fun reg(s: String?) = s?.let { runCatching { json.decodeFromString(RegimenEntity.serializer(), it) }.getOrNull() }
    private fun rule(r: RegimenEntity) = runCatching { Labels.rule(ScheduleRule.fromJson(r.scheduleRuleJson)) }.getOrDefault("schedule")

    /** Newest first. [medAudit] = entries for the medicine, [regimenAudit] = entries for its regimens. */
    fun build(medAudit: List<AuditLogEntity>, regimenAudit: List<AuditLogEntity>, zone: ZoneId): List<Entry> {
        val out = ArrayList<Entry>()
        medAudit.filter { it.entityType == AuditLogger.MEDICATION }.forEach { a ->
            val at = Instant.ofEpochMilli(a.at)
            when (a.action) {
                AuditAction.CREATE -> med(a.afterJson)?.let { m ->
                    out += Entry(at, when {
                        m.replacesId == null -> "Medicine added"
                        m.replaceKind == com.remedicord.domain.model.ReplaceKind.UPGRADE -> "Added as an upgrade"
                        m.replaceKind == com.remedicord.domain.model.ReplaceKind.DOWNGRADE -> "Added as a downgrade"
                        else -> "Added as a replacement"
                    }, "${m.name} · ${Labels.type(m.type)}")
                }
                AuditAction.UPDATE -> medicationChange(med(a.beforeJson), med(a.afterJson), a.note, zone)?.let { (t, d) -> out += Entry(at, t, d) }
                AuditAction.DELETE -> out += Entry(at, "Medicine deleted", a.note)
            }
        }
        // Regimens: each new regimen after the first is a dose / schedule change, compared with the one before it.
        val created = regimenAudit.filter { it.entityType == AuditLogger.REGIMEN && it.action == AuditAction.CREATE }
            .mapNotNull { a -> reg(a.afterJson)?.let { a to it } }.sortedWith(compareBy({ it.second.effectiveFrom }, { it.first.at }))
        created.forEachIndexed { i, (a, r) ->
            val at = Instant.ofEpochMilli(a.at)
            val prev = created.getOrNull(i - 1)?.second
            val dose = Labels.dose(r.doseAmount, r.doseUnit)
            val reason = r.changeReason.takeIf { it.isNotBlank() && !it.startsWith("Initial") }
            if (prev == null) {
                out += Entry(at, "Dose set: $dose", rule(r))
            } else {
                val parts = buildList {
                    if (prev.doseAmount.compareTo(r.doseAmount) != 0 || prev.doseUnit != r.doseUnit) add("Dose changed: ${Labels.dose(prev.doseAmount, prev.doseUnit)} → $dose")
                    if (prev.scheduleRuleJson != r.scheduleRuleJson) add("Schedule changed: ${rule(prev)} → ${rule(r)}")
                }
                val title = parts.firstOrNull() ?: if (r.changeReason == "Restarted") "Restarted with $dose" else "Dose confirmed: $dose"
                val detail = (parts.drop(1) + listOfNotNull(reason, if (r.effectiveFrom > a.at) "from ${Labels.date(Instant.ofEpochMilli(r.effectiveFrom), zone)}" else null))
                    .joinToString(" · ").ifBlank { null }
                out += Entry(at, title, detail)
            }
        }
        return out.sortedByDescending { it.at }
    }

    private fun medicationChange(b: MedicationEntity?, a: MedicationEntity?, note: String?, zone: ZoneId): Pair<String, String?>? {
        if (b == null || a == null) return null
        if (b.status != a.status) {
            return when (a.status) {
                MedicationStatus.PAUSED -> "Paused" to listOfNotNull(
                    a.pausedUntil?.let { "until ${Labels.date(Instant.ofEpochMilli(it).minusSeconds(1), zone)}" } ?: "until resumed", a.pauseReason,
                ).joinToString(" · ")
                MedicationStatus.ACTIVE -> when (b.status) {
                    MedicationStatus.PAUSED -> (if (note?.contains("automatically") == true) "Resumed automatically" else "Resumed") to null
                    else -> "Restarted" to null
                }
                MedicationStatus.STOPPED -> if (a.stopReason == StopReason.REPLACED) (a.stopNote?.substringBefore(" to")?.substringBefore(" by")?.takeIf { it.isNotBlank() } ?: "Replaced") to a.stopNote
                    else "Stopped" to listOfNotNull(a.stopReason?.let(Labels::stopReason), a.stopNote).joinToString(" · ").ifBlank { null }
                MedicationStatus.COMPLETED -> (note ?: "Course completed") to null
            }
        }
        val changes = buildList {
            if (b.name != a.name) add("Name: ${b.name} → ${a.name}")
            if (b.instructions != a.instructions) add("Instructions updated")
            if (b.purpose != a.purpose || b.purposeNote != a.purposeNote) add("Reason: ${Labels.purpose(a.purpose)}" + (a.purposeNote.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            if (b.side != a.side) add("Side: ${b.side?.let(Labels::side) ?: "—"} → ${a.side?.let(Labels::side) ?: "—"}")
            if (b.enabledSites != a.enabledSites) add("Sites: ${a.enabledSites?.joinToString(", ") ?: "none"}")
            if (b.bodyLocation != a.bodyLocation) add("Location: ${a.bodyLocation ?: "—"}")
            if (b.dressingType != a.dressingType) add("Dressing: ${a.dressingType ?: "—"}")
            if (b.colorTag != a.colorTag) add("Colour changed")
            if (b.foodRelation != a.foodRelation) add("Food: ${Labels.food(a.foodRelation)}")
            if (b.replacedById == null && a.replacedById != null) add("Replaced")
            if ((a.remindersMutedUntil ?: 0) > (b.remindersMutedUntil ?: 0)) add("Reminders off for the rest of the day")
        }
        if (changes.isEmpty()) return null
        return changes.first() to changes.drop(1).joinToString(" · ").ifBlank { null }
    }
}
