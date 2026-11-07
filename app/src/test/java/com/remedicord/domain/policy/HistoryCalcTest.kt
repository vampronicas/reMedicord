package com.remedicord.domain.policy

import com.remedicord.domain.model.DoseStatus.DUE
import com.remedicord.domain.model.DoseStatus.MISSED
import com.remedicord.domain.model.DoseStatus.PLANNED
import com.remedicord.domain.model.DoseStatus.SKIPPED
import com.remedicord.domain.model.DoseStatus.TAKEN
import com.remedicord.domain.model.DoseStatus.UNCONFIRMED
import com.remedicord.domain.policy.HistoryCalc.Marker
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryCalcTest {
    @Test fun markersWorstFirst() {
        assertEquals(Marker.NONE, HistoryCalc.marker(emptyList()))
        assertEquals(Marker.NONE, HistoryCalc.marker(listOf(PLANNED, DUE)))
        assertEquals(Marker.ALL_TAKEN, HistoryCalc.marker(listOf(TAKEN, TAKEN, PLANNED)))
        assertEquals(Marker.HAS_MISSED, HistoryCalc.marker(listOf(TAKEN, MISSED, UNCONFIRMED)))
        assertEquals(Marker.NEEDS_REVIEW, HistoryCalc.marker(listOf(TAKEN, UNCONFIRMED)))
        assertEquals(Marker.SKIPPED_ONLY, HistoryCalc.marker(listOf(SKIPPED)))
        assertEquals(Marker.PARTIAL, HistoryCalc.marker(listOf(TAKEN, SKIPPED)))
    }

    @Test fun monthGridStartsOnMonday() {
        val nov = HistoryCalc.monthGrid(YearMonth.of(2026, 11)) // 1 Nov 2026 is a Sunday
        assertEquals(6, nov.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 11, 1), nov[6])
        assertEquals(6 + 30, nov.size)
        assertEquals(0, HistoryCalc.monthGrid(YearMonth.of(2026, 6)).takeWhile { it == null }.size) // 1 Jun 2026 is a Monday
    }

    @Test fun dayOfUsesLocalZoneAndFallsBackToTakenTime() {
        val t = Instant.parse("2026-03-01T20:00:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 3, 2), HistoryCalc.dayOf(t, null, ZoneId.of("Asia/Kuala_Lumpur")))
        assertEquals(LocalDate.of(2026, 3, 1), HistoryCalc.dayOf(null, t, ZoneOffset.UTC))
        assertNull(HistoryCalc.dayOf(null, null, ZoneOffset.UTC))
    }

    @Test fun adherenceWindows() {
        val today = LocalDate.of(2026, 3, 31)
        val e = listOf(today to TAKEN, today.minusDays(6) to MISSED, today.minusDays(7) to MISSED, today.minusDays(40) to TAKEN)
        assertEquals(50, HistoryCalc.adherence(e, today, 7))
        assertEquals(33, HistoryCalc.adherence(e, today, 30))
        assertEquals(50, HistoryCalc.adherence(e, today, 90))
        assertNull(HistoryCalc.adherence(listOf(today.plusDays(1) to TAKEN), today, 7))
    }
}
