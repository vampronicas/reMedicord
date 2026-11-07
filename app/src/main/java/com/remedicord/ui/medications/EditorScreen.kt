package com.remedicord.ui.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
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
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.data.repo.MedicationRepository
import com.remedicord.domain.model.BodySide
import com.remedicord.domain.model.CourseMode
import com.remedicord.domain.model.DoseUnit
import com.remedicord.domain.model.FoodRelation
import com.remedicord.domain.model.MedicationType
import com.remedicord.domain.model.ScheduleRule
import com.remedicord.domain.model.TimeZoneMode
import com.remedicord.domain.schedule.TaperBuilder
import com.remedicord.domain.usecase.ActionResult
import com.remedicord.domain.usecase.FormValidator
import com.remedicord.domain.usecase.MedicationForm
import com.remedicord.domain.usecase.MedicationService
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.DatePickerButton
import com.remedicord.ui.components.DecimalField
import com.remedicord.ui.components.IntField
import com.remedicord.ui.components.LabeledSwitch
import com.remedicord.ui.components.RuleDraft
import com.remedicord.ui.components.ScheduleBuilder
import com.remedicord.ui.components.SectionTitle
import com.remedicord.ui.components.TimesEditor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaperDraft(val times: List<LocalTime>, val amount: String, val days: Int?)

data class EditorState(
    val editingId: Long? = null,
    val step: Int = 0,
    val form: MedicationForm = MedicationForm(),
    val amountText: String = "1",
    val rule: RuleDraft = RuleDraft(),
    val tapering: Boolean = false,
    val taper: List<TaperDraft> = listOf(TaperDraft(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), "1", 7)),
    val customSite: String = "",
    val errors: List<String> = emptyList(),
    val saved: Boolean = false,
    val original: MedicationEntity? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: MedicationRepository,
    private val service: MedicationService,
) : ViewModel() {
    private val _s = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _s

    init {
        val id = handle.get<Long>("id")?.takeIf { it > 0 }
        if (id != null) viewModelScope.launch {
            repo.get(id)?.let { m ->
                _s.update {
                    it.copy(editingId = id, original = m, form = it.form.copy(name = m.name, type = m.type, instructions = m.instructions,
                        foodRelation = m.foodRelation, side = m.side, bodyLocation = m.bodyLocation.orEmpty(),
                        dressingType = m.dressingType.orEmpty(), sites = m.enabledSites.orEmpty(), timeZoneMode = m.timeZoneMode))
                }
            }
        }
    }

    fun update(f: (EditorState) -> EditorState) = _s.update { f(it).copy(errors = emptyList()) }
    fun form(f: (MedicationForm) -> MedicationForm) = update { it.copy(form = f(it.form)) }

    fun setType(t: MedicationType) = update {
        it.copy(form = it.form.copy(type = t, unit = Labels.defaultUnit(t), sites = Labels.defaultSites[t].orEmpty(),
            side = if (t == MedicationType.EYE_DROP || t == MedicationType.EAR_DROP) it.form.side else null))
    }

    /** Builds the final form from the drafts. */
    fun built(s: EditorState = _s.value): MedicationForm {
        val amount = s.amountText.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val steps = if (s.tapering) s.taper.map { d ->
            val rule = runCatching { ScheduleRule.FixedTimes(d.times) }.getOrNull() ?: ScheduleRule.FixedTimes(listOf(LocalTime.NOON))
            TaperBuilder.Step(rule, d.amount.toBigDecimalOrNull() ?: BigDecimal.ZERO, s.form.unit, d.days ?: 0)
        } else emptyList()
        return s.form.copy(amount = amount, rule = if (s.tapering) null else s.rule.toRule(), taperSteps = steps,
            startDate = if (s.rule.kind.name in setOf("DAYS", "CYCLIC")) s.rule.startDate else s.form.startDate)
    }

    fun next() {
        val s = _s.value
        val errors = when (s.step) {
            1 -> FormValidator.validate(built().copy(rule = ScheduleRule.AsNeeded(0, 1), taperSteps = emptyList(), amount = BigDecimal.ONE, courseMode = CourseMode.NONE, mustComplete = false))
            2 -> FormValidator.validate(built().copy(courseMode = CourseMode.NONE, mustComplete = false))
            else -> emptyList()
        }
        if (errors.isNotEmpty()) _s.update { it.copy(errors = errors) } else _s.update { it.copy(step = it.step + 1, errors = emptyList()) }
    }

    fun back() = _s.update { it.copy(step = (it.step - 1).coerceAtLeast(0), errors = emptyList()) }

    fun save() = viewModelScope.launch {
        val s = _s.value
        val result = if (s.editingId != null && s.original != null) {
            val f = s.form
            service.updateDetails(s.original.copy(name = f.name, instructions = f.instructions, foodRelation = f.foodRelation, side = f.side,
                bodyLocation = f.bodyLocation.ifBlank { null }, dressingType = f.dressingType.ifBlank { null }, enabledSites = f.sites.ifEmpty { null }))
        } else service.create(built())
        when (result) {
            ActionResult.Done -> _s.update { it.copy(saved = true) }
            is ActionResult.Invalid -> _s.update { it.copy(errors = result.errors) }
            is ActionResult.Blocked -> _s.update { it.copy(errors = listOf(result.message)) }
            is ActionResult.NeedsConfirmation -> _s.update { it.copy(errors = listOf(result.message)) }
        }
    }
}

