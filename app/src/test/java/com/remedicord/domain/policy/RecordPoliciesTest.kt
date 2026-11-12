package com.remedicord.domain.policy

import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.DoseStatus.MISSED
import com.remedicord.domain.model.DoseStatus.NOT_NEEDED
import com.remedicord.domain.model.DoseStatus.PLANNED
import com.remedicord.domain.model.DoseStatus.SKIPPED
import com.remedicord.domain.model.DoseStatus.TAKEN
import com.remedicord.domain.model.DoseStatus.UNCONFIRMED
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.FlexiblePolicy.Slot
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.FormValidator
import com.remedicord.domain.usecase.MedicationForm
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AmendPolicyTest {
    private val today = LocalDate.of(2026, 11, 9)

    @Test fun fiveDayWindowIncludesTodayAndFourPreviousDays() {
        assertEquals(LocalDate.of(2026, 11, 5), AmendPolicy.firstEditableDay(today))
        assertTrue(AmendPolicy.canAmend(today, today))
        assertTrue(AmendPolicy.canAmend(today.minusDays(4), today))
        assertFalse(AmendPolicy.canAmend(today.minusDays(5), today))
        assertFalse(AmendPolicy.canAmend(today.plusDays(1), today))
    }

    @Test fun unansweredClosesAfterFiveDays() {
        val now = Instant.parse("2026-11-09T12:00:00Z")
        assertTrue(AmendPolicy.shouldAutoClose(UNCONFIRMED, now.minus(Duration.ofDays(5)).minusSeconds(1), now))
        assertFalse(AmendPolicy.shouldAutoClose(UNCONFIRMED, now.minus(Duration.ofDays(4)), now))
        assertFalse(AmendPolicy.shouldAutoClose(MISSED, now.minus(Duration.ofDays(10)), now))
    }
}

class FlexiblePolicyTest {
    private fun i(h: Int) = Instant.parse("2026-11-09T00:00:00Z").plus(Duration.ofHours(h.toLong()))
    private fun day(m: DoseStatus, a: DoseStatus, e: DoseStatus) = listOf(Slot(1, i(8), m), Slot(2, i(13), a), Slot(3, i(19), e))

    @Test fun enoughTakenMakesTheRestNotNeeded() {
        assertEquals(mapOf(3L to NOT_NEEDED), FlexiblePolicy.reconcileDay(2, day(TAKEN, TAKEN, PLANNED), i(14), dayOver = false))
        assertEquals(mapOf(2L to NOT_NEEDED), FlexiblePolicy.reconcileDay(2, day(TAKEN, MISSED, TAKEN), i(20), dayOver = true))
        assertEquals(mapOf(1L to NOT_NEEDED, 3L to NOT_NEEDED), FlexiblePolicy.reconcileDay(1, day(PLANNED, TAKEN, PLANNED), i(14), dayOver = false))
    }

    @Test fun dayOverKeepsOnlyTheShortfall() {
        // 2 a day, only 1 taken: exactly one slot represents the missing dose; the other is not needed.
        assertEquals(mapOf(1L to MISSED, 3L to NOT_NEEDED), FlexiblePolicy.reconcileDay(2, day(PLANNED, TAKEN, PLANNED), i(25), dayOver = true))
        // Phone off all day: 2 unconfirmed kept for the user's answer, 1 not needed.
        assertEquals(mapOf(3L to NOT_NEEDED), FlexiblePolicy.reconcileDay(2, day(UNCONFIRMED, UNCONFIRMED, UNCONFIRMED), i(25), dayOver = true))
        // A skipped slot counts toward the day.
        assertEquals(mapOf(3L to NOT_NEEDED), FlexiblePolicy.reconcileDay(2, day(TAKEN, SKIPPED, PLANNED), i(25), dayOver = true))
        assertTrue(FlexiblePolicy.reconcileDay(2, day(PLANNED, PLANNED, PLANNED), i(9), dayOver = false).isEmpty())
    }

    @Test fun unansweredTimeIsNotMissedWhileTheDayCanStillReachTheTarget() {
        val slots = day(DoseStatus.DUE, PLANNED, PLANNED)
        assertTrue(FlexiblePolicy.canStillReach(2, slots, 1, i(9)))   // afternoon + evening can still give 2
        assertFalse(FlexiblePolicy.canStillReach(2, day(PLANNED, PLANNED, DoseStatus.DUE), 3, i(20)))
        assertTrue(FlexiblePolicy.canStillReach(2, day(TAKEN, DoseStatus.DUE, PLANNED), 2, i(14)))
    }

