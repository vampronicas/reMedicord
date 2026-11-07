package com.remedicord.domain.policy

import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseStatus.MISSED
import com.remedicord.domain.model.DoseStatus.SKIPPED
import com.remedicord.domain.model.DoseStatus.TAKEN
import com.remedicord.domain.model.DoseStatus.UNCONFIRMED
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.PolicyResult
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.schedule.SlotGrouper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoursePolicyTest {
    private val utc = ZoneOffset.UTC
    private val start = LocalDate.of(2026, 3, 1)
    private fun times(vararg s: String) = s.map(LocalTime::parse)
    private fun reg(id: Long, from: String, to: String?, vararg t: String) =
        Regimen(id, Instant.parse(from), to?.let(Instant::parse), ScheduleRule.FixedTimes(times(*t)), BigDecimal.ONE, DoseUnit.TABLET)

    @Test fun daysModeIsDaysTimesDosesPerDay() {
        val course = CourseInfo(true, CourseMode.DAYS, 7, start)
        assertEquals(21, CoursePolicy.requiredDoses(course, listOf(reg(1, "2026-03-01T00:00:00Z", null, "08:00", "14:00", "20:00")), utc))
    }

    @Test fun daysModeRecalculatesAfterMidCourseDoseChange() {
        val course = CourseInfo(true, CourseMode.DAYS, 7, start)
        val regs = listOf(
            reg(1, "2026-03-01T00:00:00Z", "2026-03-04T00:00:00Z", "08:00", "14:00", "20:00"),
            reg(2, "2026-03-04T00:00:00Z", null, "08:00", "20:00"),
        )
        assertEquals(3 * 3 + 4 * 2, CoursePolicy.requiredDoses(course, regs, utc))
    }

    @Test fun dosesModeAndNoCourse() {
        assertEquals(21, CoursePolicy.requiredDoses(CourseInfo(true, CourseMode.DOSES, 21, null), emptyList(), utc))
        assertNull(CoursePolicy.requiredDoses(CourseInfo(false, CourseMode.NONE, null, null), emptyList(), utc))
        assertNull(CoursePolicy.requiredDoses(CourseInfo(false, CourseMode.NONE, 5, null), emptyList(), utc))
        assertNull(CoursePolicy.requiredDoses(CourseInfo(true, CourseMode.DAYS, 7, null), emptyList(), utc))
    }

    @Test fun missedAndSkippedExtendLateTakenRemovesExtension() {
        val p = CoursePolicy.progress(21, List(14) { TAKEN } + listOf(MISSED, SKIPPED))
        assertEquals(14, p.taken); assertEquals(2, p.extension); assertEquals(7, p.remaining); assertFalse(p.isComplete)
        // The missed one is later logged as taken: extension disappears automatically.
        val p2 = CoursePolicy.progress(21, List(15) { TAKEN } + listOf(SKIPPED))
        assertEquals(1, p2.extension); assertEquals(6, p2.remaining)
    }

    @Test fun unconfirmedDosesDoNotMoveTheEndUntilAnswered() {
        val p = CoursePolicy.progress(21, List(10) { TAKEN } + List(3) { UNCONFIRMED })
        assertEquals(8, p.remaining); assertEquals(0, p.extension)
    }

    @Test fun completeWhenAllTaken() {
        assertTrue(CoursePolicy.progress(3, listOf(TAKEN, MISSED, TAKEN, TAKEN)).isComplete)
        assertEquals(0, CoursePolicy.progress(3, listOf(TAKEN, TAKEN, TAKEN, TAKEN)).remaining)
    }

    @Test fun projectedEndIsTheLastRemainingDose() {
        val regs = listOf(reg(1, "2026-03-01T00:00:00Z", null, "08:00", "20:00"))
        val upcoming = CoursePolicy.upcomingForProjection(regs, utc, Instant.parse("2026-03-10T00:00:00Z"), 3)
        assertEquals(3, upcoming.size)
        assertEquals(Instant.parse("2026-03-11T08:00:00Z"), CoursePolicy.projectedEnd(3, upcoming))
        assertNull(CoursePolicy.projectedEnd(0, upcoming))
        assertTrue(CoursePolicy.upcomingForProjection(regs, utc, Instant.parse("2026-03-10T00:00:00Z"), 0).isEmpty())
        assertEquals(100, CoursePolicy.upcomingForProjection(regs, utc, Instant.parse("2026-03-10T00:00:00Z"), 100).size)
    }
}

