package com.remedicord.ui.medications

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.db.entity.RegimenEntity
import com.remedicord.data.db.entity.SkipRuleEntity
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.data.repo.SkipRuleRepository
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.SkipMode
import com.remedicord.domain.model.StopReason
import com.remedicord.domain.policy.SkipPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.ActionResult
import com.remedicord.domain.usecase.CourseView
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.domain.usecase.toDomain
import com.remedicord.domain.usecase.zoneSettings
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.DatePickerButton
import com.remedicord.ui.components.DecimalField
import com.remedicord.ui.components.IntField
import com.remedicord.ui.components.RuleDraft
import com.remedicord.ui.components.ScheduleBuilder
import com.remedicord.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailState(
    val med: MedicationEntity? = null,
    val regimens: List<RegimenEntity> = emptyList(),
    val skips: List<SkipRuleEntity> = emptyList(),
    val course: CourseView? = null,
    val upcoming: List<Instant> = emptyList(),
    val dialog: String? = null,
    /** Message from the last action (blocked / confirmation / error). */
    val message: String? = null,
    val pendingConfirm: (suspend () -> ActionResult)? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: MedicationRepository,
    private val skipRepo: SkipRuleRepository,
    private val service: MedicationService,
    private val time: TimeProvider,
) : ViewModel() {
    val id: Long = checkNotNull(handle.get<Long>("id"))
    private val _s = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _s

    init {
        viewModelScope.launch {
            combine(repo.observe(id), repo.observeRegimens(id)) { m, r -> m to r }.collect { (m, r) ->
                val zone = m?.zoneSettings()?.effectiveZone(time.zone()) ?: time.zone()
                val upcoming = if (m?.status == MedicationStatus.ACTIVE)
                    DoseGenerator.generate(r.map { it.toDomain() }, zone, time.now(), time.now().plus(Duration.ofDays(14))).take(5).map { it.at }
                else emptyList()
                _s.update { it.copy(med = m, regimens = r, upcoming = upcoming, skips = skipRepo.active(id), course = m?.let { x -> service.courseView(x) }) }
            }
        }
    }

    fun open(dialog: String?) = _s.update { it.copy(dialog = dialog, message = null) }
    fun dismissMessage() = _s.update { it.copy(message = null, pendingConfirm = null) }

    private fun run(action: suspend (confirmed: Boolean) -> ActionResult) = viewModelScope.launch {
        handle(action(false)) { action(true) }
    }

    private suspend fun handle(r: ActionResult, confirm: suspend () -> ActionResult) {
        when (r) {
            ActionResult.Done -> _s.update { it.copy(dialog = null, message = null, pendingConfirm = null, skips = skipRepo.active(id)) }
            is ActionResult.Blocked -> _s.update { it.copy(message = r.message, pendingConfirm = null) }
            is ActionResult.Invalid -> _s.update { it.copy(message = r.errors.joinToString("\n"), pendingConfirm = null) }
            is ActionResult.NeedsConfirmation -> _s.update { it.copy(message = r.message, pendingConfirm = confirm) }
        }
    }

    fun confirmPending() = viewModelScope.launch {
        val c = _s.value.pendingConfirm ?: return@launch
        handle(c()) { c() }
    }

    fun skip(mode: SkipMode?, count: Int?, until: LocalDate?, reason: String, note: String?) =
        run { c -> service.skip(id, mode, count, until, reason, note?.ifBlank { null }, c) }

    fun stop(reason: StopReason, note: String?) = run { c -> service.stop(id, reason, note?.ifBlank { null }, c) }
    fun pause() = run { service.pause(id) }
    fun resume() = run { service.resume(id) }
    fun restart() = run { service.restart(id) }
    fun cancelSkip(ruleId: Long) = viewModelScope.launch { service.cancelSkip(ruleId); _s.update { it.copy(skips = skipRepo.active(id)) } }

    fun changeDose(amount: BigDecimal, unit: DoseUnit, rule: ScheduleRule, from: LocalDate?, reason: String) = run {
        val at = from?.let { DoseGenerator.resolve(it.atStartOfDay(), time.zone()) } ?: time.now()
        service.changeDose(id, amount, unit, rule, at, reason)
    }
}

