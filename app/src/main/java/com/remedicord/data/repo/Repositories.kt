package com.remedicord.data.repo

import androidx.room.withTransaction
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SettingEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the rest of the app. Every user-visible change runs in a transaction together with
 * its audit entry, so the data and its history can never disagree.
 */
class MedicationRepository(
    private val db: AppDatabase,
    private val audit: AuditLogger,
    private val time: TimeProvider,
) {
    private val medications = db.medicationDao()
    private val regimens = db.regimenDao()

    fun observeByStatus(vararg statuses: MedicationStatus): Flow<List<MedicationEntity>> =
        medications.observeByStatus(statuses.toList())

    fun observe(id: Long): Flow<MedicationEntity?> = medications.observe(id)
    suspend fun get(id: Long): MedicationEntity? = medications.get(id)
    suspend fun regimens(medicationId: Long): List<RegimenEntity> = regimens.forMedication(medicationId)
    fun observeRegimens(medicationId: Long): Flow<List<RegimenEntity>> = regimens.observeForMedication(medicationId)
    suspend fun activeRegimen(medicationId: Long, at: Long): RegimenEntity? = regimens.activeAt(medicationId, at)

    /** Creates a medication together with its first regimen. Returns the medication id. */
    suspend fun create(medication: MedicationEntity, firstRegimen: RegimenEntity): Long = db.withTransaction {
        val now = time.nowMillis()
        val med = medication.copy(id = 0, createdAt = now, updatedAt = now)
        val medId = medications.insert(med)
        audit.record(AuditLogger.MEDICATION, medId, AuditAction.CREATE, MedicationEntity.serializer(), null, med.copy(id = medId))
        val reg = firstRegimen.copy(id = 0, medicationId = medId, createdAt = now)
        val regId = regimens.insert(reg)
        audit.record(AuditLogger.REGIMEN, regId, AuditAction.CREATE, RegimenEntity.serializer(), null, reg.copy(id = regId))
        medId
    }

    suspend fun update(medication: MedicationEntity, note: String? = null) = db.withTransaction {
        val before = requireNotNull(medications.get(medication.id)) { "Medication ${medication.id} not found" }
        val after = medication.copy(createdAt = before.createdAt, updatedAt = time.nowMillis())
        medications.update(after)
        audit.record(AuditLogger.MEDICATION, after.id, AuditAction.UPDATE, MedicationEntity.serializer(), before, after, note)
    }

    /**
     * Dose change (PLAN.md §4.2): closes the regimen in force at [newRegimen].effectiveFrom and starts the new one.
     * Returns the new regimen id.
     */
    suspend fun changeRegimen(newRegimen: RegimenEntity, note: String? = null): Long = db.withTransaction {
        val start = newRegimen.effectiveFrom
        regimens.activeAt(newRegimen.medicationId, start)?.let { current ->
            val closed = current.copy(effectiveTo = start)
            regimens.update(closed)
            audit.record(AuditLogger.REGIMEN, closed.id, AuditAction.UPDATE, RegimenEntity.serializer(), current, closed, note)
        }
        val reg = newRegimen.copy(id = 0, createdAt = time.nowMillis())
        val id = regimens.insert(reg)
        audit.record(AuditLogger.REGIMEN, id, AuditAction.CREATE, RegimenEntity.serializer(), null, reg.copy(id = id), note)
        id
    }
}

class DoseRepository(
    private val db: AppDatabase,
    private val audit: AuditLogger,
    private val time: TimeProvider,
) {
    private val doses = db.doseEventDao()

    fun observeBetween(fromInclusive: Long, toExclusive: Long): Flow<List<DoseEventEntity>> =
        doses.observeBetween(fromInclusive, toExclusive)

    fun observeWithStatus(status: DoseStatus): Flow<List<DoseEventEntity>> = doses.observeWithStatus(status)
    suspend fun get(id: Long): DoseEventEntity? = doses.get(id)
    suspend fun inSlot(slotId: Long): List<DoseEventEntity> = doses.inSlot(slotId)
    suspend fun forMedication(medicationId: Long): List<DoseEventEntity> = doses.forMedication(medicationId)
    suspend fun countWithStatus(medicationId: Long, status: DoseStatus): Int = doses.countWithStatus(medicationId, status)

    /** Planner output: system-generated, not audited (nothing a user changed yet). */
    suspend fun insertPlannedIfAbsent(planned: List<DoseEventEntity>): List<Long> = doses.insertIfAbsent(planned)

    suspend fun deleteFuturePlanned(medicationId: Long, fromInclusive: Long): Int =
        doses.deleteFuturePlanned(medicationId, fromInclusive)

    /** A dose recorded directly by the user (PRN "Take now", late log). Audited. */
    suspend fun record(dose: DoseEventEntity, note: String? = null): Long = db.withTransaction {
        val now = time.nowMillis()
        val d = dose.copy(id = 0, createdAt = now, updatedAt = now)
        val id = doses.insert(d)
        audit.record(AuditLogger.DOSE, id, AuditAction.CREATE, DoseEventEntity.serializer(), null, d.copy(id = id), note)
        id
    }

    /** Any change to an existing dose (Taken/Snooze/Skip/edit). Audited. */
    suspend fun update(dose: DoseEventEntity, note: String? = null) = db.withTransaction {
        val before = requireNotNull(doses.get(dose.id)) { "Dose ${dose.id} not found" }
        val after = dose.copy(createdAt = before.createdAt, updatedAt = time.nowMillis())
        doses.update(after)
        audit.record(AuditLogger.DOSE, after.id, AuditAction.UPDATE, DoseEventEntity.serializer(), before, after, note)
    }
}

class SkipRuleRepository(
    private val db: AppDatabase,
    private val audit: AuditLogger,
    private val time: TimeProvider,
) {
    private val rules = db.skipRuleDao()

    suspend fun active(medicationId: Long): List<SkipRuleEntity> = rules.activeForMedication(medicationId)

    suspend fun add(rule: SkipRuleEntity): Long = db.withTransaction {
        val r = rule.copy(id = 0, createdAt = time.nowMillis())
        val id = rules.insert(r)
        audit.record(AuditLogger.SKIP_RULE, id, AuditAction.CREATE, SkipRuleEntity.serializer(), null, r.copy(id = id))
        id
    }

    suspend fun cancel(id: Long, note: String? = null) = db.withTransaction {
        val before = requireNotNull(rules.get(id)) { "Skip rule $id not found" }
        if (before.cancelledAt != null) return@withTransaction
        val after = before.copy(cancelledAt = time.nowMillis())
        rules.update(after)
        audit.record(AuditLogger.SKIP_RULE, id, AuditAction.UPDATE, SkipRuleEntity.serializer(), before, after, note)
    }
}

/** Key/value settings inside the encrypted DB. Keys under [PRIVATE_PREFIX] are never exported (PIN etc.). */
class SettingsRepository(private val db: AppDatabase) {
    private val settings = db.settingDao()

    suspend fun get(key: String): String? = settings.get(key)
    fun observe(key: String): Flow<String?> = settings.observe(key)
    suspend fun put(key: String, value: String) = settings.put(SettingEntity(key, value))
    suspend fun remove(key: String) = settings.delete(key)

    /** Settings that may be written to a backup file (excludes PIN hash / lock state). */
    suspend fun exportable(): List<SettingEntity> = settings.getAll().filterNot { it.key.startsWith(PRIVATE_PREFIX) }

    companion object {
        const val PRIVATE_PREFIX = "private."
    }
}
