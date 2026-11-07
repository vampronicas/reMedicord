package com.remedicord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.remedicord.domain.model.ScheduleRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

/** Single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Whole-number field limited to digits. */
@Composable
fun IntField(label: String, value: Int?, onChange: (Int?) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { t -> if (t.length <= 4 && t.all(Char::isDigit)) onChange(t.toIntOrNull()) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Decimal field (e.g. 0.5, 2.5). */
@Composable
fun DecimalField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { t -> if (t.length <= 8 && t.matches(Regex("\\d*(\\.\\d{0,3})?"))) onChange(t) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onPicked: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerButton(label: String, date: LocalDate, onPicked: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    AssistChip(onClick = { open = true }, label = { Text("$label: $date") })
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** Editable list of times of day. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimesEditor(times: List<LocalTime>, onChange: (List<LocalTime>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        times.sorted().forEach { t ->
            InputChip(selected = false, onClick = { onChange(times - t) }, label = { Text("$t  ✕") })
        }
        if (times.size < 12) AssistChip(onClick = { adding = true }, label = { Text("+ Add time") })
    }
    if (adding) TimePickerDialog(LocalTime.of(8, 0), { adding = false }) { t -> adding = false; if (t !in times) onChange(times + t) }
}

enum class RuleKind(val label: String) { FIXED("Times each day"), HOURS("Every N hours"), DAYS("Every N days"), CYCLIC("Cycle on/off"), PRN("As needed") }

/** Draft for the schedule builder; [toRule] returns null while incomplete. */
data class RuleDraft(
    val kind: RuleKind = RuleKind.FIXED,
    val times: List<LocalTime> = listOf(LocalTime.of(8, 0)),
    val weekdays: Set<DayOfWeek> = emptySet(),
    val intervalHours: Int? = 8,
    val firstTime: LocalTime = LocalTime.of(6, 0),
    val quiet: Boolean = false,
    val quietStart: LocalTime = LocalTime.of(23, 0),
    val quietEnd: LocalTime = LocalTime.of(6, 0),
    val everyNDays: Int? = 7,
    val startDate: LocalDate = LocalDate.now(),
    val daysOn: Int? = 21,
    val daysOff: Int? = 7,
    val prnGapHours: Int? = 4,
    val prnMaxPerDay: Int? = 4,
) {
    fun toRule(): ScheduleRule? = runCatching {
        when (kind) {
            RuleKind.FIXED -> ScheduleRule.FixedTimes(times, weekdays)
            RuleKind.HOURS -> ScheduleRule.EveryNHours(intervalHours!!, firstTime, quietStart.takeIf { quiet }, quietEnd.takeIf { quiet })
            RuleKind.DAYS -> ScheduleRule.EveryNDays(everyNDays!!, times, startDate)
            RuleKind.CYCLIC -> ScheduleRule.Cyclic(daysOn!!, daysOff!!, times, startDate)
            RuleKind.PRN -> ScheduleRule.AsNeeded(prnGapHours!! * 60, prnMaxPerDay!!)
        }
    }.getOrNull()

    companion object {
        fun from(rule: ScheduleRule): RuleDraft = when (rule) {
            is ScheduleRule.FixedTimes -> RuleDraft(RuleKind.FIXED, times = rule.times, weekdays = rule.daysOfWeek)
            is ScheduleRule.EveryNHours -> RuleDraft(RuleKind.HOURS, intervalHours = rule.intervalHours, firstTime = rule.firstTime,
                quiet = rule.quietStart != null, quietStart = rule.quietStart ?: LocalTime.of(23, 0), quietEnd = rule.quietEnd ?: LocalTime.of(6, 0))
            is ScheduleRule.EveryNDays -> RuleDraft(RuleKind.DAYS, times = rule.times, everyNDays = rule.n, startDate = rule.startDate)
            is ScheduleRule.Cyclic -> RuleDraft(RuleKind.CYCLIC, times = rule.times, daysOn = rule.daysOn, daysOff = rule.daysOff, startDate = rule.startDate)
            is ScheduleRule.AsNeeded -> RuleDraft(RuleKind.PRN, prnGapHours = rule.minGapMinutes / 60, prnMaxPerDay = rule.maxPerDay)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleBuilder(draft: RuleDraft, onChange: (RuleDraft) -> Unit, allowPrn: Boolean = true) {
    var pick by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(RuleKind.entries.filter { allowPrn || it != RuleKind.PRN }, draft.kind, { it.label }) { onChange(draft.copy(kind = it)) }
        when (draft.kind) {
            RuleKind.FIXED -> {
                TimesEditor(draft.times) { onChange(draft.copy(times = it)) }
                Text("Days (none selected = every day)", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(selected = d in draft.weekdays, label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                            onClick = { onChange(draft.copy(weekdays = if (d in draft.weekdays) draft.weekdays - d else draft.weekdays + d)) })
                    }
                }
            }
            RuleKind.HOURS -> {
                IntField("Every how many hours (1–72)", draft.intervalHours, { onChange(draft.copy(intervalHours = it)) })
                AssistChip(onClick = { pick = "first" }, label = { Text("First dose: ${draft.firstTime}") })
                LabeledSwitch("Quiet hours (no reminders)", draft.quiet) { onChange(draft.copy(quiet = it)) }
                if (draft.quiet) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pick = "qs" }, label = { Text("From ${draft.quietStart}") })
                    AssistChip(onClick = { pick = "qe" }, label = { Text("To ${draft.quietEnd}") })
                }
            }
            RuleKind.DAYS -> {
                IntField("Every how many days (7 = weekly)", draft.everyNDays, { onChange(draft.copy(everyNDays = it)) })
                DatePickerButton("Starting", draft.startDate) { onChange(draft.copy(startDate = it)) }
                TimesEditor(draft.times) { onChange(draft.copy(times = it)) }
            }
            RuleKind.CYCLIC -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("Days on", draft.daysOn, { onChange(draft.copy(daysOn = it)) }, Modifier.weight(1f))
                    IntField("Days off", draft.daysOff, { onChange(draft.copy(daysOff = it)) }, Modifier.weight(1f))
                }
                DatePickerButton("Cycle starts", draft.startDate) { onChange(draft.copy(startDate = it)) }
                TimesEditor(draft.times) { onChange(draft.copy(times = it)) }
            }
            RuleKind.PRN -> {
                IntField("Minimum hours between doses", draft.prnGapHours, { onChange(draft.copy(prnGapHours = it)) })
                IntField("Maximum doses per day", draft.prnMaxPerDay, { onChange(draft.copy(prnMaxPerDay = it)) })
                Text("No reminders. Log it with \"Take now\".", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (draft.toRule() == null) Text("Schedule is incomplete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    when (pick) {
        "first" -> TimePickerDialog(draft.firstTime, { pick = null }) { onChange(draft.copy(firstTime = it)); pick = null }
        "qs" -> TimePickerDialog(draft.quietStart, { pick = null }) { onChange(draft.copy(quietStart = it)); pick = null }
        "qe" -> TimePickerDialog(draft.quietEnd, { pick = null }) { onChange(draft.copy(quietEnd = it)); pick = null }
    }
}
