package com.remedicord.reminders

import com.remedicord.domain.schedule.SlotGrouper
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN.md §12.2 (2): receivers ignore implausible input. */
class ReceiverValidationTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()

    @Test fun slotValidation() {
        assertTrue(validSlot(SlotGrouper.slotId(Instant.ofEpochMilli(now)), now))
        assertTrue(validSlot(SlotGrouper.slotId(Instant.parse("2027-06-01T08:00:00Z")), now))
        listOf(-1L, 0L, Long.MIN_VALUE, Long.MAX_VALUE, 1L, SlotGrouper.slotId(Instant.parse("2030-01-01T00:00:00Z")))
            .forEach { assertFalse("$it", validSlot(it, now)) }
    }

    @Test fun onlyKnownActions() {
        assertTrue("com.remedicord.action.TAKEN" in DoseActionReceiver.ACTIONS)
        assertFalse("android.intent.action.DELETE" in DoseActionReceiver.ACTIONS)
        assertFalse("" in DoseActionReceiver.ACTIONS)
    }
}
