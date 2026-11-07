package com.remedicord

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.remedicord.core.di.AppScope
import com.remedicord.core.lock.AppLockManager
import com.remedicord.data.repo.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ReMedicordApp : Application() {
    @Inject lateinit var lockManager: AppLockManager
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(lockManager)
        appScope.launch {
            settings.get().get(AppLockManager.SETTING_TIMEOUT_MIN)?.toLongOrNull()?.let { lockManager.timeoutMs = it * 60_000 }
        }
    }
}
