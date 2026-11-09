package com.remedicord.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.remedicord.core.security.DbKeyManager
import com.remedicord.data.db.dao.AuditLogDao
import com.remedicord.data.db.dao.DoseEventDao
import com.remedicord.data.db.dao.MedicationDao
import com.remedicord.data.db.dao.RegimenDao
import com.remedicord.data.db.dao.SettingDao
import com.remedicord.data.db.dao.SkipRuleDao
import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SettingEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MedicationEntity::class,
        RegimenEntity::class,
        DoseEventEntity::class,
        SkipRuleEntity::class,
        AuditLogEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun regimenDao(): RegimenDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun skipRuleDao(): SkipRuleDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun settingDao(): SettingDao

    companion object {
        const val DB_NAME = "remedicord.db"

        /** v1 → v2: medicine purpose, "don't remind today", why a dose awaits an answer. Keeps all data. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medication ADD COLUMN purpose TEXT NOT NULL DEFAULT 'TREATMENT'")
                db.execSQL("ALTER TABLE medication ADD COLUMN purposeNote TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE medication ADD COLUMN remindersMutedUntil INTEGER")
                db.execSQL("ALTER TABLE dose_event ADD COLUMN pendingReason TEXT")
                db.execSQL("UPDATE dose_event SET pendingReason = 'APP_NOT_RUNNING' WHERE status = 'UNCONFIRMED'")
            }
        }

        /** Migrations are added here as the schema evolves. Each one gets a test (PLAN.md §6, §11). */
        val MIGRATIONS = arrayOf<androidx.room.migration.Migration>(MIGRATION_1_2)

        /**
         * Opens the SQLCipher-encrypted database (PLAN.md §3).
         *
         * IMPORTANT: there is deliberately NO destructive-migration fallback. An app update must never
         * wipe data; a missing migration fails loudly instead. Enforced by NoDestructiveMigrationTest.
         */
        fun open(context: Context, keyManager: DbKeyManager, name: String = DB_NAME): AppDatabase {
            System.loadLibrary("sqlcipher")
            val appContext = context.applicationContext
            val key = keyManager.getOrCreateDatabaseKey(databaseExists = appContext.getDatabasePath(name).exists())
            return Room.databaseBuilder(appContext, AppDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(key))
                .addMigrations(*MIGRATIONS)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                .build()
        }
    }
}