class StopSkipPolicyTest {
    private val must = CourseInfo(true, CourseMode.DOSES, 21, null)
    private val normal = CourseInfo(false, CourseMode.NONE, null, null)

    @Test fun mustCompleteStopRules() {
        assertTrue(StopPolicy.canStop(must, StopReason.SYMPTOMS_RESOLVED, false) is PolicyResult.Blocked)
        assertTrue(StopPolicy.canStop(must, StopReason.OTHER, false) is PolicyResult.Blocked)
        assertTrue(StopPolicy.canStop(must, StopReason.COURSE_COMPLETED, false) is PolicyResult.Blocked)
        assertEquals(PolicyResult.Allowed, StopPolicy.canStop(must, StopReason.COURSE_COMPLETED, true))
        val doctor = StopPolicy.canStop(must, StopReason.DOCTOR_INSTRUCTED, false)
        assertTrue(doctor is PolicyResult.NeedsConfirmation)
        assertTrue(StopPolicy.canStop(must, StopReason.SIDE_EFFECT, false) is PolicyResult.NeedsConfirmation)
        assertFalse(StopPolicy.validateNote(doctor, " "))
        assertTrue(StopPolicy.validateNote(doctor, "Dr Tan said stop"))
        StopReason.entries.forEach { assertEquals(PolicyResult.Allowed, StopPolicy.canStop(normal, it, false)) }
        assertTrue(StopPolicy.validateNote(PolicyResult.Allowed, null))
    }

    @Test fun pauseBlockedOnlyForMustComplete() {
        assertTrue(StopPolicy.canPause(must) is PolicyResult.Blocked)
        assertEquals(PolicyResult.Allowed, StopPolicy.canPause(normal))
    }

    @Test fun skipModesRespectMustComplete() {
        assertTrue(SkipPolicy.canSkip(must, null) is PolicyResult.NeedsConfirmation)
        SkipMode.entries.forEach { assertTrue(SkipPolicy.canSkip(must, it) is PolicyResult.Blocked) }
        assertEquals(PolicyResult.Allowed, SkipPolicy.canSkip(normal, null))
        SkipMode.entries.forEach { assertEquals(PolicyResult.Allowed, SkipPolicy.canSkip(normal, it)) }
        assertFalse(SkipPolicy.validateReason(""))
        assertFalse(SkipPolicy.validateReason("x".repeat(201)))
        assertTrue(SkipPolicy.validateReason("Felt unwell"))
    }

    private val utc = ZoneOffset.UTC
    private val doses = (0 until 10).map { Instant.parse("2026-03-01T08:00:00Z").plus(Duration.ofHours(12L * it)) } // 2/day, 5 days

    @Test fun skipNextNDoses() {
        val rule = SkipPolicy.Rule(SkipMode.NEXT_N_DOSES, 3, null, Instant.parse("2026-03-02T00:00:00Z"))
        assertEquals(doses.subList(2, 5).toSet(), SkipPolicy.skipped(rule, doses, utc))
    }

    @Test fun skipNDays() {
        val rule = SkipPolicy.Rule(SkipMode.DAYS, 2, null, Instant.parse("2026-03-02T00:00:00Z"))
        assertEquals(doses.subList(2, 6).toSet(), SkipPolicy.skipped(rule, doses, utc))
    }

    @Test fun skipUntilDateInclusive() {
        val rule = SkipPolicy.Rule(SkipMode.UNTIL_DATE, null, LocalDate.of(2026, 3, 3), Instant.parse("2026-03-01T12:00:00Z"))
        assertEquals(doses.subList(1, 6).toSet(), SkipPolicy.skipped(rule, doses, utc))
        assertTrue(SkipPolicy.skipped(rule.copy(untilDate = null), doses, utc).isEmpty())
    }
}

class SmallPoliciesTest {
    private val utc = ZoneOffset.UTC
    private fun i(s: String) = Instant.parse(s)

