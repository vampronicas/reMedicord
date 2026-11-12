package com.remedicord.reminders

import com.remedicord.deleteTestKey
import android.app.NotificationManager
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
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.ui.DoseItem
import com.remedicord.ui.RecordSupport
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** v1.3: group Taken / Skip with undo, postpone next reminders, reminder sound channel. */
@RunWith(AndroidJUnit4::class)
class V13FeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "v13_test_key", "v13_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var service: MedicationService
    private lateinit var support: RecordSupport
    private lateinit var meds: MedicationRepository

    @Before fun setUp() {
        context.deleteDatabase("v13-test.db"); deleteTestKey(context, "v13_test_key")
        db = AppDatabase.open(context, keys, "v13-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        meds = MedicationRepository(db, audit, time)
        val doses = DoseRepository(db, audit, time)
        val settings = SettingsRepository(db)
        val planner = ReminderPlanner(context, db, meds, settings, AlarmScheduler(context), DirectBootStore(context), time)
        service = MedicationService(meds, SkipRuleRepository(db, audit, time), doses, time, planner)
        val actions = DoseActionService(doses, meds, time, planner)
        support = RecordSupport(actions, doses, db.auditLogDao(), ReminderSettingsRepository(settings), time)
    }

    @After fun tearDown() { db.close(); context.deleteDatabase("v13-test.db"); deleteTestKey(context, "v13_test_key") }

    private fun soon(min: Long) = LocalTime.now(ZoneId.systemDefault()).plusMinutes(min).withSecond(0).withNano(0)
    private suspend fun create(name: String, at: LocalTime): Long {
        service.create(MedicationForm(name = name, type = MedicationType.PILL, purpose = MedicationPurpose.SUPPLEMENT, rule = ScheduleRule.FixedTimes(listOf(at))))
        return db.medicationDao().getAll().first { it.name == name }.id
    }

    private suspend fun items(): List<DoseItem> = db.doseEventDao().getAll().filter { it.status == DoseStatus.PLANNED }.map { d ->
        DoseItem(d, meds.get(d.medicationId)!!, d.regimenId?.let { db.regimenDao().get(it) })
    }.sortedBy { it.dose.scheduledAt }

    @Test fun purposeDetailsAreOptional() = runTest {
        assertTrue(create("No details", soon(30)) > 0)
    }

    @Test fun groupTakenAndUndo() = runTest {
        val t = soon(30)
        create("A", t); create("B", t)
        val group = items().filter { it.dose.scheduledAt == items().first().dose.scheduledAt }
        assertEquals(2, group.size)
        val undo = support.group(group, taken = true, reason = null)
        assertEquals(2, undo.size)
        assertTrue(group.all { db.doseEventDao().get(it.dose.id)!!.status == DoseStatus.TAKEN })
        assertTrue(support.undoAll(undo))
        assertTrue(group.all { db.doseEventDao().get(it.dose.id)!!.status == DoseStatus.PLANNED })
    }

    @Test fun groupSkipRecordsTheReason() = runTest {
        val t = soon(40)
        create("C", t); create("D", t)
        val group = items().filter { it.dose.scheduledAt == items().first().dose.scheduledAt }
        support.group(group, taken = false, reason = "Not available")
        assertTrue(group.all { db.doseEventDao().get(it.dose.id)!!.let { d -> d.status == DoseStatus.SKIPPED && d.skipReason == "Not available" } })
    }

    @Test fun postponeMovesOnlyTheReminderNotTheDueTime() = runTest {
        create("Early", soon(60))
        val before = items().first().dose
        val moves = support.postpone(1, 2)
        assertEquals(1, moves.size)
        val after = db.doseEventDao().get(before.id)!!
        assertEquals(before.scheduledAt, after.scheduledAt)
        assertEquals(before.remindAt!! + Duration.ofHours(2).toMillis(), after.remindAt)
        // Survives a re-plan (in-place update keeps the postponed reminder).
        support.postpone(1, 1) // moves the NEXT reminder, which may now be this one again or another; then re-check stability
        assertTrue(db.doseEventDao().get(before.id)!!.remindAt!! >= after.remindAt!!)
    }

    @Test fun reminderSoundCreatesAFreshChannelAndRemovesTheOld() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val first = Notifications.setReminderSound(context, Notifications.ReminderSound.BELL)
        assertEquals(Notifications.ReminderSound.BELL, Notifications.currentSound(context))
        assertTrue(nm.getNotificationChannel(first).sound.toString().endsWith(com.remedicord.R.raw.tone_bell.toString()))
        Thread.sleep(5)
        val second = Notifications.setReminderSound(context, Notifications.ReminderSound.SILENT)
        assertEquals(null, nm.getNotificationChannel(second).sound)
        assertEquals(null, nm.getNotificationChannel(first)) // old channel deleted
        assertEquals(second, Notifications.reminderChannelId(context))
        Notifications.setReminderSound(context, Notifications.ReminderSound.CHIME)
    }
}
