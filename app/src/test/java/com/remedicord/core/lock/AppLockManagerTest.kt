package com.remedicord.core.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/** The saved lock timeout only ever takes one of the offered choices, whatever is stored. */
class AppLockManagerTest {
    @Test fun onlyOfferedTimeoutsAreApplied() {
        val lock = AppLockManager { 0L }
        AppLockManager.TIMEOUT_CHOICES_MIN.forEach { m ->
            lock.applyTimeoutSetting("$m")
            assertEquals(m * 60_000L, lock.timeoutMs)
        }
        lock.applyTimeoutSetting("5")
        listOf(null, "", "abc", "-1", "2", "99999999", "153722867280912931").forEach {
            lock.applyTimeoutSetting(it)
            assertEquals("'$it' must be ignored", 5 * 60_000L, lock.timeoutMs)
        }
    }
}
