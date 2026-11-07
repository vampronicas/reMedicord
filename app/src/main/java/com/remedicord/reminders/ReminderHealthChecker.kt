package com.remedicord.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** PLAN.md S13: everything that must be true for reminders to arrive on time, with a plain explanation. */
class ReminderHealthChecker(private val context: Context) {
    enum class Key { NOTIFICATIONS, CHANNEL, EXACT_ALARMS, FULL_SCREEN, BATTERY }

    data class Item(val key: Key, val title: String, val ok: Boolean, val why: String, val fix: Intent?)

    private val pkg: Uri get() = Uri.fromParts("package", context.packageName, null)

    fun check(): List<Item> {
        val nm = context.getSystemService(NotificationManager::class.java)
        val am = context.getSystemService(AlarmManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val channelOn = nm.getNotificationChannel(Notifications.CH_REMINDERS)?.importance != NotificationManager.IMPORTANCE_NONE
        return listOf(
            Item(Key.NOTIFICATIONS, "Notifications allowed", NotificationManagerCompat.from(context).areNotificationsEnabled(),
                "Reminders are shown as notifications. Without this permission you won't see or hear them.",
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)),
            Item(Key.CHANNEL, "Reminder sound on", channelOn,
                "The \"Medication reminders\" notification category must not be turned off.",
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CH_REMINDERS)),
            Item(Key.EXACT_ALARMS, "Exact alarms allowed", am.canScheduleExactAlarms(),
                "Lets reminders ring at the exact minute, like the Clock app, even when the phone is in deep sleep (Doze).",
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)),
            Item(Key.FULL_SCREEN, "Full-screen alarm allowed", Notifications.canUseFullScreen(context),
                "Shows the reminder over the lock screen like an alarm, so you notice it. On the lock screen it never shows medicine names.",
                if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null),
            Item(Key.BATTERY, "Battery: Unrestricted", pm.isIgnoringBatteryOptimizations(context.packageName),
                "Some phones shut down apps in the background to save battery. If that happens, your medicine reminders won't ring. " +
                    "Set reMedicord to \"Unrestricted\" so reminders always arrive on time.",
                batteryIntent()),
        )
    }

    /** Personal, sideloaded app whose core purpose is time-critical reminders (PLAN.md §5). */
    @SuppressLint("BatteryLife")
    private fun batteryIntent() = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
}
