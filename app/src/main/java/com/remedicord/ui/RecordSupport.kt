package com.remedicord.ui

import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.dao.AuditLogDao
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.DaySection
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.reminders.ReminderSettingsRepository
import com.remedicord.ui.components.DoseCardModel
import com.remedicord.ui.components.RecordEdit
import com.remedicord.ui.components.RecordSheetModel
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** A dose with what the screens need to show it. */
data class DoseItem(val dose: DoseEventEntity, val med: MedicationEntity, val regimen: RegimenEntity?)

/** Shared by Home and History: card text, record-sheet model, saving edits, and undo (E1, E5, E8). */
class RecordSupport @Inject constructor(
    private val actions: DoseActionService,
    private val doses: DoseRepository,
    private val auditDao: AuditLogDao,
    private val reminderSettings: ReminderSettingsRepository,
    private val time: TimeProvider,
) {
    val zone get() = time.zone()
    fun today(): LocalDate = LocalDate.now(zone)
    fun now(): Instant = time.now()

    fun dueAt(d: DoseEventEntity): Instant = Instant.ofEpochMilli(d.scheduledAt ?: d.takenAt ?: d.createdAt)
    fun dayOf(d: DoseEventEntity): LocalDate = dueAt(d).atZone(zone).toLocalDate()

    /** Which time-of-day section a dose belongs to (flexible doses use their own period name). */
    fun sectionOf(item: DoseItem): DaySection {
        val t = dueAt(item.dose).atZone(zone).toLocalTime()
        val flex = item.regimen?.let { runCatching { ScheduleRule.fromJson(it.scheduleRuleJson) }.getOrNull() } as? ScheduleRule.FlexibleDaily
        val period = flex?.periods?.firstOrNull { it.time == t.withSecond(0).withNano(0) }?.name
        return DaySection.of(t, period)
    }

    fun card(item: DoseItem): DoseCardModel {
        val d = item.dose
        val m = item.med
        val time = Labels.time(dueAt(d), zone)
        val dose = item.regimen?.let { Labels.dose(it.doseAmount, it.doseUnit) }
        val where = m.side?.let { Labels.side(it) + if (m.type == MedicationType.EYE_DROP) " eye" else if (m.type == MedicationType.EAR_DROP) " ear" else "" }
            ?: d.site ?: m.bodyLocation
        val status = when (d.status) {
            DoseStatus.TAKEN -> "Taken" + (d.takenAt?.let { " " + Labels.time(Instant.ofEpochMilli(it), zone) } ?: "")
            DoseStatus.UNCONFIRMED -> "Needs your answer · " + Labels.pending(d.pendingReason).replaceFirstChar { it.lowercase() }
            DoseStatus.SKIPPED -> "Skipped" + (d.skipReason?.let { " · $it" } ?: "")
            DoseStatus.MISSED -> "Missed" + (d.skipReason?.let { " · $it" } ?: "")
            DoseStatus.NOT_NEEDED -> "Not needed · today's doses already taken"
            DoseStatus.SNOOZED -> "Snoozed" + (d.remindAt?.let { " until " + Labels.time(Instant.ofEpochMilli(it), zone) } ?: "")
            DoseStatus.PLANNED -> if (d.remindAt != null && d.scheduledAt != null && d.remindAt > d.scheduledAt)
                "Upcoming · reminder postponed to " + Labels.time(Instant.ofEpochMilli(d.remindAt), zone) else "Upcoming"
            else -> Labels.status(d.status)
        }
        return DoseCardModel(m.type, m.colorTag, m.name, listOfNotNull(time, dose, where, if (d.isPrn) "as needed" else null).joinToString(" · "), d.status, status)
    }

    suspend fun sheet(item: DoseItem): RecordSheetModel {
        val d = item.dose
        val day = dayOf(d)
        val upcoming = d.status in setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)
        val editable = actions.amendable(d) && !(upcoming && day != today())
        val sites = if (item.med.type == MedicationType.INJECTION || item.med.type == MedicationType.PATCH) item.med.enabledSites.orEmpty() else emptyList()
        return RecordSheetModel(
            title = item.med.name,
            subtitle = listOfNotNull(item.regimen?.let { Labels.dose(it.doseAmount, it.doseUnit) }, "due " + Labels.time(dueAt(d), zone), Labels.date(dueAt(d), zone), "recorded afterwards".takeIf { d.isLateLog && d.status == DoseStatus.TAKEN }).joinToString(" · "),
            type = item.med.type, colorIndex = item.med.colorTag, current = d.status,
            takenTime = d.takenAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) },
            skipReason = d.skipReason, site = d.site ?: if (sites.isNotEmpty() && upcoming) actions.suggestSite(item.med.id) else null,
            siteChoices = sites, note = d.note.orEmpty(), editable = editable, upcoming = upcoming,
            changes = (auditDao.forEntity(AuditLogger.DOSE, d.id).size - 1).coerceAtLeast(0),
        )
    }

    /** Applies the record sheet. Returns null on success or a message. */
    suspend fun save(item: DoseItem, e: RecordEdit): String? {
        val d = item.dose
        val day = dayOf(d)
        val at = e.takenTime?.let { DoseGenerator.resolve(day.atTime(it), zone) }
        if (at != null && at.isAfter(now())) return "The time can't be in the future."
        val ok = if (d.status in setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)) {
            when (e.status) {
                DoseStatus.TAKEN -> actions.take(d.id, at ?: now(), e.site, e.note.ifBlank { null }, reminderSettings.load().eyeDropGapMinutes)
                DoseStatus.SKIPPED -> actions.skip(d.id, e.skipReason ?: return "Choose a reason.")
                else -> false
            }
        } else actions.edit(d.id, e.status, at, e.note, e.skipReason, e.site)
        return if (ok) null else "This record can't be changed (records older than 5 days are read-only)."
    }

    /** Result of a quick action, kept for Undo. */
    data class Undoable(val before: DoseEventEntity, val actionAt: Long?, val message: String)

    suspend fun quick(item: DoseItem, action: Quick): Undoable? {
        val before = doses.get(item.dose.id) ?: return null
        val s = reminderSettings.load()
        val ok = when (action) {
            Quick.TAKEN -> actions.take(before.id, now(), null, null, s.eyeDropGapMinutes)
            Quick.LATER -> actions.later(before.id)
            Quick.SNOOZE -> actions.snooze(before.id, s.snoozeMinutes, s.maxSnoozes)
        }
        if (!ok) return null
        val after = doses.get(before.id)
        return Undoable(before, after?.actionAt, when (action) {
            Quick.TAKEN -> "Marked as taken"; Quick.LATER -> "Okay, we'll ask you later"; Quick.SNOOZE -> "Snoozed ${s.snoozeMinutes} min"
        })
    }

    suspend fun undo(u: Undoable): Boolean = actions.revert(u.before, u.actionAt)

    /** v1.3: doses in a section that a group action can settle (open today, or waiting for an answer within 5 days). */
    fun groupable(items: List<DoseItem>, isToday: Boolean): List<DoseItem> = items.filter {
        (isToday && it.dose.status in setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)) ||
            (it.dose.status == DoseStatus.UNCONFIRMED && actions.amendable(it.dose))
    }

    /** "All taken" / "Skip all" for a time-of-day section. Returns one undo handle per changed dose. */
    suspend fun group(items: List<DoseItem>, taken: Boolean, reason: String?): List<Undoable> {
        val s = reminderSettings.load()
        return items.mapNotNull { item ->
            val before = doses.get(item.dose.id) ?: return@mapNotNull null
            val ok = when (before.status) {
                DoseStatus.UNCONFIRMED -> actions.review(before.id, if (taken) DoseActionService.ReviewAnswer.TAKEN else DoseActionService.ReviewAnswer.SKIPPED, null, reason)
                DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED ->
                    if (taken) actions.take(before.id, now(), null, null, s.eyeDropGapMinutes) else actions.skip(before.id, reason ?: "Skipped")
                else -> false
            }
            if (ok) Undoable(before, doses.get(before.id)?.actionAt, "") else null
        }.also { if (it.isNotEmpty()) actions.finishReview() }
    }

    suspend fun undoAll(list: List<Undoable>): Boolean = list.reversed().map { actions.revert(it.before, it.actionAt) }.all { it }

    suspend fun postpone(count: Int, hours: Int) = actions.postpone(count, hours)

    suspend fun pauseToday(medId: Long) = actions.pauseToday(medId)

    enum class Quick { TAKEN, LATER, SNOOZE }
}
