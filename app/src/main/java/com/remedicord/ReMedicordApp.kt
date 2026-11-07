package com.remedicord

import android.app.Application
import android.os.UserManager
import androidx.lifecycle.ProcessLifecycleOwner
import com.remedicord.core.di.AppScope
import com.remedicord.core.lock.AppLockManager
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.reminders.Notifications
import com.remedicord.reminders.ReminderPlanner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class ReMedicordApp : Application() {
    @Inject lateinit var lockManager: AppLockManager
    // Lazy: the encrypted DB must not be opened before the first unlock after a restart (direct boot).
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject lateinit var planner: dagger.Lazy<ReminderPlanner>
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lockManager)
        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            appScope.launch {
                runCatching {
                    settings.get().get(AppLockManager.SETTING_TIMEOUT_MIN)?.toLongOrNull()?.let { lockManager.timeoutMs = it * 60_000 }
                    planner.get().replan()
                }
            }
        }
    }
}
