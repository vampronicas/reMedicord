package com.remedicord.core.di

import android.content.Context
import com.remedicord.core.security.DbKeyManager
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun timeProvider(): TimeProvider = TimeProvider()

    @Provides @Singleton
    fun dbKeyManager(@ApplicationContext context: Context): DbKeyManager = DbKeyManager(context)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, keyManager: DbKeyManager): AppDatabase =
        AppDatabase.open(context, keyManager)

    @Provides
    fun auditLogDao(db: AppDatabase) = db.auditLogDao()

    @Provides @Singleton
    fun auditLogger(db: AppDatabase, time: TimeProvider): AuditLogger = AuditLogger(db.auditLogDao(), time)

    @Provides @Singleton
    fun medicationRepository(db: AppDatabase, audit: AuditLogger, time: TimeProvider) =
        MedicationRepository(db, audit, time)

    @Provides @Singleton
    fun doseRepository(db: AppDatabase, audit: AuditLogger, time: TimeProvider) = DoseRepository(db, audit, time)

    @Provides @Singleton
    fun skipRuleRepository(db: AppDatabase, audit: AuditLogger, time: TimeProvider) =
        SkipRuleRepository(db, audit, time)

    @Provides @Singleton
    fun settingsRepository(db: AppDatabase) = SettingsRepository(db)
}
