package com.remedicord.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.dao.AuditLogDao
import com.remedicord.data.db.entity.AuditLogEntity
import com.remedicord.data.db.entity.DoseEventEntity
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.AuditLogger
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.policy.AmendPolicy
import com.remedicord.domain.policy.HistoryCalc
import com.remedicord.domain.policy.HistoryCalc.StatusFilter
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.TimePickerDialog
import com.remedicord.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryState(
    val month: YearMonth,
    val meds: List<MedicationEntity> = emptyList(),
    val medFilter: Set<Long> = emptySet(),
    val statusFilter: Set<StatusFilter> = emptySet(),
    /** Doses after both filters. */
    val doses: List<DoseEventEntity> = emptyList(),
    val adherence: Triple<Int?, Int?, Int?> = Triple(null, null, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doses: DoseRepository,
    meds: MedicationRepository,
    private val actions: DoseActionService,
    private val auditDao: AuditLogDao,
    private val time: TimeProvider,
) : ViewModel() {
    val zone get() = time.zone()
    val today: LocalDate get() = LocalDate.now(zone)
    private val month = MutableStateFlow(YearMonth.now(time.zone()))
    private val medFilter = MutableStateFlow<Set<Long>>(emptySet())
    private val statusFilter = MutableStateFlow<Set<StatusFilter>>(emptySet())
    val selected = MutableStateFlow<LocalDate?>(LocalDate.now(time.zone()))

    val state: StateFlow<HistoryState> = combine(month, medFilter, statusFilter) { m, mf, sf -> Triple(m, mf, sf) }.flatMapLatest { (m, mf, sf) ->
        val from = minOf(m.atDay(1), today.minusDays(89))
        val fromMs = DoseGenerator.resolve(from.atStartOfDay(), zone).toEpochMilli()
        val toMs = DoseGenerator.resolve(maxOf(m.atEndOfMonth(), today).plusDays(1).atStartOfDay(), zone).toEpochMilli()
        combine(doses.observeBetween(fromMs, toMs), meds.observeByStatus(*MedicationStatus.entries.toTypedArray())) { list, ms ->
            val byMed = list.filter { mf.isEmpty() || it.medicationId in mf }
            // Adherence follows the medicine filter only (a status filter would distort it).
            val entries = byMed.mapNotNull { d -> HistoryCalc.dayOf(d.scheduledAt, d.takenAt, zone)?.let { it to d.status } }
            HistoryState(m, ms, mf, sf, byMed.filter { HistoryCalc.matches(it.status, sf) },
                Triple(HistoryCalc.adherence(entries, today, 7), HistoryCalc.adherence(entries, today, 30), HistoryCalc.adherence(entries, today, 90)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState(YearMonth.now(time.zone())))

    fun shiftMonth(by: Long) { month.value = month.value.plusMonths(by) }
    fun toggleMed(id: Long) { medFilter.value = medFilter.value.let { if (id in it) it - id else it + id } }
    fun toggleStatus(f: StatusFilter) { statusFilter.value = statusFilter.value.let { if (f in it) it - f else it + f } }
    fun clearFilters() { medFilter.value = emptySet(); statusFilter.value = emptySet() }
    fun select(d: LocalDate) { selected.value = d }
    fun canAmend(d: LocalDate) = AmendPolicy.canAmend(d, today)

    suspend fun auditFor(doseId: Long): List<AuditLogEntity> = auditDao.forEntity(AuditLogger.DOSE, doseId)

    fun edit(d: DoseEventEntity, status: DoseStatus, takenTime: LocalTime?, note: String) = viewModelScope.launch {
        val day = HistoryCalc.dayOf(d.scheduledAt, d.takenAt, zone) ?: today
        actions.edit(d.id, status, takenTime?.let { DoseGenerator.resolve(day.atTime(it), zone) }, note)
    }

    fun answer(d: DoseEventEntity, a: DoseActionService.ReviewAnswer) = viewModelScope.launch { actions.review(d.id, a); actions.finishReview() }
}

private fun filterLabel(f: StatusFilter) = when (f) {
    StatusFilter.TAKEN -> "Taken (green)"; StatusFilter.MISSED -> "Missed (red)"; StatusFilter.NEEDS_ANSWER -> "Needs answer (orange)"
    StatusFilter.SKIPPED -> "Skipped (grey)"; StatusFilter.UPCOMING -> "Upcoming"
}

private fun markerLabel(m: HistoryCalc.Marker) = when (m) {
    HistoryCalc.Marker.NONE -> "no doses"; HistoryCalc.Marker.ALL_TAKEN -> "all taken"; HistoryCalc.Marker.HAS_MISSED -> "missed dose"
    HistoryCalc.Marker.NEEDS_REVIEW -> "needs your answer"; HistoryCalc.Marker.SKIPPED_ONLY -> "skipped"; HistoryCalc.Marker.PARTIAL -> "partly taken"
}

/** S9 History. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val s by vm.state.collectAsState()
    val selected by vm.selected.collectAsState()
    var editing by remember { mutableStateOf<DoseEventEntity?>(null) }
    val byDay = s.doses.groupBy { HistoryCalc.dayOf(it.scheduledAt, it.takenAt, vm.zone) }
    val names = s.meds.associate { it.id to it.name }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Adherence  7 d: ${s.adherence.first?.let { "$it%" } ?: "–"}   30 d: ${s.adherence.second?.let { "$it%" } ?: "–"}   90 d: ${s.adherence.third?.let { "$it%" } ?: "–"}",
            style = MaterialTheme.typography.titleSmall)
        Text("Medicines (select one or more)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.meds.forEach { m -> FilterChip(selected = m.id in s.medFilter, onClick = { vm.toggleMed(m.id) }, label = { Text(m.name) }) }
        }
        Text("Show (select one or more)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusFilter.entries.forEach { f -> FilterChip(selected = f in s.statusFilter, onClick = { vm.toggleStatus(f) }, label = { Text(filterLabel(f)) }) }
        }
        if (s.medFilter.isNotEmpty() || s.statusFilter.isNotEmpty()) TextButton(onClick = vm::clearFilters) { Text("Clear filters (show everything)") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.shiftMonth(-1) }) { Text("‹ Prev") }
            Text("${s.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${s.month.year}", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { vm.shiftMonth(1) }) { Text("Next ›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
        }
        HistoryCalc.monthGrid(s.month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val marker = HistoryCalc.marker(byDay[day].orEmpty().map { it.status })
                            val bg = if (day == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            Column(
                                Modifier.fillMaxSize().background(bg, CircleShape).clickable { vm.select(day) }
                                    .semantics { contentDescription = "$day, ${markerLabel(marker)}" },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text(day.dayOfMonth.toString())
                                StatusColors.marker(marker)?.let { Box(Modifier.size(7.dp).background(it, CircleShape)) }
                            }
                        }
                    }
                }
            }
        }
        Text("Dots: green all taken · red missed · orange needs your answer · yellow partly · grey skipped", style = MaterialTheme.typography.bodySmall)

        selected?.let { day ->
            val list = byDay[day].orEmpty().filter { it.status != DoseStatus.NOT_NEEDED }.sortedBy { it.scheduledAt ?: it.takenAt }
            Text(Labels.date(DoseGenerator.resolve(day.atStartOfDay(), vm.zone)), style = MaterialTheme.typography.titleMedium)
            DaySummary(HistoryCalc.summarize(list.map { it.medicationId to it.status }), names)
            if (!vm.canAmend(day) && day.isBefore(vm.today)) Text("Read-only: records older than 5 days can't be changed.", style = MaterialTheme.typography.bodySmall)
            list.forEach { d ->
                val canEdit = vm.canAmend(day) && d.status in EDITABLE
                Card(
                    Modifier.fillMaxWidth().clickable(enabled = canEdit) { editing = d },
                    colors = CardDefaults.cardColors(containerColor = StatusColors.container(d.status)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${Labels.time(Instant.ofEpochMilli(d.scheduledAt ?: d.takenAt ?: 0))}  ${names[d.medicationId] ?: "?"}", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(Labels.status(d.status),
                            d.takenAt?.takeIf { d.status == DoseStatus.TAKEN }?.let { "at ${Labels.time(Instant.ofEpochMilli(it))}" },
                            Labels.pending(d.pendingReason).takeIf { d.status == DoseStatus.UNCONFIRMED },
                            "late log".takeIf { d.isLateLog }, "as needed".takeIf { d.isPrn }, d.site, d.skipReason, d.note).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                        if (canEdit) Text("Tap to change", style = MaterialTheme.typography.labelSmall)
                        if (d.status == DoseStatus.UNCONFIRMED && vm.canAmend(day)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { vm.answer(d, DoseActionService.ReviewAnswer.TAKEN) }) { Text("Taken") }
                            TextButton(onClick = { vm.answer(d, DoseActionService.ReviewAnswer.MISSED) }) { Text("Missed") }
                            TextButton(onClick = { vm.answer(d, DoseActionService.ReviewAnswer.SKIPPED) }) { Text("Skipped") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { d -> EditDoseDialog(vm, d, names[d.medicationId] ?: "") { editing = null } }
}

/** Per-medicine summary of the selected day. */
@Composable
private fun DaySummary(summary: List<HistoryCalc.MedSummary>, names: Map<Long, String>) {
    if (summary.isEmpty()) { Text("No doses."); return }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleSmall)
            summary.forEach { m ->
                val parts = listOfNotNull(
                    "${m.taken}/${m.total} taken",
                    m.missed.takeIf { it > 0 }?.let { "$it missed" },
                    m.needsAnswer.takeIf { it > 0 }?.let { "$it needs answer" },
                    m.skipped.takeIf { it > 0 }?.let { "$it skipped" },
                    m.upcoming.takeIf { it > 0 }?.let { "$it upcoming" },
                )
                Text((if (m.complete) "✓ " else if (m.missed > 0) "✗ " else "• ") + "${names[m.medicationId] ?: "?"}: " + parts.joinToString(", "))
            }
        }
    }
}

private val EDITABLE = setOf(DoseStatus.TAKEN, DoseStatus.MISSED, DoseStatus.SKIPPED)

@Composable
private fun EditDoseDialog(vm: HistoryViewModel, d: DoseEventEntity, name: String, onClose: () -> Unit) {
    var status by remember { mutableStateOf(d.status) }
    var time by remember { mutableStateOf(d.takenAt?.let { Instant.ofEpochMilli(it).atZone(vm.zone).toLocalTime().withSecond(0).withNano(0) } ?: LocalTime.now().withSecond(0).withNano(0)) }
    var note by remember { mutableStateOf(d.note.orEmpty()) }
    var pick by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    LaunchedEffect(d.id) { audit = vm.auditFor(d.id) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Change · $name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChips(EDITABLE.toList(), status, Labels::status) { status = it }
                if (status == DoseStatus.TAKEN) AssistChip(onClick = { pick = true }, label = { Text("Taken at $time") })
                OutlinedTextField(note, { if (it.length <= 500) note = it }, label = { Text("Note") })
                if (audit.size > 1) Text("Changed ${audit.size - 1} time(s); every change is kept in the audit trail.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { vm.edit(d, status, time.takeIf { status == DoseStatus.TAKEN }, note); onClose() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
    if (pick) TimePickerDialog(time, { pick = false }) { time = it; pick = false }
}
