package com.remedicord.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.core.time.TimeProvider
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.DoseRepository
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.DoseStatus
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.policy.DaySection
import com.remedicord.domain.policy.PrnPolicy
import com.remedicord.domain.schedule.DoseGenerator
import com.remedicord.domain.usecase.DoseActionService
import com.remedicord.ui.DoseItem
import com.remedicord.ui.Labels
import com.remedicord.ui.RecordSupport
import com.remedicord.ui.components.CollapsibleSection
import com.remedicord.ui.components.DatePickerButton
import com.remedicord.ui.components.DoseCard
import com.remedicord.ui.components.MedIcon
import com.remedicord.ui.components.RecordEdit
import com.remedicord.ui.components.RecordSheet
import com.remedicord.ui.components.RecordSheetModel
import com.remedicord.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val doses: DoseRepository,
    private val meds: MedicationRepository,
    private val actions: DoseActionService,
    val support: RecordSupport,
    private val time: TimeProvider,
) : ViewModel() {
    val today: LocalDate get() = support.today()
    private val _day = MutableStateFlow(today)
    val day: StateFlow<LocalDate> = _day

    private val medsFlow = meds.observeByStatus(*MedicationStatus.entries.toTypedArray())

    val items: StateFlow<List<DoseItem>> = _day.flatMapLatest { d ->
        val from = DoseGenerator.resolve(d.atStartOfDay(), time.zone()).toEpochMilli()
        val to = DoseGenerator.resolve(d.plusDays(1).atStartOfDay(), time.zone()).toEpochMilli()
        combine(doses.observeBetween(from, to), medsFlow) { list, ms ->
            val byId = ms.associateBy { it.id }
            list.mapNotNull { dose ->
                val m = byId[dose.medicationId] ?: return@mapNotNull null
                DoseItem(dose, m, dose.regimenId?.let { id -> meds.regimens(m.id).firstOrNull { it.id == id } })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val needsReview: StateFlow<Int> = doses.observeWithStatus(DoseStatus.UNCONFIRMED).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val prnMeds: StateFlow<List<MedicationEntity>> = meds.observeByStatus(MedicationStatus.ACTIVE).map { list ->
        list.filter { m -> meds.activeRegimen(m.id, time.nowMillis())?.let { ScheduleRule.fromJson(it.scheduleRuleJson) is ScheduleRule.AsNeeded } == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _prnConfirm = MutableStateFlow<Pair<Long, String>?>(null)
    val prnConfirm: StateFlow<Pair<Long, String>?> = _prnConfirm
    private val _sheet = MutableStateFlow<Pair<DoseItem, RecordSheetModel>?>(null)
    val sheet: StateFlow<Pair<DoseItem, RecordSheetModel>?> = _sheet

    fun goTo(d: LocalDate) { _day.value = d }
    fun shift(days: Long) { _day.value = _day.value.plusDays(days) }
    fun refreshDay() { if (_day.value.isAfter(today)) _day.value = today }
    fun dismiss() { _message.value = null; _prnConfirm.value = null }

    fun openSheet(item: DoseItem) = viewModelScope.launch { _sheet.value = item to support.sheet(item) }
    fun closeSheet() { _sheet.value = null }
    fun saveSheet(item: DoseItem, e: RecordEdit) = viewModelScope.launch {
        support.save(item, e)?.let { _message.value = it }
        _sheet.value = null
    }

    /** Quick action; returns the undo handle for the snackbar. */
    suspend fun quick(item: DoseItem, a: RecordSupport.Quick): RecordSupport.Undoable? =
        support.quick(item, a).also { if (it == null) _message.value = if (a == RecordSupport.Quick.SNOOZE) "Snooze limit reached." else "This dose can't be changed now." }

    fun undo(u: RecordSupport.Undoable) = viewModelScope.launch { if (!support.undo(u)) _message.value = "Couldn't undo: the record was changed again." }

    /** v1.3 group action for a section. Returns undo handles (empty = nothing changed). */
    suspend fun group(items: List<DoseItem>, taken: Boolean, reason: String?): List<RecordSupport.Undoable> =
        support.group(items, taken, reason).also { if (it.isEmpty()) _message.value = "Nothing in this section could be changed." }

    fun undoAll(list: List<RecordSupport.Undoable>) = viewModelScope.launch {
        if (!support.undoAll(list)) _message.value = "Some records were changed again and couldn't be undone."
    }

    /** v1.3: postpone the next [count] reminders (all medicines) by [hours]. */
    fun postpone(count: Int, hours: Int) = viewModelScope.launch {
        val moves = support.postpone(count, hours)
        _message.value = if (moves.isEmpty()) "There are no upcoming reminders to postpone."
        else "Reminders postponed:\n" + moves.groupBy { it.from }.entries.joinToString("\n") { (from, ms) ->
            "${Labels.dateTime(from, support.zone)} → ${Labels.time(ms.first().to, support.zone)} (${ms.size} dose${if (ms.size == 1) "" else "s"})"
        } + "\nDoses keep their original due time."
    }

    fun pauseToday(medId: Long) = viewModelScope.launch { support.pauseToday(medId); _message.value = "No more reminders for this medicine today. Fill in the doses later." }

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

private val OPEN_TODAY = setOf(DoseStatus.PLANNED, DoseStatus.DUE, DoseStatus.SNOOZED)

/** S2 Home (E1, E5, E8): progress ring, next dose, time-of-day sections, swipe + undo, record sheet. */
@Composable
fun TodayScreen(vm: TodayViewModel, onReview: () -> Unit = {}) {
    val items by vm.items.collectAsState()
    val day by vm.day.collectAsState()
    val review by vm.needsReview.collectAsState()
    val prn by vm.prnMeds.collectAsState()
    val message by vm.message.collectAsState()
    val prnConfirm by vm.prnConfirm.collectAsState()
    val sheet by vm.sheet.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refreshDay() }
    val isToday = day == vm.today
    val now = LocalTime.now()

    fun runQuick(item: DoseItem, a: RecordSupport.Quick) = scope.launch {
        val u = vm.quick(item, a) ?: return@launch
        if (snackbar.showSnackbar(u.message, actionLabel = "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) vm.undo(u)
    }
    var skipGroup by remember { mutableStateOf<Pair<String, List<DoseItem>>?>(null) }
    var postponeOpen by remember { mutableStateOf(false) }

    /** v1.3: settle a whole section at once, with one Undo for the group. */
    fun runGroup(items: List<DoseItem>, taken: Boolean, reason: String?, label: String) = scope.launch {
        val list = vm.group(items, taken, reason)
        if (list.isEmpty()) return@launch
        val msg = "$label: ${list.size} ${if (taken) "marked as taken" else "skipped"}"
        if (snackbar.showSnackbar(msg, actionLabel = "Undo", withDismissAction = true) == SnackbarResult.ActionPerformed) vm.undoAll(list)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (isToday) "Today" else day.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(Labels.date(DoseGenerator.resolve(day.atStartOfDay(), vm.support.zone), vm.support.zone),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { vm.shift(-1) }, modifier = Modifier.semantics { contentDescription = "Previous day" }) { Text("‹") }
                Spacer(Modifier.width(4.dp))
                FilledTonalIconButton(onClick = { vm.shift(1) }, modifier = Modifier.semantics { contentDescription = "Next day" }) { Text("›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DatePickerButton("Go to", day) { vm.goTo(it) }
                if (!isToday) TextButton(onClick = { vm.goTo(vm.today) }) { Text("Back to today") }
                if (isToday) TextButton(onClick = { postponeOpen = true }) { Text("Postpone reminders") }
            }

            if (isToday) ProgressHero(items, vm)
            if (review > 0) Surface(
                shape = RoundedCornerShape(18.dp), color = StatusColors.container(DoseStatus.UNCONFIRMED),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onReview),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("$review dose${if (review == 1) "" else "s"} need${if (review == 1) "s" else ""} your answer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Tap to say whether you took them. Unanswered for 5 days counts as missed.", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (items.isEmpty()) Text(if (isToday) "No scheduled doses today." else "No records for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val bySection = items.sortedBy { it.dose.scheduledAt ?: it.dose.takenAt }.groupBy { vm.support.sectionOf(it) }
            DaySection.entries.forEach { section ->
                val list = bySection[section].orEmpty()
                if (list.isEmpty()) return@forEach
                val statuses = list.map { it.dose.status }
                val countable = statuses.filter { it != DoseStatus.NOT_NEEDED }
                val taken = countable.count { it == DoseStatus.TAKEN }
                val attention = statuses.firstOrNull { it == DoseStatus.DUE || it == DoseStatus.SNOOZED } ?: statuses.firstOrNull { it == DoseStatus.UNCONFIRMED }
                    ?: statuses.firstOrNull { it == DoseStatus.MISSED } ?: if (taken == countable.size) DoseStatus.TAKEN else null
                val summary = buildString {
                    append("$taken/${countable.size} taken")
                    statuses.count { it == DoseStatus.UNCONFIRMED }.takeIf { it > 0 }?.let { append(" · $it needs answer") }
                    if (statuses.any { it == DoseStatus.DUE || it == DoseStatus.SNOOZED }) append(" · due now")
                }
                CollapsibleSection(section.label, summary, attention, DaySection.startsExpanded(section, statuses, now, isToday), key = "$day-${section.name}") {
                    val groupable = vm.support.groupable(list, isToday)
                    if (groupable.size >= 2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { runGroup(groupable, true, null, section.label) }) { Text("All taken (${groupable.size})") }
                        OutlinedButton(onClick = { skipGroup = section.label to groupable }) { Text("Skip all…") }
                    }
                    list.forEach { item ->
                        val d = item.dose
                        val open = isToday && d.status in OPEN_TODAY
                        DoseCard(
                            m = vm.support.card(item),
                            onOpen = { vm.openSheet(item) },
                            quick = if (open && d.status != DoseStatus.PLANNED) ({ QuickActions(item, vm, onQuick = { runQuick(item, it) }) }) else null,
                            onSwipeTaken = if (open) ({ runQuick(item, RecordSupport.Quick.TAKEN) }) else null,
                            onSwipeLater = if (open) ({ runQuick(item, RecordSupport.Quick.LATER) }) else null,
                        )
                    }
                }
            }

            if (prn.isNotEmpty() && isToday) {
                Text("As needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                prn.forEach { m ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MedIcon(m.type, m.colorTag, 38)
                            Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            FilledTonalButton(onClick = { vm.prnTake(m.id) }) { Text("${Labels.action(m.type)} now") }
                        }
                    }
                }
            }
            Spacer(Modifier.size(56.dp))
        }
    }

    sheet?.let { (item, model) -> RecordSheet(model, onDismiss = vm::closeSheet) { vm.saveSheet(item, it) } }
    skipGroup?.let { (label, items) ->
        SkipGroupDialog(label, items, onDismiss = { skipGroup = null }) { reason -> skipGroup = null; runGroup(items, false, reason, label) }
    }
    if (postponeOpen) PostponeSheet(onDismiss = { postponeOpen = false }) { count, hours -> postponeOpen = false; vm.postpone(count, hours) }
    message?.let { AlertDialog(onDismissRequest = vm::dismiss, text = { Text(it) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } }) }
    prnConfirm?.let { (medId, text) ->
        AlertDialog(onDismissRequest = vm::dismiss, text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.prnTake(medId, confirmed = true) }) { Text("Take anyway") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } })
    }
}

/** Taken · Later · Skip… · ⋯ (Snooze, No more reminders today). */
@Composable
private fun QuickActions(item: DoseItem, vm: TodayViewModel, onQuick: (RecordSupport.Quick) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Button(onClick = { onQuick(RecordSupport.Quick.TAKEN) }) { Text("Taken") }
    FilledTonalButton(onClick = { onQuick(RecordSupport.Quick.LATER) }) { Text("Later") }
    OutlinedButton(onClick = { vm.openSheet(item) }) { Text("Skip…") }
    Box {
        TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "More actions" }) { Text("⋯") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Snooze") }, onClick = { menu = false; onQuick(RecordSupport.Quick.SNOOZE) })
            DropdownMenuItem(text = { Text("No more reminders today") }, onClick = { menu = false; vm.pauseToday(item.med.id) })
        }
    }
}

