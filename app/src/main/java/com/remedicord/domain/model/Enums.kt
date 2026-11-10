package com.remedicord.domain.model

import kotlinx.serialization.Serializable

/** What kind of item is tracked (PLAN.md R1). Stored by name: never rename constants, only add. */
@Serializable
enum class MedicationType {
    PILL, LIQUID, EYE_DROP, EAR_DROP, INJECTION, INHALER, NASAL_SPRAY, CREAM, PATCH, BANDAGE
}

@Serializable
enum class MedicationStatus { ACTIVE, PAUSED, STOPPED, COMPLETED }

@Serializable
enum class FoodRelation { NONE, BEFORE_FOOD, WITH_FOOD, AFTER_FOOD }

/** Eye / ear side. */
@Serializable
enum class BodySide { LEFT, RIGHT, BOTH }

/** Must-complete course length mode (PLAN.md §4.4). */
@Serializable
enum class CourseMode { NONE, DAYS, DOSES }

@Serializable
enum class StopReason { COURSE_COMPLETED, SYMPTOMS_RESOLVED, DOCTOR_INSTRUCTED, SIDE_EFFECT, OTHER, REPLACED }

@Serializable
enum class DoseUnit { TABLET, CAPSULE, ML, DROP, UNIT, MG, PUFF, SPRAY, APPLICATION, PATCH, DRESSING }

/** Dose state machine (PLAN.md §4.6). */
@Serializable
enum class DoseStatus {
    PLANNED, DUE, SNOOZED, TAKEN, SKIPPED, MISSED,
    /** Needs the user's answer (phone off, "Later", reminders paused for the day). Auto-closed as MISSED after 5 days. */
    UNCONFIRMED,
    /** Flexible schedule: this optional time wasn't needed because the day's doses were already taken. */
    NOT_NEEDED,
}

/** Why a dose is waiting for an answer (shown in the review prompt). */
@Serializable
enum class PendingReason { APP_NOT_RUNNING, LATER, PAUSED_TODAY, RESTORED }

/** Why the medicine is taken (required when adding). */
@Serializable
enum class MedicationPurpose { SUPPLEMENT, TREATMENT, PERIODIC, LONG_TERM, PREVENTIVE, OTHER }

/** Skip rule mode (PLAN.md §4.5). "This dose only" is a direct SKIPPED status, not a rule. */
@Serializable
enum class SkipMode { NEXT_N_DOSES, DAYS, UNTIL_DATE }

/** Per-medicine time-zone behaviour (PLAN.md §4.9). */
@Serializable
enum class TimeZoneMode { LOCAL, HOME }

@Serializable
enum class AuditAction { CREATE, UPDATE, DELETE }

/** v1.4: how a replacement relates to the medicine it replaces. */
@Serializable
enum class ReplaceKind { UPGRADE, DOWNGRADE, SWITCH }
