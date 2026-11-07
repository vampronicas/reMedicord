package com.remedicord.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.remedicord.data.backup.BackupException
import com.remedicord.data.backup.BackupService
import com.remedicord.ui.Labels
import com.remedicord.ui.components.ChoiceChips
import com.remedicord.ui.components.SectionTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val lastBackup: Instant? = null,
    val reminderDays: Int = 30,
    val snapshots: List<BackupService.SnapshotInfo> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    /** Validated file waiting for confirmation. */
    val pending: Pair<Uri, BackupService.Preview>? = null,
    val pendingSnapshot: BackupService.SnapshotInfo? = null,
)

@HiltViewModel
class BackupViewModel @Inject constructor(private val backup: BackupService) : ViewModel() {
    private val _s = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _s

    init { refresh() }

    private fun refresh() = viewModelScope.launch {
        _s.update { it.copy(lastBackup = backup.lastBackupAt(), reminderDays = backup.reminderDays(), snapshots = backup.snapshots()) }
    }

    private fun run(block: suspend () -> String) = viewModelScope.launch {
        _s.update { it.copy(busy = true, message = null) }
        val msg = try { block() } catch (e: BackupException) { e.message ?: "Backup failed" } catch (e: Exception) { "Something went wrong. No data was changed." }
        _s.update { it.copy(busy = false, message = msg) }
        refresh()
    }

    fun export(open: () -> java.io.OutputStream?) = run {
        val out = open() ?: return@run "Could not open the file."
        backup.export(out); "Backup saved."
    }

    fun inspect(uri: Uri, open: () -> java.io.InputStream?) = run {
        val p = backup.preview(open() ?: return@run "Could not open the file.")
        _s.update { it.copy(pending = uri to p) }
        ""
    }

    fun confirmRestore(open: () -> java.io.InputStream?) = run {
        _s.update { it.copy(pending = null) }
        backup.restore(open() ?: return@run "Could not open the file.")
        "Restored. Doses since the backup was made are waiting for review on the Today screen."
    }

    fun askSnapshot(s: BackupService.SnapshotInfo) = _s.update { it.copy(pendingSnapshot = s) }
    fun confirmSnapshot() = run {
        val s = _s.value.pendingSnapshot ?: return@run ""
        _s.update { it.copy(pendingSnapshot = null) }
        backup.restoreSnapshot(s); "Snapshot restored."
    }

    fun setReminder(d: Int) = viewModelScope.launch { backup.setReminderDays(d); refresh() }
    fun dismiss() = _s.update { it.copy(message = null, pending = null, pendingSnapshot = null) }
}

/** S11 Backup & Restore. Files are chosen in Android's own file picker; no other app is involved. */
@Composable
fun BackupScreen(vm: BackupViewModel) {
    val s by vm.state.collectAsState()
    val resolver = LocalContext.current.contentResolver
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.export { resolver.openOutputStream(it, "wt") } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.inspect(it) { resolver.openInputStream(it) } }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Backup")
        Text("Last backup: " + (s.lastBackup?.let { Labels.dateTime(it) } ?: "never"))
        Text("The backup file is NOT encrypted. Anyone who gets the file can read your medicine history. Keep it somewhere safe. Your PIN is never included.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Button(enabled = !s.busy, onClick = {
            exportLauncher.launch("reMedicord-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".zip")
        }, modifier = Modifier.fillMaxWidth()) { Text("Export backup (.zip)") }
        Text("Remind me to back up every")
        ChoiceChips(BackupService.REMINDER_CHOICES, s.reminderDays, { if (it == 0) "Off" else "$it days" }, vm::setReminder)

        SectionTitle("Restore")
        Text("Restoring replaces ALL current data with the backup. Your current data is saved first as a snapshot.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !s.busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth()) { Text("Restore from a backup file…") }
        if (s.snapshots.isNotEmpty()) {
            Text("Automatic snapshots (taken before each restore)")
            s.snapshots.forEach { snap -> TextButton(onClick = { vm.askSnapshot(snap) }) { Text("Undo to ${Labels.dateTime(snap.takenAt)}") } }
        }
        if (s.busy) Text("Working…")
    }
    s.pending?.let { (uri, p) ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Restore this backup?") },
            text = { Text("Made ${Labels.dateTime(p.exportedAt)} (app ${p.appVersion}).\n${p.medications} medicines, ${p.doses} dose records.\n\nAll current data will be replaced.") },
            confirmButton = { TextButton(onClick = { vm.confirmRestore { resolver.openInputStream(uri) } }) { Text("Replace and restore") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
        )
    }
    s.pendingSnapshot?.let { snap ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            text = { Text("Go back to the data as it was on ${Labels.dateTime(snap.takenAt)}? Current data will be replaced (and saved as a new snapshot).") },
            confirmButton = { TextButton(onClick = vm::confirmSnapshot) { Text("Restore snapshot") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Cancel") } },
        )
    }
    s.message?.takeIf { it.isNotEmpty() }?.let {
        AlertDialog(onDismissRequest = vm::dismiss, text = { Text(it) }, confirmButton = { TextButton(onClick = vm::dismiss) { Text("OK") } })
    }
}
