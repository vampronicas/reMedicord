package com.remedicord.domain.lock

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PLAN.md §3.1: the PIN is never stored, only a salted PBKDF2-HMAC-SHA256 hash. */
object PinHasher {
    const val ITERATIONS = 210_000
    private const val KEY_BITS = 256

    fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it in '0'..'9' }

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(isValidPin(pin)) { "PIN must be exactly 4 digits" }
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time comparison. */
    fun verify(pin: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean =
        isValidPin(pin) && MessageDigest.isEqual(hash(pin, salt, iterations), expected)
}

/**
 * PLAN.md §3.1: escalating lockout after wrong PINs.
 * Uses device uptime (elapsedRealtime), never the wall clock, so changing the phone's clock can't skip it.
 * After a restart uptime starts again from 0: only time since boot counts (conservative).
 */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 5

    data class State(val failures: Int = 0, val lockRemainingMs: Long = 0, val lockSetAtElapsedMs: Long = 0)

    fun lockoutAfterFailures(failures: Int): Duration = when {
        failures < FREE_ATTEMPTS -> Duration.ZERO
        failures == 5 -> Duration.ofSeconds(30)
        failures == 6 -> Duration.ofMinutes(1)
        failures == 7 -> Duration.ofMinutes(5)
        failures == 8 -> Duration.ofMinutes(15)
        else -> Duration.ofHours(1)
    }

    fun remainingMs(state: State, nowElapsedMs: Long): Long {
        if (state.lockRemainingMs <= 0) return 0
        val elapsed = if (nowElapsedMs >= state.lockSetAtElapsedMs) nowElapsedMs - state.lockSetAtElapsedMs else nowElapsedMs
        return (state.lockRemainingMs - elapsed).coerceAtLeast(0)
    }

    fun onFailure(state: State, nowElapsedMs: Long): State {
        val failures = state.failures + 1
        return State(failures, lockoutAfterFailures(failures).toMillis(), nowElapsedMs)
    }

    fun attemptsBeforeLockout(state: State): Int = (FREE_ATTEMPTS - state.failures).coerceAtLeast(0)
}
