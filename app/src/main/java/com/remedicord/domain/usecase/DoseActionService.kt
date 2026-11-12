package com.remedicord.domain.usecase

import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.PendingReason
import com.remedicord.domain.policy.AmendPolicy
import com.remedicord.domain.policy.PrnPolicy
import com.remedicord.domain.policy.SiteRotation
import com.remedicord.domain.schedule.SlotGrouper
import java.time.Duration
import java.time.Instant

/** Taken / Snooze / Skip / late log / review / PRN (PLAN.md §4.6). Every change is audited by the repository. */
class DoseActionService(
    private val doses: DoseRepository,
    private val meds: MedicationRepository,
    private val time: TimeProvider,
    private val replanner: Replanner,
) {
    private val actionable = setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)

    /** Suggested site for injections / patches (least recently used). */
    suspend fun suggestSite(medicationId: Long): String? {
        val med = meds.get(medicationId) ?: return null
        if (med.type != MedicationType.INJECTION && med.type != MedicationType.PATCH) return med.side?.name
        val history = doses.forMedication(medicationId).filter { it.status == DoseStatus.TAKEN && it.site != null }
            .map { it.site!! to Instant.ofEpochMilli(it.takenAt ?: it.updatedAt) }
        return SiteRotation.suggestNext(med.enabledSites.orEmpty(), history)
    }

    suspend fun take(doseId: Long, at: Instant = time.now(), site: String? = null, note: String? = null, eyeDropGapMinutes: Int = 5): Boolean {
        val d = doses.get(doseId) ?: return false
        if (d.status !in actionable) return false
        val now = time.nowMillis()
        val chosenSite = site ?: suggestSite(d.medicationId)
        val amount = d.regimenId?.let { meds.regimens(d.medicationId).firstOrNull { r -> r.id == it }?.doseAmount }
        doses.update(d.copy(status = DoseStatus.TAKEN, actionAt = now, takenAt = at.toEpochMilli(), site = chosenSite, amount = amount,
            note = note ?: d.note, remindAt = null), "Taken")
        releaseNextEyeDrop(d, at, eyeDropGapMinutes)
        replanner.replan()
        return true
    }

    /** PLAN.md §4.7: the next waiting eye drop of the same slot is due [gap] after this one was actually taken. */
    private suspend fun releaseNextEyeDrop(taken: DoseEventEntity, takenAt: Instant, gap: Int) {
        val slot = taken.slotId ?: return
        val type = meds.get(taken.medicationId)?.type ?: return
        if (type != MedicationType.EYE_DROP) return
        val next = doses.inSlot(slot).filter { it.id != taken.id && it.status == DoseStatus.PLANNED && it.remindAt != null }
            .filter { meds.get(it.medicationId)?.type == MedicationType.EYE_DROP }
            .minByOrNull { it.remindAt!! } ?: return
        val at = SlotGrouper.nextEyeDropAt(takenAt, gap).toEpochMilli()
        doses.update(next.copy(remindAt = at), "Eye-drop gap")
    }

    suspend fun snooze(doseId: Long, minutes: Int, maxSnoozes: Int): Boolean {
        val d = doses.get(doseId) ?: return false
        if (d.status !in actionable || d.snoozeCount >= maxSnoozes || minutes !in 1..120) return false
        val until = time.now().plus(Duration.ofMinutes(minutes.toLong())).toEpochMilli()
        doses.update(d.copy(status = DoseStatus.SNOOZED, remindAt = until, snoozeCount = d.snoozeCount + 1, repeatCount = 0, actionAt = time.nowMillis()), "Snoozed $minutes min")
        replanner.replan()
        return true
    }

    suspend fun skip(doseId: Long, reason: String): Boolean {
        val d = doses.get(doseId) ?: return false
        if (d.status !in actionable || reason.isBlank() || reason.length > 200) return false
        doses.update(d.copy(status = DoseStatus.SKIPPED, skipReason = reason.trim(), actionAt = time.nowMillis(), remindAt = null), "Skipped")
        replanner.replan()
        return true
    }

    /** "Got it, I'll take it later": stop reminding; ask on the next app open (needs an answer within 5 days). */
    suspend fun later(doseId: Long): Boolean {
        val d = doses.get(doseId) ?: return false
        if (d.status !in actionable) return false
        doses.update(d.copy(status = DoseStatus.UNCONFIRMED, pendingReason = PendingReason.LATER, remindAt = null, actionAt = time.nowMillis()), "Later")
        replanner.replan()
        return true
    }

    /**
     * "Don't remind me again today" for a medicine: no more reminders until local midnight. The open dose(s) and the
     * rest of today's doses turn into "needs your answer" so they can be filled in later. Nothing is marked skipped.
     */
    suspend fun pauseToday(medicationId: Long): Boolean {
        val med = meds.get(medicationId) ?: return false
        val midnight = com.remedicord.domain.schedule.DoseGenerator.resolve(java.time.LocalDate.now(time.zone()).plusDays(1).atStartOfDay(), time.zone())
        meds.update(med.copy(remindersMutedUntil = midnight.toEpochMilli()), "Reminders paused for today")
        doses.forMedication(medicationId).filter { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED }.forEach {
            doses.update(it.copy(status = DoseStatus.UNCONFIRMED, pendingReason = PendingReason.PAUSED_TODAY, remindAt = null, actionAt = time.nowMillis()), "Paused for today")
        }
        replanner.replan()
        return true
    }

    /** Past records can be changed for 5 days (today + 4 previous days). */
    fun amendable(d: DoseEventEntity): Boolean {
        val at = d.scheduledAt ?: d.takenAt ?: return true
        return AmendPolicy.canAmend(Instant.ofEpochMilli(at).atZone(time.zone()).toLocalDate(), java.time.LocalDate.now(time.zone()))
    }

    enum class ReviewAnswer { TAKEN, MISSED, SKIPPED }

    /** Answer for an UNCONFIRMED dose from the review prompt (PLAN.md S14). */
    suspend fun review(doseId: Long, answer: ReviewAnswer, takenAt: Instant? = null, reason: String? = null): Boolean {
        val d = doses.get(doseId) ?: return false
        if (d.status != DoseStatus.UNCONFIRMED || !amendable(d)) return false
        val now = time.nowMillis()
        val updated = when (answer) {
            ReviewAnswer.TAKEN -> {
                val t = (takenAt ?: Instant.ofEpochMilli(d.scheduledAt ?: now)).let { if (it.isAfter(time.now())) time.now() else it }
                d.copy(status = DoseStatus.TAKEN, pendingReason = null, takenAt = t.toEpochMilli(), isLateLog = true, site = d.site ?: suggestSite(d.medicationId))
            }
            ReviewAnswer.MISSED -> d.copy(status = DoseStatus.MISSED, pendingReason = null)
            ReviewAnswer.SKIPPED -> d.copy(status = DoseStatus.SKIPPED, pendingReason = null, skipReason = reason?.trim()?.take(200)?.ifBlank { null } ?: "Not taken")
        }
        doses.update(updated.copy(actionAt = now, remindAt = null), "Reviewed: $answer")
        return true
    }

    suspend fun finishReview() = replanner.replan()

    /** PRN check before "Take now" (warning only). */
    suspend fun prnCheck(medicationId: Long): PrnPolicy.Result {
        val reg = meds.activeRegimen(medicationId, time.nowMillis()) ?: return PrnPolicy.Result.Ok
        val rule = ScheduleRule.fromJson(reg.scheduleRuleJson) as? ScheduleRule.AsNeeded ?: return PrnPolicy.Result.Ok
        val taken = doses.forMedication(medicationId).filter { it.status == DoseStatus.TAKEN }.mapNotNull { it.takenAt?.let(Instant::ofEpochMilli) }
        return PrnPolicy.check(taken, time.now(), rule.minGapMinutes, rule.maxPerDay, time.zone())
    }

    /** PRN "Take now": records a TAKEN dose without a schedule. */
    suspend fun takePrn(medicationId: Long, note: String? = null): Long {
        val now = time.nowMillis()
        val reg = meds.activeRegimen(medicationId, now)
        return doses.record(DoseEventEntity(medicationId = medicationId, regimenId = reg?.id, status = DoseStatus.TAKEN, actionAt = now, takenAt = now,
            amount = reg?.doseAmount, site = suggestSite(medicationId), note = note?.take(500), isPrn = true, scheduledZone = time.zone().id,
            createdAt = now, updatedAt = now), "As needed")
    }

    /** Edit a past dose from History (status / time / note), audited (PLAN.md R12). */
    suspend fun edit(
        doseId: Long, status: DoseStatus, takenAt: Instant?, note: String?,
        skipReason: String? = null, site: String? = null,
    ): Boolean {
        val d = doses.get(doseId) ?: return false
        if (status !in setOf(DoseStatus.TAKEN, DoseStatus.MISSED, DoseStatus.SKIPPED)) return false
        if (!amendable(d)) return false
        if (takenAt != null && takenAt.isAfter(time.now())) return false
        if (note != null && (note.length > FormValidator.MAX_TEXT || !FormValidator.cleanText(note, true))) return false
        if (skipReason != null && (skipReason.length > 200 || !FormValidator.cleanText(skipReason))) return false
        if (site != null && (site.length > FormValidator.MAX_SHORT || !FormValidator.cleanText(site))) return false
        doses.update(d.copy(status = status, takenAt = if (status == DoseStatus.TAKEN) (takenAt ?: Instant.ofEpochMilli(d.takenAt ?: d.scheduledAt ?: time.nowMillis())).toEpochMilli() else null,
            note = note?.ifBlank { null }, pendingReason = null, remindAt = null, actionAt = time.nowMillis(),
            isLateLog = d.isLateLog || (status == DoseStatus.TAKEN && (d.status == DoseStatus.MISSED || d.status == DoseStatus.UNCONFIRMED)),
            site = site?.ifBlank { null } ?: d.site,
            skipReason = if (status == DoseStatus.SKIPPED) skipReason?.ifBlank { null } ?: d.skipReason ?: "Edited" else null), "Record edited")
        replanner.replan()
        return true
    }

    /** v1.3: postpone the next [count] reminders (all medicines) by [hours]. Returns what moved (empty = nothing to move). */
    suspend fun postpone(count: Int, hours: Int): List<com.remedicord.domain.policy.PostponePolicy.Move> {
        if (count !in com.remedicord.domain.policy.PostponePolicy.COUNTS || hours !in com.remedicord.domain.policy.PostponePolicy.HOURS) return emptyList()
        val now = time.now()
        val upcoming = doses.awaiting(now.toEpochMilli(), now.plus(java.time.Duration.ofDays(3)).toEpochMilli())
            .filter { it.status == DoseStatus.PLANNED || it.status == DoseStatus.SNOOZED }
            .mapNotNull { d ->
                val s = d.scheduledAt ?: return@mapNotNull null
                com.remedicord.domain.policy.PostponePolicy.Dose(d.id, d.medicationId, Instant.ofEpochMilli(s), Instant.ofEpochMilli(d.remindAt ?: s))
            }
        val moves = com.remedicord.domain.policy.PostponePolicy.plan(upcoming, count, hours, now)
        moves.forEach { m -> doses.get(m.id)?.let { doses.update(it.copy(remindAt = m.to.toEpochMilli()), "Reminder postponed $hours h") } }
        if (moves.isNotEmpty()) replanner.replan()
        return moves
    }

    /**
     * Undo for the snackbar after Taken / Later / Skip (E8). Restores the dose exactly as it was before the action,
     * but only if nothing else changed it in between. Audited like any other change.
     */
    suspend fun revert(before: DoseEventEntity, afterActionAt: Long?): Boolean {
        val now = doses.get(before.id) ?: return false
        if (afterActionAt != null && now.actionAt != afterActionAt) return false
        doses.update(before, "Undo")
        replanner.replan()
        return true
    }
}
