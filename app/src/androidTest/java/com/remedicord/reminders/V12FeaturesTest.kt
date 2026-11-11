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
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.usecase.ActionResult
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** v1.2.0: pause until a date / until I resume (E7), delete (E4), undo + full record edit (E5, E8). */
@RunWith(AndroidJUnit4::class)
class V12FeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "v12_test_key", "v12_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var planner: ReminderPlanner
    private lateinit var service: MedicationService
    private lateinit var actions: DoseActionService

    @Before fun setUp() {
        context.deleteDatabase("v12-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "v12-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        val meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        planner = ReminderPlanner(context, db, meds, SettingsRepository(db), AlarmScheduler(context), DirectBootStore(context), time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time, planner)
        actions = DoseActionService(doses, meds, time, planner)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("v12-test.db"); keys.deleteAllKeyMaterial() }

    private fun soon(min: Long) = LocalTime.now(ZoneId.systemDefault()).plusMinutes(min).withSecond(0).withNano(0)
    private suspend fun create(name: String, must: Boolean = false, rule: ScheduleRule = ScheduleRule.FixedTimes(listOf(soon(30)))): Long {
        service.create(MedicationForm(name = name, type = MedicationType.PILL, purpose = MedicationPurpose.SUPPLEMENT, purposeNote = "test", rule = rule,
            mustComplete = must, courseMode = if (must) CourseMode.DAYS else CourseMode.NONE, courseLength = if (must) 7 else null, colorIndex = 3))
        return db.medicationDao().getAll().first { it.name == name }.id
    }

    @Test fun colourIsSaved() = runTest {
        assertEquals(3, db.medicationDao().get(create("Coloured"))!!.colorTag)
    }

    @Test fun pauseUntilADateRefusesPastDaysAndResumesAutomatically() = runTest {
        val id = create("Vitamin D")
        assertTrue(service.pause(id, LocalDate.now().minusDays(1), null) is ActionResult.Invalid)
        assertEquals(ActionResult.Done, service.pause(id, LocalDate.now(), "Travelling"))
        val paused = db.medicationDao().get(id)!!
        assertEquals(MedicationStatus.PAUSED, paused.status)
        assertEquals("Travelling", paused.pauseReason)
        assertTrue(db.doseEventDao().forMedication(id).none { it.status == DoseStatus.PLANNED })
        // Simulate the end date having passed.
        db.medicationDao().update(paused.copy(pausedUntil = Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli()))
        planner.replan()
        val resumed = db.medicationDao().get(id)!!
        assertEquals(MedicationStatus.ACTIVE, resumed.status)
        assertNull(resumed.pausedUntil)
        assertTrue(db.doseEventDao().forMedication(id).any { it.status == DoseStatus.PLANNED })
    }

    @Test fun pauseUntilIResumeStaysPaused() = runTest {
        val id = create("Magnesium")
        assertEquals(ActionResult.Done, service.pause(id, null, null))
        planner.replan()
        assertEquals(MedicationStatus.PAUSED, db.medicationDao().get(id)!!.status)
        assertNull(db.medicationDao().get(id)!!.pausedUntil)
        assertEquals(ActionResult.Done, service.resume(id))
        assertEquals(MedicationStatus.ACTIVE, db.medicationDao().get(id)!!.status)
    }

    @Test fun deleteRemovesEverythingAndStartedMustCompleteNeedsTheWarning() = runTest {
        val id = create("Amoxicillin", must = true)
        val dose = db.doseEventDao().forMedication(id).first { it.status == DoseStatus.PLANNED }
        assertTrue(actions.take(dose.id))
        val info = service.deleteInfo(id)!!
        assertTrue(info.startedMustComplete)
        assertTrue(service.delete(id, "Delete", warningAcknowledged = false) is ActionResult.NeedsConfirmation)
        assertTrue(service.delete(id, "Amoxicillin", warningAcknowledged = true) is ActionResult.Invalid) // the name is no longer the word
        assertTrue(service.delete(id, "Delet", warningAcknowledged = true) is ActionResult.Invalid)
        assertEquals(ActionResult.Done, service.delete(id, "delete", warningAcknowledged = true))
        assertNull(db.medicationDao().get(id))
        assertEquals(0, db.doseEventDao().forMedication(id).size)
        assertEquals(0, db.regimenDao().forMedication(id).size)
        val entry = db.auditLogDao().forEntity(AuditLogger.MEDICATION, id).last()
        assertEquals(AuditAction.DELETE, entry.action)
        assertTrue(entry.note!!.contains("Amoxicillin"))
    }

    @Test fun normalMedicineDeletesWithoutTheExtraWarning() = runTest {
        val id = create("Wrong one")
        assertFalse(service.deleteInfo(id)!!.startedMustComplete)
        assertEquals(ActionResult.Done, service.delete(id, "Delete", warningAcknowledged = false))
    }

    @Test fun undoRestoresTheDoseExactly() = runTest {
        val id = create("Undo me")
        val before = db.doseEventDao().forMedication(id).first { it.status == DoseStatus.PLANNED }
        assertTrue(actions.take(before.id))
        val after = db.doseEventDao().get(before.id)!!
        assertEquals(DoseStatus.TAKEN, after.status)
        assertTrue(actions.revert(before, after.actionAt))
        val restored = db.doseEventDao().get(before.id)!!
        assertEquals(DoseStatus.PLANNED, restored.status)
        assertNull(restored.takenAt)
        // A stale undo (the record changed again) is refused.
        assertTrue(actions.later(before.id))
        assertFalse(actions.revert(before, after.actionAt))
    }

    @Test fun recordEditSetsSiteReasonAndLateFlag() = runTest {
        val id = create("Edit me")
        val at = Instant.now().minus(Duration.ofHours(3)).toEpochMilli()
        val d = db.doseEventDao().insert(DoseEventEntity(medicationId = id, scheduledAt = at, status = DoseStatus.MISSED, createdAt = at, updatedAt = at))
        assertTrue(actions.edit(d, DoseStatus.TAKEN, Instant.ofEpochMilli(at), "after lunch", site = "Thigh left"))
        val e = db.doseEventDao().get(d)!!
        assertEquals(DoseStatus.TAKEN, e.status); assertEquals("Thigh left", e.site); assertTrue(e.isLateLog); assertEquals("after lunch", e.note)
        assertTrue(actions.edit(d, DoseStatus.SKIPPED, null, null, skipReason = "Felt unwell"))
        assertEquals("Felt unwell", db.doseEventDao().get(d)!!.skipReason)
        assertFalse(actions.edit(d, DoseStatus.SKIPPED, null, null, skipReason = "x".repeat(201)))
    }
}