    @Test fun minimumGapDelaysTheReminder() {
        assertEquals(i(13), FlexiblePolicy.remindAt(i(13), null, 240))
        assertEquals(i(13), FlexiblePolicy.remindAt(i(13), i(8), 0))
        assertEquals(i(15), FlexiblePolicy.remindAt(i(13), i(11), 240))
    }

    private val flexRule = ScheduleRule.FlexibleDaily(2, listOf(ScheduleRule.Period("Morning", LocalTime.of(8, 0)),
        ScheduleRule.Period("Afternoon", LocalTime.of(13, 0)), ScheduleRule.Period("Evening", LocalTime.of(19, 0))))
    private val flexRegimen = Regimen(1, Instant.parse("2026-11-01T00:00:00Z"), null, flexRule)

    @Test fun generatorOffersEveryPeriodButOnlyDailyNumberCounts() {
        val doses = DoseGenerator.generate(listOf(flexRegimen), ZoneOffset.UTC, Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-11T00:00:00Z"))
        assertEquals(6, doses.size)
        assertEquals(4, FlexiblePolicy.countable(doses, listOf(flexRegimen), ZoneOffset.UTC).size)
    }

    @Test fun courseLengthUsesDosesPerDayNotPeriods() {
        val course = CourseInfo(true, CourseMode.DAYS, 7, LocalDate.of(2026, 11, 2))
        assertEquals(14, CoursePolicy.requiredDoses(course, listOf(flexRegimen), ZoneOffset.UTC))
        val upcoming = CoursePolicy.upcomingForProjection(listOf(flexRegimen), ZoneOffset.UTC, Instant.parse("2026-11-09T00:00:00Z"), 3)
        assertEquals(Instant.parse("2026-11-10T08:00:00Z"), upcoming.last()) // 08:00, 13:00, then next day 08:00
    }

    @Test fun flexibleRuleValidationAndJson() {
        assertEquals(flexRule, ScheduleRule.fromJson(ScheduleRule.toJson(flexRule)))
        assertThrows(IllegalArgumentException::class.java) { flexRule.copy(dosesPerDay = 4) }
        assertThrows(IllegalArgumentException::class.java) { flexRule.copy(dosesPerDay = 0) }
        assertThrows(IllegalArgumentException::class.java) { flexRule.copy(periods = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { flexRule.copy(periods = flexRule.periods + flexRule.periods[0]) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.Period(" ", LocalTime.NOON) }
        assertThrows(IllegalArgumentException::class.java) { flexRule.copy(minGapMinutes = 13 * 60) }
    }
}

class HistoryFiltersAndPurposeTest {
    @Test fun summaryPerMedicineIgnoresNotNeeded() {
        val s = HistoryCalc.summarize(listOf(1L to TAKEN, 1L to TAKEN, 1L to NOT_NEEDED, 2L to MISSED, 2L to UNCONFIRMED, 2L to SKIPPED, 2L to PLANNED))
        assertEquals(2, s.size)
        assertEquals(2, s[0].taken); assertEquals(2, s[0].total); assertTrue(s[0].complete)
        assertEquals(1, s[1].missed); assertEquals(1, s[1].needsAnswer); assertEquals(1, s[1].skipped); assertEquals(1, s[1].upcoming); assertFalse(s[1].complete)
    }

    @Test fun purposeIsRequired() {
        val base = MedicationForm(name = "Vit D", rule = ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0))))
        assertTrue(FormValidator.validate(base).contains("Choose why this medicine is taken"))
        val withPurpose = base.copy(purpose = com.remedicord.domain.model.MedicationPurpose.SUPPLEMENT)
        // v1.3: the details text is optional; only the category is required.
        assertEquals(emptyList<String>(), FormValidator.validate(withPurpose))
        assertEquals(emptyList<String>(), FormValidator.validate(withPurpose.copy(purposeNote = "Vitamin D supplement")))
        assertTrue(FormValidator.validate(withPurpose.copy(purposeNote = "x".repeat(201))).isNotEmpty())
    }
}
