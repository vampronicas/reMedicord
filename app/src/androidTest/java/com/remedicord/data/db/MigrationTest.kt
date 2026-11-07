package com.remedicord.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN.md §6/§11: every schema version from v1 must migrate to the latest without data loss.
 * When version 2 arrives, add: createDatabase(v1) → insert rows → runMigrationsAndValidate(2, MIGRATION_1_2) → assert rows.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun version1SchemaIsValid() {
        helper.createDatabase(testDb, 1).close()
        helper.runMigrationsAndValidate(testDb, 1, true, *AppDatabase.MIGRATIONS).close()
    }
}
