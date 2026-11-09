package com.remedicord.ui

import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.FoodRelation
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.StopReason
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** English display text (PLAN.md R16: English only). */
object Labels {
    fun type(t: MedicationType) = when (t) {
        MedicationType.PILL -> "Pill / tablet"; MedicationType.LIQUID -> "Liquid"; MedicationType.EYE_DROP -> "Eye drop"
        MedicationType.EAR_DROP -> "Ear drop"; MedicationType.INJECTION -> "Injection"; MedicationType.INHALER -> "Inhaler"
        MedicationType.NASAL_SPRAY -> "Nasal spray"; MedicationType.CREAM -> "Cream / ointment"; MedicationType.PATCH -> "Patch"
        MedicationType.BANDAGE -> "Bandage / dressing"
    }

    /** Action verb follows the type (PLAN.md §8). */
    fun action(t: MedicationType) = when (t) {
        MedicationType.PILL, MedicationType.LIQUID -> "Take"; MedicationType.EYE_DROP, MedicationType.EAR_DROP -> "Instill"
        MedicationType.INJECTION -> "Inject"; MedicationType.INHALER -> "Inhale"; MedicationType.NASAL_SPRAY -> "Spray"
        MedicationType.CREAM, MedicationType.PATCH -> "Apply"; MedicationType.BANDAGE -> "Change dressing"
    }

    fun defaultUnit(t: MedicationType) = when (t) {
        MedicationType.PILL -> DoseUnit.TABLET; MedicationType.LIQUID -> DoseUnit.ML
        MedicationType.EYE_DROP, MedicationType.EAR_DROP -> DoseUnit.DROP; MedicationType.INJECTION -> DoseUnit.UNIT
        MedicationType.INHALER -> DoseUnit.PUFF; MedicationType.NASAL_SPRAY -> DoseUnit.SPRAY
        MedicationType.CREAM -> DoseUnit.APPLICATION; MedicationType.PATCH -> DoseUnit.PATCH; MedicationType.BANDAGE -> DoseUnit.DRESSING
    }

    val defaultSites = mapOf(
        MedicationType.INJECTION to listOf("Abdomen left", "Abdomen right", "Thigh left", "Thigh right", "Arm left", "Arm right"),
        MedicationType.PATCH to listOf("Upper arm left", "Upper arm right", "Chest left", "Chest right", "Upper back", "Hip"),
    )

    fun unit(u: DoseUnit, amount: BigDecimal): String {
        val one = amount.compareTo(BigDecimal.ONE) == 0
        return when (u) {
            DoseUnit.TABLET -> if (one) "tablet" else "tablets"; DoseUnit.CAPSULE -> if (one) "capsule" else "capsules"
            DoseUnit.ML -> "mL"; DoseUnit.DROP -> if (one) "drop" else "drops"; DoseUnit.UNIT -> if (one) "unit" else "units"
            DoseUnit.MG -> "mg"; DoseUnit.PUFF -> if (one) "puff" else "puffs"; DoseUnit.SPRAY -> if (one) "spray" else "sprays"
            DoseUnit.APPLICATION -> if (one) "application" else "applications"; DoseUnit.PATCH -> if (one) "patch" else "patches"
            DoseUnit.DRESSING -> if (one) "dressing" else "dressings"
        }
    }

    fun dose(amount: BigDecimal, u: DoseUnit) = "${amount.stripTrailingZeros().toPlainString()} ${unit(u, amount)}"

    fun purpose(p: com.remedicord.domain.model.MedicationPurpose) = when (p) {
        com.remedicord.domain.model.MedicationPurpose.SUPPLEMENT -> "Supplement / vitamin"
        com.remedicord.domain.model.MedicationPurpose.TREATMENT -> "Treatment (illness, as prescribed)"
        com.remedicord.domain.model.MedicationPurpose.PERIODIC -> "Periodic (e.g. deworming)"
        com.remedicord.domain.model.MedicationPurpose.LONG_TERM -> "Long-term condition"
        com.remedicord.domain.model.MedicationPurpose.PREVENTIVE -> "Preventive"
        com.remedicord.domain.model.MedicationPurpose.OTHER -> "Other"
    }

