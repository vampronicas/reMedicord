package com.remedicord.domain.usecase

import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.FoodRelation
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.TimeZoneMode
import com.remedicord.domain.schedule.TaperBuilder
import java.math.BigDecimal
import java.time.LocalDate

/** Everything the add-medication wizard collects (PLAN.md S4). */
data class MedicationForm(
    val name: String = "",
    val type: MedicationType = MedicationType.PILL,
    /** Why the medicine is taken: required category + short reason (e.g. "Vitamin D supplement", "Throat infection"). */
    val purpose: com.remedicord.domain.model.MedicationPurpose? = null,
    val purposeNote: String = "",
    /** E8: one of 8 soft colours (index), shown on icons and cards. */
    val colorIndex: Int? = null,
    val instructions: String = "",
    val foodRelation: FoodRelation = FoodRelation.NONE,
    val side: BodySide? = null,
    val bodyLocation: String = "",
    val dressingType: String = "",
    val sites: List<String> = emptyList(),
    val timeZoneMode: TimeZoneMode = TimeZoneMode.LOCAL,
    val amount: BigDecimal = BigDecimal.ONE,
    val unit: DoseUnit = DoseUnit.TABLET,
    val rule: ScheduleRule? = null,
    /** Non-empty = tapering; [rule]/[amount] are then ignored. */
    val taperSteps: List<TaperBuilder.Step> = emptyList(),
    val startDate: LocalDate = LocalDate.now(),
    val mustComplete: Boolean = false,
    val courseMode: CourseMode = CourseMode.NONE,
    val courseLength: Int? = null,
)

/** Input validation: length limits, allowed characters, ranges (PLAN.md §12.1 "Malicious text in fields"). */
object FormValidator {
    const val MAX_NAME = 80
    const val MAX_TEXT = 500
    const val MAX_SHORT = 60
    const val MAX_SITES = 12
    private val MAX_AMOUNT = BigDecimal(10_000)

    /** Printable text only: no control characters except newline in long text. */
    fun cleanText(s: String, allowNewlines: Boolean = false): Boolean =
        s.none { it.isISOControl() && !(allowNewlines && it == '\n') } && s.none { isBidiControl(it) }

    /** Unicode bidirectional embedding/override/isolate controls (used to disguise text, "Trojan Source"). */
    private fun isBidiControl(c: Char): Boolean = c.code in 0x202A..0x202E || c.code in 0x2066..0x2069

    fun validate(f: MedicationForm): List<String> = buildList {
        val name = f.name.trim()
        if (name.isEmpty()) add("Enter a name")
        if (name.length > MAX_NAME) add("Name is too long (max $MAX_NAME)")
        if (!cleanText(name)) add("Name contains invalid characters")
        if (f.purpose == null) add("Choose why this medicine is taken")
        if (f.purposeNote.isBlank()) add("Describe why it's needed (e.g. \"Vitamin D supplement\", \"Throat infection\")")
        if (f.colorIndex != null && f.colorIndex !in 0..7) add("Invalid colour")
        if (f.purposeNote.length > 200 || !cleanText(f.purposeNote)) add("Reason is too long or contains invalid characters")
        if (f.instructions.length > MAX_TEXT || !cleanText(f.instructions, allowNewlines = true)) add("Instructions are too long or contain invalid characters")
        listOf(f.bodyLocation, f.dressingType).forEach { if (it.length > MAX_SHORT || !cleanText(it)) add("A detail field is too long or contains invalid characters") }
        if (f.sites.size > MAX_SITES || f.sites.any { it.isBlank() || it.length > MAX_SHORT || !cleanText(it) }) add("Invalid site list")
        if ((f.type == MedicationType.EYE_DROP || f.type == MedicationType.EAR_DROP) && f.side == null) add("Choose left, right or both")
        if (f.taperSteps.isEmpty()) {
            if (f.rule == null) add("Choose a schedule")
            if (f.amount <= BigDecimal.ZERO || f.amount > MAX_AMOUNT) add("Enter a dose amount above 0")
        } else {
            if (f.taperSteps.size > 20) add("A taper can have at most 20 steps")
            if (f.taperSteps.any { it.amount <= BigDecimal.ZERO || it.amount > MAX_AMOUNT || it.days !in 1..365 }) add("Each taper step needs an amount above 0 and 1–365 days")
        }
        if (f.mustComplete && f.courseMode == CourseMode.NONE) add("A must-complete course needs a length")
        if (f.courseMode != CourseMode.NONE) {
            val max = if (f.courseMode == CourseMode.DAYS) 365 else 1000
            if (f.courseLength == null || f.courseLength !in 1..max) add("Course length must be 1–$max")
        }
        if (f.rule is ScheduleRule.AsNeeded && f.courseMode != CourseMode.NONE) add("An as-needed medicine can't have a course length")
    }.distinct()
}

/** Outcome of a user action, shown by the UI. */
sealed interface ActionResult {
    data object Done : ActionResult
    data class NeedsConfirmation(val message: String) : ActionResult
    data class Blocked(val message: String) : ActionResult
    data class Invalid(val errors: List<String>) : ActionResult
}

/** Implemented by the reminder engine (Phase 4); called after every schedule-affecting change. */
fun interface Replanner {
    suspend fun replan()
}
