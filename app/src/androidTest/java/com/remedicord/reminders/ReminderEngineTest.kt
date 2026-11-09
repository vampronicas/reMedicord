package com.remedicord.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.MainActivity
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md §11 "Reminder engine" + §12.2 security tests, on a real device/emulator. */
@RunWith(AndroidJUnit4::class)
class ReminderEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "rem_test_key", "rem_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var planner: ReminderPlanner
    private lateinit var service: MedicationService
    private lateinit var actions: DoseActionService
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        context.deleteDatabase("rem-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "rem-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        val meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        settings = SettingsRepository(db)
        planner = ReminderPlanner(context, db, meds, settings, AlarmScheduler(context), DirectBootStore(context), time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time, planner)
        actions = DoseActionService(doses, meds, time, planner)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("rem-test.db"); keys.deleteAllKeyMaterial() }

    private fun soon(minutes: Long) = LocalTime.now(ZoneId.systemDefault()).plusMinutes(minutes).withSecond(0).withNano(0)

    @Test fun replanCreatesDosesAndAnExactAlarm() = runTest {
        service.create(MedicationForm(purpose = com.remedicord.domain.model.MedicationPurpose.OTHER, purposeNote = "test", name = "Planner test", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(soon(30)))))
        val planned = db.doseEventDao().withStatus(DoseStatus.PLANNED)
        assertTrue(planned.isNotEmpty())
        assertNotNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock)
        assertTrue(settings.get(ReminderSettingsRepository.SCHEDULED_SLOTS)!!.isNotEmpty())
    }

    @Test fun phoneOffGapBecomesUnconfirmedNotMissed() = runTest {
        service.create(MedicationForm(purpose = com.remedicord.domain.model.MedicationPurpose.OTHER, purposeNote = "test", name = "Gap test", type = MedicationType.PILL,
            rule = ScheduleRule.EveryNHours(1, LocalTime.of(0, 0)), startDate = java.time.LocalDate.now().minusDays(3)))
        // A new medicine's schedule starts "now"; move its regimen start 3 days back so past doses exist.
        val medId = db.medicationDao().getAll().single().id
        db.regimenDao().forMedication(medId).single().let { db.regimenDao().update(it.copy(effectiveFrom = Instant.now().minus(Duration.ofDays(3)).toEpochMilli())) }
        // Simulate: the app last planned 6 hours ago and the phone was off since.
        db.doseEventDao().getAll().forEach { db.doseEventDao().update(it.copy(scheduledAt = null, status = DoseStatus.SKIPPED, actionAt = 1)) }
        settings.put(ReminderSettingsRepository.PLANNED_UNTIL, Instant.now().minus(Duration.ofHours(6)).toEpochMilli().toString())
        planner.replan()
        val unconfirmed = db.doseEventDao().withStatus(DoseStatus.UNCONFIRMED)
        assertTrue("expected ~5 unconfirmed, got ${unconfirmed.size}", unconfirmed.size in 4..7)
        assertEquals(0, db.doseEventDao().withStatus(DoseStatus.MISSED).size)
        // Answering the review prompt.
        assertTrue(actions.review(unconfirmed[0].id, DoseActionService.ReviewAnswer.TAKEN))
        assertTrue(actions.review(unconfirmed[1].id, DoseActionService.ReviewAnswer.MISSED))
        assertEquals(DoseStatus.TAKEN, db.doseEventDao().get(unconfirmed[0].id)!!.status)
        assertTrue(db.doseEventDao().get(unconfirmed[0].id)!!.isLateLog)
    }

    @Test fun takeSnoozeSkipAreAuditedAndValidated() = runTest {
        service.create(MedicationForm(purpose = com.remedicord.domain.model.MedicationPurpose.OTHER, purposeNote = "test", name = "Action test", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(soon(60), soon(120)))))
        val (a, b) = db.doseEventDao().withStatus(DoseStatus.PLANNED).take(2)
        assertTrue(actions.snooze(a.id, 10, maxSnoozes = 1))
        assertTrue(!actions.snooze(a.id, 10, maxSnoozes = 1)) // limit reached
        val idsBefore = db.doseEventDao().withStatus(DoseStatus.PLANNED).map { it.id }.toSet()
        assertTrue(actions.take(a.id))
        // Re-planning after an action must not re-create (re-number) the other upcoming doses.
        assertTrue(db.doseEventDao().withStatus(DoseStatus.PLANNED).map { it.id }.toSet().containsAll(idsBefore - a.id))
        assertTrue(!actions.take(a.id)) // already taken: no double recording
        assertTrue(actions.skip(b.id, "Felt unwell"))
        assertTrue(!actions.skip(b.id, " "))
        assertTrue(db.auditLogDao().forEntity(AuditLogger.DOSE, a.id).size >= 2)
        assertTrue(!actions.logLate(b.id, Instant.now())) // skipped, not missed
    }

    @Test fun pendingIntentsAreImmutable() {
        val pi = AlarmScheduler.slotIntent(context, 29_000_000L, PendingIntent.FLAG_UPDATE_CURRENT)!!
        assertTrue(pi.isImmutable)
        assertEquals(context.packageName, pi.creatorPackage)
        pi.cancel()
    }

    @Test fun receiversIgnoreForgedIntents() = runTest {
        val before = db.doseEventDao().getAll().size
        listOf(Intent("evil.ACTION"), Intent(DoseActionReceiver.ACTION_TAKEN).putExtra(AlarmScheduler.EXTRA_SLOT, -5L),
            Intent(DoseActionReceiver.ACTION_TAKEN).putExtra(AlarmScheduler.EXTRA_SLOT, Long.MAX_VALUE), Intent(AlarmScheduler.ACTION_SLOT))
            .forEach { i ->
                ReminderReceiver().onReceive(context, i)
                DoseActionReceiver().onReceive(context, i)
                SystemEventReceiver().onReceive(context, i)
            }
        assertEquals(before, db.doseEventDao().getAll().size)
    }

    @Test fun launcherIgnoresIncomingData() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("doseId", 1L).putExtra("action", "delete_all").setData(android.net.Uri.parse("content://evil/1"))
        ActivityScenario.launch<MainActivity>(intent).use { s -> s.onActivity { assertTrue(!it.isFinishing) } }
    }
}
