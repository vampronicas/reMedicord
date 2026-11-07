package com.remedicord.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.remedicord.domain.schedule.SlotGrouper
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// All receivers are android:exported="false" (PLAN.md §12.1). They still validate everything they are given:
// only known actions, only plausible slot ids, and the database decides what exists.

/** Dependencies looked up only after input validation. Lazy: DB-backed objects are never built before unlock. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverEntryPoint {
    fun engine(): dagger.Lazy<ReminderEngine>
    fun planner(): dagger.Lazy<ReminderPlanner>
    fun scheduler(): AlarmScheduler
    fun directBoot(): DirectBootStore
    fun backup(): dagger.Lazy<com.remedicord.data.backup.BackupService>
}

private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)

private const val BACKUP_NOTIFICATION_ID = 7

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun isUnlocked(context: Context) = context.getSystemService(UserManager::class.java).isUserUnlocked

/** Plausible reminder minute: within ~1 year either side of now. Anything else is ignored. */
internal fun validSlot(slot: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (slot <= 0) return false
    val at = SlotGrouper.slotStart(slot).toEpochMilli()
    return at in (nowMs - 366L * 86_400_000)..(nowMs + 366L * 86_400_000)
}

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try { block() } catch (_: Exception) { /* never crash on a broadcast; next replan recovers */ } finally { pending.finish() }
    }
}

/** An exact reminder alarm fired. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_SLOT) return
        val slot = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT, -1)
        if (!validSlot(slot)) return
        if (!isUnlocked(context)) {
            // Before the first unlock after a restart the encrypted DB is closed: generic reminder only.
            Notifications.postLockedGeneric(context, slot)
            return
        }
        runAsync { deps(context).engine().get().onSlotFired(slot) }
    }
}

/** Taken / Snooze / Skip pressed on a notification. */
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in ACTIONS || !isUnlocked(context)) return
        val slot = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT, -1)
        if (!validSlot(slot)) return
        runAsync { deps(context).engine().get().onAction(slot, action!!) }
    }

    companion object {
        const val ACTION_TAKEN = "com.remedicord.action.TAKEN"
        const val ACTION_SNOOZE = "com.remedicord.action.SNOOZE"
        const val ACTION_SKIP = "com.remedicord.action.SKIP"
        val ACTIONS = setOf(ACTION_TAKEN, ACTION_SNOOZE, ACTION_SKIP)
    }
}

/** Restart, time / time-zone change, app update, daily upkeep → re-plan (PLAN.md §5). */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // DB still closed: re-arm alarms from the timestamp-only list.
                val d = deps(context)
                d.directBoot().read(System.currentTimeMillis()).forEach { d.scheduler().scheduleSlot(it, SlotGrouper.slotStart(it).toEpochMilli()) }
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, AlarmScheduler.ACTION_MAINTENANCE -> {
                if (isUnlocked(context)) runAsync {
                    val d = deps(context)
                    d.planner().get().replan()
                    // PLAN.md §7: gentle reminder when the last backup is older than the chosen interval.
                    if (intent.action == AlarmScheduler.ACTION_MAINTENANCE && d.backup().get().shouldRemind()) {
                        Notifications.postGeneral(context, BACKUP_NOTIFICATION_ID, "Time for a backup", "Your last reMedicord backup is a while ago. Open Settings → Backup.")
                    }
                }
            }
        }
    }
}
