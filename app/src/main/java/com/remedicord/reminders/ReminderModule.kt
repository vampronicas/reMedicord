package com.remedicord.reminders

import android.content.Context
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.Replanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    // These two never touch the encrypted DB, so they are safe before the first unlock (direct boot).
    @Provides @Singleton fun alarmScheduler(@ApplicationContext c: Context) = AlarmScheduler(c)
    @Provides @Singleton fun directBootStore(@ApplicationContext c: Context) = DirectBootStore(c)
    @Provides @Singleton fun healthChecker(@ApplicationContext c: Context) = ReminderHealthChecker(c)

    @Provides @Singleton fun reminderSettings(settings: SettingsRepository) = ReminderSettingsRepository(settings)

    @Provides @Singleton
    fun planner(
        @ApplicationContext c: Context, db: AppDatabase, meds: MedicationRepository, settings: SettingsRepository,
        scheduler: AlarmScheduler, store: DirectBootStore, time: TimeProvider,
    ) = ReminderPlanner(c, db, meds, settings, scheduler, store, time)

    @Provides @Singleton fun replanner(planner: ReminderPlanner): Replanner = planner

    @Provides @Singleton
    fun doseActions(doses: DoseRepository, meds: MedicationRepository, time: TimeProvider, replanner: Replanner) =
        DoseActionService(doses, meds, time, replanner)

    @Provides @Singleton
    fun engine(
        @ApplicationContext c: Context, db: AppDatabase, planner: ReminderPlanner, actions: DoseActionService,
        settings: ReminderSettingsRepository, time: TimeProvider,
    ) = ReminderEngine(c, db, planner, actions, settings, time)
}
