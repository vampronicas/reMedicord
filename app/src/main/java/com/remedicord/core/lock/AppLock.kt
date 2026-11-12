package com.remedicord.core.lock

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.lock.LockoutPolicy
import com.remedicord.domain.lock.PinHasher
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stores the PIN hash and lockout state in the encrypted DB under never-exported private keys. */
class PinRepository(
    private val settings: SettingsRepository,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    sealed interface VerifyResult {
        data object Success : VerifyResult
        data class Wrong(val attemptsBeforeLockout: Int, val lockoutMs: Long) : VerifyResult
        data class LockedOut(val remainingMs: Long) : VerifyResult
    }

    suspend fun isPinSet(): Boolean = settings.get(HASH) != null

    suspend fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin)) { "PIN must be exactly 4 digits" }
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash(pin, salt)
        settings.put(SALT, b64(salt))
        settings.put(ITER, PinHasher.ITERATIONS.toString())
        settings.put(HASH, b64(hash))
        saveState(LockoutPolicy.State())
    }

    suspend fun changePin(current: String, new: String): VerifyResult {
        val r = verify(current)
        if (r is VerifyResult.Success) setPin(new)
        return r
    }

    suspend fun lockRemainingMs(): Long = LockoutPolicy.remainingMs(loadState(), elapsedRealtime())

    suspend fun verify(pin: String): VerifyResult {
        val state = loadState()
        val now = elapsedRealtime()
        val remaining = LockoutPolicy.remainingMs(state, now)
        if (remaining > 0) return VerifyResult.LockedOut(remaining)
        val hash = settings.get(HASH) ?: return VerifyResult.Wrong(0, 0)
        val ok = PinHasher.verify(pin, unb64(settings.get(SALT)!!), settings.get(ITER)!!.toInt(), unb64(hash))
        return if (ok) {
            saveState(LockoutPolicy.State())
            VerifyResult.Success
        } else {
            val next = LockoutPolicy.onFailure(state, now)
            saveState(next)
            VerifyResult.Wrong(LockoutPolicy.attemptsBeforeLockout(next), next.lockRemainingMs)
        }
    }

    private suspend fun loadState() = LockoutPolicy.State(
        settings.get(FAILURES)?.toIntOrNull() ?: 0,
        settings.get(LOCK_MS)?.toLongOrNull() ?: 0,
        settings.get(LOCK_AT)?.toLongOrNull() ?: 0,
    )

    private suspend fun saveState(s: LockoutPolicy.State) {
        settings.put(FAILURES, s.failures.toString())
        settings.put(LOCK_MS, s.lockRemainingMs.toString())
        settings.put(LOCK_AT, s.lockSetAtElapsedMs.toString())
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    companion object {
        private const val P = SettingsRepository.PRIVATE_PREFIX
        const val HASH = "${P}pin.hash"
        const val SALT = "${P}pin.salt"
        const val ITER = "${P}pin.iterations"
        const val FAILURES = "${P}pin.failures"
        const val LOCK_MS = "${P}pin.lockRemainingMs"
        const val LOCK_AT = "${P}pin.lockSetAtElapsed"
    }
}

/**
 * Locked on every cold start, and again after the app has been in the background longer than the timeout
 * (PLAN.md §3.1). Observes the whole process lifecycle.
 */
class AppLockManager(private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime) : DefaultLifecycleObserver {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile var timeoutMs: Long = DEFAULT_TIMEOUT_MS
    private var backgroundAt: Long? = null

    /** Applies the saved timeout setting. Only the offered choices are accepted; anything else keeps the current value. */
    fun applyTimeoutSetting(minutes: String?) {
        minutes?.toIntOrNull()?.takeIf { it in TIMEOUT_CHOICES_MIN }?.let { timeoutMs = it * 60_000L }
    }

    fun unlock() { _locked.value = false }
    fun lock() { _locked.value = true }

    override fun onStop(owner: LifecycleOwner) { backgroundAt = elapsedRealtime() }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (elapsedRealtime() - since >= timeoutMs) lock()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val SETTING_TIMEOUT_MIN = "lock.timeoutMinutes"
        val TIMEOUT_CHOICES_MIN = listOf(0, 1, 5, 15)
    }
}
