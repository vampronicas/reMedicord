package com.remedicord.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.remedicord.MainActivity
import com.remedicord.R
import com.remedicord.domain.schedule.ReminderText

/** Notification channels and builders (PLAN.md §3 privacy, §4.6 actions). */
object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_GENERAL = "general"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Medication reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders to take, apply or change your medicines"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GENERAL, "General", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Backup reminders and app messages"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /** Android 13+ needs the runtime permission; Android 12 only has the per-app "notifications on" switch. */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** Generic lock-screen version: no medicine data. */
    private fun publicVersion(context: Context) = NotificationCompat.Builder(context, CH_REMINDERS)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(ReminderText.PUBLIC_TITLE)
        .setContentText(ReminderText.PUBLIC_TEXT)
        .build()

    private fun alarmActivityIntent(context: Context, slotId: Long, requestOffset: Int) = PendingIntent.getActivity(
        context, (slotId * 4 + requestOffset).toInt(),
        Intent(context, AlarmActivity::class.java).putExtra(AlarmScheduler.EXTRA_SLOT, slotId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, slotId: Long, action: String, code: Int) = PendingIntent.getBroadcast(
        context, (slotId * 4 + code).toInt(),
        Intent(context, DoseActionReceiver::class.java).setAction(action).putExtra(AlarmScheduler.EXTRA_SLOT, slotId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * One grouped notification per slot. Taken / Skip require the phone to be unlocked
     * (setAuthenticationRequired, PLAN.md A2); Snooze works while locked.
     */
    fun postSlot(context: Context, slotId: Long, lines: List<ReminderText.Line>, snoozeMinutes: Int, canSnooze: Boolean, fullScreen: Boolean) {
        if (!canPost(context) || lines.isEmpty()) return
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(ReminderText.line(it)) } }
        val b = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ReminderText.title(lines))
            .setContentText(ReminderText.line(lines.first()))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context))
            .setAutoCancel(false)
            .setContentIntent(alarmActivityIntent(context, slotId, 0))
            .addAction(
                NotificationCompat.Action.Builder(0, if (lines.size == 1) "Taken" else "Taken (all)", actionIntent(context, slotId, DoseActionReceiver.ACTION_TAKEN, 1))
                    .setAuthenticationRequired(true).build(),
            )
        if (canSnooze) b.addAction(NotificationCompat.Action.Builder(0, "Snooze $snoozeMinutes min", actionIntent(context, slotId, DoseActionReceiver.ACTION_SNOOZE, 2)).build())
        b.addAction(
            NotificationCompat.Action.Builder(0, "Skip", actionIntent(context, slotId, DoseActionReceiver.ACTION_SKIP, 3))
                .setAuthenticationRequired(true).build(),
        )
        if (fullScreen && canUseFullScreen(context)) b.setFullScreenIntent(alarmActivityIntent(context, slotId, 0), true)
        notifySafely(context, slotId.toInt(), b.build())
    }

    /** Before the first unlock after a restart: generic text only, opens the app (PLAN.md §5). */
    fun postLockedGeneric(context: Context, slotId: Long) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ReminderText.PUBLIC_TITLE)
            .setContentText(ReminderText.PUBLIC_TEXT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .build()
        notifySafely(context, slotId.toInt(), n)
    }

    fun postGeneral(context: Context, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifySafely(context, id, NotificationCompat.Builder(context, CH_GENERAL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    @android.annotation.SuppressLint("MissingPermission") // checked by canPost() for every API level
    private fun notifySafely(context: Context, id: Int, n: android.app.Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(id, n)
    }
}
