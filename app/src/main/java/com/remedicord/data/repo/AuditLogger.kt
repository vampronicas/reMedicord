package com.remedicord.data.repo

import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.dao.AuditLogDao
import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.domain.model.AuditAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Records before/after snapshots of user-visible changes (PLAN.md §6 audit_log, R12). */
class AuditLogger(
    private val dao: AuditLogDao,
    private val time: TimeProvider,
    private val json: Json = AuditJson,
) {
    suspend fun <T> record(
        entityType: String,
        entityId: Long,
        action: AuditAction,
        serializer: KSerializer<T>,
        before: T?,
        after: T?,
        note: String? = null,
    ) {
        dao.insert(
            AuditLogEntity(
                entityType = entityType,
                entityId = entityId,
                action = action,
                beforeJson = before?.let { json.encodeToString(serializer, it) },
                afterJson = after?.let { json.encodeToString(serializer, it) },
                at = time.nowMillis(),
                note = note,
            ),
        )
    }

    companion object {
        val AuditJson = Json { encodeDefaults = true }
        const val MEDICATION = "medication"
        const val REGIMEN = "regimen"
        const val DOSE = "dose_event"
        const val SKIP_RULE = "skip_rule"
    }
}
