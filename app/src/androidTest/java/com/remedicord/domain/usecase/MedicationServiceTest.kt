package com.remedicord.domain.usecase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.core.lock.PinRepository
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.schedule.TaperBuilder
import java.math.BigDecimal
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md Phase 3 "done when": create every type, change dose, skip modes, stop/complete rules. */
@RunWith(AndroidJUnit4::class)
class MedicationServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "svc_test_key", "svc_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var service: MedicationService
    private lateinit var meds: MedicationRepository
    private var replans = 0

    @Before fun setUp() {
        context.deleteDatabase("svc-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "svc-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        meds = MedicationRepository(db, audit, time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), DoseRepository(db, audit, time), time) { replans++ }
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("svc-test.db"); keys.deleteAllKeyMaterial() }

    private val daily = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))
    private fun form(type: MedicationType = MedicationType.PILL, must: Boolean = false) = MedicationForm(purpose = com.remedicord.domain.model.MedicationPurpose.OTHER, purposeNote = "test", 
        name = "Test ${type.name}", type = type, rule = daily, unit = DoseUnit.TABLET,
        side = if (type == MedicationType.EYE_DROP || type == MedicationType.EAR_DROP) BodySide.LEFT else null,
        mustComplete = must, courseMode = if (must) CourseMode.DAYS else CourseMode.NONE, courseLength = if (must) 7 else null,
    )

    @Test fun createsEveryTypeAndReplans() = runTest {
        MedicationType.entries.forEach { assertEquals(ActionResult.Done, service.create(form(it))) }
        assertEquals(MedicationType.entries.size, db.medicationDao().getAll().size)
        assertEquals(MedicationType.entries.size, replans)
    }

    @Test fun invalidFormIsRejectedAndNothingSaved() = runTest {
        val r = service.create(form().copy(name = ""))
        assertTrue(r is ActionResult.Invalid)
        assertEquals(0, db.medicationDao().getAll().size)
    }

    @Test fun taperCreatesChainedRegimens() = runTest {
        val steps = listOf(
            TaperBuilder.Step(daily, BigDecimal.ONE, DoseUnit.DROP, 3),
            TaperBuilder.Step(ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0))), BigDecimal.ONE, DoseUnit.DROP, 3),
        )
        service.create(form(MedicationType.EYE_DROP).copy(rule = null, taperSteps = steps))
        val regs = meds.regimens(db.medicationDao().getAll().single().id)
        assertEquals(2, regs.size)
        assertEquals(regs[0].effectiveTo, regs[1].effectiveFrom)
    }

    @Test fun mustCompleteBlocksStopPauseAndMultiSkip() = runTest {
        service.create(form(must = true))
        val id = db.medicationDao().getAll().single().id
        assertTrue(service.stop(id, StopReason.SYMPTOMS_RESOLVED, null, false) is ActionResult.Blocked)
        assertTrue(service.pause(id) is ActionResult.Blocked)
        assertTrue(service.skip(id, SkipMode.DAYS, 2, null, "Felt unwell", null, false) is ActionResult.Blocked)
        assertTrue(service.skip(id, null, null, null, "Felt unwell", null, false) is ActionResult.NeedsConfirmation)
        assertEquals(ActionResult.Done, service.skip(id, null, null, null, "Felt unwell", null, true))
        // Doctor's instruction: needs confirmation AND a note.
        assertTrue(service.stop(id, StopReason.DOCTOR_INSTRUCTED, null, false) is ActionResult.NeedsConfirmation)
        assertTrue(service.stop(id, StopReason.DOCTOR_INSTRUCTED, null, true) is ActionResult.Invalid)
        assertEquals(ActionResult.Done, service.stop(id, StopReason.DOCTOR_INSTRUCTED, "Dr said stop", true))
        assertEquals(MedicationStatus.STOPPED, meds.get(id)!!.status)
    }

    @Test fun normalMedicationPauseResumeStopRestartAndChangeDose() = runTest {
        service.create(form())
        val id = db.medicationDao().getAll().single().id
        assertEquals(ActionResult.Done, service.pause(id)); assertEquals(MedicationStatus.PAUSED, meds.get(id)!!.status)
        assertEquals(ActionResult.Done, service.resume(id))
        assertEquals(ActionResult.Done, service.changeDose(id, BigDecimal("0.5"), DoseUnit.TABLET, daily, java.time.Instant.now(), "Doctor reduced"))
        assertTrue(service.changeDose(id, BigDecimal("0.5"), DoseUnit.TABLET, daily, java.time.Instant.now(), " ") is ActionResult.Invalid)
        assertEquals(ActionResult.Done, service.stop(id, StopReason.SYMPTOMS_RESOLVED, null, false))
        assertEquals(ActionResult.Done, service.restart(id))
        assertEquals(MedicationStatus.ACTIVE, meds.get(id)!!.status)
        assertTrue(meds.regimens(id).size >= 3)
    }

    // ~8 deliberately slow PBKDF2 hashes (210k rounds each) on a slow emulator: allow more than the 1-minute default.
    @Test fun pinIsHashedLockoutPersistsAndNeverExported() = runTest(timeout = 3.minutes) {
        val settings = SettingsRepository(db)
        var clock = 1_000L
        val pins = PinRepository(settings) { clock }
        pins.setPin("2468")
        assertTrue(settings.getAllKeysForTest().none { settings.get(it) == "2468" })
        assertEquals(PinRepository.VerifyResult.Success, pins.verify("2468"))
        repeat(4) { assertTrue(pins.verify("0000") is PinRepository.VerifyResult.Wrong) }
        val fifth = pins.verify("0000") as PinRepository.VerifyResult.Wrong
        assertEquals(30_000L, fifth.lockoutMs)
        assertTrue(pins.verify("2468") is PinRepository.VerifyResult.LockedOut)
        clock += 30_001
        assertEquals(PinRepository.VerifyResult.Success, pins.verify("2468"))
        assertTrue(settings.exportable().none { it.key.contains("pin") })
    }

    private suspend fun SettingsRepository.getAllKeysForTest() = db.settingDao().getAll().map { it.key }
}
