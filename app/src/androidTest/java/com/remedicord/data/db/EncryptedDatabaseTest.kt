package com.remedicord.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.core.security.DatabaseKeyUnavailableException
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md §11 "Database encryption" + DAO behaviour, on a real SQLCipher database. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "test-encrypted.db"
    private val keys = DbKeyManager(context, keyAlias = "test_db_key", keyFileName = "test_db_key.bin")
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        context.deleteDatabase(dbName)
        keys.deleteAllKeyMaterial()
        db = AppDatabase.open(context, keys, dbName)
    }

    @After fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(dbName)
        keys.deleteAllKeyMaterial()
    }

    private fun med(name: String = "SecretMedicineName123") =
        MedicationEntity(name = name, type = MedicationType.EYE_DROP, createdAt = 0, updatedAt = 0)

    private fun regimen() = RegimenEntity(
        medicationId = 0, effectiveFrom = 0, doseAmount = BigDecimal("2"), doseUnit = DoseUnit.DROP,
        scheduleRuleJson = "{}", changeReason = "Initial", createdAt = 0,
    )

    private fun repo() = MedicationRepository(db, AuditLogger(db.auditLogDao(), TimeProvider()), TimeProvider())

    @Test fun keyIsStableAndKeyFileIsNotThePlainKey() {
        val k1 = keys.getOrCreateDatabaseKey(databaseExists = true)
        val k2 = keys.getOrCreateDatabaseKey(databaseExists = true)
        assertArrayEquals(k1, k2)
        val stored = context.noBackupFilesDir.resolve("test_db_key.bin").readBytes()
        assertFalse("key file must be encrypted", String(stored, Charsets.ISO_8859_1).contains(String(k1, Charsets.US_ASCII).substring(2, 66)))
    }

    @Test fun databaseFileIsEncryptedOnDisk() = runTest {
        repo().create(med(), regimen())
        db.close()
        val file = context.getDatabasePath(dbName)
        val bytes = file.readBytes() + (context.getDatabasePath("$dbName-wal").takeIf { it.exists() }?.readBytes() ?: ByteArray(0))
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("must not have a plain SQLite header", text.startsWith("SQLite format 3"))
        assertFalse("medicine name must not appear in plain text", text.contains("SecretMedicineName123"))
    }

    @Test fun plainSqliteCannotOpenIt() = runTest {
        repo().create(med(), regimen())
        db.close()
        try {
            SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT count(*) FROM medication", null).use { c -> c.moveToFirst() }
            }
            fail("plain SQLite must not read the encrypted DB")
        } catch (_: Exception) { /* expected */ }
    }

    @Test fun wrongKeyCannotOpenIt() = runTest {
        repo().create(med(), regimen())
        db.close()
        System.loadLibrary("sqlcipher")
        try {
            CipherDatabase.openDatabase(context.getDatabasePath(dbName).path, "wrong-password".toByteArray(), null, CipherDatabase.OPEN_READONLY, null, null).use {
                it.rawQuery("SELECT count(*) FROM medication", null).use { c -> c.moveToFirst() }
            }
            fail("wrong key must fail")
        } catch (_: Exception) { /* expected */ }
    }

    @Test fun reopenWithCorrectKeyReadsData() = runTest {
        val id = repo().create(med(), regimen())
        db.close()
        db = AppDatabase.open(context, keys, dbName)
        assertEquals("SecretMedicineName123", db.medicationDao().get(id)?.name)
    }

    @Test fun missingKeyFileWithExistingDbFailsInsteadOfCreatingNewKey() = runTest {
        repo().create(med(), regimen())
        db.close()
        context.noBackupFilesDir.resolve("test_db_key.bin").delete()
        try {
            AppDatabase.open(context, keys, dbName)
            fail("must refuse to create a new key over existing data")
        } catch (_: DatabaseKeyUnavailableException) { /* expected */ }
        assertTrue(context.getDatabasePath(dbName).exists())
    }

    @Test fun createWritesMedicationRegimenAndAuditInOneTransaction() = runTest {
        val id = repo().create(med("Timolol"), regimen())
        assertEquals("Timolol", db.medicationDao().get(id)?.name)
        assertEquals(1, db.regimenDao().forMedication(id).size)
        val log = db.auditLogDao().forEntity(AuditLogger.MEDICATION, id)
        assertEquals(AuditAction.CREATE, log.single().action)
        assertNotNull(log.single().afterJson)
    }

    @Test fun updateIsAuditedWithBeforeAndAfter() = runTest {
        val r = repo()
        val id = r.create(med("Old"), regimen())
        r.update(db.medicationDao().get(id)!!.copy(name = "New"), note = "renamed")
        val update = db.auditLogDao().forEntity(AuditLogger.MEDICATION, id).last()
        assertEquals(AuditAction.UPDATE, update.action)
        assertTrue(update.beforeJson!!.contains("\"Old\""))
        assertTrue(update.afterJson!!.contains("\"New\""))
        assertEquals("renamed", update.note)
    }

    @Test fun changeRegimenClosesPreviousOne() = runTest {
        val r = repo()
        val id = r.create(med(), regimen())
        r.changeRegimen(regimen().copy(medicationId = id, effectiveFrom = 1000, doseAmount = BigDecimal("1")), "Doctor reduced")
        val regs = db.regimenDao().forMedication(id)
        assertEquals(2, regs.size)
        assertEquals(1000L, regs[0].effectiveTo)
        assertEquals(BigDecimal("1"), db.regimenDao().activeAt(id, 2000)?.doseAmount)
        assertEquals(BigDecimal("2"), db.regimenDao().activeAt(id, 500)?.doseAmount)
    }

    @Test fun doseUniquePerMedicationAndTimeAndCascadeDelete() = runTest {
        val id = repo().create(med(), regimen())
        val dose = DoseEventEntity(medicationId = id, scheduledAt = 5000, status = DoseStatus.PLANNED, createdAt = 0, updatedAt = 0)
        val doseRepo = DoseRepository(db, AuditLogger(db.auditLogDao(), TimeProvider()), TimeProvider())
        val ids = doseRepo.insertPlannedIfAbsent(listOf(dose, dose.copy(), dose.copy(scheduledAt = 6000)))
        assertEquals(listOf(1L, -1L, 2L).map { it > 0 }, ids.map { it > 0 })
        assertEquals(2, db.doseEventDao().forMedication(id).size)
        assertEquals(2, db.doseEventDao().observeBetween(0, 10_000).first().size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM medication WHERE id = $id")
        assertEquals(0, db.doseEventDao().forMedication(id).size)
    }

    @Test fun privateSettingsAreNeverExportable() = runTest {
        val s = SettingsRepository(db)
        s.put("snooze.options", "5,10,15")
        s.put(SettingsRepository.PRIVATE_PREFIX + "pin.hash", "abc")
        assertEquals(listOf("snooze.options"), s.exportable().map { it.key })
    }
}
