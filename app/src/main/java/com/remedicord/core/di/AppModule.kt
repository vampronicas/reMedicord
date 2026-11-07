package com.remedicord.core.di

import com.remedicord.core.lock.AppLockManager
import com.remedicord.core.lock.PinRepository
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.domain.usecase.Replanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun pinRepository(settings: SettingsRepository) = PinRepository(settings)

    @Provides @Singleton
    fun appLockManager() = AppLockManager()

    @Provides @Singleton
    fun medicationService(
        meds: MedicationRepository, skips: SkipRuleRepository, doses: DoseRepository, time: TimeProvider, replanner: Replanner,
    ) = MedicationService(meds, skips, doses, time, replanner)
}