/** Progress ring + next dose (E8). Done = taken or skipped; "not needed" flexible times aren't counted. */
@Composable
private fun ProgressHero(items: List<DoseItem>, vm: TodayViewModel) {
    val counted = items.filter { it.dose.status != DoseStatus.NOT_NEEDED && !it.dose.isPrn }
    val done = counted.count { it.dose.status == DoseStatus.TAKEN || it.dose.status == DoseStatus.SKIPPED }
    val answer = counted.count { it.dose.status == DoseStatus.UNCONFIRMED }
    val toCome = counted.count { it.dose.status in OPEN_TODAY }
    val next = items.filter { it.dose.status in OPEN_TODAY }.minByOrNull { it.dose.remindAt ?: it.dose.scheduledAt ?: Long.MAX_VALUE }
    val fraction = if (counted.isEmpty()) 0f else done / counted.size.toFloat()
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(76.dp).semantics { contentDescription = "$done of ${counted.size} done" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(76.dp)) {
                    val w = 8.dp.toPx()
                    val s = Size(size.width - w, size.height - w)
                    drawArc(trackColor, -90f, 360f, false, Offset(w / 2, w / 2), s, style = Stroke(w))
                    drawArc(ringColor, -90f, 360f * fraction, false, Offset(w / 2, w / 2), s, style = Stroke(w, cap = StrokeCap.Round))
                }
                Text("$done/${counted.size}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$done of ${counted.size} done today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(answer.takeIf { it > 0 }?.let { "$it needs your answer" }, toCome.takeIf { it > 0 }?.let { "$it still to come" })
                    .joinToString(" · ").ifEmpty { "All done for today" }, style = MaterialTheme.typography.bodySmall)
                next?.let {
                    Text("Next: ${it.med.name} at ${Labels.time(vm.support.dueAt(it.dose), vm.support.zone)}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** v1.3: "Skip all" for a section, with a reason; warns when a must-complete course is included. */
@Composable
private fun SkipGroupDialog(label: String, items: List<DoseItem>, onDismiss: () -> Unit, onSkip: (String) -> Unit) {
    var reason by remember { mutableStateOf<String?>(null) }
    val mustComplete = items.filter { it.med.mustComplete }.map { it.med.name }.distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Skip all $label doses?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(items.joinToString(", ") { it.med.name })
                if (mustComplete.isNotEmpty()) Text("${mustComplete.joinToString(", ")} must be completed. Skipping moves the course end back by one dose.",
                    color = MaterialTheme.colorScheme.error)
                Text("Reason", style = MaterialTheme.typography.labelLarge)
                com.remedicord.ui.components.ChoiceChips(com.remedicord.domain.policy.SkipPolicy.PRESET_REASONS, reason, { it }) { reason = it }
            }
        },
        confirmButton = { TextButton(enabled = reason != null, onClick = { onSkip(reason!!) }) { Text("Skip all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** v1.3: postpone the next 1–3 reminders (all medicines) by 1–12 hours. Doses keep their due time. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PostponeSheet(onDismiss: () -> Unit, onPostpone: (Int, Int) -> Unit) {
    var count by remember { mutableStateOf(1) }
    var hours by remember { mutableStateOf(2) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Postpone reminders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Moves the next reminders of all medicines later, e.g. for a quiet early morning. The doses keep their original due time.",
                style = MaterialTheme.typography.bodyMedium)
            Text("How many upcoming reminders", style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (1..3).forEach { n ->
                    SegmentedButton(selected = count == n, onClick = { count = n },
                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(n - 1, 3)) { Text(if (n == 1) "Next 1" else "Next $n") }
                }
            }
            Text("By how many hours", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(onClick = { hours = (hours - 1).coerceAtLeast(1) }, modifier = Modifier.semantics { contentDescription = "Fewer hours" }) { Text("−") }
                Text("$hours hour${if (hours == 1) "" else "s"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                FilledTonalIconButton(onClick = { hours = (hours + 1).coerceAtMost(12) }, modifier = Modifier.semantics { contentDescription = "More hours" }) { Text("+") }
            }
            Text("A reminder is never moved past that medicine's next dose. 1–12 hours.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onPostpone(count, hours) }) { Text("Postpone") }
            }
        }
    }
}
