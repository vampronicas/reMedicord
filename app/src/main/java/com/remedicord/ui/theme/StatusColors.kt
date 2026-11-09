package com.remedicord.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.policy.HistoryCalc

/**
 * Soft, low-contrast status colours (light and dark variants). Text stays the normal on-surface colour,
 * so readability doesn't depend on the tint. Status is also always written in words (accessibility).
 */
object StatusColors {
    private data class Pair(val light: Color, val dark: Color)

    private val taken = Pair(Color(0xFFE4F2E5), Color(0xFF1E3323))
    private val skipped = Pair(Color(0xFFEEEEEE), Color(0xFF2B2B2B))
    private val missed = Pair(Color(0xFFF8E3E1), Color(0xFF3A2323))
    private val needsAnswer = Pair(Color(0xFFFCEBD5), Color(0xFF3A2D1B))
    private val due = Pair(Color(0xFFE2ECF7), Color(0xFF1E2B3B))
    private val notNeeded = Pair(Color(0xFFF7F7F7), Color(0xFF222222))

    @Composable
    fun container(s: DoseStatus): Color {
        val p = when (s) {
            DoseStatus.TAKEN -> taken
            DoseStatus.SKIPPED -> skipped
            DoseStatus.MISSED -> missed
            DoseStatus.UNCONFIRMED -> needsAnswer
            DoseStatus.DUE, DoseStatus.SNOOZED -> due
            DoseStatus.NOT_NEEDED -> notNeeded
            DoseStatus.PLANNED -> return MaterialTheme.colorScheme.surfaceContainerLow
        }
        return if (isSystemInDarkTheme()) p.dark else p.light
    }

    /** Calendar dots: muted, not saturated. */
    fun marker(m: HistoryCalc.Marker): Color? = when (m) {
        HistoryCalc.Marker.NONE -> null
        HistoryCalc.Marker.ALL_TAKEN -> Color(0xFF81C784)
        HistoryCalc.Marker.HAS_MISSED -> Color(0xFFE57373)
        HistoryCalc.Marker.NEEDS_REVIEW -> Color(0xFFFFB74D)
        HistoryCalc.Marker.PARTIAL -> Color(0xFFFFD54F)
        HistoryCalc.Marker.SKIPPED_ONLY -> Color(0xFFBDBDBD)
    }
}
