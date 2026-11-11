package com.remedicord.reminders

import android.app.KeyguardManager
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.domain.schedule.ReminderText
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.theme.ReMedicordTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AlarmItem(val doseId: Long, val medicationId: Long, val name: String, val text: String, val snoozeCount: Int, val mustComplete: Boolean)

@HiltViewModel
class AlarmViewModel @Inject constructor(
    private val db: dagger.Lazy<AppDatabase>,
    private val actions: dagger.Lazy<DoseActionService>,
    private val engine: dagger.Lazy<ReminderEngine>,
    private val settings: dagger.Lazy<ReminderSettingsRepository>,
    private val time: TimeProvider,
) : ViewModel() {
    private val _items = MutableStateFlow<List<AlarmItem>?>(null)
    val items: StateFlow<List<AlarmItem>?> = _items
    var slotId: Long = -1
    var snoozeOptions = listOf(5, 10, 15)
    var maxSnoozes = 3

    /** Loads medicine details. Only called once the phone is unlocked. */
    fun load() = viewModelScope.launch {
        val s = settings.get().load(); snoozeOptions = s.snoozeOptions; maxSnoozes = s.maxSnoozes
        val d = db.get()
        _items.value = d.doseEventDao().openInSlot(slotId).filter { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED }.mapNotNull { dose ->
            val m = d.medicationDao().get(dose.medicationId) ?: return@mapNotNull null
            val reg = dose.regimenId?.let { d.regimenDao().get(it) }
            val site = if (m.type == MedicationType.INJECTION || m.type == MedicationType.PATCH) actions.get().suggestSite(m.id) else m.bodyLocation
            AlarmItem(dose.id, m.id, m.name, ReminderText.line(ReminderText.Line(m.type, m.name, reg?.doseAmount, reg?.doseUnit, m.side, site)) +
                (m.instructions.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), dose.snoozeCount, m.mustComplete)
        }
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); engine.get().refreshNotification(slotId); load() }

    fun take(id: Long) = act { actions.get().take(id, time.now(), eyeDropGapMinutes = settings.get().load().eyeDropGapMinutes) }
    fun snooze(id: Long, min: Int) = act { actions.get().snooze(id, min, maxSnoozes) }
    fun later(id: Long) = act { actions.get().later(id) }
    fun skip(id: Long, reason: String) = act { actions.get().skip(id, reason) }
    fun pauseToday(medicationId: Long) = act { actions.get().pauseToday(medicationId) }

    /** Allowed while the phone is locked: reveals nothing. */
    fun laterAllGeneric(onDone: () -> Unit) = viewModelScope.launch {
        db.get().doseEventDao().openInSlot(slotId).filter { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED }.forEach { actions.get().later(it.id) }
        engine.get().refreshNotification(slotId)
        onDone()
    }
}

/**
 * S10 full-screen reminder. While the phone is locked it shows ONLY a generic reminder with "Later" and
 * "Unlock to view"; medicine details and all choices appear after unlock. No app PIN needed.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    private val vm: AlarmViewModel by viewModels()
    private var locked by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.decorView.filterTouchesWhenObscured = true
        val slot = intent.getLongExtra(AlarmScheduler.EXTRA_SLOT, -1)
        if (!validSlot(slot)) { finish(); return }
        vm.slotId = slot
        val km = getSystemService(KeyguardManager::class.java)
        val userUnlocked = getSystemService(UserManager::class.java).isUserUnlocked
        locked = km.isKeyguardLocked || !userUnlocked
        if (!locked) vm.load()
        setContent {
            ReMedicordTheme {
                AlarmScreen(
                    vm = vm, locked = locked, canActLocked = userUnlocked,
                    onUnlock = {
                        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() { locked = false; vm.load() }
                        })
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_CHOICES = "choices"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmScreen(vm: AlarmViewModel, locked: Boolean, canActLocked: Boolean, onUnlock: () -> Unit, onClose: () -> Unit) {
    val items by vm.items.collectAsState()
    var skipping by remember { mutableStateOf<AlarmItem?>(null) }
    LaunchedEffect(items) { if (items?.isEmpty() == true) onClose() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(ReminderText.PUBLIC_TITLE, style = MaterialTheme.typography.headlineMedium)
            if (locked) {
                Text(ReminderText.PUBLIC_TEXT, style = MaterialTheme.typography.titleMedium)
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock to view") }
                if (canActLocked) OutlinedButton(onClick = { vm.laterAllGeneric(onClose) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Got it, I'll take it later")
                }
            } else {
                items?.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.text, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Button(onClick = { vm.take(item.doseId) }) { Text("Taken") }
                                OutlinedButton(onClick = { vm.later(item.doseId) }) { Text("Later") }
                                if (item.snoozeCount < vm.maxSnoozes) vm.snoozeOptions.forEach { m ->
                                    OutlinedButton(onClick = { vm.snooze(item.doseId, m) }) { Text("$m min") }
                                }
                                OutlinedButton(onClick = { skipping = item }) { Text("Skip this dose") }
                                OutlinedButton(onClick = { vm.pauseToday(item.medicationId) }) { Text("Don't remind me again today") }
                            }
                            Text("\"Later\" and \"Don't remind me again today\": you'll be asked whether you took it. Unanswered for 5 days counts as missed.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
    skipping?.let { item -> SkipDoseDialog(item, onDismiss = { skipping = null }) { reason -> vm.skip(item.doseId, reason); skipping = null } }
}

/** Skip with a reason; a must-complete course warns that the end date moves back. */
@Composable
private fun SkipDoseDialog(item: AlarmItem, onDismiss: () -> Unit, onSkip: (String) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip ${item.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.mustComplete) Text("This course must be completed. Skipping moves its end date back by one dose.", color = MaterialTheme.colorScheme.error)
                Text("Reason")
                ChoiceChips(SkipPolicy.PRESET_REASONS, reason, { it }) { reason = it }
            }
        },
        confirmButton = { TextButton(enabled = reason != null, onClick = { onSkip(reason!!) }) { Text("Skip") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