    @Test fun prnGapAndDailyMax() {
        val now = i("2026-03-01T12:00:00Z")
        assertEquals(PrnPolicy.Result.Ok, PrnPolicy.check(emptyList(), now, 240, 4, utc))
        assertEquals(PrnPolicy.Result.TooSoon(i("2026-03-01T14:00:00Z")), PrnPolicy.check(listOf(i("2026-03-01T10:00:00Z")), now, 240, 4, utc))
        assertEquals(PrnPolicy.Result.Ok, PrnPolicy.check(listOf(i("2026-03-01T08:00:00Z")), now, 240, 4, utc))
        val four = listOf("01", "04", "07", "08").map { i("2026-03-01T${it}:00:00Z") }
        assertEquals(PrnPolicy.Result.DailyMaxReached, PrnPolicy.check(four, now, 0, 4, utc))
        // Yesterday's doses don't count toward today's maximum.
        assertEquals(PrnPolicy.Result.Ok, PrnPolicy.check(four.map { it.minus(Duration.ofDays(1)) }, now, 0, 4, utc))
    }

    @Test fun siteRotationPrefersUnusedThenLeastRecent() {
        val sites = listOf("Abdomen L", "Abdomen R", "Thigh L")
        assertEquals("Abdomen L", SiteRotation.suggestNext(sites, emptyList()))
        assertEquals("Thigh L", SiteRotation.suggestNext(sites, listOf("Abdomen L" to i("2026-03-01T00:00:00Z"), "Abdomen R" to i("2026-03-02T00:00:00Z"))))
        val all = listOf("Abdomen L" to i("2026-03-03T00:00:00Z"), "Abdomen R" to i("2026-03-01T00:00:00Z"), "Thigh L" to i("2026-03-02T00:00:00Z"), "Abdomen R" to i("2026-02-01T00:00:00Z"))
        assertEquals("Abdomen R", SiteRotation.suggestNext(sites, all))
        assertNull(SiteRotation.suggestNext(emptyList(), all))
    }

    @Test fun adherenceExcludesSkippedAndUnconfirmed() {
        assertEquals(75, Adherence.percent(listOf(TAKEN, TAKEN, TAKEN, MISSED, SKIPPED, UNCONFIRMED)))
        assertNull(Adherence.percent(listOf(SKIPPED, DoseStatus.PLANNED)))
        assertEquals(67, Adherence.percent(listOf(TAKEN, TAKEN, MISSED)))
    }

    @Test fun missedAfterRepeatsOrWhenNextDoseIsDue() {
        val now = i("2026-03-01T09:00:00Z")
        assertTrue(MissedPolicy.shouldMarkMissed(3, 3, now, null))
        assertFalse(MissedPolicy.shouldMarkMissed(1, 3, now, i("2026-03-01T20:00:00Z")))
        assertTrue(MissedPolicy.shouldMarkMissed(1, 3, now, now))
        assertTrue(MissedPolicy.isUnconfirmed(DoseStatus.PLANNED, i("2026-03-01T08:00:00Z"), now))
        assertFalse(MissedPolicy.isUnconfirmed(DoseStatus.PLANNED, i("2026-03-01T08:59:00Z"), now))
        assertFalse(MissedPolicy.isUnconfirmed(DoseStatus.DUE, i("2026-03-01T08:00:00Z"), now))
    }

    @Test fun slotsAndEyeDropSequencing() {
        val at = i("2026-03-01T08:00:42Z")
        assertEquals(SlotGrouper.slotId(i("2026-03-01T08:00:00Z")), SlotGrouper.slotId(at))
        assertEquals(i("2026-03-01T08:00:00Z"), SlotGrouper.slotStart(SlotGrouper.slotId(at)))
        val items = listOf(
            SlotGrouper.SlotItem(1, MedicationType.EYE_DROP),
            SlotGrouper.SlotItem(2, MedicationType.PILL),
            SlotGrouper.SlotItem(3, MedicationType.EYE_DROP),
            SlotGrouper.SlotItem(4, MedicationType.EYE_DROP),
        )
        val (now, later) = SlotGrouper.splitForEyeDropGap(items)
        assertEquals(listOf(1L, 2L), now.map { it.doseId })
        assertEquals(listOf(3L, 4L), later.map { it.doseId })
        assertEquals(items.take(2), SlotGrouper.splitForEyeDropGap(items.take(2)).first)
        assertEquals(i("2026-03-01T08:05:00Z"), SlotGrouper.nextEyeDropAt(i("2026-03-01T08:00:00Z"), 5))
    }
}
