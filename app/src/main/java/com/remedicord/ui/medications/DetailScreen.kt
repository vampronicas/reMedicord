package com.remedicord.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.remedicord.domain.model.DoseStatus
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
    /** v1.4 */
    val history: List<com.remedicord.ui.ChangeHistory.Entry> = emptyList(),
    val replacedBy: MedicationEntity? = null,
    val replaces: MedicationEntity? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val repo: MedicationRepository,
    private val skipRepo: SkipRuleRepository,
    private val service: MedicationService,
    private val doseRepo: com.remedicord.data.repo.DoseRepository,
    private val backup: com.remedicord.data.backup.BackupService,
    private val auditDao: com.remedicord.data.db.dao.AuditLogDao,
    private val time: TimeProvider,
) : ViewModel() {
    val id: Long = checkNotNull(handle.get<Long>("id"))
    private val _s = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _s
    private val _deleteInfo = MutableStateFlow<MedicationService.DeleteInfo?>(null)
    val deleteInfo: StateFlow<MedicationService.DeleteInfo?> = _deleteInfo
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted
    val today: LocalDate get() = LocalDate.now(time.zone())

    init {
        viewModelScope.launch {
            combine(repo.observe(id), repo.observeRegimens(id)) { m, r -> m to r }.collect { (m, r) ->
                val zone = m?.zoneSettings()?.effectiveZone(time.zone()) ?: time.zone()
                val upcoming = if (m?.status == MedicationStatus.ACTIVE)
                    DoseGenerator.generate(r.map { it.toDomain() }, zone, time.now(), time.now().plus(Duration.ofDays(14))).take(5).map { it.at }
                else emptyList()
                val history = com.remedicord.ui.ChangeHistory.build(
                    auditDao.forEntity(com.remedicord.data.repo.AuditLogger.MEDICATION, id),
                    r.flatMap { reg -> auditDao.forEntity(com.remedicord.data.repo.AuditLogger.REGIMEN, reg.id) }, time.zone(),
                )
                _s.update {
                    it.copy(med = m, regimens = r, upcoming = upcoming, skips = skipRepo.active(id), course = m?.let { x -> service.courseView(x) },
                        history = history, replacedBy = m?.replacedById?.let { o -> repo.get(o) }, replaces = m?.replacesId?.let { o -> repo.get(o) })
                }
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
    /** E7: [until] null = until I resume. */
    fun pause(until: LocalDate?, reason: String?) = run { service.pause(id, until, reason) }
    fun resume() = run { service.resume(id) }
    fun restart() = run { service.restart(id) }
    fun cancelSkip(ruleId: Long) = viewModelScope.launch { service.cancelSkip(ruleId); _s.update { it.copy(skips = skipRepo.active(id)) } }

    fun changeDose(amount: BigDecimal, unit: DoseUnit, rule: ScheduleRule, from: LocalDate?, reason: String) = run {
        val at = from?.let { DoseGenerator.resolve(it.atStartOfDay(), time.zone()) } ?: time.now()
        service.changeDose(id, amount, unit, rule, at, reason)
    }

    // ---- E4 delete
    fun askDelete() = viewModelScope.launch { _deleteInfo.value = service.deleteInfo(id) }
    fun cancelDelete() { _deleteInfo.value = null }

    fun delete(typedName: String, warningAcknowledged: Boolean) = viewModelScope.launch {
        val info = _deleteInfo.value ?: return@launch
        if (!typedName.trim().equals(MedicationService.DELETE_WORD, ignoreCase = true)) return@launch
        backup.saveSnapshot() // undo point (Backup & restore → snapshots)
        val openSlots = doseRepo.forMedication(id).filter { it.status == DoseStatus.DUE || it.status == DoseStatus.SNOOZED }.mapNotNull { it.slotId }
        when (val r = service.delete(id, typedName, warningAcknowledged)) {
            ActionResult.Done -> {
                openSlots.forEach { com.remedicord.reminders.Notifications.cancel(context, it.toInt()) }
                _deleteInfo.value = null
                _deleted.value = true
            }
            is ActionResult.Invalid -> _s.update { it.copy(message = r.errors.joinToString("\n")) }
            is ActionResult.Blocked -> _s.update { it.copy(message = r.message) }
            is ActionResult.NeedsConfirmation -> _s.update { it.copy(message = r.message) }
        }
    }
}

/** S5 medication detail (E6): bold summary card, badges, actions, danger zone. With S6–S8, pause (E7), delete (E4). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vm: DetailViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit,
    onOpenMedication: (Long) -> Unit = {}, onReplace: (Long) -> Unit = {},
) {
    val s by vm.state.collectAsState()
    val deleted by vm.deleted.collectAsState()
    val deleteInfo by vm.deleteInfo.collectAsState()
    androidx.compose.runtime.LaunchedEffect(deleted) { if (deleted) onBack() }
    val m = s.med ?: return
    val current = s.regimens.lastOrNull { it.effectiveTo == null } ?: s.regimens.lastOrNull()
    Scaffold(topBar = { TopAppBar(title = { Text("Medicine") }, navigationIcon = { TextButton(onClick = onBack) { Text("← Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // ---- v1.4 replacement links (tap to move between the old and the new medicine)
            s.replacedBy?.let { n ->
                LinkCard("${Labels.replacedTo(n.replaceKind)} ${n.name}", "on ${m.replacedAt?.let { Labels.date(Instant.ofEpochMilli(it)) } ?: "?"} · tap to open", "›") { onOpenMedication(n.id) }
            }
            s.replaces?.let { o ->
                LinkCard("${Labels.replacedFrom(m.replaceKind)} ${o.name}", "since ${o.replacedAt?.let { Labels.date(Instant.ofEpochMilli(it)) } ?: Labels.date(Instant.ofEpochMilli(m.createdAt))} · tap to open", "‹") { onOpenMedication(o.id) }
            }

            // ---- hero summary card
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        com.remedicord.ui.components.MedIcon(m.type, m.colorTag, 48)
                        Column {
                            Text(m.name, style = MaterialTheme.typography.headlineSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text(Labels.type(m.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(statusLabel(m), MaterialTheme.colorScheme.primaryContainer)
                        if (m.mustComplete) Badge("Must complete", MaterialTheme.colorScheme.errorContainer)
                        if ((m.remindersMutedUntil ?: 0) > System.currentTimeMillis()) Badge("Reminders off today", MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    KeyValue("Dose", current?.let { "${Labels.action(m.type)} ${Labels.dose(it.doseAmount, it.doseUnit)}" + (m.side?.let { sd -> " · ${Labels.side(sd)}" } ?: "") } ?: "—")
                    KeyValue("Schedule", current?.let { Labels.rule(ScheduleRule.fromJson(it.scheduleRuleJson)) } ?: "—")
                    KeyValue("Next dose", s.upcoming.firstOrNull()?.let { Labels.dateTime(it) } ?: if (m.status == MedicationStatus.ACTIVE) "—" else "None while ${statusLabel(m).lowercase()}")
                    KeyValue("Why", Labels.purpose(m.purpose) + (m.purposeNote.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
                    s.course?.let { c ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Stacked (not side by side), so a long end-date line can't squeeze the title into one letter per line.
                            Text("Course: ${c.progress.taken} / ${c.progress.required} taken", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text(if (c.progress.isComplete) "Course complete" else listOfNotNull(c.projectedEnd?.let { "Ends " + Labels.date(it) },
                                c.progress.extension.takeIf { it > 0 }?.let { "+$it missed or skipped" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { if (c.progress.required == 0) 0f else (c.progress.taken.toFloat() / c.progress.required).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ---- actions
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (m.status) {
                    MedicationStatus.ACTIVE -> {
                        Button(onClick = { vm.open("dose") }) { Text("Change dose") }
                        OutlinedButton(onClick = { vm.open("skip") }) { Text("Skip…") }
                        OutlinedButton(onClick = { vm.open("pause") }, enabled = !m.mustComplete) { Text("Pause") }
                        OutlinedButton(onClick = { vm.open("stop") }) { Text("Stop") }
                        OutlinedButton(onClick = { onReplace(m.id) }) { Text("Replace…") }
                    }
                    MedicationStatus.PAUSED -> {
                        Button(onClick = vm::resume) { Text("Resume now") }
                        OutlinedButton(onClick = { vm.open("pause") }) { Text("Change pause") }
                        OutlinedButton(onClick = { vm.open("stop") }) { Text("Stop") }
                        OutlinedButton(onClick = { onReplace(m.id) }) { Text("Replace…") }
                    }
                    MedicationStatus.STOPPED, MedicationStatus.COMPLETED -> Button(onClick = vm::restart) { Text("Restart") }
                }
                OutlinedButton(onClick = { onEdit(m.id) }) { Text("Edit details") }
            }
            if (m.mustComplete && m.status == MedicationStatus.ACTIVE) Text("Must-complete courses can't be paused.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.status == MedicationStatus.PAUSED) Text(
                (m.pausedUntil?.let { "Paused until ${Labels.date(Instant.ofEpochMilli(it).minusSeconds(1))}; reminders resume automatically." } ?: "Paused until you resume.") +
                    (m.pauseReason?.let { " Reason: $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )

            // ---- secondary sections
            val details = listOfNotNull(
                m.instructions.takeIf { it.isNotBlank() }?.let { "Instructions" to it },
                m.bodyLocation?.let { "Location" to it }, m.dressingType?.let { "Dressing" to it },
                m.enabledSites?.let { "Sites" to it.joinToString(", ") },
                m.stopReason?.let { "Stopped" to (Labels.stopReason(it) + (m.stopNote?.let { n -> " — $n" } ?: "")) },
            )
            if (details.isNotEmpty()) {
                SectionTitle("Details")
                details.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
            }
            if (s.upcoming.size > 1) {
                SectionTitle("Coming up")
                s.upcoming.forEach { Text(Labels.dateTime(it), style = MaterialTheme.typography.bodyMedium) }
            }
            if (s.skips.isNotEmpty()) {
                SectionTitle("Active skips")
                s.skips.forEach { r ->
                    Text("${when (r.mode) { SkipMode.NEXT_N_DOSES -> "Next ${r.count} dose(s)"; SkipMode.DAYS -> "${r.count} day(s)"; SkipMode.UNTIL_DATE -> "Until ${r.untilEpochDay?.let(LocalDate::ofEpochDay)}" }} — ${r.reason}")
                    TextButton(onClick = { vm.cancelSkip(r.id) }) { Text("Cancel this skip") }
                }
            }
            if (s.history.isNotEmpty()) {
                SectionTitle("Change history")
                var showAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                (if (showAll) s.history else s.history.take(6)).forEach { e -> HistoryRow(e) }
                if (s.history.size > 6) TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all (${s.history.size})") }
            }

            // ---- delete (E4)
            SectionTitle("Added by mistake?")
            OutlinedButton(onClick = vm::askDelete, colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Delete medicine…")
            }
            Text("Deleting removes this medicine and all its records. To end a treatment, use Stop instead (keeps history).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    deleteInfo?.let { info -> DeleteDialog(info, onCancel = vm::cancelDelete) { typed, ack -> vm.delete(typed, ack) } }
    if (s.dialog == "pause") PauseSheet(m.name, vm.today, onDismiss = { vm.open(null) }) { until, reason -> vm.pause(until, reason) }

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
                ChoiceChips(StopReason.entries.filter { it != StopReason.REPLACED }, reason, Labels::stopReason) { reason = it }
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