/** S5 medication detail with S6 change dose, S7 skip and S8 stop. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val s by vm.state.collectAsState()
    val m = s.med ?: return
    val current = s.regimens.lastOrNull { it.effectiveTo == null } ?: s.regimens.lastOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text(m.name) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${Labels.type(m.type)} · ${m.status.name.lowercase().replaceFirstChar(Char::uppercase)}" + (if (m.mustComplete) " · must complete" else ""))
            m.side?.let { Text("Side: ${Labels.side(it)}") }
            m.bodyLocation?.let { Text("Location: $it") }
            m.dressingType?.let { Text("Dressing: $it") }
            m.enabledSites?.let { Text("Sites: ${it.joinToString(", ")}") }
            if (m.instructions.isNotBlank()) Text("Instructions: ${m.instructions}")
            m.stopReason?.let { Text("Stopped: ${Labels.stopReason(it)}" + (m.stopNote?.let { n -> " — $n" } ?: "")) }

            current?.let { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        SectionTitle("Current dose")
                        Text("${Labels.action(m.type)} ${Labels.dose(r.doseAmount, r.doseUnit)}")
                        Text(Labels.rule(ScheduleRule.fromJson(r.scheduleRuleJson)))
                    }
                }
            }
            s.course?.let { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        SectionTitle("Course")
                        Text("${c.progress.taken} / ${c.progress.required} taken" + if (c.progress.extension > 0) " · +${c.progress.extension} missed/skipped" else "")
                        Text(if (c.progress.isComplete) "Course complete" else c.projectedEnd?.let { "Projected end: ${Labels.dateTime(it)}" } ?: "")
                    }
                }
            }
            if (s.upcoming.isNotEmpty()) {
                SectionTitle("Next doses")
                s.upcoming.forEach { Text(Labels.dateTime(it)) }
            }
            if (s.skips.isNotEmpty()) {
                SectionTitle("Active skips")
                s.skips.forEach { r ->
                    Text("${when (r.mode) { SkipMode.NEXT_N_DOSES -> "Next ${r.count} dose(s)"; SkipMode.DAYS -> "${r.count} day(s)"; SkipMode.UNTIL_DATE -> "Until ${r.untilEpochDay?.let(LocalDate::ofEpochDay)}" }} — ${r.reason}")
                    TextButton(onClick = { vm.cancelSkip(r.id) }) { Text("Cancel this skip") }
                }
            }
            SectionTitle("Actions")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (m.status) {
                    MedicationStatus.ACTIVE -> {
                        Button(onClick = { vm.open("dose") }) { Text("Change dose") }
                        OutlinedButton(onClick = { vm.open("skip") }) { Text("Skip…") }
                        OutlinedButton(onClick = vm::pause) { Text("Pause") }
                        OutlinedButton(onClick = { vm.open("stop") }) { Text("Stop") }
                    }
                    MedicationStatus.PAUSED -> {
                        Button(onClick = vm::resume) { Text("Resume") }
                        OutlinedButton(onClick = { vm.open("stop") }) { Text("Stop") }
                    }
                    MedicationStatus.STOPPED, MedicationStatus.COMPLETED -> Button(onClick = vm::restart) { Text("Restart") }
                }
                OutlinedButton(onClick = { onEdit(m.id) }) { Text("Edit details") }
            }
            if (s.regimens.size > 1) {
                SectionTitle("Dose history")
                s.regimens.reversed().forEach { r ->
                    Text("${Labels.date(Instant.ofEpochMilli(r.effectiveFrom))}: ${Labels.dose(r.doseAmount, r.doseUnit)}, ${Labels.rule(ScheduleRule.fromJson(r.scheduleRuleJson))} — ${r.changeReason}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    when (s.dialog) {
        "skip" -> SkipDialog(onDismiss = { vm.open(null) }, onSkip = vm::skip)
        "stop" -> StopDialog(onDismiss = { vm.open(null) }, onStop = vm::stop)
        "dose" -> current?.let { ChangeDoseDialog(it, onDismiss = { vm.open(null) }, onSave = vm::changeDose) }
    }
    s.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            text = { Text(msg) },
            confirmButton = {
                if (s.pendingConfirm != null) TextButton(onClick = vm::confirmPending) { Text("Confirm") }
                else TextButton(onClick = vm::dismissMessage) { Text("OK") }
            },
            dismissButton = { if (s.pendingConfirm != null) TextButton(onClick = vm::dismissMessage) { Text("Cancel") } },
        )
    }
}

private enum class SkipChoice(val label: String) { ONE("Next dose only"), N("Next N doses"), DAYS("Number of days"), UNTIL("Until a date") }

@Composable
private fun SkipDialog(onDismiss: () -> Unit, onSkip: (SkipMode?, Int?, LocalDate?, String, String?) -> Unit) {
    var choice by remember { mutableStateOf(SkipChoice.ONE) }
    var count by remember { mutableStateOf<Int?>(1) }
    var until by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var reason by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(SkipChoice.entries, choice, { it.label }) { choice = it }
                if (choice == SkipChoice.N || choice == SkipChoice.DAYS) IntField(if (choice == SkipChoice.N) "Doses" else "Days", count, { count = it })
                if (choice == SkipChoice.UNTIL) DatePickerButton("Until", until) { until = it }
                Text("Reason")
                ChoiceChips(SkipPolicy.PRESET_REASONS, reason, { it }) { reason = it }
                OutlinedTextField(note, { if (it.length <= 200) note = it }, label = { Text("Note (optional)") })
            }
        },
        confirmButton = {
            TextButton(enabled = reason != null, onClick = {
                val mode = when (choice) { SkipChoice.ONE -> null; SkipChoice.N -> SkipMode.NEXT_N_DOSES; SkipChoice.DAYS -> SkipMode.DAYS; SkipChoice.UNTIL -> SkipMode.UNTIL_DATE }
                onSkip(mode, count, until.takeIf { choice == SkipChoice.UNTIL }, reason!!, note)
            }) { Text("Skip") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StopDialog(onDismiss: () -> Unit, onStop: (StopReason, String?) -> Unit) {
    var reason by remember { mutableStateOf<StopReason?>(null) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop medicine") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Why are you stopping?")
                ChoiceChips(StopReason.entries, reason, Labels::stopReason) { reason = it }
                OutlinedTextField(note, { if (it.length <= 500) note = it }, label = { Text("Note") })
            }
        },
        confirmButton = { TextButton(enabled = reason != null, onClick = { onStop(reason!!, note) }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangeDoseDialog(current: RegimenEntity, onDismiss: () -> Unit, onSave: (BigDecimal, DoseUnit, ScheduleRule, LocalDate?, String) -> Unit) {
    var amount by remember { mutableStateOf(current.doseAmount.stripTrailingZeros().toPlainString()) }
    var draft by remember { mutableStateOf(RuleDraft.from(ScheduleRule.fromJson(current.scheduleRuleJson))) }
    var fromNow by remember { mutableStateOf(true) }
    var from by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change dose") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Amount (${Labels.unit(current.doseUnit, BigDecimal.TEN)})", amount, { amount = it })
                ScheduleBuilder(draft, { draft = it })
                ChoiceChips(listOf(true, false), fromNow, { if (it) "From now" else "From a date" }) { fromNow = it }
                if (!fromNow) DatePickerButton("From", from) { from = it }
                OutlinedTextField(reason, { if (it.length <= 200) reason = it }, label = { Text("Reason (e.g. doctor increased dose)") })
            }
        },
        confirmButton = {
            TextButton(enabled = draft.toRule() != null && reason.isNotBlank(), onClick = {
                onSave(amount.toBigDecimalOrNull() ?: BigDecimal.ZERO, current.doseUnit, draft.toRule()!!, from.takeIf { !fromNow }, reason)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
