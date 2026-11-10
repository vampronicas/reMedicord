package com.remedicord.reminders

import android.content.Context
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.PendingReason
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.AmendPolicy
import com.remedicord.domain.policy.FlexiblePolicy
import com.remedicord.domain.policy.CoursePolicy
import com.remedicord.domain.policy.MissedPolicy
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.schedule.MedicationPlan
import com.remedicord.domain.schedule.ReminderText
import com.remedicord.domain.schedule.SlotGrouper
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.Replanner
import com.remedicord.domain.usecase.courseInfo
import com.remedicord.domain.usecase.toDomain
import com.remedicord.domain.usecase.zoneSettings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PLAN.md §5: turns schedules into dose rows and exact alarms. Runs after every change, on alarms,
 * on restart / time / time-zone change, and daily.
 */
class ReminderPlanner(
    private val context: Context,
    private val db: AppDatabase,
    private val meds: MedicationRepository,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
    private val directBoot: DirectBootStore,
    private val time: TimeProvider,
) : Replanner {
    private val mutex = Mutex()

    override suspend fun replan() = mutex.withLock { withContext(Dispatchers.IO) { doReplan() } }

    private suspend fun doReplan() {
        val dao = db.doseEventDao()
        val now = time.now()
        val nowMs = now.toEpochMilli()
        val until = now.plus(HORIZON)
        val lastPlanned = settings.get(ReminderSettingsRepository.PLANNED_UNTIL)?.toLongOrNull()?.let(Instant::ofEpochMilli)

        val allMeds = db.medicationDao().getAll()
        val mutedUntil = allMeds.associate { it.id to (it.remindersMutedUntil ?: 0L) }

        // 1. Reminders that never fired → UNCONFIRMED (asked, never assumed). Paused-for-today doses get their own reason.
        dao.withStatus(DoseStatus.PLANNED).forEach { d ->
            val at = d.remindAt ?: d.scheduledAt ?: return@forEach
            if (MissedPolicy.isUnconfirmed(d.status, Instant.ofEpochMilli(at), now)) {
                val reason = if (at < (mutedUntil[d.medicationId] ?: 0L)) PendingReason.PAUSED_TODAY else PendingReason.APP_NOT_RUNNING
                dao.update(d.copy(status = DoseStatus.UNCONFIRMED, pendingReason = reason, remindAt = null, updatedAt = nowMs))
            }
        }

        // 1b. Unanswered for more than 5 days → MISSED ("No answer within 5 days").
        dao.withStatus(DoseStatus.UNCONFIRMED).forEach { d ->
            val at = d.scheduledAt ?: d.updatedAt
            if (AmendPolicy.shouldAutoClose(d.status, Instant.ofEpochMilli(at), now)) {
                dao.update(d.copy(status = DoseStatus.MISSED, skipReason = AmendPolicy.AUTO_CLOSE_REASON, updatedAt = nowMs))
            }
        }

        for (stored in allMeds) {
            // E7: a pause with an end date resumes by itself.
            val med = if (stored.status == MedicationStatus.PAUSED && stored.pausedUntil != null && nowMs >= stored.pausedUntil) {
                stored.copy(status = MedicationStatus.ACTIVE, pausedUntil = null, pauseReason = null, pausedAt = null).also {
                    meds.update(it, "Pause ended: resumed automatically")
                    Notifications.postGeneral(context, RESUMED_NOTIFICATION_BASE + stored.id.toInt(), "Reminders resumed", "${stored.name}: the pause has ended.")
                }
            } else stored
            if (med.status != MedicationStatus.ACTIVE) { dao.deleteFutureAuto(med.id, nowMs); continue }
            val zone = med.zoneSettings().effectiveZone(time.zone())
            val regimens = db.regimenDao().forMedication(med.id).map { it.toDomain() }
            val rules = db.skipRuleDao().activeForMedication(med.id).map { r ->
                SkipPolicy.Rule(r.mode, r.count, r.untilEpochDay?.let(LocalDate::ofEpochDay), Instant.ofEpochMilli(r.startAt)) to r.reason
            }

            // 2. Gap fill: doses due while the app wasn't running.
            MedicationPlan.backfillWindow(lastPlanned, now)?.let { (from, to) ->
                dao.insertIfAbsent(MedicationPlan.plan(regimens, zone, rules, from, to).map {
                    entity(med.id, it, zone.id, nowMs, if (it.skipReason != null) DoseStatus.SKIPPED else DoseStatus.UNCONFIRMED)
                        .let { e -> if (e.status == DoseStatus.UNCONFIRMED) e.copy(pendingReason = PendingReason.APP_NOT_RUNNING) else e }
                })
            }

            // 3a. Flexible schedules: settle each recent day (enough taken → rest NOT_NEEDED; day over → only the shortfall).
            val flexRegimenIds = regimens.filter { it.rule is ScheduleRule.FlexibleDaily }.associate { it.id to it.rule as ScheduleRule.FlexibleDaily }
            if (flexRegimenIds.isNotEmpty()) settleFlexible(med.id, flexRegimenIds, zone, now)

            // 3. Due / snoozed doses become MISSED once the next dose of the same medicine is due (not for flexible times).
            val next = DoseGenerator.generate(regimens, zone, now.minus(Duration.ofDays(2)), now.plus(Duration.ofSeconds(1))).map { it.at }
            dao.forMedication(med.id).filter { (it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED) && it.regimenId !in flexRegimenIds }.forEach { d ->
                val laterDue = next.firstOrNull { it.toEpochMilli() > (d.scheduledAt ?: Long.MAX_VALUE) }
                if (MissedPolicy.shouldMarkMissed(0, Int.MAX_VALUE, now, laterDue)) {
                    dao.update(d.copy(status = DoseStatus.MISSED, remindAt = null, updatedAt = nowMs))
                    Notifications.cancel(context, (d.slotId ?: 0).toInt())
                }
            }

            // 4. Course finished, or every regimen (e.g. taper) has ended → COMPLETED.
            val required = CoursePolicy.requiredDoses(med.courseInfo(), regimens, zone)
            val statuses = dao.forMedication(med.id).map { it.status }.filter { it != DoseStatus.PLANNED && it != DoseStatus.DUE && it != DoseStatus.SNOOZED }
            val courseDone = required != null && CoursePolicy.progress(required, statuses).isComplete
            val allEnded = regimens.isNotEmpty() && regimens.all { it.effectiveTo != null && !it.effectiveTo.isAfter(now) }
            if (courseDone || allEnded) {
                meds.update(med.copy(status = MedicationStatus.COMPLETED, stoppedAt = nowMs), if (courseDone) "Course completed" else "Schedule ended")
                dao.deleteFutureAuto(med.id, nowMs)
                continue
            }

            // 5. Future doses for the next 48 h (skip rules applied). Existing rows are updated IN PLACE so dose
            //    ids stay stable (screens and notifications refer to them); only obsolete rows are removed.
            val desired = MedicationPlan.plan(regimens, zone, rules, now, until).associateBy { it.at.toEpochMilli() }
            dao.forMedication(med.id)
                .filter { (it.scheduledAt ?: -1) >= nowMs && (it.status == DoseStatus.PLANNED || (it.status == DoseStatus.SKIPPED && it.actionAt == null)) }
                .forEach { e ->
                    val want = desired[e.scheduledAt]
                    if (want == null) { dao.delete(e); return@forEach }
                    val status = if (want.skipReason != null) DoseStatus.SKIPPED else DoseStatus.PLANNED
                    if (e.status != status || e.regimenId != want.regimenId || e.skipReason != want.skipReason) {
                        dao.update(e.copy(status = status, regimenId = want.regimenId, skipReason = want.skipReason,
                            remindAt = if (status == DoseStatus.PLANNED) (e.remindAt ?: e.scheduledAt) else null, updatedAt = nowMs))
                    }
                }
            dao.insertIfAbsent(desired.values.map {
                entity(med.id, it, zone.id, nowMs, if (it.skipReason != null) DoseStatus.SKIPPED else DoseStatus.PLANNED)
            })
        }

        // 6. One exact alarm per reminder minute (none for medicines paused for today); cancel alarms no longer needed.
        val slotIds = dao.awaitingReminder(nowMs - 60_000, until.toEpochMilli())
            .filter { (it.remindAt ?: 0) >= (mutedUntil[it.medicationId] ?: 0L) }
            .mapNotNull { it.remindAt }.map { SlotGrouper.slotId(Instant.ofEpochMilli(it)) }.toSortedSet()
        val previous = settings.get(ReminderSettingsRepository.SCHEDULED_SLOTS).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        previous.filter { it !in slotIds }.forEach(scheduler::cancelSlot)
        slotIds.forEach { scheduler.scheduleSlot(it, maxOf(SlotGrouper.slotStart(it).toEpochMilli(), nowMs + 1_000)) }
        settings.put(ReminderSettingsRepository.SCHEDULED_SLOTS, slotIds.joinToString(","))
        settings.put(ReminderSettingsRepository.PLANNED_UNTIL, until.toEpochMilli().toString())
        directBoot.write(slotIds)

        // 7. Daily upkeep at ~03:00 local.
        val tomorrow3 = LocalDateTime.of(LocalDate.now(time.zone()).plusDays(1), LocalTime.of(3, 0))
        scheduler.scheduleMaintenance(DoseGenerator.resolve(tomorrow3, time.zone()).toEpochMilli())
    }

    /** Flexible days from 5 days ago to today: apply [FlexiblePolicy] and the minimum gap. */
    private suspend fun settleFlexible(medId: Long, flex: Map<Long, ScheduleRule.FlexibleDaily>, zone: java.time.ZoneId, now: Instant) {
        val dao = db.doseEventDao()
        val today = LocalDate.now(zone)
        val nowMs = now.toEpochMilli()
        val firstDay = today.minusDays(AmendPolicy.WINDOW_DAYS)
        dao.forMedication(medId).filter { it.regimenId in flex && it.scheduledAt != null }
            .groupBy { it.regimenId!! to Instant.ofEpochMilli(it.scheduledAt!!).atZone(zone).toLocalDate() }
            .filterKeys { (_, day) -> !day.isBefore(firstDay) && !day.isAfter(today) }
            .forEach { (key, rows) ->
                val rule = flex.getValue(key.first)
                val slots = rows.map { FlexiblePolicy.Slot(it.id, Instant.ofEpochMilli(it.scheduledAt!!), it.status) }
                FlexiblePolicy.reconcileDay(rule.dosesPerDay, slots, now, dayOver = key.second.isBefore(today)).forEach { (id, status) ->
                    val row = rows.first { it.id == id }
                    if (row.status != status) {
                        dao.update(row.copy(status = status, remindAt = if (status == DoseStatus.PLANNED) row.scheduledAt else null, updatedAt = nowMs))
                        row.slotId?.let { s -> if (dao.openInSlot(s).none { it.status == DoseStatus.DUE }) Notifications.cancel(context, s.toInt()) }
                    }
                }
                if (key.second == today && rule.minGapMinutes > 0) {
                    val lastTaken = rows.filter { it.status == DoseStatus.TAKEN }.mapNotNull { it.takenAt }.maxOrNull()?.let(Instant::ofEpochMilli)
                    rows.filter { it.status == DoseStatus.PLANNED }.forEach { r ->
                        val want = FlexiblePolicy.remindAt(Instant.ofEpochMilli(r.scheduledAt!!), lastTaken, rule.minGapMinutes).toEpochMilli()
                        if ((r.remindAt == null || want > r.remindAt) && want < DoseGenerator.resolve(today.plusDays(1).atStartOfDay(), zone).toEpochMilli()) {
                            dao.update(r.copy(remindAt = want, updatedAt = nowMs))
                        }
                    }
                }
            }
    }

    private fun entity(medId: Long, item: MedicationPlan.Item, zoneId: String, nowMs: Long, status: DoseStatus): DoseEventEntity {
        val at = item.at.toEpochMilli()
        return DoseEventEntity(
            medicationId = medId, regimenId = item.regimenId, slotId = SlotGrouper.slotId(item.at), scheduledAt = at, scheduledZone = zoneId,
            remindAt = if (status == DoseStatus.PLANNED) at else null, status = status, skipReason = item.skipReason,
            createdAt = nowMs, updatedAt = nowMs,
        )
    }

    companion object {
        val HORIZON: Duration = Duration.ofHours(48)
        private const val RESUMED_NOTIFICATION_BASE = 100_000
    }
}

