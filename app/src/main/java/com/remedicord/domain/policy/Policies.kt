package com.remedicord.domain.policy

import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.PolicyResult
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.schedule.DoseGenerator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Facts about a medication's course that the policies need (decoupled from Room entities). */
data class CourseInfo(
    val mustComplete: Boolean,
    val mode: CourseMode,
    val length: Int?,
    val startDate: LocalDate?,
)

/** PLAN.md §4.4: must-complete course rules and the moving end date. */
object CoursePolicy {

    /** Number of TAKEN doses the course needs, or null if the medication has no course length. */
    fun requiredDoses(course: CourseInfo, regimens: List<Regimen>, zone: ZoneId): Int? {
        val length = course.length ?: return null
        return when (course.mode) {
            CourseMode.NONE -> null
            CourseMode.DOSES -> length
            CourseMode.DAYS -> {
                val start = course.startDate ?: return null
                val from = DoseGenerator.resolve(start.atStartOfDay(), zone)
                val to = DoseGenerator.resolve(start.plusDays(length.toLong()).atStartOfDay(), zone)
                DoseGenerator.generate(regimens, zone, from, to).size
            }
        }
    }

    data class Progress(
        val required: Int,
        val taken: Int,
        /** Missed + skipped: each one pushed the end back by one dose. */
        val extension: Int,
        /** Doses still to take (UNCONFIRMED count as provisionally taken until answered). */
        val remaining: Int,
        val isComplete: Boolean,
    )

    /** The extension is always calculated, never stored, so a late "taken" automatically removes it. */
    fun progress(required: Int, statuses: List<DoseStatus>): Progress {
        val taken = statuses.count { it == DoseStatus.TAKEN }
        val unconfirmed = statuses.count { it == DoseStatus.UNCONFIRMED }
        val extension = statuses.count { it == DoseStatus.MISSED || it == DoseStatus.SKIPPED }
        val remaining = (required - taken - unconfirmed).coerceAtLeast(0)
        return Progress(required, taken, extension, remaining, isComplete = taken >= required)
    }

    /** Projected end = time of the last remaining dose, given the upcoming dose times in order. */
    fun projectedEnd(remaining: Int, upcoming: List<Instant>): Instant? =
        if (remaining <= 0) null else upcoming.getOrNull(remaining - 1)

    /** Upcoming dose times long enough to cover [remaining] doses (searches up to ~2 years ahead). */
    fun upcomingForProjection(regimens: List<Regimen>, zone: ZoneId, from: Instant, remaining: Int): List<Instant> {
        if (remaining <= 0) return emptyList()
        val out = ArrayList<Instant>()
        var start = from
        repeat(26) {
            val end = start.plus(Duration.ofDays(28))
            out += DoseGenerator.generate(regimens, zone, start, end).map { it.at }
            if (out.size >= remaining) return out.take(remaining)
            start = end
        }
        return out
    }
}

/** PLAN.md §4.4 / §4.1: stop and pause rules. */
object StopPolicy {
    fun canStop(course: CourseInfo, reason: StopReason, courseComplete: Boolean): PolicyResult {
        if (!course.mustComplete) return PolicyResult.Allowed
        return when (reason) {
            StopReason.COURSE_COMPLETED ->
                if (courseComplete) PolicyResult.Allowed
                else PolicyResult.Blocked("This course must be finished. It completes automatically when all doses are taken.")
            StopReason.SYMPTOMS_RESOLVED, StopReason.OTHER ->
                PolicyResult.Blocked("This medicine must be finished even if you feel better. Only stop it if your doctor tells you to, or if you have a reaction.")
            StopReason.DOCTOR_INSTRUCTED ->
                PolicyResult.NeedsConfirmation("Confirm your doctor told you to stop this course. Please add a note.")
            StopReason.SIDE_EFFECT ->
                PolicyResult.NeedsConfirmation("Stop because of a side effect or reaction? Contact your doctor. Please add a note.")
        }
    }

    fun canPause(course: CourseInfo): PolicyResult =
        if (course.mustComplete) PolicyResult.Blocked("A course that must be completed can't be paused.")
        else PolicyResult.Allowed

    /** A note is mandatory when confirmation is needed. */
    fun validateNote(result: PolicyResult, note: String?): Boolean =
        result !is PolicyResult.NeedsConfirmation || !note.isNullOrBlank()
}

/** PLAN.md §4.5: skip modes. */
object SkipPolicy {
    val PRESET_REASONS = listOf("Doctor advised", "Felt unwell", "Not available", "Procedure / fasting", "Other")

