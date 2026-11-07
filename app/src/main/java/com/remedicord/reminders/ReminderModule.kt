package com.remedicord.reminders

import com.remedicord.domain.usecase.Replanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Phase 3 placeholder; replaced by the real reminder planner in Phase 4. */
@Module
@InstallIn(SingletonComponent::class)
object ReminderModule {
    @Provides @Singleton
    fun replanner(): Replanner = Replanner { }
}
