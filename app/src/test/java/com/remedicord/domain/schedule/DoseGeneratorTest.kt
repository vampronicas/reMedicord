package com.remedicord.domain.schedule

import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.Regimen
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.TimeZoneMode
import com.remedicord.domain.model.ZoneSettings
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseGeneratorTest {
    private val utc = ZoneOffset.UTC
    private val ny = ZoneId.of("America/New_York")
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun t(s: String) = LocalTime.parse(s)
    private fun i(s: String) = Instant.parse(s)
    private fun reg(rule: ScheduleRule, from: String = "2026-01-01T00:00:00Z", to: String? = null, id: Long = 1) =
        Regimen(id, i(from), to?.let(::i), rule, BigDecimal.ONE, DoseUnit.TABLET)

    private fun gen(r: List<Regimen>, zone: ZoneId, from: String, to: String) = DoseGenerator.generate(r, zone, i(from), i(to))

    @Test fun fixedTimesDaily() {
        val doses = gen(listOf(reg(ScheduleRule.FixedTimes(listOf(t("20:00"), t("08:00"))))), utc, "2026-03-01T00:00:00Z", "2026-03-03T00:00:00Z")
        assertEquals(listOf("2026-03-01T08:00:00Z", "2026-03-01T20:00:00Z", "2026-03-02T08:00:00Z", "2026-03-02T20:00:00Z").map(::i), doses.map { it.at })
    }

    @Test fun windowIsInclusiveStartExclusiveEnd() {
        val r = listOf(reg(ScheduleRule.FixedTimes(listOf(t("08:00")))))
        assertEquals(1, gen(r, utc, "2026-03-01T08:00:00Z", "2026-03-02T08:00:00Z").size)
        assertEquals(0, gen(r, utc, "2026-03-01T08:00:01Z", "2026-03-02T08:00:00Z").size)
        assertEquals(0, gen(r, utc, "2026-03-02T00:00:00Z", "2026-03-01T00:00:00Z").size)
    }

    @Test fun fixedTimesOnChosenWeekdaysOnly() {
        val rule = ScheduleRule.FixedTimes(listOf(t("09:00")), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val doses = gen(listOf(reg(rule)), utc, "2026-03-02T00:00:00Z", "2026-03-09T00:00:00Z") // Mon 2 .. Sun 8
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), doses.map { it.at.atZone(utc).dayOfWeek })
    }

    @Test fun dstSpringForwardGapMovesToFirstValidMinute() {
        // New York 2026-03-08: 02:00 → 03:00. 02:30 does not exist → 03:00 EDT = 07:00Z.
        val doses = gen(listOf(reg(ScheduleRule.FixedTimes(listOf(t("02:30"))))), ny, "2026-03-08T00:00:00Z", "2026-03-09T00:00:00Z")
        assertEquals(listOf(i("2026-03-08T07:00:00Z")), doses.map { it.at })
    }

    @Test fun dstFallBackOverlapFiresOnceAtEarlierOffset() {
        // New York 2026-11-01: 01:00–02:00 happens twice. 01:30 fires once, at EDT (-4) = 05:30Z.
        val doses = gen(listOf(reg(ScheduleRule.FixedTimes(listOf(t("01:30"))))), ny, "2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z")
        assertEquals(listOf(i("2026-11-01T05:30:00Z")), doses.map { it.at })
    }

    @Test fun everyNHoursWithQuietHoursAcrossMidnight() {
        val rule = ScheduleRule.EveryNHours(6, t("06:00"), quietStart = t("23:00"), quietEnd = t("06:00"))
        val doses = gen(listOf(reg(rule, from = "2026-03-01T00:00:00Z")), utc, "2026-03-01T00:00:00Z", "2026-03-02T12:00:01Z")
        assertEquals(listOf("2026-03-01T06:00:00Z", "2026-03-01T12:00:00Z", "2026-03-01T18:00:00Z", "2026-03-02T06:00:00Z", "2026-03-02T12:00:00Z").map(::i), doses.map { it.at })
    }

    @Test fun everyNHoursKeepsElapsedIntervalAcrossDst() {
        val rule = ScheduleRule.EveryNHours(8, t("00:00"))
        val doses = gen(listOf(reg(rule, from = "2026-03-07T05:00:00Z")), ny, "2026-03-07T05:00:00Z", "2026-03-09T05:00:00Z")
        doses.zipWithNext().forEach { (a, b) -> assertEquals(Duration.ofHours(8), Duration.between(a.at, b.at)) }
        assertEquals(6, doses.size)
    }

    @Test fun everyNHoursStartsAtFirstOccurrenceAfterRegimenStart() {
        val rule = ScheduleRule.EveryNHours(4, t("06:00"))
        val doses = gen(listOf(reg(rule, from = "2026-03-01T09:30:00Z")), utc, "2026-03-01T00:00:00Z", "2026-03-01T16:00:00Z")
        assertEquals(listOf(i("2026-03-01T10:00:00Z"), i("2026-03-01T14:00:00Z")), doses.map { it.at })
    }

    @Test fun everyNDaysWeeklyInjection() {
        val rule = ScheduleRule.EveryNDays(7, listOf(t("09:00")), LocalDate.of(2026, 3, 1))
        val doses = gen(listOf(reg(rule)), utc, "2026-02-20T00:00:00Z", "2026-03-30T00:00:00Z")
        assertEquals(listOf("2026-03-01", "2026-03-08", "2026-03-15", "2026-03-22", "2026-03-29"), doses.map { it.at.atZone(utc).toLocalDate().toString() })
    }

    @Test fun cyclicDaysOnDaysOff() {
        val rule = ScheduleRule.Cyclic(2, 1, listOf(t("08:00")), LocalDate.of(2026, 3, 1))
        val days = gen(listOf(reg(rule)), utc, "2026-03-01T00:00:00Z", "2026-03-07T00:00:00Z").map { it.at.atZone(utc).dayOfMonth }
        assertEquals(listOf(1, 2, 4, 5), days)
    }

    @Test fun asNeededHasNoReminders() {
        assertTrue(gen(listOf(reg(ScheduleRule.AsNeeded(240, 4))), utc, "2026-03-01T00:00:00Z", "2026-03-08T00:00:00Z").isEmpty())
    }

    @Test fun doseChangeUsesRegimenInForceAtEachInstant() {
        val r1 = reg(ScheduleRule.FixedTimes(listOf(t("08:00"), t("20:00"))), from = "2026-03-01T00:00:00Z", to = "2026-03-02T12:00:00Z", id = 1)
        val r2 = reg(ScheduleRule.FixedTimes(listOf(t("09:00"))), from = "2026-03-02T12:00:00Z", id = 2)
        val doses = gen(listOf(r1, r2), utc, "2026-03-01T00:00:00Z", "2026-03-05T00:00:00Z")
        assertEquals(listOf(1L, 1L, 1L, 2L, 2L), doses.map { it.regimenId })
        assertEquals(i("2026-03-02T08:00:00Z"), doses[2].at)
        assertEquals(i("2026-03-03T09:00:00Z"), doses[3].at)
    }

    @Test fun localModeFollowsTravelHomeModeKeepsHomeTimes() {
        val r = listOf(reg(ScheduleRule.FixedTimes(listOf(t("08:00")))))
        val local = ZoneSettings(TimeZoneMode.LOCAL, null)
        val home = ZoneSettings(TimeZoneMode.HOME, kl)
        // Travelling to Tokyo: LOCAL → 08:00 Tokyo; HOME → still 08:00 Kuala Lumpur (= 09:00 Tokyo).
        val inTokyoLocal = gen(r, local.effectiveZone(tokyo), "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").single().at
        val inTokyoHome = gen(r, home.effectiveZone(tokyo), "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z").single().at
        assertEquals(LocalTime.of(8, 0), inTokyoLocal.atZone(tokyo).toLocalTime())
        assertEquals(LocalTime.of(9, 0), inTokyoHome.atZone(tokyo).toLocalTime())
        assertEquals(LocalTime.of(8, 0), inTokyoHome.atZone(kl).toLocalTime())
        assertEquals(tokyo, local.effectiveZone(tokyo))
    }

    @Test fun taperBuilderChainsStepsAndGeneratorFollowsThem() {
        val zone = kl
        val steps = listOf(
            TaperBuilder.Step(ScheduleRule.FixedTimes(listOf(t("08:00"), t("12:00"), t("16:00"), t("20:00"))), BigDecimal.ONE, DoseUnit.DROP, 2),
            TaperBuilder.Step(ScheduleRule.FixedTimes(listOf(t("08:00"), t("20:00"))), BigDecimal.ONE, DoseUnit.DROP, 3),
        )
        val planned = TaperBuilder.build(steps, LocalDate.of(2026, 3, 1), zone)
        assertEquals(planned[0].effectiveTo, planned[1].effectiveFrom)
        val regimens = planned.mapIndexed { idx, p -> Regimen(idx + 1L, p.effectiveFrom, p.effectiveTo, p.rule, p.amount, p.unit) }
        val doses = DoseGenerator.generate(regimens, zone, planned.first().effectiveFrom, planned.last().effectiveTo.plus(Duration.ofDays(5)))
        assertEquals(2 * 4 + 3 * 2, doses.size)
        assertThrows(IllegalArgumentException::class.java) { TaperBuilder.build(emptyList(), LocalDate.of(2026, 3, 1), zone) }
    }

    @Test fun resolveNormalTime() {
        assertEquals(i("2026-06-01T00:00:00Z"), DoseGenerator.resolve(LocalDateTime.of(2026, 6, 1, 8, 0), kl))
    }
}

