package com.remedicord.ui.today

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.PrnPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.reminders.ReminderSettingsRepository
import com.remedicord.ui.Labels
import com.remedicord.ui.components.TimePickerDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayItem(val dose: DoseEventEntity, val med: MedicationEntity, val doseText: String)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val doses: DoseRepository,
    private val meds: MedicationRepository,
    private val actions: DoseActionService,
    private val reminderSettings: ReminderSettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val day = MutableStateFlow(LocalDate.now(time.zone()))

    private val medsFlow = meds.observeByStatus(*MedicationStatus.entries.toTypedArray())

    val items: StateFlow<List<TodayItem>> = day.flatMapLatest { d ->
        val from = DoseGenerator.resolve(d.atStartOfDay(), time.zone()).toEpochMilli()
        val to = DoseGenerator.resolve(d.plusDays(1).atStartOfDay(), time.zone()).toEpochMilli()
        combine(doses.observeBetween(from, to), medsFlow) { list, ms ->
            val byId = ms.associateBy { it.id }
            list.mapNotNull { dose ->
                val m = byId[dose.medicationId] ?: return@mapNotNull null
                val reg = dose.regimenId?.let { id -> meds.regimens(m.id).firstOrNull { it.id == id } }
                TodayItem(dose, m, reg?.let { Labels.dose(it.doseAmount, it.doseUnit) } ?: "")
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val needsReview: StateFlow<Int> = doses.observeWithStatus(DoseStatus.UNCONFIRMED).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Active as-needed medicines. */
    val prnMeds: StateFlow<List<MedicationEntity>> = meds.observeByStatus(MedicationStatus.ACTIVE).map { list ->
        list.filter { m -> meds.activeRegimen(m.id, time.nowMillis())?.let { ScheduleRule.fromJson(it.scheduleRuleJson) is ScheduleRule.AsNeeded } == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _prnConfirm = MutableStateFlow<Pair<Long, String>?>(null)
    val prnConfirm: StateFlow<Pair<Long, String>?> = _prnConfirm

    fun refreshDay() { day.value = LocalDate.now(time.zone()) }
    fun dismiss() { _message.value = null; _prnConfirm.value = null }

    fun take(id: Long) = viewModelScope.launch {
        val s = reminderSettings.load()
        if (!actions.take(id, time.now(), eyeDropGapMinutes = s.eyeDropGapMinutes)) _message.value = "This dose can't be marked as taken."
    }

    fun snooze(id: Long) = viewModelScope.launch {
        val s = reminderSettings.load()
        if (!actions.snooze(id, s.snoozeMinutes, s.maxSnoozes)) _message.value = "Snooze limit reached."
    }

    fun skip(id: Long) = viewModelScope.launch { actions.skip(id, "Skipped from Today") }

    fun logLate(id: Long, t: LocalTime) = viewModelScope.launch {
        val at = DoseGenerator.resolve(day.value.atTime(t), time.zone())
        if (!actions.logLate(id, at)) _message.value = "The time can't be in the future."
    }

    fun prnTake(medId: Long, confirmed: Boolean = false) = viewModelScope.launch {
        if (!confirmed) {
            when (val r = actions.prnCheck(medId)) {
                PrnPolicy.Result.Ok -> Unit
                is PrnPolicy.Result.TooSoon -> { _prnConfirm.value = medId to "The minimum gap isn't over yet (next from ${Labels.time(r.nextAllowedAt)}). Take anyway?"; return@launch }
                PrnPolicy.Result.DailyMaxReached -> { _prnConfirm.value = medId to "You've reached today's maximum for this medicine. Take anyway?"; return@launch }
            }
        }
        _prnConfirm.value = null
        actions.takePrn(medId)
        _message.value = "Recorded."
    }
}

/** S2 Today. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(vm: TodayViewModel, onOpenMedication: (Long) -> Unit, onReview: () -> Unit = {}) {
    val items by vm.items.collectAsState()
    val review by vm.needsReview.collectAsState()
    val prn by vm.prnMeds.collectAsState()
    val message by vm.message.collectAsState()
    val prnConfirm by vm.prnConfirm.collectAsState()
    var lateFor by remember { mutableStateOf<Long?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshDay() }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Today · ${Labels.date(Instant.now())}", style = MaterialTheme.typography.titleLarge)
        if (review > 0) Card(
            Modifier.fillMaxWidth().clickable(onClick = onReview),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Needs review: $review dose(s)", style = MaterialTheme.typography.titleMedium)
                Text("These were due while reMedicord wasn't running. Tap to say whether you took them.")
            }
        }
        if (items.isEmpty()) Text("No scheduled doses today.")
        items.groupBy { Labels.time(Instant.ofEpochMilli(it.dose.scheduledAt ?: it.dose.takenAt ?: 0)) }.forEach { (time, group) ->
            Text(time, style = MaterialTheme.typography.titleMedium)
            group.forEach { item -> DoseCard(item, vm, onOpenMedication) { lateFor = item.dose.id } }
        }
        if (prn.isNotEmpty()) {
            Text("As needed", style = MaterialTheme.typography.titleMedium)
            prn.forEach { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { onOpenMedication(m.id) })
                        Button(onClick = { vm.prnTake(m.id) }) { Text("${Labels.action(m.type)} now") }
                    }
                }
            }
        }
    }
    lateFor?.let { id -> TimePickerDialog(LocalTime.now().withSecond(0).withNano(0), { lateFor = null }) { t -> vm.logLate(id, t); lateFor = null } }
    message?.let { AlertDialog(onDismissRequest = vm::dismiss, text = { Text(it) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }) }
    prnConfirm?.let { (medId, text) ->
        AlertDialog(onDismissRequest = vm::dismiss, text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.prnTake(medId, confirmed = true) }) { Text("Take anyway") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoseCard(item: TodayItem, vm: TodayViewModel, onOpen: (Long) -> Unit, onLate: () -> Unit) {
    val d = item.dose
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${Labels.action(item.med.type)} ${item.med.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { onOpen(item.med.id) })
            Text(listOfNotNull(item.doseText.ifBlank { null }, item.med.side?.let(Labels::side), d.site?.takeIf { item.med.side == null },
                Labels.status(d.status) + (d.takenAt?.takeIf { d.status == DoseStatus.TAKEN }?.let { " at ${Labels.time(Instant.ofEpochMilli(it))}" } ?: ""),
                d.skipReason?.takeIf { d.status == DoseStatus.SKIPPED }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (d.status) {
                    DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED -> {
                        Button(onClick = { vm.take(d.id) }) { Text("Taken") }
                        if (d.status != DoseStatus.PLANNED) OutlinedButton(onClick = { vm.snooze(d.id) }) { Text("Snooze") }
                        OutlinedButton(onClick = { vm.skip(d.id) }) { Text("Skip") }
                    }
                    DoseStatus.MISSED -> OutlinedButton(onClick = onLate) { Text("I took it at…") }
                    else -> Unit
                }
            }
        }
    }
}
