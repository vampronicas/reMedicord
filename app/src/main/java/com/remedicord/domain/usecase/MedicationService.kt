package com.remedicord.domain.usecase

import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.PolicyResult
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.model.TimeZoneMode
import com.remedicord.domain.model.ZoneSettings
import com.remedicord.domain.policy.CourseInfo
import com.remedicord.domain.policy.CoursePolicy
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.domain.policy.StopPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.schedule.TaperBuilder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun RegimenEntity.toDomain() = Regimen(
    id, Instant.ofEpochMilli(effectiveFrom), effectiveTo?.let(Instant::ofEpochMilli),
    ScheduleRule.fromJson(scheduleRuleJson), doseAmount, doseUnit,
)

fun MedicationEntity.courseInfo() = CourseInfo(mustComplete, courseMode, courseLength, courseStartEpochDay?.let(LocalDate::ofEpochDay))
fun MedicationEntity.zoneSettings() = ZoneSettings(timeZoneMode, homeZone?.let(ZoneId::of))

/** Course progress for display (PLAN.md §4.4). */
data class CourseView(
    val progress: CoursePolicy.Progress,
    val projectedEnd: Instant?,
)

/** All medication changes go through here so the policies are always applied (PLAN.md §4). */
class MedicationService(
    private val meds: MedicationRepository,
    private val skips: SkipRuleRepository,
    private val doses: DoseRepository,
    private val time: TimeProvider,
    private val replanner: Replanner,
) {
    suspend fun create(form: MedicationForm): ActionResult {
        val errors = FormValidator.validate(form)
        if (errors.isNotEmpty()) return ActionResult.Invalid(errors)
        val zone = time.zone()
        val now = time.nowMillis()
        val startInstant = maxOf(DoseGenerator.resolve(form.startDate.atStartOfDay(), zone).toEpochMilli(), now)
        val med = MedicationEntity(
            name = form.name.trim(), type = form.type, instructions = form.instructions.trim(),
            purpose = form.purpose!!, purposeNote = form.purposeNote.trim(),
            foodRelation = form.foodRelation, side = form.side,
            bodyLocation = form.bodyLocation.trim().ifEmpty { null }, dressingType = form.dressingType.trim().ifEmpty { null },
            enabledSites = form.sites.map(String::trim).ifEmpty { null },
            timeZoneMode = form.timeZoneMode, homeZone = if (form.timeZoneMode == TimeZoneMode.HOME) zone.id else null,
            mustComplete = form.mustComplete, courseMode = form.courseMode, courseLength = form.courseLength,
            courseStartEpochDay = form.startDate.toEpochDay(), createdAt = now, updatedAt = now,
        )
        if (form.taperSteps.isEmpty()) {
            meds.create(med, regimenEntity(0, startInstant, null, form.rule!!, form.amount, form.unit, "Initial"))
        } else {
            val planned = TaperBuilder.build(form.taperSteps, form.startDate, zone)
            val first = planned.first()
            val id = meds.create(med, regimenEntity(0, maxOf(first.effectiveFrom.toEpochMilli(), now), first.effectiveTo.toEpochMilli(), first.rule, first.amount, first.unit, "Taper step 1"))
            planned.drop(1).forEachIndexed { i, p ->
                meds.changeRegimen(regimenEntity(id, p.effectiveFrom.toEpochMilli(), p.effectiveTo.toEpochMilli(), p.rule, p.amount, p.unit, "Taper step ${i + 2}"))
            }
        }
        replanner.replan()
        return ActionResult.Done
    }

    suspend fun updateDetails(updated: MedicationEntity): ActionResult {
        val f = MedicationForm(name = updated.name, type = updated.type, instructions = updated.instructions, side = updated.side,
            purpose = updated.purpose, purposeNote = updated.purposeNote,
            bodyLocation = updated.bodyLocation.orEmpty(), dressingType = updated.dressingType.orEmpty(), sites = updated.enabledSites.orEmpty(),
            rule = ScheduleRule.AsNeeded(0, 1))
        val errors = FormValidator.validate(f)
        if (errors.isNotEmpty()) return ActionResult.Invalid(errors)
        meds.update(updated.copy(name = updated.name.trim(), purposeNote = updated.purposeNote.trim()), "Details edited")
        replanner.replan()
        return ActionResult.Done
    }

    suspend fun changeDose(medicationId: Long, amount: BigDecimal, unit: DoseUnit, rule: ScheduleRule, effectiveFrom: Instant, reason: String): ActionResult {
        val f = MedicationForm(name = "x", purpose = com.remedicord.domain.model.MedicationPurpose.OTHER, purposeNote = "x", amount = amount, unit = unit, rule = rule)
        val errors = FormValidator.validate(f) + if (reason.isBlank() || reason.length > 200) listOf("Enter a reason (max 200)") else emptyList()
        if (errors.isNotEmpty()) return ActionResult.Invalid(errors)
        val from = maxOf(effectiveFrom.toEpochMilli(), time.nowMillis())
        meds.changeRegimen(regimenEntity(medicationId, from, null, rule, amount, unit, reason.trim()))
        doses.deleteFuturePlanned(medicationId, from)
        replanner.replan()
        return ActionResult.Done
    }

    /** [mode] null = only the next dose. */
    suspend fun skip(medicationId: Long, mode: SkipMode?, count: Int?, until: LocalDate?, reason: String, note: String?, confirmed: Boolean): ActionResult {
        val med = meds.get(medicationId) ?: return ActionResult.Invalid(listOf("Medication not found"))
        if (!SkipPolicy.validateReason(reason)) return ActionResult.Invalid(listOf("Choose or enter a reason (max 200)"))
        if (note != null && (note.length > FormValidator.MAX_TEXT || !FormValidator.cleanText(note, true))) return ActionResult.Invalid(listOf("Note is too long"))
        when (val p = SkipPolicy.canSkip(med.courseInfo(), mode)) {
            is PolicyResult.Blocked -> return ActionResult.Blocked(p.message)
            is PolicyResult.NeedsConfirmation -> if (!confirmed) return ActionResult.NeedsConfirmation(p.message)
            PolicyResult.Allowed -> Unit
        }
        val m = mode ?: SkipMode.NEXT_N_DOSES
        val c = if (mode == null) 1 else count
        when (m) {
            SkipMode.NEXT_N_DOSES, SkipMode.DAYS -> if (c == null || c !in 1..365) return ActionResult.Invalid(listOf("Enter a number from 1 to 365"))
            SkipMode.UNTIL_DATE -> if (until == null || until.isBefore(LocalDate.now(time.zone()))) return ActionResult.Invalid(listOf("Choose a date from today on"))
        }
        skips.add(SkipRuleEntity(medicationId = medicationId, mode = m, count = c, untilEpochDay = until?.toEpochDay(),
            startAt = time.nowMillis(), reason = reason.trim(), note = note?.trim(), createdAt = 0))
        replanner.replan()
        return ActionResult.Done
    }

    suspend fun cancelSkip(ruleId: Long) { skips.cancel(ruleId, "Cancelled by user"); replanner.replan() }

    suspend fun pause(medicationId: Long): ActionResult {
        val med = meds.get(medicationId) ?: return ActionResult.Invalid(listOf("Medication not found"))
        (StopPolicy.canPause(med.courseInfo()) as? PolicyResult.Blocked)?.let { return ActionResult.Blocked(it.message) }
        meds.update(med.copy(status = MedicationStatus.PAUSED), "Paused")
        doses.deleteFuturePlanned(medicationId, time.nowMillis())
        replanner.replan()
        return ActionResult.Done
    }

    suspend fun resume(medicationId: Long): ActionResult {
        val med = meds.get(medicationId) ?: return ActionResult.Invalid(listOf("Medication not found"))
        meds.update(med.copy(status = MedicationStatus.ACTIVE), "Resumed")
        replanner.replan()
        return ActionResult.Done
    }

    suspend fun stop(medicationId: Long, reason: StopReason, note: String?, confirmed: Boolean): ActionResult {
        val med = meds.get(medicationId) ?: return ActionResult.Invalid(listOf("Medication not found"))
        if (note != null && (note.length > FormValidator.MAX_TEXT || !FormValidator.cleanText(note, true))) return ActionResult.Invalid(listOf("Note is too long"))
        val complete = courseView(med)?.progress?.isComplete ?: false
        val p = StopPolicy.canStop(med.courseInfo(), reason, complete)
        when (p) {
            is PolicyResult.Blocked -> return ActionResult.Blocked(p.message)
            is PolicyResult.NeedsConfirmation -> {
                if (!confirmed) return ActionResult.NeedsConfirmation(p.message)
                if (!StopPolicy.validateNote(p, note)) return ActionResult.Invalid(listOf("A note is required"))
            }
            PolicyResult.Allowed -> Unit
        }
        val now = time.nowMillis()
        meds.update(med.copy(status = MedicationStatus.STOPPED, stopReason = reason, stopNote = note?.trim(), stoppedAt = now), "Stopped: $reason")
        meds.activeRegimen(medicationId, now)?.let { meds.changeRegimenEnd(it, now) }
        doses.deleteFuturePlanned(medicationId, now)
        replanner.replan()
        return ActionResult.Done
    }

    /** Restart a stopped/completed medication with its last dose and schedule (new regimen from now). */
    suspend fun restart(medicationId: Long): ActionResult {
        val med = meds.get(medicationId) ?: return ActionResult.Invalid(listOf("Medication not found"))
        val last = meds.regimens(medicationId).lastOrNull() ?: return ActionResult.Invalid(listOf("No previous schedule"))
        val now = time.nowMillis()
        meds.update(med.copy(status = MedicationStatus.ACTIVE, stopReason = null, stopNote = null, stoppedAt = null,
            courseStartEpochDay = LocalDate.now(time.zone()).toEpochDay()), "Restarted")
        meds.changeRegimen(last.copy(id = 0, effectiveFrom = now, effectiveTo = null, changeReason = "Restarted"))
        replanner.replan()
        return ActionResult.Done
    }

    /** Course progress + projected end, or null when the medication has no course length. */
    suspend fun courseView(med: MedicationEntity): CourseView? {
        val zone = med.zoneSettings().effectiveZone(time.zone())
        val regimens = meds.regimens(med.id).map { it.toDomain() }
        val required = CoursePolicy.requiredDoses(med.courseInfo(), regimens, zone) ?: return null
        val progress = CoursePolicy.progress(required, doses.forMedication(med.id).map { it.status }
            .filter { it != DoseStatus.PLANNED && it != DoseStatus.DUE && it != DoseStatus.SNOOZED })
        val upcoming = CoursePolicy.upcomingForProjection(regimens, zone, time.now(), progress.remaining)
        return CourseView(progress, CoursePolicy.projectedEnd(progress.remaining, upcoming))
    }

    private fun regimenEntity(medId: Long, from: Long, to: Long?, rule: ScheduleRule, amount: BigDecimal, unit: DoseUnit, reason: String) =
        RegimenEntity(medicationId = medId, effectiveFrom = from, effectiveTo = to, doseAmount = amount, doseUnit = unit,
            scheduleRuleJson = ScheduleRule.toJson(rule), changeReason = reason, createdAt = 0)
}
