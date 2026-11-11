package com.remedicord.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.usecase.Replanner
import com.remedicord.reminders.ReminderSettingsRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PLAN.md §7. Export to a user-chosen file (system file picker), validate + preview, restore.
 * Before any restore the current data is saved as a private snapshot (last 3 kept).
 */
class BackupService(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val replanner: Replanner,
    private val time: TimeProvider,
    private val appVersion: String,
) {
    data class Preview(val exportedAt: Instant, val medications: Int, val doses: Int, val appVersion: String)

    private val schemaVersion get() = db.openHelper.readableDatabase.version
    private val snapshotDir get() = File(context.noBackupFilesDir, "snapshots").apply { mkdirs() }

    private suspend fun snapshot() = db.withTransaction {
        BackupSnapshot(
            db.settingDao().getAll(), db.medicationDao().getAll(), db.regimenDao().getAll(),
            db.doseEventDao().getAll(), db.skipRuleDao().getAll(), db.auditLogDao().getAll(),
        )
    }

    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        val files = BackupCodec.encode(snapshot(), appVersion, schemaVersion, time.nowMillis(), time.zone())
        out.use { BackupCodec.writeZip(files, it) }
        settings.put(LAST_BACKUP, time.nowMillis().toString())
    }

    /** Validates fully; nothing is changed. */
    suspend fun preview(input: InputStream): Preview = withContext(Dispatchers.IO) {
        val (m, s) = BackupCodec.decode(input.use { BackupCodec.readZip(it) }, schemaVersion)
        Preview(Instant.ofEpochMilli(m.exportedAt), s.medications.size, s.doses.size, m.appVersion)
    }

    /** Replaces all data in ONE transaction (all or nothing). The PIN and device settings are kept. */
    suspend fun restore(input: InputStream) = withContext(Dispatchers.IO) {
        val (m, s) = BackupCodec.decode(input.use { BackupCodec.readZip(it) }, schemaVersion)
        saveSnapshot()
        val nowMs = time.nowMillis()
        db.withTransaction {
            db.doseEventDao().deleteAll(); db.skipRuleDao().deleteAll(); db.regimenDao().deleteAll()
            db.medicationDao().deleteAll(); db.auditLogDao().deleteAll(); db.settingDao().deleteExportable()
            db.medicationDao().insertAll(s.medications)
            db.regimenDao().insertAll(s.regimens)
            // Doses that were waiting at export time can't be known now: they go to the review prompt.
            db.doseEventDao().insertAll(s.doses.map { d ->
                if (d.status in setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED) && (d.scheduledAt ?: Long.MAX_VALUE) < nowMs)
                    d.copy(status = DoseStatus.UNCONFIRMED, remindAt = null, pendingReason = com.remedicord.domain.model.PendingReason.RESTORED) else d
            })
            db.skipRuleDao().insertAll(s.skipRules)
            db.auditLogDao().insertAll(s.audit)
            s.settings.forEach { db.settingDao().put(it) }
            // Fill the gap from the export time to now as UNCONFIRMED (PLAN.md §4.6).
            settings.put(ReminderSettingsRepository.PLANNED_UNTIL, m.exportedAt.toString())
        }
        replanner.replan()
    }

    // ------------------------------------------------------------ snapshots (private, last 3)

    data class SnapshotInfo(val file: File, val takenAt: Instant)

    fun snapshots(): List<SnapshotInfo> = snapshotDir.listFiles { f -> f.name.matches(Regex("pre-restore-\\d+\\.zip")) }.orEmpty()
        .map { SnapshotInfo(it, Instant.ofEpochMilli(it.name.removePrefix("pre-restore-").removeSuffix(".zip").toLong())) }
        .sortedByDescending { it.takenAt }

    suspend fun saveSnapshot() = withContext(Dispatchers.IO) {
        val f = File(snapshotDir, "pre-restore-${time.nowMillis()}.zip")
        f.outputStream().use { BackupCodec.writeZip(BackupCodec.encode(snapshot(), appVersion, schemaVersion, time.nowMillis(), time.zone()), it) }
        snapshots().drop(KEEP_SNAPSHOTS).forEach { it.file.delete() }
    }

    suspend fun restoreSnapshot(info: SnapshotInfo) {
        require(info.file.parentFile == snapshotDir) { "Not a snapshot" }
        restore(info.file.inputStream())
    }

    // ------------------------------------------------------------ reminder (PLAN.md §7)

    suspend fun lastBackupAt(): Instant? = settings.get(LAST_BACKUP)?.toLongOrNull()?.let(Instant::ofEpochMilli)
    suspend fun reminderDays(): Int = settings.get(REMINDER_DAYS)?.toIntOrNull()?.takeIf { it in REMINDER_CHOICES } ?: 30
    suspend fun setReminderDays(d: Int) = settings.put(REMINDER_DAYS, d.toString())

    /** True at most once a day when the last backup is older than the chosen interval (0 = off). */
    suspend fun shouldRemind(): Boolean {
        val days = reminderDays()
        if (days == 0) return false
        val now = time.now()
        val last = lastBackupAt() ?: settings.get(FIRST_SEEN)?.toLongOrNull()?.let(Instant::ofEpochMilli)
            ?: now.also { settings.put(FIRST_SEEN, it.toEpochMilli().toString()) }
        val lastReminded = settings.get(LAST_REMINDED)?.toLongOrNull()?.let(Instant::ofEpochMilli)
        val due = Duration.between(last, now) >= Duration.ofDays(days.toLong()) &&
            (lastReminded == null || Duration.between(lastReminded, now) >= Duration.ofDays(1))
        if (due) settings.put(LAST_REMINDED, now.toEpochMilli().toString())
        return due
    }

    companion object {
        const val KEEP_SNAPSHOTS = 3
        const val LAST_BACKUP = "backup.lastAt"
        const val REMINDER_DAYS = "backup.reminderDays"
        const val LAST_REMINDED = "backup.lastRemindedAt"
        const val FIRST_SEEN = "backup.firstSeenAt"
        val REMINDER_CHOICES = listOf(0, 7, 14, 30)
    }
}
