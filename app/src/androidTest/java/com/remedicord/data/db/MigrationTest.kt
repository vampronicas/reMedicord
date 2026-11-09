package com.remedicord.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PLAN.md §6/§11: every schema version migrates to the latest without data loss. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun version2To3AddsPauseColumnsAndKeepsData() {
        helper.createDatabase("m23.db", 2).use { db ->
            db.execSQL(
                """INSERT INTO medication (id, name, type, instructions, foodRelation, status, mustComplete, courseMode, timeZoneMode, createdAt, updatedAt, purpose, purposeNote)
                   VALUES (1, 'Vitamin D', 'PILL', '', 'NONE', 'PAUSED', 0, 'NONE', 'LOCAL', 1, 1, 'SUPPLEMENT', 'Winter')""",
            )
        }
        helper.runMigrationsAndValidate("m23.db", 3, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, status, purposeNote, pausedUntil, pauseReason FROM medication WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("Vitamin D", c.getString(0)); assertEquals("PAUSED", c.getString(1)); assertEquals("Winter", c.getString(2))
                assertEquals(true, c.isNull(3)); assertEquals(true, c.isNull(4)) // existing pauses become "until I resume"
            }
        }
    }

    @Test fun version1To3InOneGo() {
        helper.createDatabase("m13.db", 1).close()
        helper.runMigrationsAndValidate("m13.db", 3, true, *AppDatabase.MIGRATIONS).close()
    }

    @Test fun version1To2KeepsDataAndAddsColumns() {
        helper.createDatabase(testDb, 1).use { db ->
            db.execSQL(
                """INSERT INTO medication (id, name, type, instructions, foodRelation, status, mustComplete, courseMode, timeZoneMode, createdAt, updatedAt)
                   VALUES (1, 'Timolol', 'EYE_DROP', '', 'NONE', 'ACTIVE', 0, 'NONE', 'LOCAL', 1, 1)""",
            )
            db.execSQL("INSERT INTO dose_event (id, medicationId, scheduledAt, status, snoozeCount, repeatCount, isLateLog, isPrn, createdAt, updatedAt) VALUES (1, 1, 1000, 'UNCONFIRMED', 0, 0, 0, 0, 1, 1)")
            db.execSQL("INSERT INTO dose_event (id, medicationId, scheduledAt, status, snoozeCount, repeatCount, isLateLog, isPrn, createdAt, updatedAt) VALUES (2, 1, 2000, 'TAKEN', 0, 0, 0, 0, 1, 1)")
        }
        helper.runMigrationsAndValidate(testDb, 2, true, *AppDatabase.MIGRATIONS).use { db ->
            db.query("SELECT name, purpose, purposeNote, remindersMutedUntil FROM medication WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("Timolol", c.getString(0)); assertEquals("TREATMENT", c.getString(1)); assertEquals("", c.getString(2)); assertEquals(true, c.isNull(3))
            }
            db.query("SELECT pendingReason FROM dose_event ORDER BY id").use { c ->
                c.moveToFirst(); assertEquals("APP_NOT_RUNNING", c.getString(0))
                c.moveToNext(); assertEquals(true, c.isNull(0))
            }
        }
    }
}
