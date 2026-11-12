package com.remedicord.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.lock.AppLockManager
import com.remedicord.core.lock.PinRepository
import com.remedicord.core.lock.PinRepository.VerifyResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LockUiState(
    val loading: Boolean = true,
    val pinSet: Boolean = false,
    val entered: String = "",
    /** Setup: first entry, waiting for confirmation. */
    val firstEntry: String? = null,
    val message: String? = null,
    val lockRemainingMs: Long = 0,
    val busy: Boolean = false,
)

@HiltViewModel
class LockViewModel @Inject constructor(
    private val pins: PinRepository,
    private val lock: AppLockManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state
    val locked = lock.locked

    init {
        viewModelScope.launch {
            val set = pins.isPinSet()
            _state.update { it.copy(loading = false, pinSet = set, lockRemainingMs = if (set) pins.lockRemainingMs() else 0) }
        }
    }

    fun digit(d: Char) {
        val s = _state.value
        if (s.busy || s.lockRemainingMs > 0 || s.entered.length >= 4) return
        val entered = s.entered + d
        _state.update { it.copy(entered = entered, message = null) }
        if (entered.length == 4) if (s.pinSet) verify(entered) else setup(entered)
    }

    fun backspace() = _state.update { it.copy(entered = it.entered.dropLast(1)) }

    private fun setup(pin: String) {
        val first = _state.value.firstEntry
        when {
            first == null -> _state.update { it.copy(firstEntry = pin, entered = "", message = "Enter the same PIN again to confirm") }
            first != pin -> _state.update { it.copy(firstEntry = null, entered = "", message = "PINs didn't match. Start again.") }
            else -> viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                withContext(Dispatchers.Default) { pins.setPin(pin) }
                _state.update { it.copy(busy = false, pinSet = true, entered = "", firstEntry = null, message = null) }
                lock.unlock()
            }
        }
    }

    private fun verify(pin: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        val r = withContext(Dispatchers.Default) { pins.verify(pin) }
        when (r) {
            VerifyResult.Success -> { _state.update { it.copy(busy = false, entered = "") }; lock.unlock() }
            is VerifyResult.Wrong -> _state.update {
                it.copy(busy = false, entered = "", lockRemainingMs = r.lockoutMs,
                    message = if (r.lockoutMs > 0) "Too many wrong PINs." else "Wrong PIN. ${r.attemptsBeforeLockout} tries left before a wait.")
            }
            is VerifyResult.LockedOut -> _state.update { it.copy(busy = false, entered = "", lockRemainingMs = r.remainingMs) }
        }
    }

    fun tick() = viewModelScope.launch {
        val remaining = pins.lockRemainingMs()
        _state.update { it.copy(lockRemainingMs = remaining, message = if (remaining == 0L) null else it.message) }
    }
}

/** S0 / PIN setup (S1 first step). The PIN is entered on this in-app keypad, never the system keyboard. */
@Composable
fun LockScreen(vm: LockViewModel) {
    val s by vm.state.collectAsState()
    if (s.loading) return
    LaunchedEffect(s.lockRemainingMs) {
        if (s.lockRemainingMs > 0) { delay(1000); vm.tick() }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("reMedicord", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    !s.pinSet && s.firstEntry == null -> "Create a 4-digit PIN"
                    !s.pinSet -> "Confirm your PIN"
                    else -> "Enter your PIN"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (!s.pinSet && s.firstEntry == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Important: there is no way to recover a forgotten PIN. If you forget it, the only option is " +
                        "Android Settings → Apps → reMedicord → Clear storage, which erases all data, and then restoring " +
                        "your latest backup. Keep regular backups.",
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.semantics { contentDescription = "${s.entered.length} of 4 digits entered" }) {
                repeat(4) { i ->
                    Surface(
                        shape = CircleShape,
                        color = if (i < s.entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(18.dp),
                    ) {}
                }
            }
            Spacer(Modifier.height(16.dp))
            val countdown = if (s.lockRemainingMs > 0) "Try again in ${formatWait(s.lockRemainingMs)}" else null
            Text(countdown ?: s.message ?: " ", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            PinPad(enabled = !s.busy && s.lockRemainingMs == 0L, onDigit = vm::digit, onBackspace = vm::backspace)
        }
    }
}

private fun formatWait(ms: Long): String {
    val sec = (ms + 999) / 1000
    return if (sec >= 60) "${sec / 60} min ${sec % 60} s" else "$sec s"
}

@Composable
fun PinPad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d -> Key(d.toString(), enabled) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(72.dp))
            Key("0", enabled) { onDigit('0') }
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = onBackspace, enabled = enabled) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = Modifier.size(72.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** Collects the lock state for the app gate. */
@Composable
fun rememberLocked(vm: LockViewModel): Boolean = vm.locked.collectAsState().value
