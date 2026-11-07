package com.remedicord.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.remedicord.MainActivity
import com.remedicord.domain.schedule.SlotGrouper
import java.io.File

/**
 * Exact reminders via setAlarmClock (Doze-exempt, PLAN.md §5). Every PendingIntent is EXPLICIT (fixed
 * component) and IMMUTABLE, so no other app can redirect or alter it (PLAN.md §12.1).
 */
class AlarmScheduler(private val context: Context) {
    private val am: AlarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = am.canScheduleExactAlarms()

    fun scheduleSlot(slotId: Long, triggerAtMs: Long) {
        val op = slotIntent(context, slotId, PendingIntent.FLAG_UPDATE_CURRENT)!!
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMs, show), op)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, op) // fallback; Health screen shows the problem
        }
    }

    fun cancelSlot(slotId: Long) {
        slotIntent(context, slotId, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    /** Daily upkeep (PLAN.md §5 MaintenanceAlarm): inexact, Doze-tolerant. */
    fun scheduleMaintenance(triggerAtMs: Long) {
        val pi = PendingIntent.getBroadcast(
            context, REQ_MAINTENANCE,
            Intent(context, SystemEventReceiver::class.java).setAction(ACTION_MAINTENANCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
    }

    companion object {
        const val ACTION_SLOT = "com.remedicord.action.REMINDER_SLOT"
        const val ACTION_MAINTENANCE = "com.remedicord.action.MAINTENANCE"
        const val EXTRA_SLOT = "slot"
        private const val REQ_MAINTENANCE = 1

        fun slotIntent(context: Context, slotId: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(
            context, slotId.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_SLOT).putExtra(EXTRA_SLOT, slotId),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * PLAN.md §5 / C8: upcoming reminder TIMES ONLY (no names, no medical data) in device-protected storage,
 * so alarms can be re-set after a restart before the first unlock (the encrypted DB is unavailable then).
 */
class DirectBootStore(context: Context) {
    private val file = File(context.createDeviceProtectedStorageContext().filesDir, "upcoming_slots")

    fun write(slotIds: Collection<Long>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(slotIds.sorted().joinToString("\n"))
        tmp.renameTo(file)
    }

    /** Valid future slot ids only; anything malformed is ignored. */
    fun read(nowMs: Long): List<Long> = runCatching {
        file.readLines().mapNotNull { it.trim().toLongOrNull() }.filter { SlotGrouper.slotStart(it).toEpochMilli() > nowMs - 60_000 }.take(500)
    }.getOrDefault(emptyList())
}