private val STEPS = listOf("Type", "Details", "Dose & schedule", "Course", "Review")

/** S4: add (step by step) or edit details of a medication. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    val editing = s.editingId != null
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (editing) "Edit details" else "Add medicine · ${STEPS[s.step]}") },
            navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) LinearProgressIndicator(progress = { (s.step + 1) / STEPS.size.toFloat() }, modifier = Modifier.fillMaxWidth())
            if (editing) DetailsStep(s, vm, showTimeZone = false)
            else when (s.step) {
                0 -> {
                    SectionTitle("What kind of item?")
                    ChoiceChips(MedicationType.entries, s.form.type, Labels::type, vm::setType)
                }
                1 -> DetailsStep(s, vm, showTimeZone = true)
                2 -> DoseStep(s, vm)
                3 -> CourseStep(s, vm)
                else -> ReviewStep(vm.built())
            }
            s.errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!editing && s.step > 0) OutlinedButton(onClick = vm::back) { Text("Back") }
                if (editing || s.step == STEPS.lastIndex) Button(onClick = { vm.save() }) { Text("Save") }
                else Button(onClick = vm::next) { Text("Next") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsStep(s: EditorState, vm: EditorViewModel, showTimeZone: Boolean) {
    val f = s.form
    OutlinedTextField(f.name, { if (it.length <= FormValidator.MAX_NAME) vm.form { x -> x.copy(name = it) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(f.instructions, { if (it.length <= FormValidator.MAX_TEXT) vm.form { x -> x.copy(instructions = it) } }, label = { Text("Instructions (optional)") }, modifier = Modifier.fillMaxWidth())
    SectionTitle("Food")
    ChoiceChips(FoodRelation.entries, f.foodRelation, Labels::food) { vm.form { x -> x.copy(foodRelation = it) } }
    when (f.type) {
        MedicationType.EYE_DROP, MedicationType.EAR_DROP -> {
            SectionTitle(if (f.type == MedicationType.EYE_DROP) "Which eye?" else "Which ear?")
            ChoiceChips(BodySide.entries, f.side, Labels::side) { vm.form { x -> x.copy(side = it) } }
        }
        MedicationType.INJECTION, MedicationType.PATCH -> {
            SectionTitle("Sites to rotate")
            val all = (Labels.defaultSites[f.type].orEmpty() + f.sites).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                all.forEach { site ->
                    FilterChip(selected = site in f.sites, label = { Text(site) },
                        onClick = { vm.form { x -> x.copy(sites = if (site in x.sites) x.sites - site else x.sites + site) } })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(s.customSite, { if (it.length <= FormValidator.MAX_SHORT) vm.update { x -> x.copy(customSite = it) } }, label = { Text("Add a site") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val c = s.customSite.trim()
                    if (c.isNotEmpty()) vm.update { x -> x.copy(customSite = "", form = x.form.copy(sites = (x.form.sites + c).distinct())) }
                }) { Text("Add") }
            }
        }
        MedicationType.BANDAGE -> {
            OutlinedTextField(f.bodyLocation, { if (it.length <= FormValidator.MAX_SHORT) vm.form { x -> x.copy(bodyLocation = it) } }, label = { Text("Body location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(f.dressingType, { if (it.length <= FormValidator.MAX_SHORT) vm.form { x -> x.copy(dressingType = it) } }, label = { Text("Dressing type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        MedicationType.CREAM ->
            OutlinedTextField(f.bodyLocation, { if (it.length <= FormValidator.MAX_SHORT) vm.form { x -> x.copy(bodyLocation = it) } }, label = { Text("Where to apply") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        else -> Unit
    }
    if (showTimeZone) {
        SectionTitle("When travelling")
        ChoiceChips(TimeZoneMode.entries, f.timeZoneMode, { if (it == TimeZoneMode.LOCAL) "Follow local time" else "Keep home-time intervals" }) { vm.form { x -> x.copy(timeZoneMode = it) } }
        Text(
            if (f.timeZoneMode == TimeZoneMode.LOCAL) "08:00 stays 08:00 wherever you are."
            else "Doses stay tied to your current (home) time zone, so the gap between doses never changes.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DoseStep(s: EditorState, vm: EditorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.tapering) DecimalField("Amount per dose", s.amountText, { vm.update { x -> x.copy(amountText = it) } }, Modifier.weight(1f))
    }
    SectionTitle("Unit")
    ChoiceChips(DoseUnit.entries, s.form.unit, { Labels.unit(it, BigDecimal.TEN) }) { vm.form { x -> x.copy(unit = it) } }
    LabeledSwitch("Tapering (dose steps down over time)", s.tapering) { vm.update { x -> x.copy(tapering = it) } }
    if (!s.tapering) {
        SectionTitle("Schedule")
        ScheduleBuilder(s.rule, { d -> vm.update { it.copy(rule = d) } })
    } else {
        s.taper.forEachIndexed { i, d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Step ${i + 1}", style = MaterialTheme.typography.titleSmall)
                    TimesEditor(d.times) { t -> vm.update { x -> x.copy(taper = x.taper.toMutableList().also { l -> l[i] = d.copy(times = t) }) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField("Amount", d.amount, { a -> vm.update { x -> x.copy(taper = x.taper.toMutableList().also { l -> l[i] = d.copy(amount = a) }) } }, Modifier.weight(1f))
                        IntField("Days", d.days, { n -> vm.update { x -> x.copy(taper = x.taper.toMutableList().also { l -> l[i] = d.copy(days = n) }) } }, Modifier.weight(1f))
                    }
                    if (s.taper.size > 1) TextButton(onClick = { vm.update { x -> x.copy(taper = x.taper.filterIndexed { j, _ -> j != i }) } }) { Text("Remove step") }
                }
            }
        }
        if (s.taper.size < 20) OutlinedButton(onClick = {
            vm.update { x -> x.copy(taper = x.taper + x.taper.last().let { l -> l.copy(times = l.times.dropLast(1).ifEmpty { l.times }) }) }
        }) { Text("+ Add step") }
        DatePickerButton("Taper starts", s.form.startDate) { d -> vm.form { it.copy(startDate = d) } }
    }
}

@Composable
private fun CourseStep(s: EditorState, vm: EditorViewModel) {
    val f = s.form
    LabeledSwitch("Must complete the full course (e.g. antibiotics)", f.mustComplete) { m ->
        vm.form { it.copy(mustComplete = m, courseMode = if (m && it.courseMode == CourseMode.NONE) CourseMode.DAYS else it.courseMode) }
    }
    Text("A must-complete course can't be paused or stopped early, except on a doctor's instruction or a reaction. Missed or skipped doses move the end date back.", style = MaterialTheme.typography.bodySmall)
    SectionTitle("Course length")
    ChoiceChips(CourseMode.entries.filter { !f.mustComplete || it != CourseMode.NONE }, f.courseMode, {
        when (it) { CourseMode.NONE -> "No end"; CourseMode.DAYS -> "Number of days"; CourseMode.DOSES -> "Number of doses" }
    }) { m -> vm.form { it.copy(courseMode = m) } }
    if (f.courseMode != CourseMode.NONE) IntField(if (f.courseMode == CourseMode.DAYS) "Days" else "Doses", f.courseLength, { n -> vm.form { it.copy(courseLength = n) } })
    if (!s.tapering && s.rule.kind.name !in setOf("DAYS", "CYCLIC")) DatePickerButton("Start date", f.startDate) { d -> vm.form { it.copy(startDate = d) } }
}

@Composable
private fun ReviewStep(f: MedicationForm) {
    SectionTitle("Check and save")
    val lines = buildList {
        add("Name" to f.name.trim())
        add("Type" to Labels.type(f.type))
        f.side?.let { add("Side" to Labels.side(it)) }
        if (f.sites.isNotEmpty()) add("Sites" to f.sites.joinToString(", "))
        if (f.bodyLocation.isNotBlank()) add("Location" to f.bodyLocation)
        if (f.dressingType.isNotBlank()) add("Dressing" to f.dressingType)
        if (f.taperSteps.isEmpty()) {
            add("Dose" to Labels.dose(f.amount, f.unit))
            add("Schedule" to (f.rule?.let(Labels::rule) ?: "—"))
        } else f.taperSteps.forEachIndexed { i, st -> add("Step ${i + 1}" to "${Labels.dose(st.amount, st.unit)} · ${Labels.rule(st.rule)} · ${st.days} days") }
        add("Course" to when (f.courseMode) {
            CourseMode.NONE -> "No end"; CourseMode.DAYS -> "${f.courseLength} days"; CourseMode.DOSES -> "${f.courseLength} doses"
        } + if (f.mustComplete) " (must complete)" else "")
        add("Travel" to if (f.timeZoneMode == TimeZoneMode.LOCAL) "Follow local time" else "Keep home-time intervals")
        add("Starts" to f.startDate.toString())
    }
    lines.forEach { (k, v) -> Text("$k: $v") }
}
