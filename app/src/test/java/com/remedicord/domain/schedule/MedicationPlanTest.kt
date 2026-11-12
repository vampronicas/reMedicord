package com.remedicord.domain.schedule

import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.policy.SkipPolicy
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationPlanTest {
    private val utc = ZoneOffset.UTC
    private fun i(s: String) = Instant.parse(s)
    private val twiceDaily = listOf(Regimen(1, i("2026-03-01T00:00:00Z"), null,
        ScheduleRule.FixedTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)))))

    @Test fun planWithoutSkips() {
        val items = MedicationPlan.plan(twiceDaily, utc, emptyList(), i("2026-03-02T00:00:00Z"), i("2026-03-03T00:00:00Z"))
        assertEquals(listOf(i("2026-03-02T08:00:00Z"), i("2026-03-02T20:00:00Z")), items.map { it.at })
        assertTrue(items.all { it.skipReason == null && it.regimenId == 1L })
    }

    @Test fun nextNDosesCountsFromRuleStartEvenBeforeWindow() {
        // Rule started 1 Mar 12:00 skipping 3 doses: 1 Mar 20:00, 2 Mar 08:00, 2 Mar 20:00. Window starts 2 Mar.
        val rule = SkipPolicy.Rule(SkipMode.NEXT_N_DOSES, 3, null, i("2026-03-01T12:00:00Z")) to "Procedure / fasting"
        val items = MedicationPlan.plan(twiceDaily, utc, listOf(rule), i("2026-03-02T00:00:00Z"), i("2026-03-04T00:00:00Z"))
        assertEquals(listOf("Procedure / fasting", "Procedure / fasting", null, null), items.map { it.skipReason })
    }

    @Test fun firstMatchingRuleReasonWins() {
        val a = SkipPolicy.Rule(SkipMode.DAYS, 1, null, i("2026-03-02T00:00:00Z")) to "A"
        val b = SkipPolicy.Rule(SkipMode.DAYS, 2, null, i("2026-03-02T00:00:00Z")) to "B"
        val items = MedicationPlan.plan(twiceDaily, utc, listOf(a, b), i("2026-03-02T00:00:00Z"), i("2026-03-04T00:00:00Z"))
        assertEquals(listOf("A", "A", "B", "B"), items.map { it.skipReason })
    }

    @Test fun backfillWindowRules() {
        val now = i("2026-03-10T12:00:00Z")
        assertNull(MedicationPlan.backfillWindow(null, now))
        assertEquals(i("2026-03-09T00:00:00Z") to now.minus(Duration.ofMinutes(2)), MedicationPlan.backfillWindow(i("2026-03-09T00:00:00Z"), now))
        // Capped at 31 days back (e.g. very old backup restored).
        assertEquals(now.minus(Duration.ofDays(31)), MedicationPlan.backfillWindow(i("2025-01-01T00:00:00Z"), now)!!.first)
        // Nothing to fill when planning is up to date.
        assertNull(MedicationPlan.backfillWindow(now, now))
        assertNull(MedicationPlan.backfillWindow(i("2026-03-11T00:00:00Z"), now))
    }
}

class ReminderTextTest {
    private val names = listOf("Timolol", "Amoxicillin")
    private val lines = listOf(
        ReminderText.Line(MedicationType.EYE_DROP, "Timolol", BigDecimal("1"), DoseUnit.DROP, BodySide.LEFT, null),
        ReminderText.Line(MedicationType.INJECTION, "Amoxicillin", BigDecimal("2.50"), DoseUnit.ML, null, "Thigh left"),
    )

    @Test fun lockScreenTextNeverContainsMedicineData() {
        names.forEach {
            assertFalse(ReminderText.PUBLIC_TITLE.contains(it))
            assertFalse(ReminderText.PUBLIC_TEXT.contains(it))
        }
        assertEquals("Medication reminder", ReminderText.PUBLIC_TITLE)
    }

    @Test fun privateLinesUseTypeVerbDoseSideSite() {
        assertEquals("Instill Timolol · 1 drop · Left", ReminderText.line(lines[0]))
        assertEquals("Inject Amoxicillin · 2.5 mL · Thigh left", ReminderText.line(lines[1]))
        assertEquals("Change dressing Knee", ReminderText.line(ReminderText.Line(MedicationType.BANDAGE, "Knee", null, null, null, null)))
        assertEquals("Time for Timolol", ReminderText.title(lines.take(1)))
        assertEquals("Time for 2 medicines", ReminderText.title(lines))
        MedicationType.entries.forEach { assertTrue(ReminderText.verb(it).isNotBlank()) }
        assertEquals("Take X · 500 mg", ReminderText.line(ReminderText.Line(MedicationType.PILL, "X", BigDecimal(500), DoseUnit.MG, null, null)))
    }
}
