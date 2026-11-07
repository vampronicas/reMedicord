package com.remedicord.reminders

import com.remedicord.data.repo.SettingsRepository

/** User-configurable reminder behaviour (PLAN.md §4.6, §4.7, S12). */
data class ReminderSettings(
    val snoozeMinutes: Int = 10,
    val snoozeOptions: List<Int> = listOf(5, 10, 15),
    val maxSnoozes: Int = 3,
    val repeatMinutes: Int = 10,
    val maxRepeats: Int = 3,
    val eyeDropGapMinutes: Int = 5,
    val fullScreen: Boolean = true,
)

class ReminderSettingsRepository(private val settings: SettingsRepository) {
    suspend fun load() = ReminderSettings(
        snoozeMinutes = int(SNOOZE, 10, 1..60),
        maxSnoozes = int(MAX_SNOOZES, 3, 0..10),
        repeatMinutes = int(REPEAT, 10, 1..60),
        maxRepeats = int(MAX_REPEATS, 3, 0..10),
        eyeDropGapMinutes = int(EYE_GAP, 5, 1..30),
        fullScreen = settings.get(FULL_SCREEN)?.toBooleanStrictOrNull() ?: true,
    )

    suspend fun save(s: ReminderSettings) {
        settings.put(SNOOZE, s.snoozeMinutes.toString())
        settings.put(MAX_SNOOZES, s.maxSnoozes.toString())
        settings.put(REPEAT, s.repeatMinutes.toString())
        settings.put(MAX_REPEATS, s.maxRepeats.toString())
        settings.put(EYE_GAP, s.eyeDropGapMinutes.toString())
        settings.put(FULL_SCREEN, s.fullScreen.toString())
    }

    private suspend fun int(key: String, default: Int, range: IntRange) =
        settings.get(key)?.toIntOrNull()?.takeIf { it in range } ?: default

    companion object {
        const val SNOOZE = "reminder.snoozeMinutes"
        const val MAX_SNOOZES = "reminder.maxSnoozes"
        const val REPEAT = "reminder.repeatMinutes"
        const val MAX_REPEATS = "reminder.maxRepeats"
        const val EYE_GAP = "reminder.eyeDropGapMinutes"
        const val FULL_SCREEN = "reminder.fullScreen"
        const val PLANNED_UNTIL = "planner.plannedUntil"
        const val SCHEDULED_SLOTS = "planner.scheduledSlots"
        const val ONBOARDING_DONE = "onboarding.done"
    }
}
