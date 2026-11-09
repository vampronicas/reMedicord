package com.remedicord.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.PendingReason
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.AmendPolicy
import com.remedicord.domain.schedule.SlotGrouper
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 2026-11-09 requirements: Later, pause today, 5-day amend window + auto-close, flexible daily doses. */
@RunWith(AndroidJUnit4::class)
class NewRecordRulesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "rules_test_key", "rules_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var planner: ReminderPlanner
    private lateinit var service: MedicationService
    private lateinit var actions: DoseActionService
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        context.deleteDatabase("rules-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "rules-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        val meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        settings = SettingsRepository(db)
        planner = ReminderPlanner(context, db, meds, settings, AlarmScheduler(context), DirectBootStore(context), time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time, planner)
        actions = DoseActionService(doses, meds, time, planner)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("rules-test.db"); keys.deleteAllKeyMaterial() }

    private fun soon(min: Long) = LocalTime.now(ZoneId.systemDefault()).plusMinutes(min).withSecond(0).withNano(0)
    private suspend fun create(rule: ScheduleRule, name: String = "Med"): Long {
        service.create(MedicationForm(name = name, type = MedicationType.PILL, purpose = MedicationPurpose.SUPPLEMENT, purposeNote = "Vitamin", rule = rule))
        return db.medicationDao().getAll().first { it.name == name }.id
    }

    @Test fun laterTurnsADoseIntoNeedsAnswerAndStopsItsReminder() = runTest {
        val medId = create(ScheduleRule.FixedTimes(listOf(soon(30))))
        val dose = db.doseEventDao().forMedication(medId).first { it.status == DoseStatus.PLANNED }
        assertTrue(actions.later(dose.id))
        val after = db.doseEventDao().get(dose.id)!!
        assertEquals(DoseStatus.UNCONFIRMED, after.status)
        assertEquals(PendingReason.LATER, after.pendingReason)
        assertEquals(null, after.remindAt)
        assertTrue(actions.review(dose.id, DoseActionService.ReviewAnswer.TAKEN))
        assertEquals(DoseStatus.TAKEN, db.doseEventDao().get(dose.id)!!.status)
    }

    @Test fun pauseTodaySchedulesNoAlarmForThatMedicineToday() = runTest {
        val medId = create(ScheduleRule.FixedTimes(listOf(soon(20))), "Paused")
        val slot = SlotGrouper.slotId(Instant.ofEpochMilli(db.doseEventDao().forMedication(medId).first { it.status == DoseStatus.PLANNED }.remindAt!!))
        assertTrue(settings.get(ReminderSettingsRepository.SCHEDULED_SLOTS)!!.split(',').contains(slot.toString()))
        assertTrue(actions.pauseToday(medId))
        assertTrue(db.medicationDao().get(medId)!!.remindersMutedUntil!! > System.currentTimeMillis())
        assertFalse(settings.get(ReminderSettingsRepository.SCHEDULED_SLOTS).orEmpty().split(',').contains(slot.toString()))
        // Nothing is marked skipped.
        assertEquals(0, db.doseEventDao().forMedication(medId).count { it.status == DoseStatus.SKIPPED })
    }

    @Test fun unansweredForFiveDaysBecomesMissedAndOldRecordsAreReadOnly() = runTest {
        val medId = create(ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0))), "Old")
        val sixDaysAgo = Instant.now().minus(Duration.ofDays(6)).toEpochMilli()
        val twoDaysAgo = Instant.now().minus(Duration.ofDays(2)).toEpochMilli()
        val old = db.doseEventDao().insert(DoseEventEntity(medicationId = medId, scheduledAt = sixDaysAgo, status = DoseStatus.UNCONFIRMED,
            pendingReason = PendingReason.LATER, createdAt = sixDaysAgo, updatedAt = sixDaysAgo))
        val recent = db.doseEventDao().insert(DoseEventEntity(medicationId = medId, scheduledAt = twoDaysAgo, status = DoseStatus.UNCONFIRMED,
            pendingReason = PendingReason.LATER, createdAt = twoDaysAgo, updatedAt = twoDaysAgo))
        planner.replan()
        val closed = db.doseEventDao().get(old)!!
        assertEquals(DoseStatus.MISSED, closed.status)
        assertEquals(AmendPolicy.AUTO_CLOSE_REASON, closed.skipReason)
        assertEquals(DoseStatus.UNCONFIRMED, db.doseEventDao().get(recent)!!.status)
        // Older than 5 days: can't be changed any more. Within 5 days: can.
        assertFalse(actions.edit(old, DoseStatus.TAKEN, null, null))
        assertFalse(actions.logLate(old, Instant.ofEpochMilli(sixDaysAgo)))
        assertTrue(actions.review(recent, DoseActionService.ReviewAnswer.TAKEN))
    }

    @Test fun flexibleDayStopsRemindingOnceEnoughAreTaken() = runTest {
        val rule = ScheduleRule.FlexibleDaily(2, listOf(ScheduleRule.Period("A", soon(30)), ScheduleRule.Period("B", soon(60)), ScheduleRule.Period("C", soon(90))))
        val medId = create(rule, "Flex")
        val today = db.doseEventDao().forMedication(medId).filter { it.status == DoseStatus.PLANNED }.sortedBy { it.scheduledAt }.take(3)
        assertEquals(3, today.size)
        assertTrue(actions.take(today[0].id))
        assertTrue(actions.take(today[1].id))
        assertEquals(DoseStatus.NOT_NEEDED, db.doseEventDao().get(today[2].id)!!.status)
    }
}
