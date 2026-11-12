package com.remedicord.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.lock.AppLockManager
import com.remedicord.core.lock.PinRepository
import com.remedicord.data.repo.SettingsRepository
import com.remedicord.domain.usecase.Replanner
import com.remedicord.reminders.ReminderSettings
import com.remedicord.reminders.ReminderSettingsRepository
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.LabeledSwitch
import com.remedicord.ui.components.SectionTitle
import com.remedicord.ui.lock.PinPad
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val pins: PinRepository,
    private val lock: AppLockManager,
    private val reminderSettings: ReminderSettingsRepository,
    private val replanner: Replanner,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val _sound = MutableStateFlow(com.remedicord.reminders.Notifications.currentSound(context))
    val sound: StateFlow<com.remedicord.reminders.Notifications.ReminderSound> = _sound
    private var player: android.media.MediaPlayer? = null

    /** v1.3: changes the reminder sound (recreates the notification channel with the new sound). */
    fun setSound(s: com.remedicord.reminders.Notifications.ReminderSound) {
        com.remedicord.reminders.Notifications.setReminderSound(context, s)
        _sound.value = s
        viewModelScope.launch { settings.put(com.remedicord.data.backup.BackupService.SOUND_KEY, s.name) }
    }

    /** Plays a short preview of the chosen sound. */
    fun preview(s: com.remedicord.reminders.Notifications.ReminderSound) {
        player?.release(); player = null
        when {
            s.rawRes != null -> player = android.media.MediaPlayer.create(context, s.rawRes)?.apply { setOnCompletionListener { it.release(); if (player === it) player = null }; start() }
            s == com.remedicord.reminders.Notifications.ReminderSound.DEFAULT ->
                android.media.RingtoneManager.getRingtone(context, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))?.play()
        }
    }

    override fun onCleared() { player?.release(); player = null }
    private val _timeout = MutableStateFlow((lock.timeoutMs / 60_000).toInt())
    val timeoutMin: StateFlow<Int> = _timeout
    private val _reminders = MutableStateFlow(ReminderSettings())
    val reminders: StateFlow<ReminderSettings> = _reminders

    init { viewModelScope.launch { _reminders.value = reminderSettings.load() } }

    fun updateReminders(f: (ReminderSettings) -> ReminderSettings) = viewModelScope.launch {
        val s = f(_reminders.value)
        reminderSettings.save(s)
        _reminders.value = s
        replanner.replan()
    }

    fun setTimeout(min: Int) = viewModelScope.launch {
        settings.put(AppLockManager.SETTING_TIMEOUT_MIN, min.toString())
        lock.timeoutMs = min * 60_000L
        _timeout.value = min
    }

    suspend fun changePin(current: String, new: String): PinRepository.VerifyResult =
        withContext(Dispatchers.Default) { pins.changePin(current, new) }
}

/** S12: reminders, backup, lock and PIN settings. */
@Composable
fun SettingsScreen(vm: SettingsViewModel, onHealth: () -> Unit, onBackup: () -> Unit) {
    val timeout by vm.timeoutMin.collectAsState()
    val r by vm.reminders.collectAsState()
    var changing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Reminders")
        Button(onClick = onHealth) { Text("Reminder health check") }
        val currentSound by vm.sound.collectAsState()
        Text("Reminder sound")
        ChoiceChips(com.remedicord.reminders.Notifications.ReminderSound.entries, currentSound, { it.label }) { vm.setSound(it) }
        androidx.compose.material3.OutlinedButton(onClick = { vm.preview(currentSound) }, enabled = currentSound != com.remedicord.reminders.Notifications.ReminderSound.SILENT) {
            Text("Play \"${currentSound.label}\"")
        }
        Text("Gentle tones made for reMedicord. The sound follows your phone's notification volume and Do Not Disturb.", style = MaterialTheme.typography.bodySmall)
        Text("Default snooze")
        ChoiceChips(listOf(5, 10, 15, 30), r.snoozeMinutes, { "$it min" }) { m -> vm.updateReminders { it.copy(snoozeMinutes = m) } }
        Text("If there's no answer, remind again every")
        ChoiceChips(listOf(5, 10, 15), r.repeatMinutes, { "$it min" }) { m -> vm.updateReminders { it.copy(repeatMinutes = m) } }
        Text("…this many times before marking the dose missed")
        ChoiceChips(listOf(1, 2, 3, 5), r.maxRepeats, { "$it×" }) { m -> vm.updateReminders { it.copy(maxRepeats = m) } }
        Text("Maximum snoozes per dose")
        ChoiceChips(listOf(1, 2, 3, 5), r.maxSnoozes, { "$it" }) { m -> vm.updateReminders { it.copy(maxSnoozes = m) } }
        Text("Gap between different eye drops")
        ChoiceChips(listOf(3, 5, 10), r.eyeDropGapMinutes, { "$it min" }) { m -> vm.updateReminders { it.copy(eyeDropGapMinutes = m) } }
        LabeledSwitch("Full-screen alarm (generic on the lock screen)", r.fullScreen) { b -> vm.updateReminders { it.copy(fullScreen = b) } }

        SectionTitle("Backup")
        Button(onClick = onBackup) { Text("Backup & restore") }

        SectionTitle("App lock")
        Text("Lock again after the app has been in the background for:")
        ChoiceChips(AppLockManager.TIMEOUT_CHOICES_MIN, timeout, { if (it == 0) "Immediately" else "$it min" }, vm::setTimeout)
        Button(onClick = { changing = true }) { Text("Change PIN") }
    }
    if (changing) ChangePinDialog(vm) { changing = false }
}

@Composable
private fun ChangePinDialog(vm: SettingsViewModel, onClose: () -> Unit) {
    var stage by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var entered by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val title = listOf("Current PIN", "New PIN", "Confirm new PIN")[stage]
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (done) "PIN changed" else title) },
        text = {
            if (!done) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●".repeat(entered.length) + "○".repeat(4 - entered.length), style = MaterialTheme.typography.headlineSmall)
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PinPad(enabled = true, onDigit = { d ->
                    if (entered.length < 4) entered += d
                    if (entered.length == 4) {
                        val e = entered; entered = ""
                        when (stage) {
                            0 -> { current = e; stage = 1; message = null }
                            1 -> { new = e; stage = 2 }
                            else -> if (e != new) { message = "PINs didn't match"; stage = 1 } else scope.launch {
                                when (val r = vm.changePin(current, new)) {
                                    PinRepository.VerifyResult.Success -> done = true
                                    is PinRepository.VerifyResult.Wrong -> { message = "Current PIN is wrong"; stage = 0 }
                                    is PinRepository.VerifyResult.LockedOut -> { message = "Too many wrong PINs. Try again in ${r.remainingMs / 1000} s"; stage = 0 }
                                }
                            }
                        }
                    }
                }, onBackspace = { entered = entered.dropLast(1) })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (done) "OK" else "Cancel") } },
    )
}
