package com.remedicord.data.backup

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
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md Phase 6 "done when": export → wipe → restore = identical; bad files change nothing; PIN kept. */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = DbKeyManager(context, "bak_test_key", "bak_test_key.bin")
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupService
    private lateinit var service: MedicationService
    private lateinit var settings: SettingsRepository

    @Before fun setUp() {
        context.deleteDatabase("bak-test.db"); keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, "bak-test.db")
        val time = TimeProvider()
        val audit = AuditLogger(db.auditLogDao(), time)
        settings = SettingsRepository(db)
        service = MedicationService(MedicationRepository(db, audit, time), SkipRuleRepository(db, audit, time), DoseRepository(db, audit, time), time) {}
        backup = BackupService(context, db, settings, {}, time, "test")
    }

    @After fun tearDown() {
        backup.snapshots().forEach { it.file.delete() }
        db.close(); context.deleteDatabase("bak-test.db"); keys.deleteAllKeyMaterial()
    }

    private suspend fun all() = listOf(db.medicationDao().getAll(), db.regimenDao().getAll(), db.skipRuleDao().getAll(), db.auditLogDao().getAll())

    @Test fun exportWipeRestoreIsIdenticalAndPinIsKept() = runTest {
        service.create(MedicationForm(name = "Timolol", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0)))))
        settings.put("reminder.snoozeMinutes", "15")
        settings.put(SettingsRepository.PRIVATE_PREFIX + "pin.hash", "PIN-ON-THIS-PHONE")
        val before = all()
        val bytes = ByteArrayOutputStream().also { backup.export(it) }.toByteArray()
        assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("PIN-ON-THIS-PHONE"))

        service.create(MedicationForm(name = "Added later", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(9, 0)))))
        settings.put("reminder.snoozeMinutes", "5")

        val preview = backup.preview(ByteArrayInputStream(bytes))
        assertEquals(1, preview.medications)
        backup.restore(ByteArrayInputStream(bytes))
        assertEquals(before, all())
        assertEquals("15", settings.get("reminder.snoozeMinutes"))
        assertEquals("PIN-ON-THIS-PHONE", settings.get(SettingsRepository.PRIVATE_PREFIX + "pin.hash"))
        assertEquals(1, backup.snapshots().size) // current data was saved first
    }

    @Test fun invalidFileChangesNothing() = runTest {
        service.create(MedicationForm(name = "Keep me", type = MedicationType.PILL, rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0)))))
        val before = all()
        try { backup.restore(ByteArrayInputStream("garbage".toByteArray())); fail() } catch (_: BackupException) {}
        assertEquals(before, all())
        assertEquals(0, backup.snapshots().size)
    }

    @Test fun keepsOnlyThreeSnapshots() = runTest {
        repeat(5) { backup.saveSnapshot(); Thread.sleep(5) }
        assertEquals(BackupService.KEEP_SNAPSHOTS, backup.snapshots().size)
    }
}