class ScheduleRuleTest {
    private fun t(s: String) = LocalTime.parse(s)

    @Test fun everyRuleTypeRoundTripsThroughJson() {
        val rules = listOf(
            ScheduleRule.FixedTimes(listOf(t("08:00"), t("20:30")), setOf(DayOfWeek.MONDAY)),
            ScheduleRule.EveryNHours(6, t("06:00"), t("23:00"), t("06:00")),
            ScheduleRule.EveryNDays(7, listOf(t("09:00")), LocalDate.of(2026, 3, 1)),
            ScheduleRule.Cyclic(21, 7, listOf(t("21:00")), LocalDate.of(2026, 3, 1)),
            ScheduleRule.AsNeeded(240, 4),
        )
        rules.forEach { assertEquals(it, ScheduleRule.fromJson(ScheduleRule.toJson(it))) }
        assertTrue(ScheduleRule.toJson(rules[4]).contains("\"kind\":\"as_needed\""))
    }

    @Test fun invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.FixedTimes(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.EveryNHours(0, t("06:00")) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.EveryNHours(6, t("06:00"), quietStart = t("23:00")) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.EveryNDays(0, listOf(t("09:00")), LocalDate.of(2026, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.Cyclic(0, 7, listOf(t("09:00")), LocalDate.of(2026, 1, 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.AsNeeded(-1, 4) }
        // Tampered JSON (e.g. from a backup) is rejected by the same checks.
        assertThrows(IllegalArgumentException::class.java) { ScheduleRule.fromJson("""{"kind":"as_needed","minGapMinutes":60,"maxPerDay":999}""") }
    }
}
