package com.remedicord.data.backup

import android.content.Context
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.usecase.Replanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides @Singleton
    fun backupService(
        @ApplicationContext c: Context, db: AppDatabase, settings: SettingsRepository, replanner: Replanner, time: TimeProvider,
        lock: com.remedicord.core.lock.AppLockManager,
    ) = BackupService(c, db, settings, replanner, time, c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?") {
        settings.get(com.remedicord.core.lock.AppLockManager.SETTING_TIMEOUT_MIN)?.toLongOrNull()?.let { lock.timeoutMs = it * 60_000 }
    }
}
