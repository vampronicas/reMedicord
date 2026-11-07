package com.remedicord.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SettingEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import kotlinx.coroutines.flow.Flow

// Security: only Room's compile-time-checked, parameterised queries. No SQL is ever built from strings.

@Dao
interface MedicationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<MedicationEntity>)

    @Query("DELETE FROM medication")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(medication: MedicationEntity): Long

    @Update
    suspend fun update(medication: MedicationEntity)

    @Query("SELECT * FROM medication WHERE id = :id")
    suspend fun get(id: Long): MedicationEntity?

    @Query("SELECT * FROM medication WHERE id = :id")
    fun observe(id: Long): Flow<MedicationEntity?>

    @Query("SELECT * FROM medication WHERE status IN (:statuses) ORDER BY name COLLATE NOCASE")
    fun observeByStatus(statuses: List<MedicationStatus>): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication ORDER BY id")
    suspend fun getAll(): List<MedicationEntity>
}

@Dao
interface RegimenDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<RegimenEntity>)

    @Query("DELETE FROM regimen")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(regimen: RegimenEntity): Long

    @Update
    suspend fun update(regimen: RegimenEntity)

    @Query("SELECT * FROM regimen WHERE id = :id")
    suspend fun get(id: Long): RegimenEntity?

    @Query("SELECT * FROM regimen WHERE medicationId = :medicationId ORDER BY effectiveFrom")
    suspend fun forMedication(medicationId: Long): List<RegimenEntity>

    @Query("SELECT * FROM regimen WHERE medicationId = :medicationId ORDER BY effectiveFrom")
    fun observeForMedication(medicationId: Long): Flow<List<RegimenEntity>>

    /** The regimen in force at [at]: started at or before it and not yet ended. */
    @Query(
        """SELECT * FROM regimen WHERE medicationId = :medicationId AND effectiveFrom <= :at
           AND (effectiveTo IS NULL OR effectiveTo > :at) ORDER BY effectiveFrom DESC LIMIT 1""",
    )
    suspend fun activeAt(medicationId: Long, at: Long): RegimenEntity?

    @Query("SELECT * FROM regimen ORDER BY id")
    suspend fun getAll(): List<RegimenEntity>
}

@Dao
interface DoseEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<DoseEventEntity>)

    @Query("DELETE FROM dose_event")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(dose: DoseEventEntity): Long

    /** For the planner: inserts new planned doses, skipping ones already present (unique med+time). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(doses: List<DoseEventEntity>): List<Long>

    @Update
    suspend fun update(dose: DoseEventEntity)

    @Upsert
    suspend fun upsert(dose: DoseEventEntity): Long

    @androidx.room.Delete
    suspend fun delete(dose: DoseEventEntity)

    @Query("SELECT * FROM dose_event WHERE id = :id")
    suspend fun get(id: Long): DoseEventEntity?

    @Query("SELECT * FROM dose_event WHERE medicationId = :medicationId ORDER BY scheduledAt")
    suspend fun forMedication(medicationId: Long): List<DoseEventEntity>

    /** Doses whose due time (or taken time, for PRN) falls in [fromInclusive, toExclusive). */
    @Query(
        """SELECT * FROM dose_event
           WHERE COALESCE(scheduledAt, takenAt) >= :fromInclusive AND COALESCE(scheduledAt, takenAt) < :toExclusive
           ORDER BY COALESCE(scheduledAt, takenAt), medicationId""",
    )
    fun observeBetween(fromInclusive: Long, toExclusive: Long): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_event WHERE status = :status ORDER BY scheduledAt")
    suspend fun withStatus(status: DoseStatus): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event WHERE status = :status ORDER BY scheduledAt")
    fun observeWithStatus(status: DoseStatus): Flow<List<DoseEventEntity>>

    @Query("SELECT * FROM dose_event WHERE slotId = :slotId ORDER BY medicationId")
    suspend fun inSlot(slotId: Long): List<DoseEventEntity>

    @Query("SELECT COUNT(*) FROM dose_event WHERE medicationId = :medicationId AND status = :status")
    suspend fun countWithStatus(medicationId: Long, status: DoseStatus): Int

    @Query("DELETE FROM dose_event WHERE status = 'PLANNED' AND medicationId = :medicationId AND scheduledAt >= :fromInclusive")
    suspend fun deleteFuturePlanned(medicationId: Long, fromInclusive: Long): Int

    /** Future doses the planner owns: PLANNED, or SKIPPED by a skip rule (no user action). */
    @Query(
        """DELETE FROM dose_event WHERE medicationId = :medicationId AND scheduledAt >= :fromInclusive
           AND (status = 'PLANNED' OR (status = 'SKIPPED' AND actionAt IS NULL))""",
    )
    suspend fun deleteFutureAuto(medicationId: Long, fromInclusive: Long): Int

    /** Doses waiting for a reminder (PLANNED / SNOOZED / DUE) with remindAt in [from, to). */
    @Query(
        """SELECT * FROM dose_event WHERE status IN ('PLANNED','SNOOZED','DUE') AND remindAt >= :fromInclusive
           AND remindAt < :toExclusive ORDER BY remindAt""",
    )
    suspend fun awaitingReminder(fromInclusive: Long, toExclusive: Long): List<DoseEventEntity>

    /** Doses in an original slot that still need an answer. */
    @Query("SELECT * FROM dose_event WHERE slotId = :slotId AND status IN ('DUE','SNOOZED','PLANNED') ORDER BY remindAt, medicationId")
    suspend fun openInSlot(slotId: Long): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event WHERE status IN ('DUE','SNOOZED')")
    suspend fun dueOrSnoozed(): List<DoseEventEntity>

    @Query("SELECT * FROM dose_event ORDER BY id")
    suspend fun getAll(): List<DoseEventEntity>
}

@Dao
interface SkipRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<SkipRuleEntity>)

    @Query("DELETE FROM skip_rule")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: SkipRuleEntity): Long

    @Update
    suspend fun update(rule: SkipRuleEntity)

    @Query("SELECT * FROM skip_rule WHERE id = :id")
    suspend fun get(id: Long): SkipRuleEntity?

    @Query("SELECT * FROM skip_rule WHERE medicationId = :medicationId AND cancelledAt IS NULL ORDER BY startAt")
    suspend fun activeForMedication(medicationId: Long): List<SkipRuleEntity>

    @Query("SELECT * FROM skip_rule ORDER BY id")
    suspend fun getAll(): List<SkipRuleEntity>
}

@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<AuditLogEntity>)

    @Query("DELETE FROM audit_log")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log WHERE entityType = :entityType AND entityId = :entityId ORDER BY at, id")
    suspend fun forEntity(entityType: String, entityId: Long): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun getAll(): List<AuditLogEntity>
}

@Dao
interface SettingDao {
    /** Removes user settings but keeps this phone's private/device keys (PIN, planner state). */
    @Query("DELETE FROM setting WHERE `key` NOT LIKE 'private.%' AND `key` NOT LIKE 'planner.%' AND `key` NOT LIKE 'onboarding.%' AND `key` NOT LIKE 'backup.%'")
    suspend fun deleteExportable()

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM setting WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM setting ORDER BY `key`")
    suspend fun getAll(): List<SettingEntity>
}
