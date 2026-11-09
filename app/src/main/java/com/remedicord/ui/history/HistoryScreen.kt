package com.remedicord.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.policy.DaySection
import com.remedicord.domain.policy.HistoryCalc
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.ui.DoseItem
import com.remedicord.ui.Labels
import com.remedicord.ui.RecordSupport
import com.remedicord.ui.components.CollapsibleSection
import com.remedicord.ui.components.DoseCard
import com.remedicord.ui.components.FilterItem
import com.remedicord.ui.components.MedColors
import com.remedicord.ui.components.MedicineFilterButton
import com.remedicord.ui.components.RecordEdit
import com.remedicord.ui.components.RecordSheet
import com.remedicord.ui.components.RecordSheetModel
import com.remedicord.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val filterItems: List<FilterItem> = emptyList(),
    val medFilter: Set<Long> = emptySet(),
    val items: List<DoseItem> = emptyList(),
    val adherence: Triple<Int?, Int?, Int?> = Triple(null, null, null),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val doses: DoseRepository,
    private val meds: MedicationRepository,
    val support: RecordSupport,
) : ViewModel() {
    val zone get() = support.zone
    val today: LocalDate get() = support.today()
    private val month = MutableStateFlow(YearMonth.now(zone))
    private val medFilter = MutableStateFlow<Set<Long>>(emptySet())
    val selected = MutableStateFlow<LocalDate?>(LocalDate.now(zone))
    private val _sheet = MutableStateFlow<Pair<DoseItem, RecordSheetModel>?>(null)
    val sheet: StateFlow<Pair<DoseItem, RecordSheetModel>?> = _sheet
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val state: StateFlow<HistoryState> = combine(month, medFilter) { m, mf -> m to mf }.flatMapLatest { (m, mf) ->
        val from = minOf(m.atDay(1), today.minusDays(89))
        val fromMs = DoseGenerator.resolve(from.atStartOfDay(), zone).toEpochMilli()
        val toMs = DoseGenerator.resolve(maxOf(m.atEndOfMonth(), today).plusDays(1).atStartOfDay(), zone).toEpochMilli()
        combine(doses.observeBetween(fromMs, toMs), meds.observeByStatus(*MedicationStatus.entries.toTypedArray())) { list, ms ->
            val byId = ms.associateBy { it.id }
            val items = list.filter { mf.isEmpty() || it.medicationId in mf }.mapNotNull { d ->
                val med = byId[d.medicationId] ?: return@mapNotNull null
                DoseItem(d, med, d.regimenId?.let { id -> meds.regimens(med.id).firstOrNull { it.id == id } })
            }
            val entries = items.mapNotNull { HistoryCalc.dayOf(it.dose.scheduledAt, it.dose.takenAt, zone)?.let { day -> day to it.dose.status } }
            HistoryState(
                m, ms.map { FilterItem(it.id, it.name, it.type, it.colorTag, it.status == MedicationStatus.ACTIVE || it.status == MedicationStatus.PAUSED) },
                mf, items,
                Triple(HistoryCalc.adherence(entries, today, 7), HistoryCalc.adherence(entries, today, 30), HistoryCalc.adherence(entries, today, 90)),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryState(YearMonth.now(zone)))

    fun shiftMonth(by: Long) { month.value = month.value.plusMonths(by) }
    fun setFilter(ids: Set<Long>) { medFilter.value = ids }
    fun select(d: LocalDate) { selected.value = d }
    fun openSheet(item: DoseItem) = viewModelScope.launch { _sheet.value = item to support.sheet(item) }
    fun closeSheet() { _sheet.value = null }
    fun dismiss() { _message.value = null }
    fun saveSheet(item: DoseItem, e: RecordEdit) = viewModelScope.launch {
        support.save(item, e)?.let { _message.value = it }
        _sheet.value = null
    }
}

private fun markerLabel(m: HistoryCalc.Marker) = when (m) {
    HistoryCalc.Marker.NONE -> "no doses"; HistoryCalc.Marker.ALL_TAKEN -> "all taken"; HistoryCalc.Marker.HAS_MISSED -> "missed dose"
    HistoryCalc.Marker.NEEDS_REVIEW -> "needs your answer"; HistoryCalc.Marker.SKIPPED_ONLY -> "skipped"; HistoryCalc.Marker.PARTIAL -> "partly taken"
}

/** S9 History (E1, E2, E3, E5): compact filter, calendar, per-medicine day summary, sections, record sheet. */
@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val s by vm.state.collectAsState()
    val selected by vm.selected.collectAsState()
    val sheet by vm.sheet.collectAsState()
    val message by vm.message.collectAsState()
    val byDay = s.items.groupBy { HistoryCalc.dayOf(it.dose.scheduledAt, it.dose.takenAt, vm.zone) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("7 days" to s.adherence.first, "30 days" to s.adherence.second, "90 days" to s.adherence.third).forEach { (l, v) ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text("$l ${v?.let { "$it%" } ?: "–"}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        MedicineFilterButton(s.filterItems, s.medFilter, vm::setFilter)

        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.shiftMonth(-1) }) { Text("‹") }
                    Text("${s.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${s.month.year}", Modifier.weight(1f),
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { vm.shiftMonth(1) }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HistoryCalc.monthGrid(s.month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (week + List(7 - week.size) { null }).forEach { day ->
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val marker = HistoryCalc.marker(byDay[day].orEmpty().map { it.dose.status })
                                    val sel = day == selected
                                    Column(
                                        Modifier.fillMaxSize().background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                                            .clickable { vm.select(day) }.semantics { contentDescription = "$day, ${markerLabel(marker)}" },
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                                    ) {
                                        Text(day.dayOfMonth.toString(), fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (day.isAfter(vm.today)) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                        Box(Modifier.size(6.dp).background(StatusColors.marker(marker) ?: Color.Transparent, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        selected?.let { day ->
            val list = byDay[day].orEmpty().sortedBy { it.dose.scheduledAt ?: it.dose.takenAt }
            DaySummary(day, vm, HistoryCalc.summarize(list.map { it.dose.medicationId to it.dose.status }), list)
            if (day.isBefore(vm.today.minusDays(4))) Text("Read-only: records older than 5 days can't be changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val bySection = list.filter { it.dose.status != DoseStatus.NOT_NEEDED }.groupBy { vm.support.sectionOf(it) }
            DaySection.entries.forEach { section ->
                val rows = bySection[section].orEmpty()
                if (rows.isEmpty()) return@forEach
                val statuses = rows.map { it.dose.status }
                val taken = statuses.count { it == DoseStatus.TAKEN }
                val worst = statuses.firstOrNull { it == DoseStatus.MISSED } ?: statuses.firstOrNull { it == DoseStatus.UNCONFIRMED }
                    ?: if (taken == statuses.size) DoseStatus.TAKEN else statuses.firstOrNull()
                val extra = listOfNotNull(statuses.count { it == DoseStatus.MISSED }.takeIf { it > 0 }?.let { "$it missed" },
                    statuses.count { it == DoseStatus.UNCONFIRMED }.takeIf { it > 0 }?.let { "$it needs answer" })
                CollapsibleSection(section.label, (listOf("$taken/${statuses.size}") + extra).joinToString(" · "), worst,
                    DaySection.startsExpanded(section, statuses, LocalTime.now(), isToday = false), key = "h-$day-${section.name}") {
                    rows.forEach { item -> DoseCard(vm.support.card(item), onOpen = { vm.openSheet(item) }) }
                }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
    sheet?.let { (item, model) -> RecordSheet(model, onDismiss = vm::closeSheet) { vm.saveSheet(item, it) } }
    message?.let { AlertDialog(onDismissRequest = vm::dismiss, text = { Text(it) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }) }
}

/** Per-medicine summary of the selected day. */
@Composable
private fun DaySummary(day: LocalDate, vm: HistoryViewModel, summary: List<HistoryCalc.MedSummary>, items: List<DoseItem>) {
    val meds = items.associate { it.med.id to it.med }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Labels.date(DoseGenerator.resolve(day.atStartOfDay(), vm.zone), vm.zone), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val missed = summary.sumOf { it.missed }
                if (missed > 0) Surface(shape = RoundedCornerShape(8.dp), color = StatusColors.container(DoseStatus.MISSED)) {
                    Text("$missed missed", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (summary.isEmpty()) Text("No doses.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            summary.forEach { m ->
                val med = meds[m.medicationId]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(MedColors.of(med?.colorTag), CircleShape))
                    Text(med?.name ?: "?", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull("${m.taken}/${m.total}${if (m.complete) " ✓" else ""}",
                        m.missed.takeIf { it > 0 }?.let { "$it missed" }, m.needsAnswer.takeIf { it > 0 }?.let { "$it needs answer" },
                        m.skipped.takeIf { it > 0 }?.let { "$it skipped" }, m.upcoming.takeIf { it > 0 }?.let { "$it upcoming" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