    fun side(s: BodySide) = when (s) { BodySide.LEFT -> "Left"; BodySide.RIGHT -> "Right"; BodySide.BOTH -> "Both" }
    fun food(f: FoodRelation) = when (f) {
        FoodRelation.NONE -> "No food rule"; FoodRelation.BEFORE_FOOD -> "Before food"
        FoodRelation.WITH_FOOD -> "With food"; FoodRelation.AFTER_FOOD -> "After food"
    }

    fun pending(p: com.remedicord.domain.model.PendingReason?) = when (p) {
        com.remedicord.domain.model.PendingReason.LATER -> "You said you'd take it later"
        com.remedicord.domain.model.PendingReason.PAUSED_TODAY -> "Reminders were paused for the day"
        com.remedicord.domain.model.PendingReason.RESTORED -> "Restored from a backup"
        com.remedicord.domain.model.PendingReason.APP_NOT_RUNNING, null -> "Due while reMedicord wasn't running"
    }

    fun stopReason(r: StopReason) = when (r) {
        StopReason.COURSE_COMPLETED -> "Course completed"; StopReason.SYMPTOMS_RESOLVED -> "Symptoms resolved"
        StopReason.DOCTOR_INSTRUCTED -> "Doctor instructed"; StopReason.SIDE_EFFECT -> "Side effect / reaction"; StopReason.OTHER -> "Other"
    }

    fun status(s: DoseStatus) = when (s) {
        DoseStatus.PLANNED -> "Upcoming"; DoseStatus.DUE -> "Due now"; DoseStatus.SNOOZED -> "Snoozed"; DoseStatus.TAKEN -> "Taken"
        DoseStatus.SKIPPED -> "Skipped"; DoseStatus.MISSED -> "Missed"; DoseStatus.UNCONFIRMED -> "Needs your answer"
        DoseStatus.NOT_NEEDED -> "Not needed"
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

    fun time(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = timeFmt.format(i.atZone(zone))
    fun date(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateFmt.format(i.atZone(zone))
    fun dateTime(i: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dateTimeFmt.format(i.atZone(zone))

    fun rule(r: ScheduleRule): String = when (r) {
        is ScheduleRule.FixedTimes -> r.times.sorted().joinToString(", ") { timeFmt.format(it) } +
            if (r.daysOfWeek.isEmpty()) " daily" else " on " + r.daysOfWeek.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
        is ScheduleRule.EveryNHours -> "Every ${r.intervalHours} h from ${timeFmt.format(r.firstTime)}" +
            (r.quietStart?.let { " (quiet ${timeFmt.format(it)}–${timeFmt.format(r.quietEnd)})" } ?: "")
        is ScheduleRule.EveryNDays -> (if (r.n == 7) "Weekly" else "Every ${r.n} days") + " at " + r.times.sorted().joinToString(", ") { timeFmt.format(it) } + " from ${r.startDate}"
        is ScheduleRule.Cyclic -> "${r.daysOn} days on / ${r.daysOff} off at " + r.times.sorted().joinToString(", ") { timeFmt.format(it) }
        is ScheduleRule.FlexibleDaily -> "${r.dosesPerDay}× a day, any of " + r.periods.joinToString(", ") { "${it.name} ${timeFmt.format(it.time)}" } +
            (if (r.minGapMinutes > 0) " (≥ ${r.minGapMinutes / 60} h apart)" else "")
        is ScheduleRule.AsNeeded -> "As needed (≥ ${r.minGapMinutes / 60.0} h apart, max ${r.maxPerDay}/day)".replace(".0 h", " h")
    }
}
