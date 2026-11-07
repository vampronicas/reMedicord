package com.remedicord.reminders

import android.app.KeyguardManager
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.activity.viewModels
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.AppDatabase
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.schedule.ReminderText
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.ui.theme.ReMedicordTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AlarmItem(val doseId: Long, val text: String, val snoozeCount: Int)

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
            AlarmItem(dose.id, ReminderText.line(ReminderText.Line(m.type, m.name, reg?.doseAmount, reg?.doseUnit, m.side, site)) +
                (m.instructions.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""), dose.snoozeCount)
        }
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch { block(); engine.get().refreshNotification(slotId); load() }

    fun take(id: Long) = act { actions.get().take(id, time.now(), eyeDropGapMinutes = settings.get().load().eyeDropGapMinutes) }
    fun snooze(id: Long, min: Int) = act { actions.get().snooze(id, min, maxSnoozes) }
    fun skip(id: Long) = act { actions.get().skip(id, "Skipped from reminder") }

    /** Snooze everything without revealing names (allowed while the phone is locked). */
    fun snoozeAllGeneric(onDone: () -> Unit) = viewModelScope.launch {
        val s = settings.get().load()
        db.get().doseEventDao().openInSlot(slotId).filter { it.status == DoseStatus.DUE }.forEach { actions.get().snooze(it.id, s.snoozeMinutes, s.maxSnoozes) }
        engine.get().refreshNotification(slotId)
        onDone()
    }
}

/**
 * S10 full-screen alarm. While the phone is locked it shows ONLY a generic reminder with Snooze and
 * "Unlock to view" (PLAN.md A2); medicine details load after the user unlocks. No app PIN needed.
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
                    vm = vm, locked = locked, canSnoozeLocked = userUnlocked,
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmScreen(vm: AlarmViewModel, locked: Boolean, canSnoozeLocked: Boolean, onUnlock: () -> Unit, onClose: () -> Unit) {
    val items by vm.items.collectAsState()
    LaunchedEffect(items) { if (items?.isEmpty() == true) onClose() }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(ReminderText.PUBLIC_TITLE, style = MaterialTheme.typography.headlineMedium)
            if (locked) {
                Text(ReminderText.PUBLIC_TEXT, style = MaterialTheme.typography.titleMedium)
                Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock to view") }
                if (canSnoozeLocked) OutlinedButton(onClick = { vm.snoozeAllGeneric(onClose) }, modifier = Modifier.fillMaxWidth()) { Text("Snooze") }
            } else {
                items?.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.text, style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.take(item.doseId) }) { Text("Taken") }
                                if (item.snoozeCount < vm.maxSnoozes) vm.snoozeOptions.forEach { m ->
                                    OutlinedButton(onClick = { vm.snooze(item.doseId, m) }) { Text("$m min") }
                                }
                                OutlinedButton(onClick = { vm.skip(item.doseId) }) { Text("Skip") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}