    /** null mode = "this dose only". */
    fun canSkip(course: CourseInfo, mode: SkipMode?): PolicyResult = when {
        mode == null && course.mustComplete ->
            PolicyResult.NeedsConfirmation("Skipping a dose of a must-complete course moves its end date back by one dose.")
        mode == null -> PolicyResult.Allowed
        course.mustComplete -> PolicyResult.Blocked("Only a single dose can be skipped on a course that must be completed.")
        else -> PolicyResult.Allowed
    }

    fun validateReason(reason: String?): Boolean = !reason.isNullOrBlank() && reason.length <= 200

    data class Rule(val mode: SkipMode, val count: Int?, val untilDate: LocalDate?, val startAt: Instant)

    /**
     * Which of [doses] (sorted planned instants, starting at or before the rule's start) a rule skips.
     * NEXT_N_DOSES counts doses from the rule start; DAYS / UNTIL_DATE use local calendar dates.
     */
    fun skipped(rule: Rule, doses: List<Instant>, zone: ZoneId): Set<Instant> {
        val fromStart = doses.filter { !it.isBefore(rule.startAt) }
        return when (rule.mode) {
            SkipMode.NEXT_N_DOSES -> fromStart.take(rule.count ?: 0).toSet()
            SkipMode.DAYS -> {
                val first = rule.startAt.atZone(zone).toLocalDate()
                val last = first.plusDays(((rule.count ?: 0) - 1).toLong())
                fromStart.filter { it.atZone(zone).toLocalDate().let { d -> !d.isBefore(first) && !d.isAfter(last) } }.toSet()
            }
            SkipMode.UNTIL_DATE -> {
                val until = rule.untilDate ?: return emptySet()
                fromStart.filter { !it.atZone(zone).toLocalDate().isAfter(until) }.toSet()
            }
        }
    }
}

/** PLAN.md §4.6: as-needed limits (warnings, the user decides). */
object PrnPolicy {
    sealed interface Result {
        data object Ok : Result
        data class TooSoon(val nextAllowedAt: Instant) : Result
        data object DailyMaxReached : Result
    }

    fun check(takenTimes: List<Instant>, now: Instant, minGapMinutes: Int, maxPerDay: Int, zone: ZoneId): Result {
        val today = now.atZone(zone).toLocalDate()
        if (takenTimes.count { it.atZone(zone).toLocalDate() == today } >= maxPerDay) return Result.DailyMaxReached
        val last = takenTimes.filter { !it.isAfter(now) }.maxOrNull() ?: return Result.Ok
        val next = last.plus(Duration.ofMinutes(minGapMinutes.toLong()))
        return if (now.isBefore(next)) Result.TooSoon(next) else Result.Ok
    }
}

/** PLAN.md §4.8: least-recently-used site, never-used sites first (in the user's order). */
object SiteRotation {
    fun suggestNext(enabledSites: List<String>, history: List<Pair<String, Instant>>): String? {
        if (enabledSites.isEmpty()) return null
        val lastUsed = history.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.max() }
        return enabledSites.firstOrNull { it !in lastUsed } ?: enabledSites.minBy { lastUsed.getValue(it) }
    }
}

/** Adherence = taken / (taken + missed). Skipped (with a reason) and unconfirmed doses are excluded. */
object Adherence {
    fun percent(statuses: List<DoseStatus>): Int? {
        val taken = statuses.count { it == DoseStatus.TAKEN }
        val missed = statuses.count { it == DoseStatus.MISSED }
        if (taken + missed == 0) return null
        return Math.round(taken * 100.0 / (taken + missed)).toInt()
    }
}

/** PLAN.md §4.6: when a due dose with no reply becomes MISSED. */
object MissedPolicy {
    fun shouldMarkMissed(repeatCount: Int, maxRepeats: Int, now: Instant, nextDoseOfSameMedAt: Instant?): Boolean =
        repeatCount >= maxRepeats || (nextDoseOfSameMedAt != null && !now.isBefore(nextDoseOfSameMedAt))

    /** PLANNED doses whose reminder never fired (phone off / restore) → UNCONFIRMED (PLAN.md §4.6). */
    fun isUnconfirmed(status: DoseStatus, remindAt: Instant, now: Instant, grace: Duration = Duration.ofMinutes(2)): Boolean =
        status == DoseStatus.PLANNED && remindAt.plus(grace).isBefore(now)
}