/** Handles alarm firings and notification buttons (PLAN.md §4.6, §4.7, §5). */
class ReminderEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val planner: ReminderPlanner,
    private val actions: DoseActionService,
    private val reminderSettings: ReminderSettingsRepository,
    private val time: TimeProvider,
) {
    /** An alarm fired for reminder minute [alarmSlot]. */
    suspend fun onSlotFired(alarmSlot: Long) = withContext(Dispatchers.IO) {
        val s = reminderSettings.load()
        val dao = db.doseEventDao()
        val nowMs = time.nowMillis()
        val start = SlotGrouper.slotStart(alarmSlot).toEpochMilli()
        val firing = dao.awaitingReminder(start - 60_000, maxOf(start + 60_000, nowMs + 1_000))
            .filter { d -> (d.remindAt ?: 0) >= (db.medicationDao().get(d.medicationId)?.remindersMutedUntil ?: 0L) }
        val affectedSlots = HashSet<Long>()
        firing.groupBy { it.slotId ?: alarmSlot }.forEach { (origSlot, group) ->
            val items = group.map { SlotGrouper.SlotItem(it.id, typeOf(it.medicationId)) }
            val (announce, deferred) = SlotGrouper.splitForEyeDropGap(items)
            deferred.forEachIndexed { k, item ->
                group.first { it.id == item.doseId }.let { d ->
                    if (d.status == DoseStatus.PLANNED && (d.remindAt ?: 0) <= nowMs + 60_000) {
                        dao.update(d.copy(remindAt = nowMs + (k + 1L) * s.eyeDropGapMinutes * 60_000, updatedAt = nowMs))
                    }
                }
            }
            announce.map { a -> group.first { it.id == a.doseId } }.forEach { d ->
                if (d.status == DoseStatus.DUE) {
                    val repeats = d.repeatCount + 1
                    if (MissedPolicy.shouldMarkMissed(repeats, s.maxRepeats + 1, time.now(), null)) {
                        val closed = if (flexibleCanStillReach(d)) DoseStatus.NOT_NEEDED else DoseStatus.MISSED
                        dao.update(d.copy(status = closed, remindAt = null, repeatCount = repeats, updatedAt = nowMs))
                    } else dao.update(d.copy(repeatCount = repeats, remindAt = nowMs + s.repeatMinutes * 60_000L, updatedAt = nowMs))
                } else {
                    dao.update(d.copy(status = DoseStatus.DUE, remindAt = nowMs + s.repeatMinutes * 60_000L, updatedAt = nowMs))
                }
            }
            affectedSlots += origSlot
        }
        affectedSlots.forEach { refreshNotification(it) }
        planner.replan()
    }

    /** Re-posts (or removes) the notification for an original slot from the current dose states. */
    suspend fun refreshNotification(slotId: Long) = withContext(Dispatchers.IO) {
        val s = reminderSettings.load()
        val open = db.doseEventDao().openInSlot(slotId).filter { it.status == DoseStatus.DUE }
        if (open.isEmpty()) { Notifications.cancel(context, slotId.toInt()); return@withContext }
        val lines = open.mapNotNull { d ->
            val m = db.medicationDao().get(d.medicationId) ?: return@mapNotNull null
            val reg = d.regimenId?.let { db.regimenDao().get(it) }
            ReminderText.Line(m.type, m.name, reg?.doseAmount, reg?.doseUnit, m.side,
                if (m.type == MedicationType.INJECTION || m.type == MedicationType.PATCH) actions.suggestSite(m.id) else m.bodyLocation)
        }
        Notifications.postSlot(context, slotId, lines, s.snoozeMinutes, canSnooze = open.all { it.snoozeCount < s.maxSnoozes }, fullScreen = s.fullScreen)
    }

    /** Notification button pressed for an original slot. */
    suspend fun onAction(slotId: Long, action: String) = withContext(Dispatchers.IO) {
        val s = reminderSettings.load()
        db.doseEventDao().openInSlot(slotId).filter { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED }.forEach { d ->
            when (action) {
                DoseActionReceiver.ACTION_TAKEN -> actions.take(d.id, eyeDropGapMinutes = s.eyeDropGapMinutes)
                DoseActionReceiver.ACTION_LATER -> actions.later(d.id)
            }
        }
        refreshNotification(slotId)
    }

    /** Flexible schedule: can later times today still reach the daily number without this one? */
    private suspend fun flexibleCanStillReach(d: com.remedicord.data.db.entity.DoseEventEntity): Boolean {
        val reg = d.regimenId?.let { db.regimenDao().get(it) } ?: return false
        val rule = com.remedicord.domain.model.ScheduleRule.fromJson(reg.scheduleRuleJson) as? com.remedicord.domain.model.ScheduleRule.FlexibleDaily ?: return false
        val zone = time.zone()
        val day = Instant.ofEpochMilli(d.scheduledAt ?: return false).atZone(zone).toLocalDate()
        val slots = db.doseEventDao().forMedication(d.medicationId).filter { it.regimenId == reg.id && it.scheduledAt != null &&
            Instant.ofEpochMilli(it.scheduledAt).atZone(zone).toLocalDate() == day }
            .map { com.remedicord.domain.policy.FlexiblePolicy.Slot(it.id, Instant.ofEpochMilli(it.scheduledAt!!), it.status) }
        return com.remedicord.domain.policy.FlexiblePolicy.canStillReach(rule.dosesPerDay, slots, d.id, time.now())
    }

    private suspend fun typeOf(medId: Long): MedicationType = db.medicationDao().get(medId)?.type ?: MedicationType.PILL
}
