package com.remedicord.domain.usecase

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
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md Phase 5: history edits are audited; PRN limits; late logging. */
@RunWith(AndroidJUnit4::class)
class HistoryEditTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "hist_test_key", "hist_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var actions: DoseActionService
    private lateinit var service: MedicationService

    @Before fun setUp() {
        context.deleteDatabase("hist-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "hist-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        val meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time) {}
        actions = DoseActionService(doses, meds, time) {}
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("hist-test.db"); keys.deleteAllKeyMaterial() }

    private suspend fun missedDose(): Long {
        service.create(MedicationForm(name = "Hist", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0)))))
        val medId = db.medicationDao().getAll().single().id
        val at = Instant.now().minus(Duration.ofHours(3)).toEpochMilli()
        return db.doseEventDao().insert(DoseEventEntity(medicationId = medId, scheduledAt = at, status = DoseStatus.MISSED, createdAt = at, updatedAt = at))
    }

    @Test fun editIsAuditedAndValidated() = runTest {
        val id = missedDose()
        assertTrue(actions.edit(id, DoseStatus.TAKEN, Instant.now().minus(Duration.ofHours(2)), "remembered later"))
        assertEquals(DoseStatus.TAKEN, db.doseEventDao().get(id)!!.status)
        val log = db.auditLogDao().forEntity(AuditLogger.DOSE, id)
        assertEquals(1, log.size)
        assertTrue(log[0].beforeJson!!.contains("MISSED") && log[0].afterJson!!.contains("TAKEN"))
        assertFalse(actions.edit(id, DoseStatus.TAKEN, Instant.now().plus(Duration.ofHours(1)), null)) // future time
        assertFalse(actions.edit(id, DoseStatus.PLANNED, null, null)) // not an allowed edit target
        assertFalse(actions.edit(id, DoseStatus.TAKEN, null, "x".repeat(501)))
    }

    @Test fun lateLogOnlyForMissedAndNotInFuture() = runTest {
        val id = missedDose()
        assertFalse(actions.logLate(id, Instant.now().plus(Duration.ofMinutes(5))))
        assertTrue(actions.logLate(id, Instant.now().minus(Duration.ofHours(1))))
        assertTrue(db.doseEventDao().get(id)!!.isLateLog)
        assertFalse(actions.logLate(id, Instant.now()))
    }

    @Test fun asNeededRecordsAndWarnsOnLimits() = runTest {
        service.create(MedicationForm(name = "Pain", type = MedicationType.PILL, rule = ScheduleRule.AsNeeded(240, 2)))
        val medId = db.medicationDao().getAll().single().id
        assertEquals(com.remedicord.domain.policy.PrnPolicy.Result.Ok, actions.prnCheck(medId))
        actions.takePrn(medId)
        assertTrue(actions.prnCheck(medId) is com.remedicord.domain.policy.PrnPolicy.Result.TooSoon)
        val dose = db.doseEventDao().forMedication(medId).single()
        assertTrue(dose.isPrn && dose.status == DoseStatus.TAKEN)
    }
}
