package com.remedicord.domain.policy

import com.remedicord.domain.model.DoseStatus.DUE
import com.remedicord.domain.model.DoseStatus.MISSED
import com.remedicord.domain.model.DoseStatus.NOT_NEEDED
import com.remedicord.domain.model.DoseStatus.PLANNED
import com.remedicord.domain.model.DoseStatus.SKIPPED
import com.remedicord.domain.model.DoseStatus.TAKEN
import com.remedicord.domain.model.DoseStatus.UNCONFIRMED
import com.remedicord.domain.model.MedicationPurpose
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.usecase.FormValidator
import com.remedicord.domain.usecase.MedicationForm
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySectionsTest {
    private fun t(s: String) = LocalTime.parse(s)

    @Test fun boundaries() {
        assertEquals(DaySection.NIGHT, DaySection.of(t("04:59")))
        assertEquals(DaySection.MORNING, DaySection.of(t("05:00")))
        assertEquals(DaySection.MORNING, DaySection.of(t("11:59")))
        assertEquals(DaySection.AFTERNOON, DaySection.of(t("12:00")))
        assertEquals(DaySection.AFTERNOON, DaySection.of(t("16:59")))
        assertEquals(DaySection.EVENING, DaySection.of(t("17:00")))
        assertEquals(DaySection.EVENING, DaySection.of(t("20:59")))
        assertEquals(DaySection.NIGHT, DaySection.of(t("21:00")))
        assertEquals(DaySection.NIGHT, DaySection.of(t("00:30")))
    }

    @Test fun flexiblePeriodNameWins() {
        assertEquals(DaySection.EVENING, DaySection.of(t("16:30"), "Evening"))
        assertEquals(DaySection.AFTERNOON, DaySection.of(t("16:30"), "after work")) // unknown name → by time
        assertEquals(DaySection.MORNING, DaySection.of(t("09:00"), null))
    }

    @Test fun startingStateOnToday() {
        val now = t("19:30") // evening
        assertFalse(DaySection.startsExpanded(DaySection.MORNING, listOf(TAKEN, TAKEN, NOT_NEEDED), now, isToday = true))
        assertTrue(DaySection.startsExpanded(DaySection.AFTERNOON, listOf(TAKEN, UNCONFIRMED), now, isToday = true))
        assertTrue(DaySection.startsExpanded(DaySection.MORNING, listOf(TAKEN, DUE), now, isToday = true))
        assertTrue(DaySection.startsExpanded(DaySection.EVENING, listOf(TAKEN), now, isToday = true))      // current section
        assertTrue(DaySection.startsExpanded(DaySection.NIGHT, listOf(PLANNED), now, isToday = true))      // still ahead
        assertFalse(DaySection.startsExpanded(DaySection.AFTERNOON, listOf(MISSED, SKIPPED), now, isToday = true))
        assertTrue(DaySection.startsExpanded(DaySection.MORNING, listOf(TAKEN), now, isToday = false))     // History: always open
    }

    @Test fun undoneTakenBringsBackFlexibleTimes() {
        val base = Instant.parse("2026-11-09T00:00:00Z")
        val slots = listOf(
            FlexiblePolicy.Slot(1, base.plus(Duration.ofHours(8)), TAKEN),
            FlexiblePolicy.Slot(2, base.plus(Duration.ofHours(13)), PLANNED),
            FlexiblePolicy.Slot(3, base.plus(Duration.ofHours(19)), NOT_NEEDED),
        )
        // Only 1 of 2 taken now (the second "Taken" was undone): the evening time is needed again.
        assertEquals(mapOf(3L to PLANNED), FlexiblePolicy.reconcileDay(2, slots, base.plus(Duration.ofHours(14)), dayOver = false))
        // A past NOT_NEEDED time isn't revived.
        assertTrue(FlexiblePolicy.reconcileDay(2, slots, base.plus(Duration.ofHours(20)), dayOver = false).isEmpty())
    }

    @Test fun colourMustBeOneOfEight() {
        val f = MedicationForm(name = "X", purpose = MedicationPurpose.OTHER, purposeNote = "x", rule = ScheduleRule.FixedTimes(listOf(t("08:00"))))
        assertEquals(emptyList<String>(), FormValidator.validate(f.copy(colorIndex = 7)))
        assertTrue(FormValidator.validate(f.copy(colorIndex = 8)).contains("Invalid colour"))
        assertTrue(FormValidator.validate(f.copy(colorIndex = -1)).contains("Invalid colour"))
    }
}
