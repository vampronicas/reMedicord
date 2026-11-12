package com.remedicord.reminders

import com.remedicord.deleteTestKey
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.usecase.ActionResult
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.ui.ChangeHistory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** v1.4: replace a medicine (linked entries), delete clears links, change history from real changes. */
@RunWith(AndroidJUnit4::class)
class V14FeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "v14_test_key", "v14_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var service: MedicationService

    @Before fun setUp() {
        context.deleteDatabase("v14-test.db"); deleteTestKey(context, "v14_test_key")
        db = AppDatabase.open(context, keys, "v14-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        val meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        val planner = ReminderPlanner(context, db, meds, SettingsRepository(db), AlarmScheduler(context), DirectBootStore(context), time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time, planner)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("v14-test.db"); deleteTestKey(context, "v14_test_key") }

    private fun soon(min: Long) = LocalTime.now(ZoneId.systemDefault()).plusMinutes(min).withSecond(0).withNano(0)
    private fun form(name: String, must: Boolean = false) = MedicationForm(name = name, type = MedicationType.PILL, purpose = MedicationPurpose.TREATMENT,
        rule = ScheduleRule.FixedTimes(listOf(soon(30))), mustComplete = must, courseMode = if (must) CourseMode.DAYS else CourseMode.NONE, courseLength = if (must) 7 else null)
    private suspend fun id(name: String) = db.medicationDao().getAll().first { it.name == name }.id

    @Test fun replaceCreatesALinkedNewEntryAndStopsTheOld() = runTest {
        service.create(form("Amoxicillin 500"))
        val oldId = id("Amoxicillin 500")
        var newId = -1L
        assertEquals(ActionResult.Done, service.replace(oldId, form("Amoxicillin 250"), confirmed = false, kind = com.remedicord.domain.model.ReplaceKind.DOWNGRADE) { newId = it })
        val old = db.medicationDao().get(oldId)!!
        val new = db.medicationDao().get(newId)!!
        assertEquals(MedicationStatus.STOPPED, old.status)
        assertEquals(StopReason.REPLACED, old.stopReason)
        assertEquals(newId, old.replacedById)
        assertTrue(old.replacedAt != null)
        assertEquals(oldId, new.replacesId)
        assertEquals(com.remedicord.domain.model.ReplaceKind.DOWNGRADE, new.replaceKind)
        assertEquals("Downgraded to Amoxicillin 250", old.stopNote)
        assertEquals(MedicationStatus.ACTIVE, new.status)
        assertTrue(db.doseEventDao().forMedication(oldId).none { it.status == DoseStatus.PLANNED })
        assertTrue(db.doseEventDao().forMedication(newId).any { it.status == DoseStatus.PLANNED })
        // Can't replace an already ended medicine.
        assertTrue(service.replace(oldId, form("Again"), confirmed = true) is ActionResult.Invalid)
    }

    @Test fun replacingAMustCompleteCourseNeedsConfirmation() = runTest {
        service.create(form("Course", must = true))
        val oldId = id("Course")
        assertTrue(service.replace(oldId, form("Course B", must = true), confirmed = false) is ActionResult.NeedsConfirmation)
        assertEquals(MedicationStatus.ACTIVE, db.medicationDao().get(oldId)!!.status)
        assertEquals(ActionResult.Done, service.replace(oldId, form("Course B", must = true), confirmed = true))
    }

    @Test fun deletingOneSideClearsTheLink() = runTest {
        service.create(form("Old"))
        val oldId = id("Old")
        var newId = -1L
        service.replace(oldId, form("New"), confirmed = false) { newId = it }
        assertEquals(ActionResult.Done, service.delete(newId, "Delete", warningAcknowledged = true))
        assertNull(db.medicationDao().get(oldId)!!.replacedById)
    }

    @Test fun pauseDateIsRecordedAndClearedOnResume() = runTest {
        service.create(form("Paused one"))
        val mid = id("Paused one")
        service.pause(mid, java.time.LocalDate.now().plusDays(3), null)
        assertTrue(db.medicationDao().get(mid)!!.pausedAt != null)
        service.resume(mid)
        assertNull(db.medicationDao().get(mid)!!.pausedAt)
    }

    @Test fun changeHistoryShowsDoseChangeAndPause() = runTest {
        service.create(form("Vitamin D"))
        val mid = id("Vitamin D")
        service.changeDose(mid, BigDecimal("2"), DoseUnit.TABLET, ScheduleRule.FixedTimes(listOf(soon(60))), Instant.now(), "Winter dose")
        service.pause(mid, null, "Travelling")
        val regs = db.regimenDao().forMedication(mid)
        val h = ChangeHistory.build(db.auditLogDao().forEntity(AuditLogger.MEDICATION, mid),
            regs.flatMap { db.auditLogDao().forEntity(AuditLogger.REGIMEN, it.id) }, ZoneId.systemDefault())
        val titles = h.map { it.title }
        assertTrue(titles.toString(), titles.any { it.startsWith("Dose changed: 1 tablet → 2 tablets") })
        assertTrue(titles.contains("Paused"))
        assertTrue(titles.contains("Medicine added"))
    }
}
