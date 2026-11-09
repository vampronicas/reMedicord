package com.remedicord.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.PendingReason
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.remedicord.data.db.BigDecimalAsStringSerializer
import com.remedicord.domain.model.AuditAction
import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.FoodRelation
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.model.TimeZoneMode
import java.math.BigDecimal
import kotlinx.serialization.Serializable

// Conventions (PLAN.md §6):
//  - *At fields: UTC instants as epoch milliseconds.
//  - *EpochDay fields: calendar dates as LocalDate.toEpochDay().
//  - All entities are @Serializable for the audit trail (Phase 1) and backup files (Phase 6).

@Serializable
@Entity(tableName = "medication", indices = [Index("status")])
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: MedicationType,
    val instructions: String = "",
    val foodRelation: FoodRelation = FoodRelation.NONE,
    val colorTag: Int? = null,
    val status: MedicationStatus = MedicationStatus.ACTIVE,
    val mustComplete: Boolean = false,
    val courseMode: CourseMode = CourseMode.NONE,
    /** Days or doses depending on [courseMode]. */
    val courseLength: Int? = null,
    val courseStartEpochDay: Long? = null,
    val stopReason: StopReason? = null,
    val stopNote: String? = null,
    val stoppedAt: Long? = null,
    /** Eye / ear drops. */
    val side: BodySide? = null,
    /** Bandage / cream. */
    val bodyLocation: String? = null,
    /** Bandage. */
    val dressingType: String? = null,
    /** Injection / patch site rotation (PLAN.md §4.8). */
    val enabledSites: List<String>? = null,
    val timeZoneMode: TimeZoneMode = TimeZoneMode.LOCAL,
    /** IANA zone id used when [timeZoneMode] is HOME. */
    val homeZone: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Why the medicine is taken (schema v2). */
    @ColumnInfo(defaultValue = "TREATMENT") val purpose: MedicationPurpose = MedicationPurpose.TREATMENT,
    @ColumnInfo(defaultValue = "") val purposeNote: String = "",
    /** "Don't remind me again today": no reminder alarms before this instant (schema v2). */
    val remindersMutedUntil: Long? = null,
)

@Serializable
@Entity(
    tableName = "regimen",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId"), Index(value = ["medicationId", "effectiveFrom"])],
)
data class RegimenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val effectiveFrom: Long,
    val effectiveTo: Long? = null,
    @Serializable(with = BigDecimalAsStringSerializer::class) val doseAmount: BigDecimal,
    val doseUnit: DoseUnit,
    /** Serialized ScheduleRule (sealed type, Phase 2). */
    val scheduleRuleJson: String,
    val changeReason: String,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "dose_event",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["medicationId", "scheduledAt"], unique = true),
        Index("status"),
        Index("remindAt"),
        Index("scheduledAt"),
        Index("regimenId"),
    ],
)
data class DoseEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    /** Null for as-needed (PRN) doses. */
    val regimenId: Long? = null,
    /** Doses sharing a reminder minute share a slot (PLAN.md §4.7). */
    val slotId: Long? = null,
    /** When the dose was originally due (UTC). Null for PRN / unscheduled. */
    val scheduledAt: Long? = null,
    val scheduledZone: String? = null,
    /** When to remind next (snooze / eye-drop gap). Separate from [scheduledAt]. */
    val remindAt: Long? = null,
    val status: DoseStatus,
    val actionAt: Long? = null,
    /** Actual time taken (may differ from [actionAt] for late logging). */
    val takenAt: Long? = null,
    @Serializable(with = BigDecimalAsStringSerializer::class) val amount: BigDecimal? = null,
    /** Eye/ear side, injection/patch site or body location used for this dose. */
    val site: String? = null,
    val skipReason: String? = null,
    val note: String? = null,
    val snoozeCount: Int = 0,
    val repeatCount: Int = 0,
    val isLateLog: Boolean = false,
    val isPrn: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /** Why the dose is UNCONFIRMED (schema v2). */
    val pendingReason: PendingReason? = null,
)

@Serializable
@Entity(
    tableName = "skip_rule",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("medicationId")],
)
data class SkipRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val mode: SkipMode,
    /** NEXT_N_DOSES: number of doses. DAYS: number of days. */
    val count: Int? = null,
    /** UNTIL_DATE: last skipped date (inclusive). */
    val untilEpochDay: Long? = null,
    val startAt: Long,
    val reason: String,
    val note: String? = null,
    val createdAt: Long,
    val cancelledAt: Long? = null,
)

@Serializable
@Entity(tableName = "audit_log", indices = [Index(value = ["entityType", "entityId"]), Index("at")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val action: AuditAction,
    val beforeJson: String? = null,
    val afterJson: String? = null,
    val at: Long,
    val note: String? = null,
)

@Serializable
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
