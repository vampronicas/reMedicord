package com.remedicord.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.remedicord.domain.policy.HistoryCalc
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.TimePickerDialog
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
    val filter: Long? = null,
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
    private val month = MutableStateFlow(YearMonth.now(time.zone()))
    private val filter = MutableStateFlow<Long?>(null)
    val selected = MutableStateFlow<LocalDate?>(LocalDate.now(time.zone()))

    val state: StateFlow<HistoryState> = combine(month, filter) { m, f -> m to f }.flatMapLatest { (m, f) ->
        val today = LocalDate.now(zone)
        // Load from 90 days back (adherence) or month start, whichever is earlier.
        val from = minOf(m.atDay(1), today.minusDays(89))
        val fromMs = DoseGenerator.resolve(from.atStartOfDay(), zone).toEpochMilli()
        val toMs = DoseGenerator.resolve(maxOf(m.atEndOfMonth(), today).plusDays(1).atStartOfDay(), zone).toEpochMilli()
        combine(doses.observeBetween(fromMs, toMs), meds.observeByStatus(*MedicationStatus.entries.toTypedArray())) { list, ms ->
            val shown = list.filter { f == null || it.medicationId == f }
            val entries = shown.mapNotNull { d -> HistoryCalc.dayOf(d.scheduledAt, d.takenAt, zone)?.let { it to d.status } }
            HistoryState(m, ms, f, shown, Triple(HistoryCalc.adherence(entries, today, 7), HistoryCalc.adherence(entries, today, 30), HistoryCalc.adherence(entries, today, 90)))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState(YearMonth.now(time.zone())))

    fun shiftMonth(by: Long) { month.value = month.value.plusMonths(by) }
    fun setFilter(id: Long?) { filter.value = id }
    fun select(d: LocalDate) { selected.value = d }

    suspend fun auditFor(doseId: Long): List<AuditLogEntity> = auditDao.forEntity(AuditLogger.DOSE, doseId)

    fun edit(d: DoseEventEntity, status: DoseStatus, takenTime: LocalTime?, note: String) = viewModelScope.launch {
        val day = HistoryCalc.dayOf(d.scheduledAt, d.takenAt, zone) ?: LocalDate.now(zone)
        val at = takenTime?.let { DoseGenerator.resolve(day.atTime(it), zone) }
        actions.edit(d.id, status, at, note)
    }
}

private fun markerColor(m: HistoryCalc.Marker): Color? = when (m) {
    HistoryCalc.Marker.NONE -> null
    HistoryCalc.Marker.ALL_TAKEN -> Color(0xFF2E7D32)
    HistoryCalc.Marker.HAS_MISSED -> Color(0xFFC62828)
    HistoryCalc.Marker.NEEDS_REVIEW -> Color(0xFFEF6C00)
    HistoryCalc.Marker.SKIPPED_ONLY -> Color(0xFF757575)
    HistoryCalc.Marker.PARTIAL -> Color(0xFFF9A825)
}

private fun markerLabel(m: HistoryCalc.Marker) = when (m) {
    HistoryCalc.Marker.NONE -> "no doses"; HistoryCalc.Marker.ALL_TAKEN -> "all taken"; HistoryCalc.Marker.HAS_MISSED -> "missed dose"
    HistoryCalc.Marker.NEEDS_REVIEW -> "needs review"; HistoryCalc.Marker.SKIPPED_ONLY -> "skipped"; HistoryCalc.Marker.PARTIAL -> "partly taken"
}

/** S9 History. */
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
        ChoiceChips(listOf<Long?>(null) + s.meds.map { it.id }, s.filter, { id -> id?.let { names[it] } ?: "All medicines" }, vm::setFilter)
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
                                markerColor(marker)?.let { Box(Modifier.size(6.dp).background(it, CircleShape)) }
                            }
                        }
                    }
                }
            }
        }
        Text("Green: all taken · Red: missed · Orange: needs review · Yellow: partly · Grey: skipped", style = MaterialTheme.typography.bodySmall)
        selected?.let { day ->
            Text(Labels.date(DoseGenerator.resolve(day.atStartOfDay(), vm.zone)), style = MaterialTheme.typography.titleMedium)
            val list = byDay[day].orEmpty().sortedBy { it.scheduledAt ?: it.takenAt }
            if (list.isEmpty()) Text("No doses.")
            list.forEach { d ->
                Card(Modifier.fillMaxWidth().clickable(enabled = d.status in EDITABLE) { editing = d }) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${Labels.time(Instant.ofEpochMilli(d.scheduledAt ?: d.takenAt ?: 0))}  ${names[d.medicationId] ?: "?"}", style = MaterialTheme.typography.titleSmall)
                        Text(listOfNotNull(Labels.status(d.status),
                            d.takenAt?.takeIf { d.status == DoseStatus.TAKEN }?.let { "at ${Labels.time(Instant.ofEpochMilli(it))}" },
                            "late log".takeIf { d.isLateLog }, "as needed".takeIf { d.isPrn }, d.site, d.skipReason, d.note).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    editing?.let { d -> EditDoseDialog(vm, d, names[d.medicationId] ?: "") { editing = null } }
}

private val EDITABLE = setOf(DoseStatus.TAKEN, DoseStatus.MISSED, DoseStatus.SKIPPED)

@Composable
private fun EditDoseDialog(vm: HistoryViewModel, d: DoseEventEntity, name: String, onClose: () -> Unit) {
    var status by remember { mutableStateOf(d.status) }
    var time by remember { mutableStateOf(d.takenAt?.let { Instant.ofEpochMilli(it).atZone(vm.zone).toLocalTime().withSecond(0).withNano(0) } ?: LocalTime.now().withSecond(0).withNano(0)) }
    var note by remember { mutableStateOf(d.note.orEmpty()) }
    var pick by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(d.id) { audit = vm.auditFor(d.id) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Edit · $name") },
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
