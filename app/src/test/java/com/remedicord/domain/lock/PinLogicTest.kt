package com.remedicord.domain.lock

import java.time.Duration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinLogicTest {
    private val fastIter = 1_000 // keep unit tests quick; production uses PinHasher.ITERATIONS

    @Test fun onlyExactlyFourDigitsAreValid() {
        listOf("0000", "1234", "9999").forEach { assertTrue(PinHasher.isValidPin(it)) }
        listOf("", "123", "12345", "12a4", "１２３４", " 123", "12.4").forEach { assertFalse(it, PinHasher.isValidPin(it)) }
        assertThrows(IllegalArgumentException::class.java) { PinHasher.hash("12a4", PinHasher.newSalt(), fastIter) }
    }

    @Test fun hashIsSaltedDeterministicAndVerifies() {
        val salt = PinHasher.newSalt()
        assertEquals(16, salt.size)
        val h = PinHasher.hash("1234", salt, fastIter)
        assertEquals(32, h.size)
        assertArrayEquals(h, PinHasher.hash("1234", salt, fastIter))
        assertNotEquals(h.toList(), PinHasher.hash("1234", PinHasher.newSalt(), fastIter).toList())
        assertTrue(PinHasher.verify("1234", salt, fastIter, h))
        assertFalse(PinHasher.verify("1235", salt, fastIter, h))
        assertFalse(PinHasher.verify("abcd", salt, fastIter, h))
        assertTrue(PinHasher.ITERATIONS >= 200_000)
    }

    @Test fun lockoutEscalates() {
        assertEquals(Duration.ZERO, LockoutPolicy.lockoutAfterFailures(4))
        assertEquals(Duration.ofSeconds(30), LockoutPolicy.lockoutAfterFailures(5))
        assertEquals(Duration.ofMinutes(1), LockoutPolicy.lockoutAfterFailures(6))
        assertEquals(Duration.ofMinutes(5), LockoutPolicy.lockoutAfterFailures(7))
        assertEquals(Duration.ofMinutes(15), LockoutPolicy.lockoutAfterFailures(8))
        assertEquals(Duration.ofHours(1), LockoutPolicy.lockoutAfterFailures(9))
        assertEquals(Duration.ofHours(1), LockoutPolicy.lockoutAfterFailures(50))
    }

    @Test fun failuresCountDownAttemptsThenLock() {
        var s = LockoutPolicy.State()
        repeat(4) { s = LockoutPolicy.onFailure(s, 1_000) }
        assertEquals(1, LockoutPolicy.attemptsBeforeLockout(s))
        assertEquals(0, LockoutPolicy.remainingMs(s, 1_000))
        s = LockoutPolicy.onFailure(s, 10_000)
        assertEquals(0, LockoutPolicy.attemptsBeforeLockout(s))
        assertEquals(30_000, LockoutPolicy.remainingMs(s, 10_000))
        assertEquals(20_000, LockoutPolicy.remainingMs(s, 20_000))
        assertEquals(0, LockoutPolicy.remainingMs(s, 50_000))
    }

    @Test fun rebootDoesNotSkipLockoutAndWallClockIsNeverUsed() {
        val s = LockoutPolicy.State(failures = 9, lockRemainingMs = 3_600_000, lockSetAtElapsedMs = 5_000_000)
        // After a restart uptime restarts near 0: only time since boot counts.
        assertEquals(3_600_000 - 60_000, LockoutPolicy.remainingMs(s, 60_000))
    }
}
