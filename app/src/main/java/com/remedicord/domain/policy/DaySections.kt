package com.remedicord.domain.policy

import com.remedicord.domain.model.DoseStatus
import java.time.LocalTime

/** E1: Morning / Afternoon / Evening / Night sections (fixed boundaries, decided 2026-11-09). */
enum class DaySection(val label: String) {
    MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening"), NIGHT("Night");

    companion object {
        /** Morning 05:00–11:59, Afternoon 12:00–16:59, Evening 17:00–20:59, Night 21:00–04:59. */
        fun of(t: LocalTime): DaySection = when (t.hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }

        /** Flexible doses use their own period name when it matches a section ("Morning" etc.). */
        fun of(t: LocalTime, periodName: String?): DaySection =
            entries.firstOrNull { periodName != null && it.label.equals(periodName.trim(), ignoreCase = true) } ?: of(t)

        private val NEEDS_ATTENTION = setOf(DoseStatus.DUE, DoseStatus.SNOOZED, DoseStatus.UNCONFIRMED)
        private val FINISHED = setOf(DoseStatus.TAKEN, DoseStatus.SKIPPED, DoseStatus.MISSED, DoseStatus.NOT_NEEDED)

        /**
         * Starting state of a section header.
         * - History (or any other day): expanded.
         * - Today: expanded if it needs attention, is the current section, or is still ahead; finished past sections collapse.
         */
        fun startsExpanded(section: DaySection, statuses: List<DoseStatus>, now: LocalTime, isToday: Boolean): Boolean {
            if (!isToday) return true
            if (statuses.any { it in NEEDS_ATTENTION }) return true
            val current = of(now)
            if (section == current) return true
            val past = section.order() < current.order()
            return !(past && statuses.all { it in FINISHED })
        }

        /** Day order starting in the morning; Night (late evening + small hours) comes last. */
        private fun DaySection.order() = ordinal
    }
}
