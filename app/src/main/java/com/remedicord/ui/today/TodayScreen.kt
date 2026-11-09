package com.remedicord.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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
import com.remedicord.domain.policy.AmendPolicy
import com.remedicord.domain.policy.PrnPolicy
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.domain.usecase.DoseActionService.ReviewAnswer
import com.remedicord.reminders.ReminderSettingsRepository
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.DatePickerButton
import com.remedicord.ui.components.TimePickerDialog
import com.remedicord.ui.theme.StatusColors
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
    val today: LocalDate get() = LocalDate.now(time.zone())
    private val _day = MutableStateFlow(today)
    val day: StateFlow<LocalDate> = _day

    private val medsFlow = meds.observeByStatus(*MedicationStatus.entries.toTypedArray())

    val items: StateFlow<List<TodayItem>> = _day.flatMapLatest { d ->
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

    fun goTo(d: LocalDate) { _day.value = d }
    fun shift(days: Long) { _day.value = _day.value.plusDays(days) }
    fun refreshDay() { if (_day.value.isAfter(today)) _day.value = today }
    fun dismiss() { _message.value = null; _prnConfirm.value = null }
    fun canAmend(d: LocalDate) = AmendPolicy.canAmend(d, today)

    private fun done(ok: Boolean, failure: String) { if (!ok) _message.value = failure }

    fun take(id: Long) = viewModelScope.launch {
        done(actions.take(id, time.now(), eyeDropGapMinutes = reminderSettings.load().eyeDropGapMinutes), "This dose can't be marked as taken.")
    }

    fun snooze(id: Long) = viewModelScope.launch {
        val s = reminderSettings.load()
        done(actions.snooze(id, s.snoozeMinutes, s.maxSnoozes), "Snooze limit reached.")
    }

    fun later(id: Long) = viewModelScope.launch { actions.later(id) }
    fun skip(id: Long, reason: String) = viewModelScope.launch { actions.skip(id, reason) }
    fun pauseToday(medId: Long) = viewModelScope.launch { actions.pauseToday(medId); _message.value = "No more reminders for this medicine today. Fill in the doses later." }

    fun answer(id: Long, a: ReviewAnswer, at: LocalTime? = null) = viewModelScope.launch {
        val takenAt = at?.let { DoseGenerator.resolve(_day.value.atTime(it), time.zone()) }
        done(actions.review(id, a, takenAt), "Records older than 5 days can't be changed.")
        actions.finishReview()
    }

    fun logLate(id: Long, t: LocalTime) = viewModelScope.launch {
        done(actions.logLate(id, DoseGenerator.resolve(_day.value.atTime(t), time.zone())), "The time can't be in the future, and records older than 5 days can't be changed.")
    }

    fun edit(d: DoseEventEntity, status: DoseStatus) = viewModelScope.launch {
        done(actions.edit(d.id, status, null, d.note), "Records older than 5 days can't be changed.")
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

/** S2 Today, with any date selectable. Past days can be amended for 5 days. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayScreen(vm: TodayViewModel, onOpenMedication: (Long) -> Unit, onReview: () -> Unit = {}) {
    val items by vm.items.collectAsState()
    val day by vm.day.collectAsState()
    val review by vm.needsReview.collectAsState()
    val prn by vm.prnMeds.collectAsState()
    val message by vm.message.collectAsState()
    val prnConfirm by vm.prnConfirm.collectAsState()
    var timeFor by remember { mutableStateOf<Pair<Long, Boolean>?>(null) } // (doseId, isReviewAnswer)
    var skipping by remember { mutableStateOf<TodayItem?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshDay() }
    val isToday = day == vm.today
    val editable = vm.canAmend(day)
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.shift(-1) }) { Text("‹") }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isToday) "Today" else Labels.date(DoseGenerator.resolve(day.atStartOfDay(), java.time.ZoneId.systemDefault())), style = MaterialTheme.typography.titleLarge)
                DatePickerButton("Date", day) { vm.goTo(it) }
            }
            TextButton(onClick = { vm.shift(1) }) { Text("›") }
        }
        if (!isToday) TextButton(onClick = { vm.goTo(vm.today) }) { Text("Back to today") }
        if (!editable && day.isBefore(vm.today)) Text("Read-only: records older than 5 days can't be changed.", style = MaterialTheme.typography.bodySmall)
        if (review > 0) Card(
            Modifier.fillMaxWidth().clickable(onClick = onReview),
            colors = CardDefaults.cardColors(containerColor = StatusColors.container(DoseStatus.UNCONFIRMED)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Needs your answer: $review dose(s)", style = MaterialTheme.typography.titleMedium)
                Text("Tap to say whether you took them. Unanswered for 5 days counts as missed.")
            }
        }
        if (items.isEmpty()) Text(if (isToday) "No scheduled doses today." else "No records for this day.")
        items.filter { it.dose.status != DoseStatus.NOT_NEEDED || isToday }
            .groupBy { Labels.time(Instant.ofEpochMilli(it.dose.scheduledAt ?: it.dose.takenAt ?: 0)) }.forEach { (time, group) ->
                Text(time, style = MaterialTheme.typography.titleMedium)
                group.forEach { item ->
                    DoseCard(item, isToday, editable, vm, onOpenMedication,
                        onTime = { review -> timeFor = item.dose.id to review }, onSkip = { skipping = item })
                }
            }
        if (prn.isNotEmpty() && isToday) {
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
        Text("Colours: green taken · grey skipped · red missed · orange needs your answer · blue due now", style = MaterialTheme.typography.bodySmall)
    }
    timeFor?.let { (id, isReview) ->
        TimePickerDialog(LocalTime.now().withSecond(0).withNano(0), { timeFor = null }) { t ->
            if (isReview) vm.answer(id, ReviewAnswer.TAKEN, t) else vm.logLate(id, t)
            timeFor = null
        }
    }
    skipping?.let { item ->
        var reason by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { skipping = null },
            title = { Text("Skip ${item.med.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.med.mustComplete) Text("This course must be completed. Skipping moves its end date back by one dose.", color = MaterialTheme.colorScheme.error)
                    ChoiceChips(SkipPolicy.PRESET_REASONS, reason, { it }) { reason = it }
                }
            },
            confirmButton = { TextButton(enabled = reason != null, onClick = { vm.skip(item.dose.id, reason!!); skipping = null }) { Text("Skip") } },
            dismissButton = { TextButton(onClick = { skipping = null }) { Text("Cancel") } },
        )
    }
    message?.let { AlertDialog(onDismissRequest = vm::dismiss, text = { Text(it) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }) }
    prnConfirm?.let { (medId, text) ->
        AlertDialog(onDismissRequest = vm::dismiss, text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.prnTake(medId, confirmed = true) }) { Text("Take anyway") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoseCard(
    item: TodayItem, isToday: Boolean, editable: Boolean, vm: TodayViewModel, onOpen: (Long) -> Unit,
    onTime: (review: Boolean) -> Unit, onSkip: () -> Unit,
) {
    val d = item.dose
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = StatusColors.container(d.status))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${Labels.action(item.med.type)} ${item.med.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { onOpen(item.med.id) })
            Text(listOfNotNull(item.doseText.ifBlank { null }, item.med.side?.let(Labels::side), d.site?.takeIf { item.med.side == null },
                Labels.status(d.status) + (d.takenAt?.takeIf { d.status == DoseStatus.TAKEN }?.let { " at ${Labels.time(Instant.ofEpochMilli(it))}" } ?: ""),
                Labels.pending(d.pendingReason).takeIf { d.status == DoseStatus.UNCONFIRMED },
                d.skipReason?.takeIf { d.status == DoseStatus.SKIPPED || d.status == DoseStatus.MISSED }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    d.status in setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED) && isToday -> {
                        Button(onClick = { vm.take(d.id) }) { Text("Taken") }
                        OutlinedButton(onClick = { vm.later(d.id) }) { Text("Later") }
                        if (d.status != DoseStatus.PLANNED) OutlinedButton(onClick = { vm.snooze(d.id) }) { Text("Snooze") }
                        OutlinedButton(onClick = onSkip) { Text("Skip") }
                        if (d.status != DoseStatus.PLANNED) OutlinedButton(onClick = { vm.pauseToday(item.med.id) }) { Text("No more reminders today") }
                    }
                    !editable -> Unit
                    d.status == DoseStatus.UNCONFIRMED -> {
                        Button(onClick = { vm.answer(d.id, ReviewAnswer.TAKEN) }) { Text("Taken") }
                        OutlinedButton(onClick = { onTime(true) }) { Text("Taken at…") }
                        OutlinedButton(onClick = { vm.answer(d.id, ReviewAnswer.MISSED) }) { Text("Missed") }
                        OutlinedButton(onClick = { vm.answer(d.id, ReviewAnswer.SKIPPED) }) { Text("Skipped") }
                    }
                    d.status == DoseStatus.MISSED -> OutlinedButton(onClick = { onTime(false) }) { Text("I took it at…") }
                    d.status == DoseStatus.TAKEN -> TextButton(onClick = { vm.edit(d, DoseStatus.MISSED) }) { Text("Change to missed") }
                    d.status == DoseStatus.SKIPPED -> TextButton(onClick = { vm.edit(d, DoseStatus.TAKEN) }) { Text("Change to taken") }
                    else -> Unit
                }
            }
        }
    }
}
