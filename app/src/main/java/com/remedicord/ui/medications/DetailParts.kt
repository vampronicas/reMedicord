package com.remedicord.ui.medications

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remedicord.data.db.entity.MedicationEntity
import com.remedicord.domain.model.MedicationStatus
import com.remedicord.domain.usecase.MedicationService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

fun statusLabel(m: MedicationEntity) = when (m.status) {
    MedicationStatus.ACTIVE -> "Active"; MedicationStatus.PAUSED -> "Paused"
    MedicationStatus.STOPPED -> if (m.stopReason == com.remedicord.domain.model.StopReason.REPLACED) (m.stopNote?.substringBefore(" to")?.substringBefore(" by") ?: "Replaced") else "Stopped"
    MedicationStatus.COMPLETED -> "Completed"
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** E6: label in regular weight, value in bold. */
@Composable
fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(92.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** E7: "Until a date" (past days can't be picked; resumes automatically) or "Until I resume". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PauseSheet(name: String, today: LocalDate, onDismiss: () -> Unit, onPause: (LocalDate?, String?) -> Unit) {
    var dated by remember { mutableStateOf(true) }
    var until by remember { mutableStateOf(today.plusDays(7)) }
    var reason by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Pause $name", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = dated, onClick = { dated = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Until a date") }
                SegmentedButton(selected = !dated, onClick = { dated = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Until I resume") }
            }
            if (dated) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3L to "3 days", 7L to "1 week", 14L to "2 weeks").forEach { (n, l) ->
                        FilterChip(selected = until == today.plusDays(n), onClick = { until = today.plusDays(n) }, label = { Text(l) })
                    }
                }
                OutlinedButton(onClick = { pick = true }, modifier = Modifier.fillMaxWidth()) { Text("Last paused day: ${dayFmt.format(until)}") }
                Text("Reminders resume automatically on ${dayFmt.format(until.plusDays(1))}. Past days can't be picked.",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            } else {
                Text("No end date. Reminders stay off until you tap Resume on the medicine page.", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(reason, { if (it.length <= 200) reason = it }, label = { Text("Reason (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onPause(until.takeIf { dated }, reason.ifBlank { null }) }) { Text("Pause") }
            }
        }
    }
    if (pick) {
        val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = until.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pick = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { until = maxOf(today, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pick = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pick = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** E4: a started must-complete course gets an extra warning first; then the name must be typed. */
@Composable
fun DeleteDialog(info: MedicationService.DeleteInfo, onCancel: () -> Unit, onDelete: (String, Boolean) -> Unit) {
    var acknowledged by remember { mutableStateOf(!info.startedMustComplete) }
    var typed by remember { mutableStateOf("") }
    if (!acknowledged) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("This course has already started") },
            text = {
                Text("${info.name} must be completed and ${info.taken} of ${info.required ?: "?"} doses are recorded as taken. " +
                    "Deleting removes the course and all ${info.doseRecords} dose records. Only do this if it was added by mistake.")
            },
            confirmButton = { TextButton(onClick = { acknowledged = true }) { Text("I understand, continue") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
        )
        return
    }
    val matches = typed.trim().equals(info.name.trim(), ignoreCase = true)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete ${info.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This removes the medicine and its ${info.doseRecords} dose record${if (info.doseRecords == 1) "" else "s"}. It can't be undone here.")
                OutlinedTextField(typed, { if (it.length <= 80) typed = it }, label = { Text("Type \"${info.name}\" to confirm") }, singleLine = true)
                Text("A snapshot is saved first, so you can undo this from Backup & restore.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onDelete(typed, true) }, enabled = matches,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Keep it") } },
    )
}

/** v1.4: tappable link between a replaced medicine and its replacement. */
@Composable
fun LinkCard(title: String, subtitle: String, arrow: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Text(arrow, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** v1.4: one change-history entry: date, what changed (bold), details. */
@Composable
fun HistoryRow(e: com.remedicord.ui.ChangeHistory.Entry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(com.remedicord.ui.Labels.date(e.at).substringBeforeLast(' ').removeSuffix(","), Modifier.width(92.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            e.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
